#!/usr/bin/env python3
"""Instrument contract suite (T0-dependency face) — bare-RPC checks against a
plain agent-driver dedicated server. Independent of the testkit assertion
stack by design (spec §4.2): green here => testkit setup/asserts may trust
the driver's instrument face. Verdict semantics mirror contract v0 via the
shared verdict module (registered==executed reconciliation, canary
mis-judgement => DEAD)."""
import argparse, base64, hashlib, json, os, socket, struct, subprocess, sys, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from verdict import parse, judge

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
MODULE = {"neoforge": "neoforge", "fabric": "fabric"}
SERVER_PORT = 25597


class ContractFailure(Exception):
    pass


# ---------- minimal websocket client (stdlib only, mirrors rpc_call.py) ----------
class Ws:
    def __init__(self, host, port):
        self.sock = socket.create_connection((host, port), timeout=10)
        key = base64.b64encode(os.urandom(16)).decode()
        self.sock.sendall((
            f"GET /rpc HTTP/1.1\r\nHost: {host}:{port}\r\nUpgrade: websocket\r\n"
            f"Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\n"
            f"Sec-WebSocket-Version: 13\r\n\r\n").encode())
        buf = b""
        while b"\r\n\r\n" not in buf:
            buf += self.sock.recv(4096)
        if b" 101 " not in buf.split(b"\r\n", 1)[0]:
            raise ContractFailure("websocket handshake failed")
        self.sock.settimeout(30)

    def send(self, obj):
        data = json.dumps(obj).encode()
        mask = os.urandom(4)
        hdr = b"\x81"
        n = len(data)
        if n < 126:
            hdr += bytes([0x80 | n])
        elif n < 65536:
            hdr += bytes([0x80 | 126]) + struct.pack(">H", n)
        else:
            hdr += bytes([0x80 | 127]) + struct.pack(">Q", n)
        self.sock.sendall(hdr + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(data)))

    def _read_exact(self, n):
        buf = b""
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                raise ContractFailure("socket closed")
            buf += chunk
        return buf

    def recv(self):
        while True:
            b1, b2 = self._read_exact(2)
            op = b1 & 0x0F
            n = b2 & 0x7F
            if n == 126:
                n = struct.unpack(">H", self._read_exact(2))[0]
            elif n == 127:
                n = struct.unpack(">Q", self._read_exact(8))[0]
            payload = self._read_exact(n) if n else b""
            if op == 1:
                return json.loads(payload.decode())
            if op == 8:
                raise ContractFailure("websocket closed by server")
            # ignore ping/pong/continuation for this suite


class Ctx:
    def __init__(self, ws):
        self.ws = ws
        self._id = 0

    def call_raw(self, method, params=None):
        self._id += 1
        rid = self._id
        self.ws.send({"id": rid, "method": method, "params": params or {}})
        while True:
            frame = self.ws.recv()
            if frame.get("id") == rid:
                return frame.get("result"), frame.get("error")
            # notifications (event push) are ignored; we never subscribe

    def call(self, method, params=None):
        result, error = self.call_raw(method, params)
        if error is not None:
            raise ContractFailure(f"{method} -> error: {error}")
        return result


# ---------- checks ----------
def check_version_shape(ctx):
    v = ctx.call("mc.system.version")
    if v.get("modid") != "agent_driver":
        raise ContractFailure(f"modid={v.get('modid')!r} != 'agent_driver'")
    if not isinstance(v.get("uptimeMs"), int) or v["uptimeMs"] < 0:
        raise ContractFailure(f"uptimeMs not a non-negative int: {v.get('uptimeMs')!r}")


def check_unknown_method(ctx):
    result, error = ctx.call_raw("mc.no.suchMethod")
    if error is None:
        raise ContractFailure(f"unknown method answered result={result!r} instead of error")
    if "unknown method" not in error:
        raise ContractFailure(f"error shape drifted: {error!r}")


def check_invalid_params_missing(ctx):
    # NOTE: the brief's draft asserted this against mc.observe.eventsSince, but
    # AgentApi.java:107 defaults a missing `cursor` to 0L and ObserveActionTools.java's
    # schema declares cursor as integer().min(0) with no .req() — cursor is optional,
    # so that call round-trips clean (verified live with ctx.call_raw before writing
    # this). Substituted mc.system.waitTicks, whose SystemTools.java schema is
    # object().req("ticks", integer(0, 200)) — a genuinely required int key.
    result, error = ctx.call_raw("mc.system.waitTicks", {})
    if error is None:
        raise ContractFailure(f"missing required key accepted: {result!r}")
    if "invalid params" not in error and "ticks" not in error:
        raise ContractFailure(f"error shape drifted: {error!r}")


def check_invalid_params_wrong_type(ctx):
    # Same substitution as above (mc.observe.eventsSince's cursor is optional, so a
    # wrong-typed cursor isn't a clean "required key" probe) — mc.system.waitTicks's
    # `ticks` is a required, closed-schema integer.
    result, error = ctx.call_raw("mc.system.waitTicks", {"ticks": "not-a-number"})
    if error is None:
        raise ContractFailure(f"wrong-typed param accepted: {result!r}")


def check_invalid_params_unknown_key(ctx):
    result, error = ctx.call_raw("mc.system.waitTicks", {"ticks": 1, "bogusKey": 1})
    if error is None:
        raise ContractFailure(f"unexpected key accepted: {result!r}")
    if "unexpected key" not in error and "invalid params" not in error:
        raise ContractFailure(f"error shape drifted: {error!r}")


def check_client_only_verb(ctx):
    # instrument face must fail LOUDLY on a dedicated server, never silently no-op
    result, error = ctx.call_raw("mc.bot.status")
    if error is None:
        raise ContractFailure(f"mc.bot.* answered on dedicated server: {result!r}")
    if "client only" not in error:
        raise ContractFailure(f"error shape drifted: {error!r}")


def check_script_eval_parity(ctx):
    # in-JVM route (invokeJson) must agree with the external transport
    r = ctx.call("mc.script.eval",
                 {"source": "Agent.invoke('mc.system.version').modid", "timeoutMs": 5000})
    if r.get("error"):
        raise ContractFailure(f"script error: {r['error']}")
    if r.get("result") != "agent_driver":
        raise ContractFailure(f"in-JVM route parity broken: {r.get('result')!r}")


def canary_must_fail(ctx):
    raise ContractFailure("canary: this check must be reported as FAIL")


CHECKS = [
    ("system.versionShape", "NONE", check_version_shape),
    ("route.unknownMethod", "NONE", check_unknown_method),
    ("route.invalidParams.missingKey", "NONE", check_invalid_params_missing),
    ("route.invalidParams.wrongType", "NONE", check_invalid_params_wrong_type),
    ("route.invalidParams.unknownKey", "NONE", check_invalid_params_unknown_key),
    ("route.clientOnlyVerb", "NONE", check_client_only_verb),
    ("script.evalParity", "NONE", check_script_eval_parity),
    ("canary.mustFail", "MUST_FAIL", canary_must_fail),
    ("canary.mustSwallow", "MUST_SWALLOW", None),  # registered, never executed
]


# ---------- provision / launch / sweep ----------
def run_dir(loader):
    return os.path.join(ROOT, MODULE[loader], "run-contract")


def provision(loader):
    rd = run_dir(loader)
    os.makedirs(rd, exist_ok=True)
    with open(os.path.join(rd, "eula.txt"), "w") as f:
        f.write("eula=true\n")
    with open(os.path.join(rd, "server.properties"), "w") as f:
        f.write("\n".join([
            f"server-port={SERVER_PORT}", "online-mode=false", "level-type=minecraft:flat",
            "sync-chunk-writes=false", "spawn-protection=0", "motd=instrument-contract",
        ]) + "\n")
    subprocess.run(["rm", "-rf", os.path.join(rd, "world")], check=True)
    for leftover in ("agent-rpc.port", "agent-mcp.port", "instrument-results.jsonl"):
        p = os.path.join(rd, leftover)
        if os.path.exists(p):
            os.remove(p)


def sweep():
    out = subprocess.run(["ps", "ax", "-o", "pid=,args="], capture_output=True, text=True).stdout
    for line in out.splitlines():
        if "agent.contractRun" in line and "grep" not in line:
            pid = line.strip().split()[0]
            print(f"[instrument] killing leftover contract JVM pid={pid}")
            subprocess.run(["kill", "-9", pid], check=False)


def wait_port_file(loader, wall):
    pf = os.path.join(run_dir(loader), "agent-rpc.port")
    deadline = time.time() + wall
    while time.time() < deadline:
        if os.path.exists(pf):
            try:
                return int(open(pf).read().strip())
            except ValueError:
                pass
        time.sleep(2)
    return None


def launch(loader, wall):
    sweep()
    provision(loader)
    task = f":{MODULE[loader]}:runContractServer"
    print(f"[instrument] launching: ./gradlew {task} (wall={wall}s, waiting on agent-rpc.port)")
    proc = subprocess.Popen(["./gradlew", task], cwd=ROOT,
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    port = wait_port_file(loader, wall)
    if port is None:
        proc.kill()
        sweep()
        return None
    # RPC comes up before attachServer(onServerStarted); wait until version answers
    deadline = time.time() + 120
    while time.time() < deadline:
        try:
            ws = Ws("127.0.0.1", port)
            ctx = Ctx(ws)
            ctx.call("mc.system.version")
            return ctx
        except Exception:
            time.sleep(2)
    sweep()
    return None


def stop(ctx):
    try:
        ctx.call_raw("mc.action.runCommand", {"cmd": "stop"})
    except Exception:
        pass
    time.sleep(5)
    sweep()


# ---------- run + judge ----------
def run_suite(loader, wall):
    ctx = launch(loader, wall)
    if ctx is None:
        print("[instrument] ENV: server/RPC never came up")
        return 3
    lines = [json.dumps({"type": "suite", "loader": loader, "face": "instrument",
                         "registered": [{"name": n, "required": True, "canary": c}
                                        for n, c, _ in CHECKS]})]
    try:
        for name, canary, fn in CHECKS:
            if fn is None:
                continue  # MUST_SWALLOW: registered, deliberately not executed
            t0 = time.time()
            try:
                fn(ctx)
                outcome, reason = "PASS", ""
            except ContractFailure as e:
                outcome, reason = "FAIL", str(e)
            except Exception as e:  # transport/unexpected => ENV-grade, but record honestly
                outcome, reason = "ENV_FAIL", f"{type(e).__name__}: {e}"
            lines.append(json.dumps({"type": "check", "name": name, "outcome": outcome,
                                     "ticks": 0, "wallMs": int((time.time() - t0) * 1000),
                                     "reason": reason}))
    finally:
        stop(ctx)
    lines.append(json.dumps({"type": "done", "scenes": sum(1 for _, c, f in CHECKS if f is not None)}))
    results = os.path.join(run_dir(loader), "instrument-results.jsonl")
    with open(results, "w") as f:
        f.write("\n".join(lines) + "\n")
    code, report = judge(parse(results), record_type="check")
    for r in report:
        print(f"[instrument] {r}")
    print(f"[instrument] VERDICT: {['GREEN','RED','DEAD','ENV'][code]}")
    return code


# ---------- self-test ----------
def self_test():
    reg = {"type": "suite", "loader": "x", "registered": [
        {"name": "a", "required": True, "canary": "NONE"},
        {"name": "cf", "required": True, "canary": "MUST_FAIL"},
        {"name": "cs", "required": True, "canary": "MUST_SWALLOW"}]}
    def rec(name, outcome):
        return {"type": "check", "name": name, "outcome": outcome, "ticks": 0,
                "wallMs": 0, "reason": ""}
    done = {"type": "done", "scenes": 2}
    checks = [
        ("all good -> 0", judge([reg, rec("a", "PASS"), rec("cf", "FAIL"), done],
                                record_type="check")[0] == 0),
        ("real check FAIL -> 1", judge([reg, rec("a", "FAIL"), rec("cf", "FAIL"), done],
                                       record_type="check")[0] == 1),
        ("must-fail canary PASS -> 2", judge([reg, rec("a", "PASS"), rec("cf", "PASS"), done],
                                             record_type="check")[0] == 2),
        ("swallow canary executed -> 2", judge([reg, rec("a", "PASS"), rec("cf", "FAIL"),
                                                rec("cs", "PASS"), done],
                                               record_type="check")[0] == 2),
        ("real check swallowed -> 1", judge([reg, rec("cf", "FAIL"), done],
                                            record_type="check")[0] == 1),
    ]
    failed = [n for n, ok in checks if not ok]
    for n, ok in checks:
        print(f"[self-test] {'PASS' if ok else 'FAIL'}: {n}")
    print(f"[self-test] {len(checks) - len(failed)}/{len(checks)} PASS")
    return 1 if failed else 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--loader", choices=["neoforge", "fabric"], default="neoforge")
    ap.add_argument("--wall", type=int, default=300)
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    sys.exit(self_test() if args.self_test else run_suite(args.loader, args.wall))


if __name__ == "__main__":
    main()
