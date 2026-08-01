#!/usr/bin/env python3
"""mc-testkit T2 orchestrator (contract v0) — PRODUCTION topology (dedicated server + real client).

Where T1 proved the harness on an INTEGRATED server (a client hosting its own world),
T2 proves the true production topology: a plain DEDICATED server (t2Server, run-t2) and a
SEPARATE real game client (testkitClient, run-t1, under Xvfb) that DIRECT-CONNECTS to it over
multiplayer. This task (P3a Task 2) builds the topology SHELL: it stands both processes up,
drives the client from the title screen through the multiplayer direct-connect flow, and proves
the dual end is live — the client is in-world AND the dedicated server's PlayerList holds a real
ServerPlayer. Scene execution over this topology and the JUnit attach endpoint come in Tasks 3-4.

Two loaders, one shell (--loader {fabric,neoforge}, default fabric): the server is the loader's
:<loader>:runT2Server (run-t2); the client is the loader's :<loader>:runTestkitClient (run-t1,
reused verbatim from T1 via importing t1.py — NOT forked). Flow:

  mint (no per-loader template yet):
    boot t2Server clean (autorun OFF) → wait run-t2/agent-rpc.port → connect server RPC → poll
    until the world is loaded (mc.observe.player answers; it asserts an attached server, so a
    successful reply == SERVER_STARTED + overworld loaded — worldReady itself is client-only and
    throws on a dedicated server, so it can't be the gate here) → clean stop (SIGTERM the gradle
    process group, bounded wait, cwd-scoped JVM sweep) → verify run-t2/world/level.dat → archive
    run-t2/world → scripts/testkit/.t2-world-template-<loader> → delete the mint world copy.

  scored (P3a Task 3):
    copy template → run-t2/world → boot t2Server (autorun OFF) → discover server RPC port + wait
    world ready → boot the T1 client (testkitClient, run-t1, autorun OFF, Xvfb) → discover client
    RPC port → guidrive: title → Multiplayer → (online-play warning if present) → Direct Connection →
    127.0.0.1:<server-port> → Join Server → in-world → DUAL-END PROBE (client mc.client.player has a
    pos AND server mc.observe.player is present in the DEDICATED PlayerList) → fire mc.test.run over
    the SERVER RPC (bare envelope; assert accepted:true) → poll run-t2/testkit-results.jsonl for the
    done footer → judge via verdict.py + expected-scenes-<loader>.txt (reconciled against the suite
    header's registered[]) → exit 0/1/2/3. The three byte-metric goldens (descentYaw, selfShaftDigUp
    worstBackslide, gearScope attributes) are asserted INSIDE the scenes, so a scene PASS == byte-hit.

  --hold (P3a Task 3): stand the dedicated_plus_client topology up, run NO scenes, and stay online
    for a JUnit attach. After the dual-end probe passes, write run-t2/testkit-endpoint.json (schema
    v1: topology="dedicated_plus_client", rpcPort=CLIENT RPC port, NEW OPTIONAL serverRpcPort=SERVER
    RPC port), print `export TESTKIT_ENDPOINT=<abs path>`, and idle until Ctrl-C. The endpoint file is
    deleted in the finally on EVERY exit path (Ctrl-C included).

  teardown (finally, EVERY exit path): client first (quit-to-title best effort → PID kill + client
    JVM sweep), server second (SIGTERM the gradle group → bounded wait → cwd-scoped JVM sweep),
    delete the endpoint descriptor, delete run-t2/world, remove both agent-rpc.port files, kill
    Xvfb. Never pkill.

Exit codes (T2 scored semantics, verdict.py-judged like T0/T1):
  0 GREEN  — done footer present, verdict GREEN (every scene on its expected outcome; goldens hit)
  1 RED    — done footer present, a non-canary scene failed / reconciliation failed
  2 DEAD   — done footer present, a canary landed on the WRONG outcome (framework void)
  3 ENV    — a process never came up / GUI drive failed / probe never satisfied / no done footer

The server-port (the multiplayer listen socket) is pinned per loader in run-t2/server.properties,
which this orchestrator GENERATES-AND-PINS on every provision (run-t2 is gitignored, so the file is
never checked in — deterministic regeneration is the single source). t2.py reads the port back from
that file for the client connect address.
"""
import argparse
import asyncio
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
import platform_compat  # noqa: E402
import t1  # noqa: E402 — REUSED Xvfb/launch/stop/sweep/kill helpers, not forked
from verdict import parse, judge  # noqa: E402 — REUSED judging logic, not forked
from t0 import load_expect_file  # noqa: E402 — REUSED expect-file parser, not forked

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TESTKIT_DIR = os.path.join(REPO_ROOT, "scripts", "testkit")
LOADERS = ("fabric", "neoforge")
WORLD_NAME = "world"  # a dedicated server always names its level "world" (server.properties level-name)

# Per-loader multiplayer listen ports, chosen DISTINCT from the dogfood server's 25599 so a t2 run
# never collides with a dogfood run on either loader (and fabric != neoforge so both can run at once).
SERVER_PORTS = {"fabric": 25597, "neoforge": 25596}


@dataclass(frozen=True)
class T2Paths:
    """Every loader-specific path/task the T2 SERVER side needs, resolved once from --loader.
    The CLIENT side reuses T1's run-t1 tree/task via t1._install_loader(loader) (the client is
    byte-identical to T1's — only the server topology is new here). Pure/derivable, so self-test
    can assert both loaders' resolution without a live process."""
    loader: str
    run_dir: str            # run-t2 (dedicated server working dir)
    port_file: str          # run-t2/agent-rpc.port (server RPC)
    world_dir: str          # run-t2/world
    template_dir: str       # scripts/testkit/.t2-world-template-<loader>
    run_task: str           # :<loader>:runT2Server
    server_properties: str  # run-t2/server.properties
    server_port: int        # multiplayer listen socket
    results: str            # run-t2/testkit-results.jsonl (harness writes here, cwd-relative)
    endpoint_file: str      # run-t2/testkit-endpoint.json (--hold attach descriptor)
    default_expect: str     # scripts/testkit/expected-scenes-<loader>.txt (reconciliation gate)


def resolve_t2(name):
    """Derive the T2Paths for ``name`` (fabric|neoforge). Templates are per-loader
    (``.t2-world-template-<loader>``) so a fabric-minted world never seeds a neoforge run."""
    run_dir = os.path.join(REPO_ROOT, name, "run-t2")
    return T2Paths(
        loader=name,
        run_dir=run_dir,
        port_file=os.path.join(run_dir, "agent-rpc.port"),
        world_dir=os.path.join(run_dir, "world"),
        template_dir=os.path.join(TESTKIT_DIR, f".t2-world-template-{name}"),
        run_task=f":{name}:runT2Server",
        server_properties=os.path.join(run_dir, "server.properties"),
        server_port=SERVER_PORTS[name],
        # The dedicated harness writes OUT_FILE="testkit-results.jsonl" relative to its own
        # working directory (loom launches the forked server with cwd == run-t2), so the scored
        # footer lands here — the exact same cwd-relative contract T0/T1 rely on.
        results=os.path.join(run_dir, "testkit-results.jsonl"),
        endpoint_file=os.path.join(run_dir, "testkit-endpoint.json"),
        default_expect=os.path.join("scripts", "testkit", f"expected-scenes-{name}.txt"),
    )


# --------------------------------------------------------- pure decisions ----
def render_server_properties(port):
    """The server.properties body t2.py pins into run-t2 (pure, for self-test). online-mode=false so
    the offline dev client can join; a fixed server-port so t2.py knows the connect address; a flat
    level for fast, deterministic generation; spawn-protection=0 so the joining player isn't shoved."""
    return (
        "#mc-testkit T2 dedicated server (generated by t2.py)\n"
        f"server-port={port}\n"
        "online-mode=false\n"
        "level-type=minecraft\\:flat\n"
        "level-name=world\n"
        "spawn-protection=0\n"
        "sync-chunk-writes=false\n"
        "max-players=8\n"
        "motd=mc-testkit T2\n"
    )


def parse_server_port(text):
    """Parse the ``server-port`` value out of a server.properties body (pure, for self-test).
    Returns the int port, or None if absent/malformed. Mirrors java.util.Properties line handling
    enough for our own generated file (ignores blanks and '#'/'!' comments, splits on the first '=')."""
    for line in text.splitlines():
        s = line.strip()
        if not s or s[0] in "#!":
            continue
        if "=" not in s:
            continue
        key, val = s.split("=", 1)
        if key.strip() == "server-port":
            try:
                return int(val.strip())
            except ValueError:
                return None
    return None


def read_server_port(properties_path):
    """Read the pinned server-port back off disk (used for the client connect address)."""
    with open(properties_path, encoding="utf-8") as f:
        port = parse_server_port(f.read())
    if port is None:
        raise RuntimeError(f"no server-port in {properties_path}")
    return port


def level_dat_present(world_dir):
    """A dedicated server writes world/level.dat once the level is created (pure, for self-test)."""
    return os.path.isfile(os.path.join(world_dir, "level.dat"))


def write_endpoint(path, loader, client_port, server_port, pid):
    """Write the TESTKIT_ENDPOINT descriptor (schema v1) for the T2 ``dedicated_plus_client``
    topology, atomically (``<path>.tmp`` then os.replace, so a JUnit-side reader never sees a
    partial file). Same frozen v1 required-8 keys as t1's ``write_endpoint`` — with two T2
    differences that stay v1-COMPATIBLE (the required-8 set is unchanged):
      * ``topology`` is ``"dedicated_plus_client"`` (client attaches to a SEPARATE dedicated
        server, not a client-hosted integrated server);
      * ``rpcPort`` is the **CLIENT** RPC port — the JUnit UI scenes only touch the client face,
        so the attach socket (``wsUri()``) must land on the client;
      * NEW OPTIONAL ``serverRpcPort`` = the dedicated server's RPC port (present only on T2
        endpoints; T1 descriptors omit it). Endpoint.java parses it optionally and tolerates its
        absence, so a T1 endpoint still parses and the frozen contract does not break.
    ``worldName`` is the dedicated server's level name ("world"). Pure aside from the write —
    every varying value is a parameter — so self-test exercises it without a live topology.
    Returns the dict that was written."""
    doc = {
        "version": 1,
        "topology": "dedicated_plus_client",
        "loader": loader,
        "rpcHost": "127.0.0.1",
        "rpcPort": client_port,
        "worldName": WORLD_NAME,
        "holdPid": pid,
        "writtenAtEpochMs": int(time.time() * 1000),
        "serverRpcPort": server_port,
    }
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(doc, f, indent=2)
    os.replace(tmp, path)
    return doc


# ------------------------------------------------------------ process mgmt ----
def sweep_server_jvms(run_dir):
    """Kill any leftover T2 dedicated-server game JVM by explicit PID (pkill is banned). The tie-break
    is the process's working directory: loom launches the forked server with cwd == run-t2, so a
    /proc/<pid>/cwd that resolves to run-t2's absolute path is unambiguously OUR server (never the
    gradle wrapper — its cwd is the repo root — and never a dogfood/contract/other run in a different
    dir). This is loader-neutral and does not depend on how loom lays out the -D system-property argv."""
    run_abs = os.path.abspath(run_dir)
    killed = []
    for pid, cmdline in platform_compat.iter_processes():
        if "java" not in cmdline:
            continue
        cwd = platform_compat.process_cwd(pid)
        if cwd is not None:
            owned = cwd == run_abs
            why = f"cwd={run_abs}"
        else:
            # Windows: no readable cwd (see platform_compat.process_cwd). Fall back to
            # the run dir appearing in the argv — loom puts the natives/assets paths
            # under it, so a forked run-t2 JVM names it even though its cwd is the only
            # airtight proof. Weaker, and deliberately visible in the log line.
            owned = run_abs in cmdline or os.path.basename(run_abs) in cmdline
            why = f"argv~{os.path.basename(run_abs)}"
        if owned:
            print(f"[t2] killing leftover T2 server JVM pid={pid} ({why})")
            platform_compat.kill_pid(pid)
            killed.append(str(pid))
    return killed


def launch_server(t2, env, autorun, wall):
    """Launch the dedicated t2Server (:<loader>:runT2Server). --no-daemon + start_new_session so the
    whole gradle→game process tree is one killable group. -Pt2Autorun controls testkit.autorun."""
    os.makedirs(t2.run_dir, exist_ok=True)
    logf = open(os.path.join(t2.run_dir, "t2-server.log"), "w")
    cmd = platform_compat.gradlew_cmd(
        "--no-daemon", f"-Pt2Autorun={'true' if autorun else 'false'}", t2.run_task)
    print(f"[t2] launching {' '.join(cmd)} (wall={wall}s, autorun={autorun})")
    proc = subprocess.Popen(cmd, cwd=REPO_ROOT, env=env, stdout=logf,
                            stderr=subprocess.STDOUT, **platform_compat.detach_kwargs())
    return proc, logf


def stop_server(proc, run_dir):
    """Stop the server gradle process tree, then sweep the forked game JVM by cwd. Mirrors
    t1.stop_client's belt-and-suspenders (tree signal for the clean path, PID sweep for the strays)."""
    if proc is not None:
        platform_compat.kill_tree(proc)
        print(f"[t2] stopped server gradle tree pid={proc.pid}")
        # Bounded wait for the group to drain before the hard sweep.
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline and proc.poll() is None:
            time.sleep(0.5)
    sweep_server_jvms(run_dir)


# ---------------------------------------------------------------- readiness ---
async def wait_server_world_ready(rpc, timeout=180):
    """Poll the dedicated server until its world is loaded. mc.observe.player asserts an attached
    server (api.level()), so the FIRST non-erroring reply means SERVER_STARTED fired and the overworld
    is loaded — before that it raises "no server attached". Returns when ready; raises on timeout.
    (mc.wait.worldReady is deliberately NOT used: it requires a bound CLIENT and throws on a server.)"""
    start = time.time()
    last = None
    while time.time() - start < timeout:
        try:
            await rpc.call("mc.observe.player", timeout=8)
            print(f"[t2] server world ready after {time.time()-start:.1f}s")
            return
        except Exception as e:  # noqa: BLE001 — not-yet-attached raises; retry
            last = e
            await asyncio.sleep(1.0)
    raise TimeoutError(f"server world not ready within {timeout}s (last={last})")


# --------------------------------------------------------------- provisioning -
def provision_server(t2, reuse):
    """Prepare run-t2 before a server boot: eula, generated-and-pinned server.properties, a fresh
    (or template-seeded) world, no stale port file. Returns nothing."""
    os.makedirs(t2.run_dir, exist_ok=True)
    with open(os.path.join(t2.run_dir, "eula.txt"), "w") as f:
        f.write("eula=true\n")
    with open(t2.server_properties, "w") as f:
        f.write(render_server_properties(t2.server_port))
    if os.path.exists(t2.port_file):
        os.remove(t2.port_file)
    # Never let a scored run judge a STALE footer from a previous run: clear the results file
    # (and any orphan endpoint descriptor) before the server boots and (re)writes it.
    for stale in (t2.results, t2.endpoint_file):
        if os.path.exists(stale):
            os.remove(stale)
    shutil.rmtree(t2.world_dir, ignore_errors=True)  # never reuse a dirty world
    if reuse:
        print(f"[t2] copying template → {t2.world_dir}")
        shutil.copytree(t2.template_dir, t2.world_dir)


def provision_client(loader):
    """Prepare run-t1 for the T2 client boot (installs T1's module state for the loader first). The
    client connects to a REMOTE server, so — unlike T1 — there is no singleplayer world to seed; we
    only need options.txt (straight to TitleScreen, no narrator, and skip the one-time multiplayer
    online-play warning) and a cleared stale port file. Reuses t1's run-t1 paths, not a fork."""
    t1._install_loader(loader)
    os.makedirs(t1.RUN_DIR, exist_ok=True)
    with open(os.path.join(t1.RUN_DIR, "options.txt"), "w") as f:
        f.write("onboardAccessibility:false\nnarrator:0\nskipMultiplayerWarning:true\n")
    if os.path.exists(t1.PORT_FILE):
        os.remove(t1.PORT_FILE)


def archive_template(t2):
    """Archive the pristine minted world to the per-loader template cache for future scored runs."""
    if not level_dat_present(t2.world_dir):
        print("[t2] WARN: world/level.dat absent at archive time — cannot cache template")
        return False
    shutil.rmtree(t2.template_dir, ignore_errors=True)
    shutil.copytree(t2.world_dir, t2.template_dir)
    print(f"[t2] archived pristine world → {t2.template_dir}")
    return True


# ------------------------------------------------------------- orchestration --
async def mint_session(t2, wall):
    """Connect to the freshly-booted dedicated server and wait for its world to load. NO client, NO
    scenes — the world is generated byte-clean, then the caller stops the server and archives it."""
    port = await gd.discover_port(Path(t2.port_file), timeout=max(30, wall))
    print(f"[t2] (mint) discovered server agent-rpc port={port}")
    ws = await gd.connect(port)
    async with ws:
        rpc = gd.Rpc(ws)
        await wait_server_world_ready(rpc)
        print("[t2] (mint) server world loaded — will stop + archive")


def mint_template(t2, env, wall):
    """First-run-ever mint: boot the dedicated server clean (autorun OFF), let it generate world/,
    stop cleanly, archive the world as the per-loader template, delete the mint copy. Returns True."""
    print("[t2] template ABSENT — minting a pristine T2 world (dedicated server, autorun OFF)")
    provision_server(t2, reuse=False)
    server, _ = launch_server(t2, env, autorun=False, wall=wall)
    ok = True
    try:
        asyncio.run(mint_session(t2, wall))
    except Exception as e:  # noqa: BLE001
        print(f"[t2] mint error (ENV): {e}")
        ok = False
    finally:
        stop_server(server, t2.run_dir)
    if ok:
        if not level_dat_present(t2.world_dir):
            print(f"[t2] mint FAILED: {t2.world_dir}/level.dat never written")
            ok = False
        else:
            ok = archive_template(t2)
    shutil.rmtree(t2.world_dir, ignore_errors=True)
    if os.path.exists(t2.port_file):
        os.remove(t2.port_file)
    return ok


async def _server_ready(t2):
    """Discover the server RPC port and wait for its world to load (single event loop — a websocket
    is bound to the loop it was opened in, so connect + poll must live in one asyncio.run)."""
    port = await gd.discover_port(Path(t2.port_file), timeout=180)
    print(f"[t2] discovered server agent-rpc port={port}")
    ws = await gd.connect(port)
    async with ws:
        await wait_server_world_ready(gd.Rpc(ws))


async def _client_probe(t2, client_port):
    """Open the client socket + a fresh server socket in ONE loop, drive the multiplayer direct-connect,
    and run the dual-end probe. Returns the evidence dict (ok + both raw player snapshots + the
    discovered server RPC port, which the scored trigger / --hold endpoint write both need)."""
    ws = await gd.connect(client_port)
    async with ws:
        client_rpc = gd.Rpc(ws)
        await gd.wait_api_ready(client_rpc)
        print("[t2] client API reachable at title")
        server_rpc_port = await gd.discover_port(Path(t2.port_file), timeout=60)
        server_ws = await gd.connect(server_rpc_port)
        async with server_ws:
            server_rpc = gd.Rpc(server_ws)
            address = f"127.0.0.1:{t2.server_port}"
            print(f"[t2] driving multiplayer direct-connect → {address}")
            await gd.drive_multiplayer_connect(client_rpc, address)
            # DUAL-END PROBE: client in-world AND a real player in the DEDICATED PlayerList.
            client_player = await client_rpc.call("mc.client.player")
            server_player = await server_rpc.call("mc.observe.player")
            client_pos = client_player.get("pos") if isinstance(client_player, dict) else None
            server_present = isinstance(server_player, dict) and server_player.get("present") is True
            print(f"[t2] client mc.client.player: present="
                  f"{client_player.get('present') if isinstance(client_player, dict) else None} "
                  f"pos={client_pos}")
            print(f"[t2] server mc.observe.player: present={server_present} "
                  f"name={server_player.get('name') if isinstance(server_player, dict) else None} "
                  f"pos={server_player.get('pos') if isinstance(server_player, dict) else None}")
            return {"ok": bool(client_pos) and server_present,
                    "client_player": client_player, "server_player": server_player,
                    "server_rpc_port": server_rpc_port}


async def _trigger_scene_run(server_rpc_port):
    """Scored path only: open a fresh server socket and fire the on-demand suite trigger
    (``mc.test.run``, bare envelope — the hidden verb takes no params). Assert the accept
    envelope ({accepted:true, scenes:N}) and return it; the JSONL done footer (harvested off
    the results file, NOT this socket) remains the sole completion signal. Raises if the run
    is not accepted (e.g. the server is stopping, or the suite already ran)."""
    server_ws = await gd.connect(server_rpc_port)
    async with server_ws:
        server_rpc = gd.Rpc(server_ws)
        resp = await server_rpc.call("mc.test.run", timeout=30)
        accepted = isinstance(resp, dict) and resp.get("accepted") is True
        scenes = resp.get("scenes") if isinstance(resp, dict) else None
        print(f"[t2] mc.test.run → {resp}")
        if not accepted:
            raise RuntimeError(f"mc.test.run not accepted: {resp}")
        print(f"[t2] scene suite ACCEPTED (registered scenes={scenes}) — harvesting done footer")
        return resp


def _best_effort_quit_client(client_port):
    """Disconnect the client from the dedicated server before we kill it (so the server drops the
    ServerPlayer cleanly rather than timing the connection out). Best-effort + fully bounded: any
    failure is swallowed (the PID kill is the real teardown). Deliberately does NOT reuse
    guidrive.quit_to_title — that waits for the TitleScreen, but a MULTIPLAYER "Disconnect" lands on
    the JoinMultiplayerScreen (server list), so we wait only until we've left the world."""
    if client_port is None:
        return

    async def _go():
        ws = await gd.connect(client_port, retries=3)
        async with ws:
            rpc = gd.Rpc(ws)
            info = await rpc.call("mc.client.screen.info")
            if not info.get("worldOpen"):
                return
            await rpc.call("mc.client.input.key", {"key": "ESCAPE"})
            await gd.wait_until(rpc, lambda i: i.get("type") == "PauseScreen",
                                label="PauseScreen", timeout=15, poll=0.5)
            tree = await rpc.call("mc.client.screen.tree")
            btn = (gd.find_widget(tree, gd.by_label("Disconnect"))
                   or gd.find_widget(tree, gd.by_label("Save and Quit"))
                   or gd.find_widget(tree, gd.by_label("Quit to Title")))
            if btn is None:
                return
            await gd.click_widget(rpc, btn, why="disconnect from dedicated server")
            await gd.wait_until(rpc, lambda i: not i.get("worldOpen"),
                                label="left-world", timeout=20, poll=0.5)
            print("[t2] client disconnected from dedicated server")
    asyncio.run(_go())


def run(args):
    t2 = resolve_t2(args.loader)
    # Pre-clean any strays from a previous crashed run BEFORE we start.
    sweep_server_jvms(t2.run_dir)
    t1._install_loader(args.loader)
    t1.sweep_client_jvms()

    minted_now = False
    if not t1.template_reuse(t2.template_dir):
        if not mint_template(t2, dict(os.environ), args.wall):
            print("[t2] VERDICT: ENV — template mint failed")
            return 3, minted_now
        minted_now = True
    else:
        print("[t2] template PRESENT — reuse path")

    # See t1.run(): display acquisition is platform-specific and lives in platform_compat.
    disp = platform_compat.display_session(t1.probe_free_display, args.display)
    client_env = disp.env
    server_env = dict(os.environ)  # dedicated server is headless — no DISPLAY needed

    server = None
    client = None
    client_port = None
    env_err = None
    probe_ok = False
    footer = False
    elapsed = 0.0
    t_start = time.monotonic()
    try:
        provision_server(t2, reuse=True)
        server, _ = launch_server(t2, server_env, autorun=False, wall=args.wall)
        # Gate on the server world BEFORE booting the client, so the direct-connect never races an
        # unloaded overworld (in practice the ~minutes-long client boot dwarfs server world-load).
        asyncio.run(_server_ready(t2))

        provision_client(args.loader)
        client, _ = t1.launch_client(client_env, args.wall, autorun=False, log_name="t2-client.log")
        client_port = asyncio.run(gd.discover_port(Path(t1.PORT_FILE), timeout=args.wall))
        print(f"[t2] discovered client agent-rpc port={client_port}")
        evidence = asyncio.run(_client_probe(t2, client_port))
        probe_ok = evidence["ok"]
        server_rpc_port = evidence.get("server_rpc_port")
        print(f"[t2] dual-end probe {'PASS' if probe_ok else 'FAIL'}")

        if probe_ok and args.hold:
            # --hold: NO scenes run. Both processes stay up with the RPC live; publish the
            # dedicated_plus_client endpoint (rpcPort=CLIENT face — the JUnit UI scenes only touch
            # the client; serverRpcPort=SERVER for a future dual-socket consumer) and idle until
            # Ctrl-C. The KeyboardInterrupt is a BaseException — it slips past `except Exception`
            # straight into the finally teardown, which deletes the endpoint file on EVERY exit
            # path (mirrors t1 --hold's documented release + endpoint-residue discipline).
            write_endpoint(t2.endpoint_file, t2.loader, client_port, server_rpc_port, client.pid)
            print(f"export TESTKIT_ENDPOINT={t2.endpoint_file}")
            print(f"[t2] --hold: dedicated_plus_client topology online (client rpc={client_port}, "
                  f"server rpc={server_rpc_port}), NO scenes; staying up for JUnit attach. "
                  "Ctrl-C to release.")
            while True:
                time.sleep(5)

        if probe_ok:  # scored: fire mc.test.run over the SERVER RPC, then harvest the footer
            asyncio.run(_trigger_scene_run(server_rpc_port))
            # The dedicated harness writes its JSONL to run-t2 and halt()s the server on the done
            # footer (disconnecting the client) — harvest is a pure file poll, socket-independent.
            footer = t1.harvest_footer(t2.results, t_start + args.wall, server)
            if not footer:
                print("[t2] no done footer within wall — scored run incomplete")
    except Exception as e:  # noqa: BLE001 — any drive/connect failure = ENV
        env_err = e
        print(f"[t2] session error (ENV): {e}")
    finally:
        # Teardown: CLIENT first (quit-to-title best effort → PID kill + JVM sweep) ...
        if client is not None:
            try:
                _best_effort_quit_client(client_port)
            except Exception as e:  # noqa: BLE001
                print(f"[t2] client quit-to-title best-effort failed ({e})")
            t1.stop_client(client)
        # ... SERVER second (SIGTERM group → bounded wait → cwd sweep) ...
        stop_server(server, t2.run_dir)
        # ... then the display, world copy, the endpoint descriptor, both port files.
        disp.close()
        # A stale endpoint descriptor is the most dangerous residue a --hold run can leave — a
        # JUnit consumer would attach to a port now dead (or worse, reused). Delete on every exit
        # path (Ctrl-C included), tolerant of it never having been written (scored / early-fail).
        if os.path.exists(t2.endpoint_file):
            os.remove(t2.endpoint_file)
            print(f"[t2] deleted endpoint file {t2.endpoint_file}")
        shutil.rmtree(t2.world_dir, ignore_errors=True)
        print(f"[t2] deleted world copy {t2.world_dir}")
        for pf in (t2.port_file, t1.PORT_FILE):
            if os.path.exists(pf):
                os.remove(pf)
                print(f"[t2] removed port file {pf}")
        elapsed = time.monotonic() - t_start
        print(f"[t2] session elapsed {elapsed:.1f}s (probe_ok={probe_ok}, footer={footer})")

    # Verdict (scored path only — --hold never returns here: its Ctrl-C propagates out of the
    # while-loop through the finally and on out of run()).
    if env_err is not None or not probe_ok:
        reason = env_err if env_err is not None else "dual-end probe not satisfied"
        print(f"[t2] VERDICT: ENV — {reason}")
        return 3, minted_now
    if not footer:
        print("[t2] VERDICT: ENV — no done footer within wall")
        return 3, minted_now
    code, report = judge(parse(t2.results), expected=args.expected)
    for line in report:
        print(f"[t2] {line}")
    print(f"[t2] VERDICT: {['GREEN', 'RED', 'DEAD', 'ENV'][code]}  (elapsed {elapsed:.1f}s"
          f"{', minted template this run' if minted_now else ''})")
    return code, minted_now


# -------------------------------------------------------------- self-test -----
def self_test():
    checks = [
        ("resolve_t2 fabric task", resolve_t2("fabric").run_task == ":fabric:runT2Server"),
        ("resolve_t2 neoforge task", resolve_t2("neoforge").run_task == ":neoforge:runT2Server"),
        ("resolve_t2 fabric run-dir under fabric/run-t2",
         resolve_t2("fabric").run_dir == os.path.join(REPO_ROOT, "fabric", "run-t2")),
        ("resolve_t2 neoforge run-dir under neoforge/run-t2",
         resolve_t2("neoforge").run_dir == os.path.join(REPO_ROOT, "neoforge", "run-t2")),
        ("resolve_t2 port/world/props derive from run-dir", _check_paths_derive("neoforge")),
        ("resolve_t2 templates per-loader (distinct + suffixed)",
         resolve_t2("fabric").template_dir != resolve_t2("neoforge").template_dir
         and resolve_t2("neoforge").template_dir.endswith(".t2-world-template-neoforge")),
        ("resolve_t2 fabric server-port 25597", resolve_t2("fabric").server_port == 25597),
        ("resolve_t2 neoforge server-port 25596", resolve_t2("neoforge").server_port == 25596),
        ("t2 ports distinct from dogfood 25599",
         25599 not in (resolve_t2("fabric").server_port, resolve_t2("neoforge").server_port)),
        ("t2 fabric/neoforge ports distinct",
         resolve_t2("fabric").server_port != resolve_t2("neoforge").server_port),
        ("render_server_properties has online-mode=false",
         "online-mode=false" in render_server_properties(25597)),
        ("render_server_properties pins the given port",
         "server-port=25597" in render_server_properties(25597)),
        ("parse_server_port round-trips render (fabric)",
         parse_server_port(render_server_properties(25597)) == 25597),
        ("parse_server_port round-trips render (neoforge)",
         parse_server_port(render_server_properties(25596)) == 25596),
        ("parse_server_port ignores comments/blank lines",
         parse_server_port("#server-port=1\n\n  \nserver-port=25555\n") == 25555),
        ("parse_server_port first wins",
         parse_server_port("server-port=25501\nserver-port=25502\n") == 25501),
        ("parse_server_port absent -> None", parse_server_port("motd=x\nonline-mode=false\n") is None),
        ("parse_server_port malformed -> None", parse_server_port("server-port=notaport\n") is None),
        ("level_dat_present false when absent", level_dat_present("/nonexistent/xyz") is False),
        ("level_dat_present true when file exists", _check_level_dat_present()),
        ("template_reuse (t1 helper) false when absent",
         t1.template_reuse("/nonexistent/xyz") is False),
        ("resolve_t2 results/endpoint derive from run-dir",
         resolve_t2("fabric").results == os.path.join(resolve_t2("fabric").run_dir, "testkit-results.jsonl")
         and resolve_t2("fabric").endpoint_file
         == os.path.join(resolve_t2("fabric").run_dir, "testkit-endpoint.json")),
        ("resolve_t2 default-expect per loader",
         resolve_t2("neoforge").default_expect
         == os.path.join("scripts", "testkit", "expected-scenes-neoforge.txt")),
        ("write_endpoint: T2 schema (dedicated_plus_client + serverRpcPort) value/type fidelity",
         _check_write_endpoint()),
        ("write_endpoint: atomic overwrite leaves no .tmp, second call wins",
         _check_write_endpoint_overwrite()),
        ("verdict reused: green fixture -> 0", judge(_GREEN)[0] == 0),
        ("verdict reused: canary drift -> 2 DEAD", judge(_DEAD)[0] == 2),
        ("load_expect_file (t0 helper) parses names", load_expect_file(_expect_fixture()) == ["ad.one", "ad.two"]),
        ("parse_args default loader fabric", _parse(["--self-test"]).loader == "fabric"),
        ("parse_args --loader neoforge", _parse(["--loader", "neoforge"]).loader == "neoforge"),
        ("parse_args rejects unknown loader", _raises_systemexit(lambda: _parse(["--loader", "quilt"]))),
        ("parse_args default wall 900", _parse(["--self-test"]).wall == 900),
        ("parse_args --hold default false", _parse(["--self-test"]).hold is False),
        ("parse_args --hold sets true", _parse(["--hold"]).hold is True),
        ("parse_args default expect resolves fabric manifest", _parse([]).expected == _expected_default("fabric")),
        ("parse_args --loader neoforge resolves neoforge expect",
         _parse(["--loader", "neoforge"]).expected == _expected_default("neoforge")),
        ("parse_args --expect-file overrides", _parse(["--expect-file", _expect_fixture()]).expected
         == ["ad.one", "ad.two"]),
    ]
    failed = [n for n, ok in checks if not ok]
    for n, ok in checks:
        print(f"  [{'PASS' if ok else 'FAIL'}] {n}")
    return 0 if not failed else 1


def _check_paths_derive(name):
    t2 = resolve_t2(name)
    return (
        t2.port_file == os.path.join(t2.run_dir, "agent-rpc.port")
        and t2.world_dir == os.path.join(t2.run_dir, "world")
        and t2.server_properties == os.path.join(t2.run_dir, "server.properties")
    )


def _check_level_dat_present():
    import tempfile
    d = tempfile.mkdtemp()
    try:
        open(os.path.join(d, "level.dat"), "w").close()
        return level_dat_present(d)
    finally:
        shutil.rmtree(d)


def _check_write_endpoint():
    """write_endpoint's returned dict and what lands on disk must carry the frozen v1 required-8
    keys PLUS the new optional serverRpcPort, all rightly-typed (rpcPort/serverRpcPort/holdPid are
    numbers, not stringly-typed — a gson record parse fails loudly on that; self-test catches it
    first), with the T2-specific topology + client/server port split."""
    import tempfile
    d = tempfile.mkdtemp()
    try:
        path = os.path.join(d, "testkit-endpoint.json")
        doc = write_endpoint(path, "fabric", 39843, 39777, 12345)
        if not os.path.isfile(path):
            return False
        with open(path, encoding="utf-8") as f:
            on_disk = json.load(f)
        if on_disk != doc:
            return False
        if set(doc.keys()) != {"version", "topology", "loader", "rpcHost", "rpcPort",
                               "worldName", "holdPid", "writtenAtEpochMs", "serverRpcPort"}:
            return False
        return (
            doc["version"] == 1
            and doc["topology"] == "dedicated_plus_client"
            and doc["loader"] == "fabric"
            and doc["rpcHost"] == "127.0.0.1"
            and doc["rpcPort"] == 39843 and isinstance(doc["rpcPort"], int)
            and doc["serverRpcPort"] == 39777 and isinstance(doc["serverRpcPort"], int)
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
        write_endpoint(path, "fabric", 1111, 1001, 111)
        write_endpoint(path, "fabric", 2222, 2002, 222)
        if os.path.exists(path + ".tmp"):
            return False
        with open(path, encoding="utf-8") as f:
            on_disk = json.load(f)
        return (on_disk["rpcPort"] == 2222 and on_disk["serverRpcPort"] == 2002
                and on_disk["holdPid"] == 222)
    finally:
        shutil.rmtree(d)


def _expect_fixture():
    import tempfile
    f = tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False)
    f.write("# hdr\nad.one\nad.two\n")
    f.close()
    return f.name


def _expected_default(name):
    path = os.path.join(REPO_ROOT, resolve_t2(name).default_expect)
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


def _raises_systemexit(fn):
    import contextlib
    import io
    try:
        with contextlib.redirect_stderr(io.StringIO()):
            fn()
        return False
    except SystemExit:
        return True


# ---------------------------------------------------------------- argparse ----
def _parse(argv):
    ap = argparse.ArgumentParser(
        description="mc-testkit T2 orchestrator (dedicated server + real client, multiplayer)")
    ap.add_argument("--loader", choices=LOADERS, default="fabric",
                    help="target loader (default fabric): selects <loader>/run-t2 + :<loader>:runT2Server "
                         "for the server, and the T1 run-t1/runTestkitClient for the client")
    ap.add_argument("--wall", type=int, default=900, help="wall-clock cap in seconds")
    ap.add_argument("--expect-file", default=None,
                    help="expected-scene manifest (default expected-scenes-<loader>.txt); union "
                         "reconciled against the suite header's registered[] (t0/t1 gate)")
    ap.add_argument("--display", type=int, default=None,
                    help="force an Xvfb display number (default: probe free)")
    ap.add_argument("--hold", action="store_true",
                    help="stand the dedicated_plus_client topology up, run NO scenes, write the "
                         "TESTKIT_ENDPOINT descriptor (rpcPort=client, serverRpcPort=server) and "
                         "stay online for JUnit attach; Ctrl-C to release")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args(argv)
    lp = resolve_t2(args.loader)
    # --hold never judges scenes, so don't couple the attach-only path to the
    # scored-only manifest (final-review Minor 5)
    if not args.self_test and not args.hold:
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
    print(f"[t2] loader={args.loader}  server-task=:{args.loader}:runT2Server  "
          f"server-port={SERVER_PORTS[args.loader]}")
    code, _minted = run(args)
    sys.exit(code)


if __name__ == "__main__":
    main()
