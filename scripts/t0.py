#!/usr/bin/env python3
"""stagewright T0 orchestrator (contract v0).

Provisions the loader's run-stagewright dir, launches the plain dedicated server run
(:testkit-<loader>:runStageWrightServer, armed by -Dstagewright.autorun), wall-caps it,
then judges stagewright-results.jsonl:

  exit 0  GREEN  — footer present, registered==executed (swallow-canaries excepted),
                   every canary on its expected outcome, every non-canary PASS
  exit 1  RED    — a non-canary scene failed/timed out, or reconciliation failed
  exit 2  DEAD   — a canary landed on the WRONG outcome: the framework can no longer
                   catch failures; the whole run's results are void (spec §5)
  exit 3  ENV    — launch failed / results file missing / no suite header

The orchestrator is the verdict authority; the server process exit code is NOT
consulted (halt() exits 0 regardless of scene outcomes).
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import platform_compat  # noqa: E402  (sibling module; path fixed up just above)

# Import verdict module from same directory
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from verdict import parse, judge

# The gradle project the run is driven against: its `gradlew` is invoked, and every
# relative --results / --expect-file resolves against it.
#
# It defaults to THIS repo (three levels up from the script), which is what every
# worlddriver invocation has always meant — but an EXTERNAL consumer applies the
# stagewright gradle plugin in its own repo while the frozen orchestrators keep living
# here, and for it the two are different directories. Without an override such a run
# silently drove worlddriver's build instead of the consumer's: the wrong `gradlew`,
# the wrong run task, the wrong results file. `--project-root` (or the
# TESTKIT_PROJECT_ROOT env var, which the gradle plugin sets) names the consumer.
SCRIPT_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
REPO_ROOT = os.environ.get("TESTKIT_PROJECT_ROOT") or SCRIPT_REPO_ROOT


def run_dir(loader):
    return os.path.join(REPO_ROOT, "stagewright", loader, "run-stagewright")


def default_results(loader):
    return os.path.join(run_dir(loader), "stagewright-results.jsonl")


def provision(results):
    """Provision the run dir holding `results` (dirname-derived) and return `results`."""
    d = os.path.dirname(results)
    os.makedirs(d, exist_ok=True)
    with open(os.path.join(d, "eula.txt"), "w") as f:
        f.write("eula=true\n")
    with open(os.path.join(d, "server.properties"), "w") as f:
        f.write("server-port=25599\nlevel-type=minecraft\\:flat\nonline-mode=false\n"
                "spawn-protection=0\nsync-chunk-writes=false\nmotd=stagewright T0\n")
    shutil.rmtree(os.path.join(d, "world"), ignore_errors=True)
    if os.path.exists(results):
        os.remove(results)
    # Removing latest.log turns "this file exists" into "the game JVM has started
    # logging" — the signal launch() uses to stop charging build time against the
    # run wall. Nothing is lost: the server overwrites latest.log on every start
    # anyway, and log4j has already rotated the previous run to debug-N.log.gz.
    try:
        os.remove(os.path.join(d, "logs", "latest.log"))
    except OSError:
        pass
    return results


def sweep():
    """Kill leftover testkit server JVMs by explicit PID (pkill is banned)."""
    for pid in platform_compat.find_processes("stagewright.autorun", "java"):
        print(f"[t0] killing leftover testkit JVM pid={pid}")
        platform_compat.kill_pid(pid)


def _terminate(proc):
    """Stop the gradle wrapper, then let sweep() deal with the game JVM it spawned.

    Terminating the wrapper does NOT reliably take the JVM with it: on 2026-07-27 a
    run killed this way left the server holding port 25599, and the NEXT run died at
    boot with "FAILED TO BIND TO PORT" — a failure that looks nothing like its cause.
    sweep() is what actually reclaims it, so every exit path must reach one.
    """
    if proc.poll() is None:
        proc.terminate()
        try:
            proc.wait(timeout=20)
        except subprocess.TimeoutExpired:
            proc.kill()


def launch(task, wall, results, build_wall=1800):
    """Launch the gradle `task` and wait for the DONE FOOTER, not for gradle.

    Task-3 smoke finding: after the harness halt()s the server, the game JVM
    exits cleanly in seconds but the gradle run task does NOT return control.
    So gradle's exit is neither awaited as the happy path nor consulted for the
    verdict (contract v0): we poll the results file for the done footer, give a
    short grace for final writes, then sweep whatever is left and move to judge.

    TWO CLOCKS, because `gradlew <runTask>` compiles before it runs anything.
    The wall used to start at launch, so build time was charged against run time:
    on 2026-07-27 a cold build (right after `gradlew --stop`) ate 864s of a 900s
    wall, the server booted with 36s left, completed 132/189 scenes, and the run
    was reported as "no done footer within 900s -> RED" — indistinguishable from a
    scene actually failing, and it cost a full diagnostic round to tell apart.

    So `build_wall` covers "gradle invoked -> the game JVM starts logging" and
    `wall` covers "server alive -> done footer". provision() deleted latest.log,
    so its reappearance is the handover. A warm incremental build clears the first
    phase in seconds; a cold one can take as long as it likes without eating the
    budget the scenes need. Timing out in the FIRST phase is reported as an
    environment failure, not RED, because no scene ever ran.
    """
    import time
    log_path = os.path.join(os.path.dirname(results), "logs", "latest.log")
    cmd = platform_compat.gradlew_cmd(task)
    print(f"[t0] launching: {' '.join(cmd)} "
          f"(build<={build_wall}s, then run wall={wall}s waiting on done footer)")
    proc = subprocess.Popen(cmd, cwd=REPO_ROOT,
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    build_started = time.monotonic()
    build_deadline = build_started + build_wall
    while time.monotonic() < build_deadline:
        if os.path.exists(log_path):
            break
        if proc.poll() is not None:
            break  # gradle gave up before the JVM ever logged — judge() will see no results
        time.sleep(2)
    build_s = time.monotonic() - build_started
    if not os.path.exists(log_path) and proc.poll() is None:
        print(f"[t0] ENV: no server log after {int(build_s)}s of build — "
              f"the game JVM never started (raise --build-wall or build first)")
        _terminate(proc)
        sweep()
        return 125
    print(f"[t0] build+boot took {int(build_s)}s (not charged to the run wall)")

    deadline = time.monotonic() + wall
    footer = False
    while time.monotonic() < deadline:
        if os.path.exists(results):
            with open(results, encoding="utf-8", errors="replace") as f:
                if '"type":"done"' in f.read():
                    footer = True
                    break
        if proc.poll() is not None:
            break  # gradle actually returned (crash or clean) — judge whatever exists
        time.sleep(2)
    if footer:
        print("[t0] done footer observed — reaping the run")
        time.sleep(3)  # grace for file flush + server teardown
    else:
        print(f"[t0] no done footer within {wall}s — wall timeout")
    _terminate(proc)
    sweep()
    return 0 if footer else 124




# ---- embedded self-test fixtures ----

F_SUITE = {"type": "suite", "loader": "x", "registered": [
    {"name": "a", "required": True, "canary": "NONE"},
    {"name": "cf", "required": True, "canary": "MUST_FAIL"},
    {"name": "ct", "required": True, "canary": "MUST_TIMEOUT"},
    {"name": "cs", "required": True, "canary": "MUST_SWALLOW"},
    {"name": "opt", "required": False, "canary": "NONE"}]}


def _scene(name, outcome):
    return {"type": "scene", "name": name, "outcome": outcome, "ticks": 1, "wallMs": 1, "reason": ""}


F_DONE = {"type": "done", "scenes": 4}
F_GREEN = [F_SUITE, _scene("a", "PASS"), _scene("cf", "FAIL"), _scene("ct", "TIMEOUT"), _scene("opt", "PASS"), F_DONE]


def _check_provision_clears_server_log():
    """provision() must delete latest.log — launch() reads its reappearance as
    "the JVM started", so a stale file would end the build phase instantly and put
    the run wall back to covering the build."""
    d = tempfile.mkdtemp()
    logs = os.path.join(d, "logs")
    os.makedirs(logs)
    stale = os.path.join(logs, "latest.log")
    with open(stale, "w") as f:
        f.write("previous run")
    provision(os.path.join(d, "stagewright-results.jsonl"))
    return not os.path.exists(stale)


def _check_build_phase_timeout_is_env_not_red():
    """A build that never produces a server log returns 125, which main() maps to
    ENV(3). It must NOT reach judge(): with no scene records judge() would say RED,
    i.e. "your code failed", for what is actually "the build never finished"."""
    class NeverExits:
        def poll(self): return None
        def terminate(self): pass
        def wait(self, timeout=None): pass
        def kill(self): pass

    d = tempfile.mkdtemp()
    results = os.path.join(d, "stagewright-results.jsonl")
    real_popen, real_sweep = subprocess.Popen, globals()["sweep"]
    subprocess.Popen = lambda *a, **k: NeverExits()
    globals()["sweep"] = lambda: None
    try:
        rc = launch("::noop", wall=1, results=results, build_wall=0)
    finally:
        subprocess.Popen, globals()["sweep"] = real_popen, real_sweep
    return rc == 125 and not os.path.exists(results)


def self_test():
    checks = [
        ("green run -> 0", judge(F_GREEN)[0] == 0),
        ("real scene FAIL -> 1",
         judge([F_SUITE, _scene("a", "FAIL"), _scene("cf", "FAIL"), _scene("ct", "TIMEOUT"),
                _scene("opt", "PASS"), F_DONE])[0] == 1),
        ("real scene swallowed -> 1",
         judge([F_SUITE, _scene("cf", "FAIL"), _scene("ct", "TIMEOUT"), _scene("opt", "PASS"),
                {"type": "done", "scenes": 3}])[0] == 1),
        ("canary wrong outcome -> 2 DEAD",
         judge([F_SUITE, _scene("a", "PASS"), _scene("cf", "PASS"), _scene("ct", "TIMEOUT"),
                _scene("opt", "PASS"), F_DONE])[0] == 2),
        ("swallow-canary executed -> 2 DEAD",
         judge(F_GREEN[:-1] + [_scene("cs", "PASS"), {"type": "done", "scenes": 5}])[0] == 2),
        ("missing footer -> 1",
         judge([F_SUITE, _scene("a", "PASS"), _scene("cf", "FAIL"), _scene("ct", "TIMEOUT")])[0] == 1),
        ("missing header -> 3",
         judge([_scene("a", "PASS")])[0] == 3),
        ("drifted record -> 1",
         judge([F_SUITE, _scene("a", "PASS"), _scene("cf", "FAIL"), _scene("ct", "TIMEOUT"), _scene("opt", "PASS"),
                _scene("ghost", "PASS"), {"type": "done", "scenes": 5}])[0] == 1),
        ("optional scene FAIL tolerated -> 0",
         judge([F_SUITE, _scene("a", "PASS"), _scene("opt", "FAIL"), _scene("cf", "FAIL"),
                _scene("ct", "TIMEOUT"), F_DONE])[0] == 0),
        ("optional scene swallowed still -> 1",
         judge([F_SUITE, _scene("a", "PASS"), _scene("cf", "FAIL"),
                _scene("ct", "TIMEOUT"), {"type": "done", "scenes": 3}])[0] == 1),
        ("duplicate scene record (FAIL then PASS) does not judge GREEN -> 1",
         judge([F_SUITE, _scene("a", "FAIL"), _scene("a", "PASS"), _scene("cf", "FAIL"),
                _scene("ct", "TIMEOUT"), _scene("opt", "PASS"), {"type": "done", "scenes": 5}])[0] == 1),
        ("done.scenes mismatch -> 1",
         judge([F_SUITE, _scene("a", "PASS"), _scene("opt", "PASS"), _scene("cf", "FAIL"),
                _scene("ct", "TIMEOUT"), {"type": "done", "scenes": 99}])[0] == 1),
        ("done.scenes absent tolerated -> 0",
         judge([F_SUITE, _scene("a", "PASS"), _scene("opt", "PASS"), _scene("cf", "FAIL"),
                _scene("ct", "TIMEOUT"), {"type": "done"}])[0] == 0),
        ("provision clears a stale server log", _check_provision_clears_server_log()),
        ("build-phase timeout is ENV(125), never RED", _check_build_phase_timeout_is_env_not_red()),
        ("expected scene present -> 0",
         judge([F_SUITE, _scene("a", "PASS"), _scene("opt", "PASS"), _scene("cf", "FAIL"),
                _scene("ct", "TIMEOUT"), F_DONE], expected=["a"])[0] == 0),
        ("expected scene missing -> 1",
         judge([F_SUITE, _scene("a", "PASS"), _scene("opt", "PASS"), _scene("cf", "FAIL"),
                _scene("ct", "TIMEOUT"), F_DONE], expected=["ghost"])[0] == 1),
        ("expected=None unchanged -> 0",
         judge([F_SUITE, _scene("a", "PASS"), _scene("opt", "PASS"), _scene("cf", "FAIL"),
                _scene("ct", "TIMEOUT"), F_DONE])[0] == 0),
        ("parse drops undecodable line without raising",
         _check_parse_bad_line()),
        ("load_expect_file parses comments/blank/comma lines",
         _check_load_expect_file()),
        ("--expect-file union with --expect-scene dedups overlap",
         _check_expect_union_dedup()),
        ("registered-but-undeclared scene is caught (reverse manifest gate)",
         judge([F_SUITE, _scene("a", "PASS"), _scene("opt", "PASS"), _scene("cf", "FAIL"),
                _scene("ct", "TIMEOUT"), F_DONE], expected=["a"])[0] == 0
         and judge([{"type": "suite", "loader": "x",
                     "registered": [{"name": "ad.a", "required": True, "canary": "NONE"},
                                    {"name": "ad.b", "required": True, "canary": "NONE"}]},
                    _scene("ad.a", "PASS"), _scene("ad.b", "PASS"),
                    {"type": "done", "scenes": 2}], expected=["ad.a"])[0] == 1),
        ("shipped per-loader manifests agree",
         not check_manifest_siblings(
             os.path.join(os.path.dirname(os.path.abspath(__file__)),
                          "expected-scenes-fabric.txt"))),
    ]
    failed = [n for n, ok in checks if not ok]
    for n, ok in checks:
        print(f"  [{'PASS' if ok else 'FAIL'}] {n}")
    return 0 if not failed else 1


def _check_parse_bad_line():
    with tempfile.NamedTemporaryFile("w", suffix=".jsonl", delete=False) as f:
        f.write(json.dumps({"type": "done", "scenes": 0}) + "\n")
        f.write("{not valid json\n")
        path = f.name
    try:
        records = parse(path)
        return len(records) == 1 and records[0]["type"] == "done"
    finally:
        os.remove(path)


def load_expect_file(path):
    names = []
    with open(path) as f:
        for line in f:
            line = line.split("#", 1)[0].strip()
            if not line:
                continue
            names.extend(s.strip() for s in line.split(",") if s.strip())
    return names


# The per-loader manifests are identical BY CONSTRUCTION — the wd.* scenes live in
# :common and both loaders register them through the same common SceneProvider service
# file. Until this check, that invariant was stated only in a comment at the top of each
# manifest ("any scene added to common MUST be added to BOTH manifests in the same
# commit"), so a one-sided edit stayed invisible until somebody happened to run the other
# loader. Comparing them here costs one file read and makes the drift impossible to miss
# on the loader you DID run.
_MANIFEST_SIBLINGS = ("expected-scenes-fabric.txt", "expected-scenes-neoforge.txt")


def check_manifest_siblings(path):
    """Report per-loader manifest drift as a list of message lines (empty == consistent)."""
    base = os.path.basename(path)
    if base not in _MANIFEST_SIBLINGS:
        return []          # a caller-supplied one-off manifest has no sibling contract
    other = _MANIFEST_SIBLINGS[1 - _MANIFEST_SIBLINGS.index(base)]
    other_path = os.path.join(os.path.dirname(os.path.abspath(path)), other)
    if not os.path.isfile(other_path):
        return []
    mine, theirs = set(load_expect_file(path)), set(load_expect_file(other_path))
    msgs = []
    for name in sorted(mine - theirs):
        msgs.append(f"MANIFEST-DRIFT: {name} is in {base} but not {other}")
    for name in sorted(theirs - mine):
        msgs.append(f"MANIFEST-DRIFT: {name} is in {other} but not {base}")
    return msgs


def _check_load_expect_file():
    with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False) as f:
        f.write("# comment line\n")
        f.write("\n")
        f.write("ad.foo\n")
        f.write("ad.bar, ad.baz  # trailing comment\n")
        f.write("   \n")
        path = f.name
    try:
        return load_expect_file(path) == ["ad.foo", "ad.bar", "ad.baz"]
    finally:
        os.remove(path)


def _check_expect_union_dedup():
    with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False) as f:
        f.write("ad.foo\n")
        f.write("ad.bar\n")
        path = f.name
    try:
        from_file = load_expect_file(path)
        from_scene = ["ad.bar", "ad.qux"]
        union = sorted(set(from_file) | set(from_scene))
        return union == ["ad.bar", "ad.foo", "ad.qux"]
    finally:
        os.remove(path)


def main():
    global REPO_ROOT
    ap = argparse.ArgumentParser()
    ap.add_argument("--loader", choices=["neoforge", "fabric"])
    ap.add_argument("--wall", type=int, default=900,
                    help="seconds the SERVER gets, measured from its first log line — "
                         "gradle's compile is not charged against it (see launch())")
    ap.add_argument("--build-wall", type=int, default=1800,
                    help="seconds gradle gets to compile and boot the JVM before t0 gives up")
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--project-root", default=None,
                    help="gradle project to drive (default: this repo, or $TESTKIT_PROJECT_ROOT). "
                         "External consumers of the stagewright gradle plugin point this at "
                         "their own repo — its gradlew is what gets launched, and relative "
                         "--results / --expect-file resolve against it")
    ap.add_argument("--run-task", default=None,
                    help="gradle run task (default :testkit-<loader>:runStageWrightServer)")
    ap.add_argument("--results", default=None,
                    help="results JSONL path (default stagewright/<loader>/run-stagewright/stagewright-results.jsonl)")
    ap.add_argument("--expect-scene", default=None,
                    help="comma-separated scene names that MUST appear in registered[] (RED if absent)")
    ap.add_argument("--expect-file", default=None,
                    help="file of expected scene names (one per line, '#' comments, commas ok); union with --expect-scene")
    args = ap.parse_args()
    if args.self_test:
        sys.exit(self_test())
    if not args.loader:
        ap.error("--loader is required (or use --self-test)")

    if args.project_root:
        REPO_ROOT = os.path.abspath(args.project_root)
        if not os.path.isdir(REPO_ROOT):
            ap.error(f"--project-root is not a directory: {REPO_ROOT}")
    if REPO_ROOT != SCRIPT_REPO_ROOT:
        print(f"[t0] driving external project root: {REPO_ROOT}")

    if args.results and not os.path.isabs(args.results):
        args.results = os.path.join(REPO_ROOT, args.results)
    results_path = args.results or default_results(args.loader)
    task = args.run_task or f":testkit-{args.loader}:runStageWrightServer"

    from_scene = None
    if args.expect_scene is not None:
        from_scene = [s.strip() for s in args.expect_scene.split(",") if s.strip()]

    from_file = None
    if args.expect_file is not None:
        expect_file_path = args.expect_file
        if not os.path.isabs(expect_file_path):
            expect_file_path = os.path.join(REPO_ROOT, expect_file_path)
        if not os.path.exists(expect_file_path):
            ap.error(f"--expect-file not found: {expect_file_path}")
        from_file = load_expect_file(expect_file_path)
        drift = check_manifest_siblings(expect_file_path)
        if drift:
            # Fail BEFORE launching a server: the manifests disagree, so whichever
            # verdict this run produced would be measured against the wrong list.
            for msg in drift:
                print(f"[t0] {msg}", file=sys.stderr)
            ap.error("per-loader scene manifests have drifted — fix both in one commit")

    if from_scene is None and from_file is None:
        expected = None
    else:
        expected = sorted(set(from_scene or []) | set(from_file or []))
        if not expected:
            ap.error("expectation source given but contains no scene names")

    sweep()
    results = provision(results_path)
    rc = launch(task, args.wall, results, build_wall=args.build_wall)
    print(f"[t0] launch rc={rc} (informational only — verdict comes from the results file)")
    if rc == 125:
        # The build phase timed out: no scene ever ran, so there is nothing to judge.
        # Reported as ENV, never RED — a RED here would read as "the code is broken".
        print("[t0] VERDICT: ENV")
        sys.exit(3)
    if not os.path.exists(results):
        print("[t0] ENV: results file missing")
        sys.exit(3)
    code, report = judge(parse(results), expected=expected)
    for line in report:
        print(f"[t0] {line}")
    print(f"[t0] VERDICT: {['GREEN', 'RED', 'DEAD', 'ENV'][code]}")
    sys.exit(code)


if __name__ == "__main__":
    main()
