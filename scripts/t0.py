#!/usr/bin/env python3
"""mc-testkit T0 orchestrator (contract v0).

Provisions the loader's run-testkit dir, launches the plain dedicated server run
(:testkit-<loader>:runTestkitServer, armed by -Dtestkit.autorun), wall-caps it,
then judges testkit-results.jsonl:

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

# Import verdict module from same directory
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from verdict import parse, judge

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def run_dir(loader):
    return os.path.join(REPO_ROOT, "mc-testkit", loader, "run-testkit")


def default_results(loader):
    return os.path.join(run_dir(loader), "testkit-results.jsonl")


def provision(results):
    """Provision the run dir holding `results` (dirname-derived) and return `results`."""
    d = os.path.dirname(results)
    os.makedirs(d, exist_ok=True)
    with open(os.path.join(d, "eula.txt"), "w") as f:
        f.write("eula=true\n")
    with open(os.path.join(d, "server.properties"), "w") as f:
        f.write("server-port=25599\nlevel-type=minecraft\\:flat\nonline-mode=false\n"
                "spawn-protection=0\nsync-chunk-writes=false\nmotd=mc-testkit T0\n")
    shutil.rmtree(os.path.join(d, "world"), ignore_errors=True)
    if os.path.exists(results):
        os.remove(results)
    return results


def sweep():
    """Kill leftover testkit server JVMs by explicit PID (pkill is banned)."""
    out = subprocess.run(["ps", "-eo", "pid,args"], capture_output=True, text=True).stdout
    for line in out.splitlines():
        if "testkit.autorun" in line and "java" in line:
            pid = line.strip().split()[0]
            print(f"[t0] killing leftover testkit JVM pid={pid}")
            subprocess.run(["kill", "-9", pid])


def launch(task, wall, results):
    """Launch the gradle `task` and wait for the DONE FOOTER, not for gradle.

    Task-3 smoke finding: after the harness halt()s the server, the game JVM
    exits cleanly in seconds but the gradle run task does NOT return control.
    So gradle's exit is neither awaited as the happy path nor consulted for the
    verdict (contract v0): we poll the results file for the done footer, give a
    short grace for final writes, then sweep whatever is left and move to judge.
    """
    import time
    cmd = ["./gradlew", task]
    print(f"[t0] launching: {' '.join(cmd)} (wall={wall}s, waiting on done footer)")
    proc = subprocess.Popen(cmd, cwd=REPO_ROOT,
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
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
    if proc.poll() is None:
        proc.terminate()
        try:
            proc.wait(timeout=20)
        except subprocess.TimeoutExpired:
            proc.kill()
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


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--loader", choices=["neoforge", "fabric"])
    ap.add_argument("--wall", type=int, default=900)
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--run-task", default=None,
                    help="gradle run task (default :testkit-<loader>:runTestkitServer)")
    ap.add_argument("--results", default=None,
                    help="results JSONL path (default mc-testkit/<loader>/run-testkit/testkit-results.jsonl)")
    ap.add_argument("--expect-scene", default=None,
                    help="comma-separated scene names that MUST appear in registered[] (RED if absent)")
    args = ap.parse_args()
    if args.self_test:
        sys.exit(self_test())
    if not args.loader:
        ap.error("--loader is required (or use --self-test)")

    if args.results and not os.path.isabs(args.results):
        args.results = os.path.join(REPO_ROOT, args.results)
    results_path = args.results or default_results(args.loader)
    task = args.run_task or f":testkit-{args.loader}:runTestkitServer"
    if args.expect_scene is not None:
        expected = [s.strip() for s in args.expect_scene.split(",") if s.strip()]
        if not expected:
            ap.error("--expect-scene given but contains no scene names")
    else:
        expected = None

    sweep()
    results = provision(results_path)
    rc = launch(task, args.wall, results)
    print(f"[t0] launch rc={rc} (informational only — verdict comes from the results file)")
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
