#!/usr/bin/env python3
"""mc-testkit T1 orchestrator (contract v0) — client topology under Xvfb.

Runs the 9 ad.* scenes on an INTEGRATED server (client-hosted world) for the
first time, proving the mc-testkit harness topology generalizes from the
dedicated dogfood server (t0.py) to a real Fabric client. Flow:

  1. self-manage an Xvfb on a probed-free DISPLAY (never hardcode :99 — the live
     dev client may own it), tracked by PID, killed by PID on exit
  2. pre-create fabric/run-t1/saves and, if a cached world template exists, copy
     it in BEFORE launch so the world list sees it
  3. launch :fabric:runTestkitClient (a CLIENT JVM, -Dtestkit.autorun=true) with
     DISPLAY in its environment; foreground bounded polling for the port file
  4. drive title → singleplayer → world (guidrive, RPC widget clicks, no WM) —
     reuse the template world, or GUI-create it once and archive it as the template
  5. world entry starts the integrated server → SERVER_STARTED arms the harness →
     scenes auto-run → poll run-t1/testkit-results.jsonl for the done footer
  6. judge via verdict.py (REUSED, not forked) with --expect-file
  7. teardown: quit-to-title (best effort) → kill client JVM by PID → kill Xvfb by
     PID → delete the saves/TestkitT1 copy (leave the template archive)

Exit codes (T1 semantics):
  0 GREEN  — footer present, verdict GREEN (all scenes on their expected outcome)
  1 RED    — footer present, a non-canary scene failed / reconciliation failed
  2 DEAD   — footer present, a canary landed on the WRONG outcome (framework void)
  3 ENV    — client never came up / GUI drive failed / no done footer
"""
import argparse
import asyncio
import glob
import json
import os
import shutil
import signal
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import guidrive as gd  # noqa: E402
from verdict import parse, judge  # noqa: E402 — REUSED judging logic, not forked
from t0 import load_expect_file  # noqa: E402 — REUSED expect-file parser, not forked

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TESTKIT_DIR = os.path.join(REPO_ROOT, "scripts", "testkit")
WORLD_NAME = "TestkitT1"
LOADERS = ("fabric", "neoforge")


@dataclass(frozen=True)
class LoaderPaths:
    """Every loader-specific path/task the orchestrator needs, resolved once from the
    --loader argument. The client topology is identical across loaders; only the run-t1
    tree (under ``<loader>/``), the gradle task (``:<loader>:runTestkitClient``), the
    per-loader template cache, and the default expect manifest differ. Pure/derivable,
    so self-test can assert both loaders' resolution without touching a live client."""
    loader: str
    run_dir: str
    port_file: str
    results: str
    saves: str
    world_dir: str
    template_dir: str
    run_task: str
    endpoint_file: str
    default_expect: str


def resolve_loader(name):
    """Derive the LoaderPaths for ``name`` (fabric|neoforge). Templates are per-loader
    (``.t1-world-template-<loader>``) so a fabric-minted world never seeds a neoforge run
    and vice-versa — each loader mints its own byte-clean template under its own run-t1."""
    run_dir = os.path.join(REPO_ROOT, name, "run-t1")
    saves = os.path.join(run_dir, "saves")
    return LoaderPaths(
        loader=name,
        run_dir=run_dir,
        port_file=os.path.join(run_dir, "agent-rpc.port"),
        results=os.path.join(run_dir, "testkit-results.jsonl"),
        saves=saves,
        world_dir=os.path.join(saves, WORLD_NAME),
        template_dir=os.path.join(TESTKIT_DIR, f".t1-world-template-{name}"),
        run_task=f":{name}:runTestkitClient",
        endpoint_file=os.path.join(run_dir, "testkit-endpoint.json"),
        default_expect=os.path.join("scripts", "testkit", f"expected-scenes-{name}.txt"),
    )


def _install_loader(name):
    """Install ``name``'s LoaderPaths as module state so the orchestration functions
    (which reference these paths/task as module globals) target the right loader's
    run-t1 tree. Returns the LoaderPaths (its ``.loader`` field is the endpoint loader)."""
    global LOADER, RUN_DIR, PORT_FILE, RESULTS, SAVES, WORLD_DIR, TEMPLATE_DIR
    global RUN_TASK, ENDPOINT_FILE, DEFAULT_EXPECT
    lp = resolve_loader(name)
    LOADER = lp
    RUN_DIR = lp.run_dir
    PORT_FILE = lp.port_file
    RESULTS = lp.results
    SAVES = lp.saves
    WORLD_DIR = lp.world_dir
    TEMPLATE_DIR = lp.template_dir
    RUN_TASK = lp.run_task
    ENDPOINT_FILE = lp.endpoint_file
    DEFAULT_EXPECT = lp.default_expect
    return lp


# Default module state = fabric, so import-time users (self-test, argparse help) and the
# default --loader path resolve without an explicit install. main()/run() re-install the
# actual --loader before any live work.
_install_loader("fabric")


# --------------------------------------------------------- pure decisions ----
def taken_displays(sockets):
    """Parse a list of /tmp/.X11-unix/X* socket paths → set of display numbers."""
    nums = set()
    for s in sockets:
        base = os.path.basename(s)
        if base.startswith("X"):
            try:
                nums.add(int(base[1:]))
            except ValueError:
                pass
    return nums


def pick_free_display(taken, start=101, end=990):
    """First display number in [start, end) not in ``taken`` (pure, for self-test).
    Starts at 101 to steer clear of :99/:97 that live dev clients conventionally own."""
    for n in range(start, end):
        if n not in taken:
            return n
    raise RuntimeError(f"no free display in [{start},{end})")


def probe_free_display():
    return pick_free_display(taken_displays(glob.glob("/tmp/.X11-unix/X*")))


def template_reuse(template_dir):
    """Reuse the cached template iff it exists and is non-empty (pure, for self-test)."""
    return os.path.isdir(template_dir) and bool(os.listdir(template_dir))


def write_endpoint(path, loader, port, pid):
    """Write the TESTKIT_ENDPOINT descriptor (schema v1, frozen — see File Structure of
    the P2c plan and the attach-contract appendix in orchestration-contract-v0.md) to
    ``path`` atomically (write to ``<path>.tmp`` then os.replace, so a JUnit-side reader
    never observes a partial file). Pure aside from the filesystem write — takes every
    varying value as a parameter — so self-test can exercise it without a live client.
    Returns the dict that was written."""
    doc = {
        "version": 1,
        "topology": "integrated_plus_client",
        "loader": loader,
        "rpcHost": "127.0.0.1",
        "rpcPort": port,
        "worldName": WORLD_NAME,
        "holdPid": pid,
        "writtenAtEpochMs": int(time.time() * 1000),
    }
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(doc, f, indent=2)
    os.replace(tmp, path)
    return doc


# ------------------------------------------------------------ process mgmt ----
def kill_pid(pid, name, grace=15):
    """SIGTERM then, after ``grace`` s, SIGKILL a single PID. No-op if pid is None."""
    if pid is None:
        return
    try:
        os.kill(pid, signal.SIGTERM)
        print(f"[t1] SIGTERM {name} pid={pid}")
    except ProcessLookupError:
        return
    deadline = time.monotonic() + grace
    while time.monotonic() < deadline:
        try:
            os.kill(pid, 0)
        except ProcessLookupError:
            return
        time.sleep(0.5)
    try:
        os.kill(pid, signal.SIGKILL)
        print(f"[t1] SIGKILL {name} pid={pid}")
    except ProcessLookupError:
        pass


def sweep_client_jvms():
    """Kill leftover T1 CLIENT JVMs by explicit PID (pkill is banned). Matches the
    forked Knot CLIENT with testkit.autorun armed — never the dedicated dogfood
    KnotServer (which is also testkit.autorun but a server), and never the gradle
    wrapper. run-t1 in the argv is the tie-breaker when present."""
    out = subprocess.run(["ps", "-eo", "pid,args"], capture_output=True, text=True).stdout
    killed = []
    for line in out.splitlines():
        if "java" not in line or "testkit.autorun" not in line:
            continue
        is_client = "KnotClient" in line or "runTestkitClient" in line or "run-t1" in line
        if not is_client:
            continue
        pid = line.strip().split()[0]
        print(f"[t1] killing leftover T1 client JVM pid={pid}")
        subprocess.run(["kill", "-9", pid])
        killed.append(pid)
    return killed


def start_xvfb(display, screen="1280x720x24"):
    """Start an Xvfb on :display, return its Popen. Wait for the socket to appear."""
    sock = f"/tmp/.X11-unix/X{display}"
    proc = subprocess.Popen(
        ["Xvfb", f":{display}", "-screen", "0", screen,
         "-ac", "+extension", "GLX", "+render", "-noreset"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    for _ in range(50):
        if os.path.exists(sock):
            print(f"[t1] Xvfb up on :{display} (pid={proc.pid})")
            return proc
        if proc.poll() is not None:
            raise RuntimeError(f"Xvfb :{display} died on startup (rc={proc.returncode})")
        time.sleep(0.2)
    proc.terminate()
    raise RuntimeError(f"Xvfb :{display} socket never appeared")


# ------------------------------------------------------------- world drive ----
async def drive_into_world(rpc, reuse):
    """Title → singleplayer → (reuse|create) TestkitT1 → in-world. Raises on failure."""
    await gd.goto_main_menu(rpc)
    info = await gd.goto_singleplayer(rpc)
    if reuse:
        if info.get("type") != "SelectWorldScreen":
            raise RuntimeError(f"reuse expected SelectWorldScreen, got {info.get('type')}")
        tree = await gd.await_world_list(rpc)
        entry = gd.find_widget(tree, gd.by_label(WORLD_NAME))
        if entry is None:
            raise RuntimeError(f"template world '{WORLD_NAME}' not in world list")
        print(f"[t1] reuse: open existing world '{WORLD_NAME}'")
        await gd.open_existing_world(rpc, entry, WORLD_NAME)
    else:
        print(f"[t1] create: GUI-create world '{WORLD_NAME}' (superflat/creative/cheats)")
        if info.get("type") == "SelectWorldScreen":
            await gd.open_create_form(rpc)
        await gd.set_world_name(rpc, WORLD_NAME)
        await gd.set_superflat_creative_cheats(rpc)
        await gd.commit_world_creation(rpc)
    await gd.wait_in_world(rpc)


async def drive_into_world_selfheal(rpc, reuse):
    """Wrap drive_into_world with a single TitleScreen-lingering retry: if the drive
    times out / errors and we're still parked on the TitleScreen, retry the click
    sequence once before letting the failure propagate (declared ENV upstream)."""
    try:
        await drive_into_world(rpc, reuse)
        return
    except Exception as e:  # noqa: BLE001
        info = await rpc.call("mc.client.screen.info")
        print(f"[t1] world-drive attempt 1 failed ({e}); screen={info.get('type')} — retrying once")
        if info.get("type") not in ("TitleScreen", "SelectWorldScreen", "CreateWorldScreen"):
            raise
    await drive_into_world(rpc, reuse)


# ---------------------------------------------------------------- harvest -----
def harvest_footer(results, deadline, client_proc):
    """Poll ``results`` for the done footer until deadline or the client dies. Each
    candidate line is parsed as JSON and checked for {"type": "done"} — mirroring how
    instrument_client._outcomes parses records — rather than a raw substring match, which
    was coupled to the Java harness's exact compact-JSON key/value spacing. Unparsable
    lines are skipped (same fallback as a mid-flush partial line always had). Returns True
    if the footer was observed."""
    while time.monotonic() < deadline:
        if os.path.exists(results):
            with open(results, encoding="utf-8", errors="replace") as f:
                for line in f:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        rec = json.loads(line)
                    except json.JSONDecodeError:
                        continue
                    if isinstance(rec, dict) and rec.get("type") == "done":
                        print("[t1] done footer observed — reaping the run")
                        time.sleep(3)  # grace for final flush
                        return True
        if client_proc is not None and client_proc.poll() is not None:
            print("[t1] gradle client process exited before footer")
            break
        time.sleep(2)
    return False


# ------------------------------------------------------------- orchestration --
async def mint_session(wall):
    """Mint phase (autorun OFF): connect, GUI-create a pristine TestkitT1, quit-to-title
    to flush a clean save. NO scenes run (autorun off) so the world stays byte-clean —
    reusing an after-scenes world flips the ad.selfShaftDigUp inverted-lottery signature."""
    deadline = time.monotonic() + wall
    port = await gd.discover_port(Path(PORT_FILE),
                                  timeout=max(30, int(deadline - time.monotonic())))
    print(f"[t1] (mint) discovered agent-rpc port={port}")
    ws = await gd.connect(port)
    async with ws:
        rpc = gd.Rpc(ws)
        await gd.wait_api_ready(rpc)
        await gd.goto_main_menu(rpc)
        info = await gd.goto_singleplayer(rpc)
        print(f"[t1] (mint) GUI-create pristine '{WORLD_NAME}' (superflat/creative/cheats)")
        if info.get("type") == "SelectWorldScreen":
            await gd.open_create_form(rpc)
        await gd.set_world_name(rpc, WORLD_NAME)
        await gd.set_superflat_creative_cheats(rpc)
        await gd.commit_world_creation(rpc)
        await gd.wait_in_world(rpc)
        print("[t1] (mint) in-world — flushing clean save via quit-to-title")
        await gd.quit_to_title(rpc)


async def run_session(wall, hold, hold_pid=None):
    """Scored run (autorun ON, template reuse): connect, drive into world, harvest.
    Returns footer_seen. ``hold_pid`` is the CLIENT JVM pid (only meaningful when
    ``hold`` is True — it becomes the endpoint file's holdPid)."""
    deadline = time.monotonic() + wall
    port = await gd.discover_port(Path(PORT_FILE),
                                  timeout=max(30, int(deadline - time.monotonic())))
    print(f"[t1] discovered agent-rpc port={port}")
    ws = await gd.connect(port)
    async with ws:
        rpc = gd.Rpc(ws)
        await gd.wait_api_ready(rpc)
        print("[t1] client API reachable at title")
        await drive_into_world_selfheal(rpc, reuse=True)
        if hold:
            # --hold is launched with autorun OFF (see run()): NO scenes run, so the
            # harness never halt()s the integrated server — it stays up in-world with
            # the RPC live, which is exactly what instrument_client.py --attach needs
            # (a live server to /give /damage against). A --hold with autorun ON would
            # run the scenes then get disconnected to a DisconnectedScreen, useless for
            # attach. So hold ⇒ live idle world, not a post-suite world.
            #
            # In-world + port known ⇒ the attach surface is ready: write the
            # TESTKIT_ENDPOINT descriptor (schema v1) for an out-of-process JUnit
            # consumer to discover this topology, and echo the export line the
            # human/CI driving t1.py needs to wire it into the JUnit run's env.
            # loader carries the actual --loader (Task 4 generalization) via module state.
            write_endpoint(ENDPOINT_FILE, LOADER.loader, port, hold_pid)
            print(f"export TESTKIT_ENDPOINT={ENDPOINT_FILE}")
            print(f"[t1] in-world '{WORLD_NAME}' — integrated server up, NO scenes "
                  "(autorun off for --hold); staying online for instrument_client --attach. "
                  "Ctrl-C to release.")
            while True:
                await asyncio.sleep(5)
        print(f"[t1] in-world '{WORLD_NAME}' — integrated server up, scenes running")
        footer = harvest_footer(RESULTS, deadline, None)
        # After the done footer the harness halt()s the integrated server, which
        # disconnects the client to a DisconnectedScreen and cleanly saves the world.
        # That IS the flush we'd want from quit-to-title, so if we're already there we
        # skip it; otherwise (e.g. --hold-less early exit) drive quit-to-title best-effort.
        info = await rpc.call("mc.client.screen.info")
        if info.get("type") == "DisconnectedScreen":
            print("[t1] harness halted integrated server (DisconnectedScreen) — world saved")
        else:
            try:
                await gd.quit_to_title(rpc)
                print("[t1] quit-to-title OK")
            except Exception as e:  # noqa: BLE001
                print(f"[t1] quit-to-title best-effort failed ({e})")
        return footer


def provision(reuse):
    """Prepare run-t1/saves before launch: fresh results, no stale world copy, and —
    if reusing — the template copied in so the world list sees it."""
    os.makedirs(SAVES, exist_ok=True)
    # Seed options.txt so the client boots straight to TitleScreen: a fresh game dir
    # otherwise opens the AccessibilityOnboardingScreen (its dismiss button is not a
    # reliable label-click target over RPC), and we disable the narrator so no TTS is
    # attempted headless. MC merges missing keys with defaults, so these two lines suffice.
    with open(os.path.join(RUN_DIR, "options.txt"), "w") as f:
        f.write("onboardAccessibility:false\nnarrator:0\n")
    if os.path.exists(RESULTS):
        os.remove(RESULTS)
    if os.path.exists(PORT_FILE):
        os.remove(PORT_FILE)
    shutil.rmtree(WORLD_DIR, ignore_errors=True)  # never reuse a dirty world
    if reuse:
        print(f"[t1] copying template → {WORLD_DIR}")
        shutil.copytree(TEMPLATE_DIR, WORLD_DIR)


def archive_template():
    """Archive the pristine minted world to the template cache for future runs."""
    if not os.path.isdir(WORLD_DIR):
        print("[t1] WARN: world dir absent at archive time — cannot cache template")
        return False
    shutil.rmtree(TEMPLATE_DIR, ignore_errors=True)
    shutil.copytree(WORLD_DIR, TEMPLATE_DIR)
    print(f"[t1] archived pristine world → {TEMPLATE_DIR}")
    return True


def launch_client(env, wall, autorun):
    """Launch RUN_TASK (:<loader>:runTestkitClient). --no-daemon so the forked game JVM inherits this
    launcher's environment (a reused daemon would carry no DISPLAY → GLFW init fails).
    autorun=False passes -Pt1Autorun=false to mint a pristine (scene-free) template."""
    os.makedirs(RUN_DIR, exist_ok=True)
    logf = open(os.path.join(RUN_DIR, "t1-mint.log" if not autorun else "t1-runclient.log"), "w")
    cmd = ["./gradlew", "--no-daemon"]
    if not autorun:
        cmd.append("-Pt1Autorun=false")
    cmd.append(RUN_TASK)
    print(f"[t1] launching {' '.join(cmd)} (DISPLAY={env['DISPLAY']}, wall={wall}s, "
          f"autorun={autorun})")
    proc = subprocess.Popen(cmd, cwd=REPO_ROOT, env=env, stdout=logf,
                            stderr=subprocess.STDOUT, start_new_session=True)
    return proc, logf


def stop_client(proc):
    """Stop the gradle wrapper's process group, then sweep the forked client JVM by PID."""
    try:
        os.killpg(os.getpgid(proc.pid), signal.SIGTERM)
    except (ProcessLookupError, PermissionError):
        pass
    sweep_client_jvms()


def mint_template(env, wall):
    """First-run-ever mint: create a pristine TestkitT1 (autorun OFF, no scenes), flush a
    clean save, kill the JVM, archive it as the template, then delete the mint copy.
    Returns True on success."""
    print("[t1] template ABSENT — minting a pristine template (autorun OFF, no scenes)")
    provision(reuse=False)
    client, _ = launch_client(env, wall, autorun=False)
    ok = True
    try:
        asyncio.run(mint_session(wall))
    except Exception as e:  # noqa: BLE001
        print(f"[t1] mint error (ENV): {e}")
        ok = False
    finally:
        stop_client(client)
    if ok:
        ok = archive_template()
    shutil.rmtree(WORLD_DIR, ignore_errors=True)
    return ok


def run(args):
    sweep_client_jvms()
    display = args.display or probe_free_display()
    xvfb = start_xvfb(display)
    env = dict(os.environ, DISPLAY=f":{display}")
    try:
        # First run ever: mint the pristine template before scoring. The scored run
        # ALWAYS uses a fresh copy of the clean template (create-path and reuse-path
        # converge on identical byte-clean world state → deterministic scenes).
        minted_now = False
        if not template_reuse(TEMPLATE_DIR):
            if not mint_template(env, args.wall):
                print("[t1] VERDICT: ENV — template mint failed")
                return 3, 0.0, False
            minted_now = True
        else:
            print("[t1] template PRESENT — reuse path")

        provision(reuse=True)
        footer = False
        env_err = None
        elapsed = 0.0
        # --hold ⇒ autorun OFF: enter the world but run NO scenes, so the integrated
        # server stays up in-world (harness only halt()s after a scored scene run).
        # instrument_client.py --attach then drives its checks against that live world.
        client, _ = launch_client(env, args.wall, autorun=not args.hold)
        t_launch = time.monotonic()
        try:
            footer = asyncio.run(run_session(args.wall, args.hold, client.pid))
        except Exception as e:  # noqa: BLE001 — any drive/connect failure = ENV
            env_err = e
            print(f"[t1] session error (ENV): {e}")
        finally:
            # Mirror mint_template: stop_client MUST run on EVERY exit — including
            # KeyboardInterrupt, which is the documented release path of --hold.
            # Leaving it in the normal flow orphaned the forked client JVM on
            # Ctrl-C (zombie session.lock disease; P2b Task 1 review I1).
            elapsed = time.monotonic() - t_launch
            print(f"[t1] session elapsed {elapsed:.1f}s (footer={footer})")
            stop_client(client)
    finally:
        kill_pid(xvfb.pid, "Xvfb")
        # A stale TESTKIT_ENDPOINT descriptor is the most dangerous residue this run can
        # leave behind — a JUnit consumer would happily attach to a port that now belongs
        # to a dead (or worse, unrelated future) process. Delete on every exit path
        # (Ctrl-C included — finally runs regardless of exception type), tolerant of it
        # never having been written (non-hold runs, or a hold run that died before the
        # in-world+port milestone).
        if os.path.exists(ENDPOINT_FILE):
            os.remove(ENDPOINT_FILE)
            print(f"[t1] deleted endpoint file {ENDPOINT_FILE}")
        if not args.keep_world:
            shutil.rmtree(WORLD_DIR, ignore_errors=True)
            print(f"[t1] deleted world copy {WORLD_DIR}")

    if env_err is not None or not footer:
        reason = env_err if env_err is not None else "no done footer within wall"
        print(f"[t1] VERDICT: ENV — {reason}")
        return 3, elapsed, minted_now

    code, report = judge(parse(RESULTS), expected=args.expected)
    for line in report:
        print(f"[t1] {line}")
    print(f"[t1] VERDICT: {['GREEN', 'RED', 'DEAD', 'ENV'][code]}  (elapsed {elapsed:.1f}s"
          f"{', minted template this run' if minted_now else ''})")
    return code, elapsed, minted_now


# -------------------------------------------------------------- self-test -----
def self_test():
    checks = [
        ("taken_displays parses X sockets",
         taken_displays(["/tmp/.X11-unix/X99", "/tmp/.X11-unix/X97", "/tmp/.X11-unix/Xbad"])
         == {99, 97}),
        ("pick_free_display skips taken", pick_free_display({101, 102}) == 103),
        ("pick_free_display default start 101", pick_free_display(set()) == 101),
        ("pick_free_display all-taken raises", _raises(lambda: pick_free_display(
            set(range(101, 990)), 101, 990))),
        ("template_reuse false when absent", template_reuse("/nonexistent/xyz") is False),
        ("template_reuse false when empty dir", _reuse_empty() is False),
        ("template_reuse true when populated", _reuse_populated() is True),
        ("verdict reused: green fixture -> 0", judge(_GREEN)[0] == 0),
        ("verdict reused: canary drift -> 2", judge(_DEAD)[0] == 2),
        ("load_expect_file reused parses names",
         load_expect_file(_expect_fixture()) == ["ad.one", "ad.two"]),
        ("parse_args default wall 900", _parse(["--self-test"]).wall == 900),
        ("parse_args default expect-file resolves",
         _parse([]).expected == _expected_from_default()),
        ("parse_args --expect-file overrides",
         _parse(["--expect-file", _expect_fixture()]).expected == ["ad.one", "ad.two"]),
        ("write_endpoint: full schema key set + value/type fidelity", _check_write_endpoint()),
        ("write_endpoint: atomic overwrite leaves no .tmp, second call wins",
         _check_write_endpoint_overwrite()),
        ("resolve_loader fabric task", resolve_loader("fabric").run_task
         == ":fabric:runTestkitClient"),
        ("resolve_loader neoforge task", resolve_loader("neoforge").run_task
         == ":neoforge:runTestkitClient"),
        ("resolve_loader fabric run-dir under fabric/",
         resolve_loader("fabric").run_dir == os.path.join(REPO_ROOT, "fabric", "run-t1")),
        ("resolve_loader neoforge run-dir under neoforge/",
         resolve_loader("neoforge").run_dir == os.path.join(REPO_ROOT, "neoforge", "run-t1")),
        ("resolve_loader neoforge port/results/saves/world derive from run-dir",
         _check_loader_paths_derive("neoforge")),
        ("resolve_loader templates are per-loader (distinct + suffixed)",
         resolve_loader("fabric").template_dir != resolve_loader("neoforge").template_dir
         and resolve_loader("neoforge").template_dir.endswith(".t1-world-template-neoforge")),
        ("resolve_loader default-expect per loader",
         resolve_loader("neoforge").default_expect
         == os.path.join("scripts", "testkit", "expected-scenes-neoforge.txt")),
        ("resolve_loader endpoint file under run-dir",
         resolve_loader("neoforge").endpoint_file
         == os.path.join(REPO_ROOT, "neoforge", "run-t1", "testkit-endpoint.json")),
        ("_install_loader mutates module state to neoforge", _check_install_loader()),
        ("parse_args default loader fabric", _parse(["--self-test"]).loader == "fabric"),
        ("parse_args --loader neoforge", _parse(["--loader", "neoforge"]).loader == "neoforge"),
        ("parse_args --loader neoforge resolves neoforge expect",
         _parse(["--loader", "neoforge"]).expected == _expected_from_loader("neoforge")),
        ("parse_args rejects unknown loader (argparse choices)",
         _raises_systemexit(lambda: _parse(["--loader", "quilt"]))),
    ]
    failed = [n for n, ok in checks if not ok]
    for n, ok in checks:
        print(f"  [{'PASS' if ok else 'FAIL'}] {n}")
    return 0 if not failed else 1


def _raises(fn):
    try:
        fn()
        return False
    except Exception:  # noqa: BLE001
        return True


def _raises_systemexit(fn):
    """argparse rejects an invalid choice via ap.error → sys.exit → SystemExit, which is a
    BaseException (NOT caught by _raises' `except Exception`). Suppress argparse's stderr
    usage dump so the self-test output stays clean."""
    import contextlib
    import io
    try:
        with contextlib.redirect_stderr(io.StringIO()):
            fn()
        return False
    except SystemExit:
        return True


def _reuse_empty():
    import tempfile
    d = tempfile.mkdtemp()
    try:
        return template_reuse(d)
    finally:
        os.rmdir(d)


def _reuse_populated():
    import tempfile
    d = tempfile.mkdtemp()
    try:
        open(os.path.join(d, "level.dat"), "w").close()
        return template_reuse(d)
    finally:
        shutil.rmtree(d)


def _check_write_endpoint():
    """write_endpoint's returned dict, and what actually landed on disk, must carry every
    schema v1 key with the right value AND the right JSON type (rpcPort/holdPid/
    writtenAtEpochMs are numbers, not stringly-typed — a JUnit-side gson record parse
    would fail loudly on that, but self-test should catch it first)."""
    import tempfile
    d = tempfile.mkdtemp()
    try:
        path = os.path.join(d, "testkit-endpoint.json")
        doc = write_endpoint(path, "fabric", 39843, 12345)
        if not os.path.isfile(path):
            return False
        with open(path, encoding="utf-8") as f:
            on_disk = json.load(f)
        if on_disk != doc:
            return False
        if set(doc.keys()) != {"version", "topology", "loader", "rpcHost", "rpcPort",
                                "worldName", "holdPid", "writtenAtEpochMs"}:
            return False
        return (
            doc["version"] == 1
            and doc["topology"] == "integrated_plus_client"
            and doc["loader"] == "fabric"
            and doc["rpcHost"] == "127.0.0.1"
            and doc["rpcPort"] == 39843 and isinstance(doc["rpcPort"], int)
            and doc["worldName"] == WORLD_NAME
            and doc["holdPid"] == 12345 and isinstance(doc["holdPid"], int)
            and isinstance(doc["writtenAtEpochMs"], int) and doc["writtenAtEpochMs"] > 0
        )
    finally:
        shutil.rmtree(d)


def _check_write_endpoint_overwrite():
    import tempfile
    d = tempfile.mkdtemp()
    try:
        path = os.path.join(d, "testkit-endpoint.json")
        write_endpoint(path, "fabric", 1111, 111)
        write_endpoint(path, "fabric", 2222, 222)
        if os.path.exists(path + ".tmp"):
            return False
        with open(path, encoding="utf-8") as f:
            on_disk = json.load(f)
        return on_disk["rpcPort"] == 2222 and on_disk["holdPid"] == 222
    finally:
        shutil.rmtree(d)


def _check_loader_paths_derive(name):
    lp = resolve_loader(name)
    return (
        lp.saves == os.path.join(lp.run_dir, "saves")
        and lp.world_dir == os.path.join(lp.run_dir, "saves", WORLD_NAME)
        and lp.port_file == os.path.join(lp.run_dir, "agent-rpc.port")
        and lp.results == os.path.join(lp.run_dir, "testkit-results.jsonl")
    )


def _check_install_loader():
    """_install_loader must swap the module globals wholesale, then restore fabric so the
    rest of self-test (which assumes fabric defaults) stays unaffected."""
    try:
        _install_loader("neoforge")
        ok = (
            RUN_TASK == ":neoforge:runTestkitClient"
            and RUN_DIR.endswith(os.path.join("neoforge", "run-t1"))
            and LOADER.loader == "neoforge"
            and TEMPLATE_DIR.endswith(".t1-world-template-neoforge")
            and DEFAULT_EXPECT.endswith("expected-scenes-neoforge.txt")
        )
    finally:
        _install_loader("fabric")
    return ok


def _expected_from_loader(name):
    path = os.path.join(REPO_ROOT, resolve_loader(name).default_expect)
    return sorted(set(load_expect_file(path)))


def _expect_fixture():
    import tempfile
    f = tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False)
    f.write("# hdr\nad.one\nad.two\n")
    f.close()
    return f.name


def _expected_from_default():
    path = os.path.join(REPO_ROOT, DEFAULT_EXPECT)
    return sorted(set(load_expect_file(path)))


_SUITE = {"type": "suite", "loader": "fabric", "registered": [
    {"name": "a", "required": True, "canary": "NONE"},
    {"name": "cf", "required": True, "canary": "MUST_FAIL"},
    {"name": "ct", "required": True, "canary": "MUST_TIMEOUT"}]}


def _sc(name, outcome):
    return {"type": "scene", "name": name, "outcome": outcome, "ticks": 1, "wallMs": 1, "reason": ""}


_GREEN = [_SUITE, _sc("a", "PASS"), _sc("cf", "FAIL"), _sc("ct", "TIMEOUT"),
          {"type": "done", "scenes": 3}]
_DEAD = [_SUITE, _sc("a", "PASS"), _sc("cf", "PASS"), _sc("ct", "TIMEOUT"),
         {"type": "done", "scenes": 3}]


# ---------------------------------------------------------------- argparse ----
def _parse(argv):
    ap = argparse.ArgumentParser(description="mc-testkit T1 orchestrator (client under Xvfb)")
    ap.add_argument("--loader", choices=LOADERS, default="fabric",
                    help="target loader (default fabric): selects <loader>/run-t1, the "
                         ":<loader>:runTestkitClient task, and expected-scenes-<loader>.txt")
    ap.add_argument("--wall", type=int, default=900, help="wall-clock cap in seconds")
    ap.add_argument("--expect-file", default=None,
                    help="expected-scene manifest (default expected-scenes-<loader>.txt)")
    ap.add_argument("--display", type=int, default=None,
                    help="force an Xvfb display number (default: probe free)")
    ap.add_argument("--hold", action="store_true",
                    help="enter world (autorun OFF, NO scenes) and stay online with the "
                         "integrated server live, for instrument_client --attach; no harvest")
    ap.add_argument("--keep-world", action="store_true",
                    help="do not delete the saves/TestkitT1 copy on exit (debug)")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args(argv)
    lp = resolve_loader(args.loader)
    if not args.self_test:
        path = args.expect_file or lp.default_expect
        if not os.path.isabs(path):
            path = os.path.join(REPO_ROOT, path)
        if not os.path.exists(path):
            ap.error(f"--expect-file not found: {path}")
        args.expected = sorted(set(load_expect_file(path)))
    else:
        args.expected = None
    return args


def main():
    args = _parse(sys.argv[1:])
    if args.self_test:
        sys.exit(self_test())
    _install_loader(args.loader)
    print(f"[t1] loader={args.loader}  task={RUN_TASK}  run-dir={RUN_DIR}")
    code, _elapsed, _archived = run(args)
    sys.exit(code)


if __name__ == "__main__":
    main()
