#!/usr/bin/env python3
"""mc-testkit client PROCESS POOL (contract v0, P3b T2) — keep --hold topologies
alive ACROSS invocations so consumers attach in seconds instead of cold-booting.

t1.py --hold (integrated server + client) and t2.py --hold (dedicated server +
client) each stand a topology up, publish a TESTKIT_ENDPOINT descriptor, and idle
until Ctrl-C — the attach surface instrument_client.py --attach and the JUnit
module consume. But every consumer today pays a fresh ~30-90s (T1) / minutes (T2)
cold boot. This pool amortizes that: `pool.py ensure` reuses a live hold if one is
already serving, otherwise launches one DETACHED (survives the pool invocation) and
records its PID so a later `stop` can release it via the documented Ctrl-C path.

CLI:
  pool.py ensure --topology {t1,t2} --loader {fabric,neoforge}   (defaults t1/fabric)
  pool.py status                                                 (all topology×loader)
  pool.py stop   --topology {t1,t2} --loader {fabric,neoforge}
  pool.py --self-test

Semantics:
  * ensure — probe the topology's endpoint file (t1: <loader>/run-t1/…; t2:
    <loader>/run-t2/…, both resolved via t1.resolve_loader / t2.resolve_t2 — NOT
    hardcoded). Endpoint present AND a bare-RPC ``mc.system.version`` liveness probe
    succeeds against its rpcPort (T2 also probes serverRpcPort) → print the endpoint
    path + ``reused``, exit 0. Otherwise clean stale residue (stale endpoint file;
    any hold PID this pool recorded), launch the topology's ``--hold`` as a detached
    subprocess (its own session, log → run dir), record {pid, topology, loader,
    startedAtEpochMs, log} in .pool-state.json, bounded-poll for the endpoint file
    (t1 240s / t2 360s), verify liveness → print path + ``started``, exit 0. On
    timeout kill the launched hold by recorded PID (SIGINT → SIGKILL grace),
    exit 3 (ENV).
  * status — probe-based alive/stale/absent for every topology×loader; exit 0.
  * stop — SIGINT the recorded hold PID (the hold's ``finally`` deletes the endpoint
    file), bounded-wait for the endpoint file to vanish, SIGKILL after grace, drop
    the state entry. No state entry but a LIVE endpoint on disk (an orphan from a
    manual --hold) → REFUSE loudly (never kill a process we didn't start — the PID
    is not ours to guess); a stale (probe-dead) orphan endpoint → just delete it.

  Never pkill. Every process op targets an explicit recorded PID.
"""
import argparse
import contextlib
import fcntl
import json
import os
import signal
import subprocess
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import t1                       # noqa: E402 — REUSE resolve_loader/run-dir/endpoint paths
import t2 as t2mod              # noqa: E402 — REUSE resolve_t2 paths (NOT forked)
import instrument as inst       # noqa: E402 — REUSE the stdlib synchronous Ws/Ctx for the probe

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TESTKIT_DIR = os.path.join(REPO_ROOT, "scripts", "testkit")
STATE_FILE = os.path.join(TESTKIT_DIR, ".pool-state.json")
STATE_LOCK = os.path.join(TESTKIT_DIR, ".pool-state.lock")

TOPOLOGIES = ("t1", "t2")
LOADERS = ("fabric", "neoforge")
HOST = "127.0.0.1"

# Bounded-poll budgets for the endpoint file to appear + go live after a --hold launch.
# T1 = integrated client under Xvfb + GUI-drive into a template world; T2 = a dedicated
# server world-load THEN the client boot + multiplayer direct-connect (roughly double).
ENSURE_BUDGET = {"t1": 240, "t2": 360}
PROBE_TIMEOUT = 5.0     # per the attach contract: one mc.system.version, ~5s
STOP_GRACE = 45         # seconds to wait for the hold's finally to delete the endpoint file
PID_EXIT_GRACE = 6      # seconds to wait for a signalled PID to actually exit


# ------------------------------------------------------- path resolution ------
def endpoint_path(topology, loader):
    """The topology's TESTKIT_ENDPOINT descriptor path, derived from t1/t2's own
    LoaderPaths (single source — never a hardcoded duplicate)."""
    if topology == "t1":
        return t1.resolve_loader(loader).endpoint_file
    return t2mod.resolve_t2(loader).endpoint_file


def run_dir_for(topology, loader):
    """The run directory the hold works in (where its endpoint file + our log live)."""
    if topology == "t1":
        return t1.resolve_loader(loader).run_dir
    return t2mod.resolve_t2(loader).run_dir


def hold_script(topology):
    return os.path.join(TESTKIT_DIR, "t1.py" if topology == "t1" else "t2.py")


def endpoint_ports(topology, doc):
    """The RPC ports a live ``topology`` endpoint must answer on. T1 = the single
    client rpcPort; T2 = the client rpcPort AND the dedicated server serverRpcPort
    (both must be live for the dual-socket topology to count as reusable)."""
    ports = []
    rp = doc.get("rpcPort")
    if isinstance(rp, int):
        ports.append(rp)
    if topology == "t2":
        sp = doc.get("serverRpcPort")
        if isinstance(sp, int):
            ports.append(sp)
    return ports


# ----------------------------------------------------------- pure decisions ---
def state_key(topology, loader):
    return f"{topology}/{loader}"


def ensure_decision(endpoint_exists, alive):
    """reuse iff an endpoint file exists AND its probe is live; otherwise (stale or
    absent) clean+start. Pure — the ensure control-flow spine, self-test-covered."""
    return "reuse" if (endpoint_exists and alive) else "start"


def stop_decision(has_entry, pid_alive, endpoint_exists, endpoint_alive):
    """Classify a stop request from (recorded entry?, its PID still alive?, endpoint file
    on disk?, endpoint probes live?). Pure — the stop control-flow spine, self-test-covered.

      entry + live PID                          -> signal_pid           (SIGINT our hold)
      entry + dead PID + no endpoint            -> clear_entry          (drop the stale record)
      entry + dead PID + endpoint LIVE          -> refuse_clear_entry   (a DIFFERENT process now
                                                                         publishes that endpoint —
                                                                         NOT ours to kill/delete;
                                                                         drop only our dead record)
      entry + dead PID + endpoint stale         -> clear_entry_delete_stale (residue: drop + delete)
      no entry + no endpoint                    -> noop
      no entry + endpoint LIVE                  -> refuse_orphan        (manual --hold, not ours)
      no entry + endpoint stale                 -> delete_stale_endpoint
    """
    if has_entry:
        if pid_alive:
            return "signal_pid"
        if not endpoint_exists:
            return "clear_entry"
        return "refuse_clear_entry" if endpoint_alive else "clear_entry_delete_stale"
    if not endpoint_exists:
        return "noop"
    return "refuse_orphan" if endpoint_alive else "delete_stale_endpoint"


def status_classify(endpoint_exists, alive):
    """Probe-based status of one topology×loader (not mere file existence)."""
    if not endpoint_exists:
        return "absent"
    return "alive" if alive else "stale"


# --------------------------------------------------------------- state file ---
def load_state(path=STATE_FILE):
    """Read the pool state file → {"version":1,"entries":{key:entry}}. A missing or
    corrupt file degrades to an empty pool (never raises — a stop/status on a fresh
    checkout must still work)."""
    try:
        with open(path, encoding="utf-8") as f:
            d = json.load(f)
        if isinstance(d, dict) and isinstance(d.get("entries"), dict):
            return d
    except (FileNotFoundError, json.JSONDecodeError, OSError):
        pass
    return {"version": 1, "entries": {}}


def save_state(state, path=STATE_FILE):
    """Atomically persist the state file (write .tmp then os.replace, so a concurrent
    reader never observes a half-written pool state)."""
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(state, f, indent=2)
    os.replace(tmp, path)


@contextlib.contextmanager
def _state_lock(lock_path=STATE_LOCK):
    """Hold an exclusive fcntl.flock across a load→mutate→save critical section. Two
    concurrent `ensure` runs for DIFFERENT keys are both multi-minute cold boots whose
    put_entry() calls would otherwise interleave (load A, load A, set-t1 save, set-t2
    save) and the last writer would erase the other's PID — a launched hold left running
    with no recorded PID, unreleasable by `stop`. The lock serializes every read-modify-
    write so each mutation observes the prior one's committed state."""
    fd = os.open(lock_path, os.O_CREAT | os.O_RDWR, 0o644)
    try:
        fcntl.flock(fd, fcntl.LOCK_EX)
        yield
    finally:
        fcntl.flock(fd, fcntl.LOCK_UN)
        os.close(fd)


def _mutate_state(mutate, lock_path=STATE_LOCK, path=STATE_FILE):
    """Serialized read-modify-write: reload INSIDE the lock (so a sibling key another
    process committed while we waited is preserved), apply ``mutate(state)``, save."""
    with _state_lock(lock_path):
        st = load_state(path)
        mutate(st)
        save_state(st, path)


def get_entry(topology, loader):
    return load_state()["entries"].get(state_key(topology, loader))


def put_entry(topology, loader, pid, log):
    entry = {
        "pid": pid,
        "topology": topology,
        "loader": loader,
        "startedAtEpochMs": int(time.time() * 1000),
        "log": log,
    }
    _mutate_state(lambda st: st["entries"].__setitem__(state_key(topology, loader), entry))


def del_entry(topology, loader):
    _mutate_state(lambda st: st["entries"].pop(state_key(topology, loader), None))


# ------------------------------------------------------------- liveness probe -
def probe_alive(host, port, timeout=PROBE_TIMEOUT):
    """One bare-RPC ``mc.system.version`` against ws://host:port/rpc (the attach
    contract's liveness proof). True iff the driver answers with modid==agent_driver
    within ``timeout``. Runs the blocking stdlib websocket handshake in a daemon
    thread joined with a bound, so a black-hole TCP accept can never stall the probe
    past ~timeout (a refused connection returns instantly regardless)."""
    result = {"ok": False}

    def _run():
        try:
            ws = inst.Ws(host, int(port))
            ws.sock.settimeout(timeout)
            v = inst.Ctx(ws).call("mc.system.version")
            result["ok"] = isinstance(v, dict) and v.get("modid") == "agent_driver"
            try:
                ws.sock.close()
            except OSError:
                pass
        except Exception:  # noqa: BLE001 — any failure = not alive
            result["ok"] = False

    th = threading.Thread(target=_run, daemon=True)
    th.start()
    th.join(timeout + 1.0)
    return result["ok"]


def read_endpoint(path):
    try:
        with open(path, encoding="utf-8") as f:
            d = json.load(f)
        return d if isinstance(d, dict) else None
    except (FileNotFoundError, json.JSONDecodeError, OSError):
        return None


def endpoint_alive(topology, path):
    """True iff the endpoint file parses, names its RPC port(s), and EVERY one of them
    passes the liveness probe (T2 needs both client and server faces up)."""
    doc = read_endpoint(path)
    if doc is None:
        return False
    ports = endpoint_ports(topology, doc)
    if not ports:
        return False
    return all(probe_alive(HOST, p) for p in ports)


# --------------------------------------------------------------- process mgmt -
def pid_alive(pid):
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return True


def _signal(pid, sig):
    try:
        os.kill(pid, sig)
        return True
    except ProcessLookupError:
        return False


def _wait_pid_gone(pid, grace):
    deadline = time.monotonic() + grace
    while time.monotonic() < deadline:
        if not pid_alive(pid):
            return True
        time.sleep(0.3)
    return not pid_alive(pid)


def stop_hold(pid, endpoint_file, grace=STOP_GRACE):
    """Release a LIVE recorded hold by its explicit PID (never pkill, never guess). SIGINT
    is the documented --hold release path (t1.py/t2.py turn the KeyboardInterrupt into
    their teardown ``finally``, which deletes the endpoint file). Bounded-wait for that
    file to vanish; if it never does, escalate SIGKILL and delete the endpoint residue
    ourselves — safe here because the PID was OUR live hold, so the endpoint is ours.

    A PID that is already dead SHORT-CIRCUITS to ``already-dead`` immediately (no grace
    wait, no endpoint delete): the endpoint at that path may now belong to a DIFFERENT
    live process, so this function must NOT touch it — the caller re-probes and decides
    (see cmd_stop's refuse_clear_entry / clear_entry_delete_stale split). Returns a tag."""
    if pid is None:
        return "no-pid"
    if not pid_alive(pid):
        return "already-dead"
    _signal(pid, signal.SIGINT)
    deadline = time.monotonic() + grace
    while time.monotonic() < deadline:
        if not os.path.exists(endpoint_file):
            _wait_pid_gone(pid, PID_EXIT_GRACE)
            return "clean"
        time.sleep(0.5)
    # Grace exhausted: the hold's finally never completed. Hard-kill by PID and sweep
    # the endpoint residue ourselves.
    _signal(pid, signal.SIGKILL)
    _wait_pid_gone(pid, PID_EXIT_GRACE)
    if os.path.exists(endpoint_file):
        try:
            os.remove(endpoint_file)
        except OSError:
            pass
    return "killed"


def _launch_hold(topology, loader, log):
    """Launch ``t1.py/t2.py --hold`` DETACHED: its own session (start_new_session, so a
    Ctrl-C on pool.py never reaches it), stdin closed, stdout+stderr → the run-dir log.
    Returns the Popen (its .pid is what we record + later SIGINT)."""
    cmd = ["python3", hold_script(topology), "--hold", "--loader", loader]
    logf = open(log, "w")
    logf.write(f"# pool.py --hold launch: {' '.join(cmd)} @ {time.ctime()}\n")
    logf.flush()
    return subprocess.Popen(
        cmd, cwd=REPO_ROOT, stdout=logf, stderr=subprocess.STDOUT,
        stdin=subprocess.DEVNULL, start_new_session=True)


def _clean_stale(topology, loader, ep):
    """Before a fresh launch, remove stale residue: any hold PID this pool recorded
    (SIGINT→SIGKILL by explicit PID), then the state entry, then a stale endpoint file."""
    entry = get_entry(topology, loader)
    if entry:
        pid = entry.get("pid")
        if isinstance(pid, int) and pid_alive(pid):
            print(f"[pool] cleaning recorded stale hold pid={pid}")
            stop_hold(pid, ep)
        del_entry(topology, loader)
    if os.path.exists(ep):
        try:
            os.remove(ep)
            print(f"[pool] removed stale endpoint {ep}")
        except OSError as e:
            print(f"[pool] WARN could not remove stale endpoint {ep}: {e}")


def _await_endpoint(topology, ep, proc, budget):
    """Bounded-poll for the endpoint file to appear AND go live, or the hold to die."""
    deadline = time.monotonic() + budget
    while time.monotonic() < deadline:
        if os.path.exists(ep) and endpoint_alive(topology, ep):
            return True
        if proc.poll() is not None:
            print(f"[pool] hold process exited early (rc={proc.returncode}) before the "
                  "endpoint went live — see the log")
            return False
        time.sleep(2.0)
    return False


# ------------------------------------------------------------------ commands --
def cmd_ensure(topology, loader):
    ep = endpoint_path(topology, loader)
    rd = run_dir_for(topology, loader)
    key = state_key(topology, loader)

    # Fast path: an endpoint is already serving → reuse it (seconds, no boot).
    if os.path.exists(ep) and endpoint_alive(topology, ep):
        print(f"[pool] {key}: endpoint live at {ep}")
        print(f"[pool] {ep} reused")
        print(f"export TESTKIT_ENDPOINT={ep}")
        return 0

    # Slow path: clean any stale residue, then launch a fresh hold detached.
    _clean_stale(topology, loader, ep)
    os.makedirs(rd, exist_ok=True)
    log = os.path.join(rd, "pool-hold.log")
    proc = _launch_hold(topology, loader, log)
    put_entry(topology, loader, proc.pid, log)
    budget = ENSURE_BUDGET[topology]
    print(f"[pool] {key}: launched {os.path.basename(hold_script(topology))} --hold "
          f"pid={proc.pid} (budget {budget}s) log={log}")

    if _await_endpoint(topology, ep, proc, budget):
        print(f"[pool] {ep} started")
        print(f"export TESTKIT_ENDPOINT={ep}")
        return 0

    # Timeout / early death: kill the hold WE launched by its recorded PID, drop it.
    print(f"[pool] ENV: {key} endpoint {ep} not live within {budget}s — "
          f"killing launched hold pid={proc.pid}")
    stop_hold(proc.pid, ep)
    del_entry(topology, loader)
    return 3


def _refuse_orphan_msg(key, ep, topology, why):
    print(f"[pool] REFUSING to stop {key}: {why}")
    print("[pool] The pool never started (or no longer owns) this hold, so its PID is "
          "not ours to guess — killing/deleting for a process we did not start is forbidden.")
    print(f"[pool] Release it at its source: Ctrl-C the `{os.path.basename(hold_script(topology))} "
          f"--hold`, or once it is dead delete {ep}.")


def _delete_endpoint(key, ep):
    try:
        os.remove(ep)
        print(f"[pool] {key}: deleted stale (probe-dead) endpoint {ep}")
    except OSError as e:
        print(f"[pool] {key}: could not delete stale endpoint {ep}: {e}")


def cmd_stop(topology, loader):
    ep = endpoint_path(topology, loader)
    key = state_key(topology, loader)
    entry = get_entry(topology, loader)
    pid = entry.get("pid") if entry else None
    alive_pid = isinstance(pid, int) and pid_alive(pid)
    exists = os.path.exists(ep)
    ep_alive = endpoint_alive(topology, ep) if exists else False
    decision = stop_decision(bool(entry), alive_pid, exists, ep_alive)

    if decision == "signal_pid":
        print(f"[pool] stopping {key}: SIGINT hold pid={pid} → wait endpoint gone → "
              "SIGKILL after grace")
        outcome = stop_hold(pid, ep)
        del_entry(topology, loader)
        gone = not os.path.exists(ep)
        print(f"[pool] {key} stopped ({outcome}); endpoint "
              f"{'gone' if gone else 'STILL PRESENT — inspect the log'}")
        return 0

    if decision == "clear_entry":
        del_entry(topology, loader)
        print(f"[pool] {key}: recorded hold pid={pid} already dead and no endpoint on "
              "disk — cleared the stale state entry")
        return 0

    if decision == "refuse_clear_entry":
        # Our recorded PID is dead, yet the endpoint at that path PROBES LIVE — a different
        # process (e.g. a manual --hold) now publishes it. Drop our dead record but NEVER
        # delete/kill: the endpoint is not ours. (Fix 2: no blind os.remove on dead-pid.)
        del_entry(topology, loader)
        _refuse_orphan_msg(key, ep, topology,
                           f"our recorded pid={pid} is dead but a LIVE endpoint at {ep} is "
                           "now served by a DIFFERENT process (re-probed).")
        return 0

    if decision == "clear_entry_delete_stale":
        # Dead PID + stale (probe-dead) endpoint = pure residue. Short-circuit (fix 3): no
        # grace wait — just drop the record and sweep the dead endpoint file.
        del_entry(topology, loader)
        _delete_endpoint(key, ep)
        return 0

    if decision == "noop":
        print(f"[pool] {key}: nothing to stop (no state entry, no endpoint file)")
        return 0

    if decision == "refuse_orphan":
        _refuse_orphan_msg(key, ep, topology,
                           f"a LIVE endpoint at {ep} is NOT pool-managed (no state entry).")
        return 0

    # delete_stale_endpoint
    _delete_endpoint(key, ep)
    return 0


def cmd_status():
    st = load_state()
    n = len(st["entries"])
    print(f"[pool] state file {STATE_FILE} — {n} managed entr{'y' if n == 1 else 'ies'}")
    print(f"  {'topology/loader':<18}{'status':<8}{'origin':<14}{'pid':<8}endpoint")
    for topology in TOPOLOGIES:
        for loader in LOADERS:
            ep = endpoint_path(topology, loader)
            entry = st["entries"].get(state_key(topology, loader))
            exists = os.path.exists(ep)
            alive = endpoint_alive(topology, ep) if exists else False
            cls = status_classify(exists, alive)
            origin = "pool-managed" if entry else ("orphan" if exists else "-")
            pid = str(entry.get("pid")) if entry else "-"
            print(f"  {state_key(topology, loader):<18}{cls:<8}{origin:<14}{pid:<8}"
                  f"{ep if exists else ''}")
    return 0


# -------------------------------------------------------------- self-test -----
def self_test():
    checks = [
        # ---- ensure decision table (endpoint_exists × alive) ----
        ("ensure: absent -> start", ensure_decision(False, False) == "start"),
        ("ensure: stale (exists, dead) -> start", ensure_decision(True, False) == "start"),
        ("ensure: live (exists, alive) -> reuse", ensure_decision(True, True) == "reuse"),
        ("ensure: absent-but-alive-flag defensive -> start",
         ensure_decision(False, True) == "start"),
        # ---- stop decision table (has_entry × pid_alive × endpoint_exists × endpoint_alive) ----
        ("stop: entry + live PID -> signal_pid",
         stop_decision(True, True, True, True) == "signal_pid"),
        ("stop: entry + live PID, endpoint not yet up -> signal_pid",
         stop_decision(True, True, False, False) == "signal_pid"),
        ("stop: entry + dead PID + no endpoint -> clear_entry",
         stop_decision(True, False, False, False) == "clear_entry"),
        ("stop: entry + dead PID + LIVE endpoint -> refuse_clear_entry (fix 2)",
         stop_decision(True, False, True, True) == "refuse_clear_entry"),
        ("stop: entry + dead PID + stale endpoint -> clear_entry_delete_stale (fix 3)",
         stop_decision(True, False, True, False) == "clear_entry_delete_stale"),
        ("stop: no entry, no endpoint -> noop",
         stop_decision(False, False, False, False) == "noop"),
        ("stop: no entry, live endpoint -> refuse_orphan",
         stop_decision(False, False, True, True) == "refuse_orphan"),
        ("stop: no entry, stale endpoint -> delete_stale_endpoint",
         stop_decision(False, False, True, False) == "delete_stale_endpoint"),
        # ---- status classification ----
        ("status: absent when no endpoint", status_classify(False, False) == "absent"),
        ("status: stale when endpoint but dead", status_classify(True, False) == "stale"),
        ("status: alive when endpoint and live", status_classify(True, True) == "alive"),
        # ---- state key ----
        ("state_key format", state_key("t1", "fabric") == "t1/fabric"),
        ("state_key t2 neoforge", state_key("t2", "neoforge") == "t2/neoforge"),
        # ---- state-file round-trip ----
        ("state round-trips through disk", _check_state_roundtrip()),
        ("load_state on missing file -> empty pool", _check_load_missing()),
        ("load_state on corrupt file -> empty pool", _check_load_corrupt()),
        ("_mutate_state under lock preserves sibling keys (lost-update fix 1)",
         _check_mutate_preserves_sibling()),
        # ---- endpoint port extraction (T1 single vs T2 dual) ----
        ("endpoint_ports t1 = [rpcPort] only",
         endpoint_ports("t1", {"rpcPort": 39843, "serverRpcPort": 39777}) == [39843]),
        ("endpoint_ports t2 = [rpcPort, serverRpcPort]",
         endpoint_ports("t2", {"rpcPort": 39843, "serverRpcPort": 39777}) == [39843, 39777]),
        ("endpoint_ports t2 tolerates missing serverRpcPort",
         endpoint_ports("t2", {"rpcPort": 39843}) == [39843]),
        ("endpoint_ports ignores non-int ports",
         endpoint_ports("t1", {"rpcPort": "nope"}) == []),
        # ---- path resolution derives from t1/t2 (no hardcoded duplicate) ----
        ("endpoint_path t1 == t1.resolve_loader.endpoint_file",
         endpoint_path("t1", "fabric") == t1.resolve_loader("fabric").endpoint_file),
        ("endpoint_path t2 == t2.resolve_t2.endpoint_file",
         endpoint_path("t2", "neoforge") == t2mod.resolve_t2("neoforge").endpoint_file),
        ("endpoint_path t1 under <loader>/run-t1",
         endpoint_path("t1", "fabric")
         == os.path.join(REPO_ROOT, "fabric", "run-t1", "testkit-endpoint.json")),
        ("endpoint_path t2 under <loader>/run-t2",
         endpoint_path("t2", "fabric")
         == os.path.join(REPO_ROOT, "fabric", "run-t2", "testkit-endpoint.json")),
        ("run_dir_for t2 is run-t2 (endpoint lives with server, not client run-t1)",
         run_dir_for("t2", "fabric") == t2mod.resolve_t2("fabric").run_dir),
        ("hold_script t1/t2 resolve", hold_script("t1").endswith("t1.py")
         and hold_script("t2").endswith("t2.py")),
        # ---- arg parsing ----
        ("args: ensure default topology t1", _parse(["ensure"]).topology == "t1"),
        ("args: ensure default loader fabric", _parse(["ensure"]).loader == "fabric"),
        ("args: command parsed", _parse(["stop"]).command == "stop"),
        ("args: --topology t2", _parse(["ensure", "--topology", "t2"]).topology == "t2"),
        ("args: --loader neoforge",
         _parse(["ensure", "--loader", "neoforge"]).loader == "neoforge"),
        ("args: status command", _parse(["status"]).command == "status"),
        ("args: rejects unknown command", _raises_systemexit(lambda: _parse(["frobnicate"]))),
        ("args: rejects unknown topology",
         _raises_systemexit(lambda: _parse(["ensure", "--topology", "t9"]))),
        ("args: rejects unknown loader",
         _raises_systemexit(lambda: _parse(["ensure", "--loader", "quilt"]))),
        ("args: no command allowed (None) for bare/self-test invocation",
         _parse([]).command is None),
    ]
    failed = [n for n, ok in checks if not ok]
    for n, ok in checks:
        print(f"  [{'PASS' if ok else 'FAIL'}] {n}")
    print(f"[pool] self-test: {len(checks) - len(failed)}/{len(checks)} passed")
    return 0 if not failed else 1


def _check_state_roundtrip():
    import tempfile
    d = tempfile.mkdtemp()
    try:
        path = os.path.join(d, ".pool-state.json")
        state = {"version": 1, "entries": {
            "t1/fabric": {"pid": 4242, "topology": "t1", "loader": "fabric",
                          "startedAtEpochMs": 1752700000000, "log": "/x/pool-hold.log"}}}
        save_state(state, path)
        if os.path.exists(path + ".tmp"):
            return False
        return load_state(path) == state
    finally:
        import shutil
        shutil.rmtree(d)


def _check_mutate_preserves_sibling():
    """Two independent-key writes through _mutate_state (each reloads inside the lock)
    must leave BOTH entries — the lost-update the flock exists to prevent."""
    import shutil
    import tempfile
    d = tempfile.mkdtemp()
    try:
        path = os.path.join(d, ".pool-state.json")
        lock = os.path.join(d, ".pool-state.lock")
        _mutate_state(lambda st: st["entries"].__setitem__("t1/fabric", {"pid": 1}), lock, path)
        _mutate_state(lambda st: st["entries"].__setitem__("t2/fabric", {"pid": 2}), lock, path)
        _mutate_state(lambda st: st["entries"].pop("t1/fabric", None), lock, path)
        return set(load_state(path)["entries"]) == {"t2/fabric"}
    finally:
        shutil.rmtree(d)


def _check_load_missing():
    return load_state("/nonexistent/dir/.pool-state.json") == {"version": 1, "entries": {}}


def _check_load_corrupt():
    import tempfile
    d = tempfile.mkdtemp()
    try:
        path = os.path.join(d, ".pool-state.json")
        with open(path, "w") as f:
            f.write("{ this is not json ]")
        return load_state(path) == {"version": 1, "entries": {}}
    finally:
        import shutil
        shutil.rmtree(d)


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
        description="mc-testkit client process pool (keep --hold topologies alive across "
                    "invocations)")
    ap.add_argument("command", nargs="?", choices=["ensure", "status", "stop"],
                    help="ensure (reuse-or-launch) | status (probe all) | stop (release)")
    ap.add_argument("--topology", choices=TOPOLOGIES, default="t1",
                    help="t1 (integrated+client) | t2 (dedicated+client); default t1. "
                         "status probes ALL topology×loader regardless")
    ap.add_argument("--loader", choices=LOADERS, default="fabric",
                    help="target loader (default fabric)")
    ap.add_argument("--self-test", action="store_true",
                    help="pure decision-table + state round-trip + arg tests (no live processes)")
    return ap.parse_args(argv)


def main():
    argv = sys.argv[1:]
    if "--self-test" in argv:
        sys.exit(self_test())
    args = _parse(argv)
    if not args.command:
        print("[pool] no command — one of {ensure,status,stop} required "
              "(or --self-test)", file=sys.stderr)
        sys.exit(2)
    if args.command == "ensure":
        sys.exit(cmd_ensure(args.topology, args.loader))
    if args.command == "status":
        sys.exit(cmd_status())
    if args.command == "stop":
        sys.exit(cmd_stop(args.topology, args.loader))


if __name__ == "__main__":
    main()
