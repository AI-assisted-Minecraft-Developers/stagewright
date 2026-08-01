#!/usr/bin/env python3
"""Instrument contract suite (T0-dependency face) — bare-RPC checks against a
plain worlddriver dedicated server. Independent of the testkit assertion
stack by design (spec §4.2): green here => testkit setup/asserts may trust
the driver's instrument face. Verdict semantics mirror contract v0 via the
shared verdict module (registered==executed reconciliation, canary
mis-judgement => DEAD)."""
import argparse, base64, http.client, json, os, socket, struct, subprocess, sys, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from verdict import parse, judge
import platform_compat  # noqa: E402

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
    if v.get("modid") != "worlddriver":
        raise ContractFailure(f"modid={v.get('modid')!r} != 'worlddriver'")
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
    # Approved carry-over from batch A review: tighten past "any error" to the real
    # shape. SchemaValidator.typeErr (SchemaValidator.java:123-125) renders wrong-type
    # violations as "'ticks' must be integer, got string (...)" — assert on it.
    if "must be integer" not in error:
        raise ContractFailure(f"error shape drifted: {error!r}")


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
    if r.get("result") != "worlddriver":
        raise ContractFailure(f"in-JVM route parity broken: {r.get('result')!r}")


def _cmd(ctx, cmd):
    r = ctx.call("mc.action.runCommand", {"cmd": cmd})
    if not r.get("ok"):
        raise ContractFailure(f"command dispatch failed: {cmd!r} -> {r!r}")
    return r


def check_setblock_query_readback(ctx):
    _cmd(ctx, "setblock 0 200 0 minecraft:gold_block")
    rows = ctx.call("mc.query", {"q": "blocks", "center": {"x": 0, "y": 200, "z": 0},
                                 "filter": {"in_radius": 1, "type": "minecraft:gold_block"}})
    if len(rows) != 1 or rows[0]["pos"] != {"x": 0, "y": 200, "z": 0}:
        raise ContractFailure(f"driver read disagrees with vanilla write: {rows!r}")


def check_fill_count(ctx):
    r = ctx.call("mc.action.fill", {"from": {"x": 4, "y": 200, "z": 4},
                                    "to": {"x": 6, "y": 202, "z": 6},
                                    "type": "minecraft:polished_andesite"})
    if not r.get("ok") or r.get("placed") != 27:
        raise ContractFailure(f"fill 3x3x3 placed={r.get('placed')!r} != 27")
    rows = ctx.call("mc.query", {"q": "blocks", "center": {"x": 5, "y": 201, "z": 5},
                                 "filter": {"in_radius": 2, "type": "minecraft:polished_andesite"}})
    if len(rows) != 27:
        raise ContractFailure(f"query readback {len(rows)} != 27")


def check_snapshot_restore(ctx):
    snap = ctx.call("mc.world.snapshot", {"from": {"x": 10, "y": 200, "z": 10},
                                          "to": {"x": 12, "y": 202, "z": 12}})
    if not snap.get("ok"):
        raise ContractFailure(f"snapshot failed: {snap!r}")
    _cmd(ctx, "setblock 11 201 11 minecraft:emerald_block")
    r = ctx.call("mc.world.restore", {"id": snap["id"], "discard": True})
    if not r.get("ok"):
        raise ContractFailure(f"restore failed: {r!r}")
    rows = ctx.call("mc.query", {"q": "blocks", "center": {"x": 11, "y": 201, "z": 11},
                                 "filter": {"in_radius": 2, "type": "minecraft:emerald_block"}})
    if rows:
        raise ContractFailure(f"restore left the marker block behind: {rows!r}")


def check_container_durability(ctx):
    # #42 permanent assertion, dual-source: vanilla write path vs driver read path
    _cmd(ctx, "setblock 20 200 20 minecraft:chest")
    _cmd(ctx, "item replace block 20 200 20 container.0 with minecraft:diamond_pickaxe[minecraft:damage=123] 1")
    r = ctx.call("mc.observe.container", {"pos": {"x": 20, "y": 200, "z": 20}})
    if not r.get("present"):
        raise ContractFailure(f"chest not present: {r!r}")
    s0 = (r.get("slots") or [None])[0]
    if not s0 or s0.get("id") != "minecraft:diamond_pickaxe":
        raise ContractFailure(f"slot0={s0!r}")
    if s0.get("damage") != 123 or s0.get("maxDamage") != 1561 or s0.get("durability") != 1438:
        raise ContractFailure(
            f"#42 wear fields drifted: damage={s0.get('damage')!r} "
            f"maxDamage={s0.get('maxDamage')!r} durability={s0.get('durability')!r}")


def check_player_absent_pin(ctx):
    # documented semantics: empty PlayerList => {present:false}, never a throw
    r = ctx.call("mc.observe.player")
    if r.get("present") is not False:
        raise ContractFailure(f"expected present:false on empty dedicated server, got {r!r}")


def check_entity_query(ctx):
    _cmd(ctx, "time set midnight")  # keep the zombie from burning at y=200 open sky
    _cmd(ctx, "summon minecraft:zombie 30.5 200.0 30.5 {NoAI:1b,PersistenceRequired:1b}")
    rows = ctx.call("mc.query", {"q": "entities", "center": {"x": 30, "y": 200, "z": 30},
                                 "filter": {"in_radius": 4, "type": "zombie"}})
    if len(rows) != 1:
        raise ContractFailure(f"expected exactly 1 zombie, got {len(rows)}: {rows!r}")
    if not isinstance(rows[0].get("health"), (int, float)) or rows[0]["health"] <= 0:
        raise ContractFailure(f"living row lacks health: {rows[0]!r}")
    _cmd(ctx, "kill @e[type=zombie]")


def check_command_result_event(ctx):
    # ⚠️ assertion-drift fixes (binding rule), evidence below:
    #
    # 1. mc.observe.cursor routes to ObserveApi.cursor() (ObserveApi.java:49-52),
    #    which returns a bare `long` — NOT {"cursor": N}. The brief's draft did
    #    ctx.call("mc.observe.cursor")["cursor"], which would TypeError on a plain
    #    int. Tightened to use the raw return value directly.
    # 2. mc.observe.eventsSince routes DIRECTLY to
    #    ObserveApi.eventsSince(cursor, types, limit) (AgentApi.java:107-118),
    #    which returns a bare List<AgentEvent> (ObserveApi.java:54-76) — unlike
    #    mc.wait.event / mc.wait.condition, which wrap the same call in
    #    {events, timedOut, cursor, ms} (WaitApi.java:139-164). There is no
    #    envelope key here; the RPC result IS the array. Tightened accordingly
    #    (and asserting isinstance(evs, list) so a future re-wrap is caught, not
    #    silently reinterpreted).
    # 3. A plain `setblock <x> <y> <z> <plain-id>` command takes ActionApi's
    #    fast-path (ActionApi.java:126-163), which returns BEFORE the
    #    `command.result` emit at ActionApi.java:232 — only the Brigadier branch
    #    (any other verb, or a bracketed/keep|destroy|replace setblock) emits it.
    #    The brief's draft used a plain setblock, which would never produce a
    #    command.result event. Substituted vanilla `/fill` (single-cell region)
    #    for a verb the fast-path never intercepts, forcing the Brigadier path.
    cur = ctx.call("mc.observe.cursor")
    _cmd(ctx, "fill 40 200 40 40 200 40 minecraft:iron_block")
    evs = ctx.call("mc.observe.eventsSince", {"cursor": cur, "types": ["command.result"]})
    if not isinstance(evs, list):
        raise ContractFailure(f"eventsSince shape drifted (expected a bare array): {evs!r}")
    hits = [e for e in evs if "iron_block" in json.dumps(e)]
    if not hits:
        raise ContractFailure("no command.result event for a dispatched command")
    data = hits[0].get("data")
    payload = json.loads(data) if isinstance(data, str) else data
    if payload.get("success") is not True:
        raise ContractFailure(f"success flag wrong on a succeeding command: {payload!r}")


def check_cursor_monotonic(ctx):
    # same unwrapped-cursor fix as check_command_result_event above (evidence there)
    c1 = ctx.call("mc.observe.cursor")
    _cmd(ctx, "setblock 42 200 42 minecraft:copper_block")
    c2 = ctx.call("mc.observe.cursor")
    if not (isinstance(c1, int) and isinstance(c2, int) and c2 > c1):
        raise ContractFailure(f"cursor not monotonic: {c1!r} -> {c2!r}")


def check_wait_ticks(ctx):
    t0 = time.time()
    r = ctx.call("mc.system.waitTicks", {"ticks": 10})
    ms = (time.time() - t0) * 1000
    # ⚠️ tightened: SystemApi.waitTicks (SystemApi.java:34-49) returns
    # Map.of("waited", ticks) unconditionally on the non-interrupted path — it
    # never returns a boolean True. The brief's draft tolerated either shape;
    # there is only one real shape, so assert it exactly.
    if r.get("waited") != 10:
        raise ContractFailure(f"waitTicks answer drifted: {r!r}")
    if ms < 300:  # 10 ticks nominal 500ms; <300ms means it did not actually wait
        raise ContractFailure(f"waitTicks returned too fast ({ms:.0f}ms) — did not block on ticks")


def check_wait_condition_value(ctx):
    _cmd(ctx, "setblock 50 200 50 minecraft:chest")
    _cmd(ctx, "item replace block 50 200 50 container.2 with minecraft:stone 7")
    r = ctx.call("mc.wait.condition", {
        "invoke": "mc.observe.container", "params": {"pos": {"x": 50, "y": 200, "z": 50}},
        "field": "slots.2.count", "value": 7, "timeoutMs": 5000, "pollMs": 200})
    if not r.get("satisfied") or r.get("value") != 7:
        raise ContractFailure(f"wait.condition value semantics broken: {r!r}")


# ---------- MCP tools/list reader (P2a: schema readback for the closed-schema contract) ----------
# The bare-RPC /rpc transport (AgentApi.route) does NOT expose the schema catalog — schemas are
# advertised through the MCP HTTP endpoint's `tools/list` (the SAME typed Schema the route-layer
# SchemaValidator enforces; ToolSchema.mcpTool -> Schemas.render, single source). The contract
# server brings the MCP server up in WorldDriverCommon.ensureMcpUp (onServerStarting, alongside
# RPC), on an ephemeral port written to `worlddriver-mcp.port` in the runDir. mc.script.eval cannot read
# the catalog either — this Rhino fork strips the Packages global, so JS cannot resolve ToolCatalog
# by name (independent of the sandbox denylist). So the honest structural readback is a plain HTTP
# POST tools/list, done here.
def _mcp_port(ctx):
    pf = os.path.join(ctx.run_dir, "worlddriver-mcp.port")
    # RPC + MCP both come up in onServerStarting; the readiness gate (mc.observe.player,
    # onServerStarted) is strictly later, so the port file already exists by now. Short grace anyway.
    deadline = time.time() + 30
    while time.time() < deadline:
        if os.path.exists(pf):
            try:
                return int(open(pf).read().strip())
            except ValueError:
                pass
        time.sleep(1)
    raise ContractFailure(f"worlddriver-mcp.port never appeared in {ctx.run_dir}")


def _tools_list(ctx):
    port = _mcp_port(ctx)
    body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": "tools/list"}).encode()
    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=15)
    try:
        conn.request("POST", "/mcp", body, {"Content-Type": "application/json"})
        resp = conn.getresponse()
        payload = resp.read().decode()
    finally:
        conn.close()
    if resp.status != 200:
        raise ContractFailure(f"tools/list HTTP {resp.status}: {payload[:200]}")
    doc = json.loads(payload)
    tools = (doc.get("result") or {}).get("tools")
    if not isinstance(tools, list):
        raise ContractFailure(f"tools/list shape drifted: {doc!r}")
    return {t.get("name"): t for t in tools}


def check_setting_schema_closed(ctx):
    # #280 STRUCTURAL half (contract check ①): read the advertised mc.bot.setting schema from
    # the MCP tools/list catalog and prove it is CLOSED with the full single-source key set.
    #
    # ⚠️ RENDERING FINDING (verified in Schema.java): additionalProperties(false) stores the field
    # as null (Schema.java:134 `allow ? Boolean.TRUE : null`) and the Obj codec emits it via
    # optionalFieldOf (Schema.java:46), so a CLOSED object renders in tools/list with the
    # `additionalProperties` key OMITTED entirely — never a literal `false`. The catalog's
    # closed-object convention is exactly SchemaValidator.java:100 (`open == additionalProperties
    # is Boolean.TRUE`): absent-or-false == closed, only `true` == open. We assert that reading —
    # additionalProperties must NOT be true — plus the full key set (single-source SettingsRegistry
    # is 229 keys; >=200 catches a registry that silently lost its reflective completion pass).
    tools = _tools_list(ctx)
    tool = tools.get("mc.bot.setting")
    if tool is None:
        raise ContractFailure("mc.bot.setting absent from tools/list")
    sch = tool.get("inputSchema") or {}
    if sch.get("type") != "object":
        raise ContractFailure(f"mc.bot.setting inputSchema not an object: {sch.get('type')!r}")
    if sch.get("additionalProperties") is True:
        raise ContractFailure("mc.bot.setting schema is OPEN (additionalProperties:true) — #280 not closed")
    props = sch.get("properties")
    if not isinstance(props, dict):
        raise ContractFailure(f"mc.bot.setting properties missing: {sch!r}")
    if len(props) < 200:
        raise ContractFailure(
            f"mc.bot.setting prop count {len(props)} < 200 — single-source registry undersized")


def check_setting_unknown_key_rejected(ctx):
    # #280 BEHAVIORAL half + validator-first ordering contract (check ②): mc.bot.setting is
    # client-only, but route() runs the SchemaValidator (AgentApi.java:444) BEFORE the handler
    # (line 445, where requireBot() throws client-only). So on a DEDICATED server a bogus-key call
    # must fail with the VALIDATOR's unexpected-key error, NOT the client-only error — validation is
    # transport/side-uniform. Order confirmed by code + this live gate (see contract appendix).
    result, error = ctx.call_raw("mc.bot.setting", {"definitelyNotAKnob": True})
    if error is None:
        raise ContractFailure(f"unknown setting key accepted: {result!r}")
    if "definitelyNotAKnob" not in error or "unexpected key" not in error:
        raise ContractFailure(
            f"expected the validator's unexpected-key error (validation runs before the "
            f"client-only gate — see AgentApi.route), got: {error!r}")


def check_test_reset_client_only(ctx):
    # mc.test.reset (paired-registered hidden verb) is client-only (check ③): valid (empty) params
    # pass the validator, then the handler throws the established client-only error on a dedicated
    # server. Loud, never a silent no-op.
    result, error = ctx.call_raw("mc.test.reset")
    if error is None:
        raise ContractFailure(f"mc.test.reset answered on dedicated server: {result!r}")
    if "client only" not in error or "mc.test.reset" not in error:
        raise ContractFailure(f"error shape drifted: {error!r}")


def check_test_reset_schema_paired(ctx):
    # registerVerb pairing metaproof + schema-less-dispatch-hole regression (check ④): mc.test.reset
    # was registered via the paired ToolCatalog.registerVerb (schema + route atomically). A bogus
    # param key must be rejected by the VALIDATOR (unexpected-key) BEFORE the client-only handler —
    # proving the schema is present AND closed AND validation uniform. If the schema-less dispatch
    # hole regressed (no schema => validate() skipped), this would fall through to client-only.
    result, error = ctx.call_raw("mc.test.reset", {"nope": True})
    if error is None:
        raise ContractFailure(f"unknown mc.test.reset key accepted: {result!r}")
    if "nope" not in error or "unexpected key" not in error:
        raise ContractFailure(
            f"expected the validator's unexpected-key error (schema present + closed + validated "
            f"before client-only), got: {error!r}")


def check_test_run_paired(ctx):
    # P3a check ⑤ — mc.test.run pairing metaproof (runs on the DOGFOOD/autorun server, the only
    # topology where the testkit runtime armed and registered the verb through its own
    # StageWrightVerbHook SPI). Two assertions, mirroring the mc.test.reset precedent (checks ③/④):
    #   (a) HIDDEN: mc.test.run must NOT appear in the MCP tools/list catalog — it is a dev/test
    #       harness verb, reachable over RPC only, exactly like mc.test.reset / mc.test.yaml.
    #   (b) SCHEMA-PAIRED: a bogus param key must be rejected by the VALIDATOR (unexpected-key)
    #       BEFORE the handler ever triggers a run — proving the schema was registered atomically
    #       with the route (registerVerb pairing) AND is closed AND validation is uniform. If the
    #       schema-less-dispatch hole regressed, a schema-less route would skip validation and the
    #       bogus key would fall through to the handler instead.
    tools = _tools_list(ctx)
    if "mc.test.run" in tools:
        raise ContractFailure("mc.test.run leaked into tools/list — the on-demand trigger must stay hidden")
    result, error = ctx.call_raw("mc.test.run", {"nope": True})
    if error is None:
        raise ContractFailure(f"unknown mc.test.run key accepted: {result!r}")
    if "nope" not in error or "unexpected key" not in error:
        raise ContractFailure(
            f"expected the validator's unexpected-key error (schema present + closed + validated "
            f"before the handler), got: {error!r}")


def check_test_run_idempotent(ctx):
    # P3a check ⑥ — mc.test.run idempotency闩 (runs on the DOGFOOD/autorun server, where the suite
    # already armed at SERVER_STARTED). A valid (empty) mc.test.run must NOT silently re-run the
    # suite: it must fail LOUDLY with an "already ..." error envelope. Assert the error, never a
    # re-run (a second {accepted:true} here would mean the guard is dead and results could be
    # clobbered mid-run).
    result, error = ctx.call_raw("mc.test.run", {})
    if error is None:
        raise ContractFailure(
            f"mc.test.run re-accepted on an already-armed suite (idempotency guard dead): {result!r}")
    if "already" not in error:
        raise ContractFailure(f"expected an 'already ran/running' idempotency error, got: {error!r}")


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
    ("world.setblockQueryReadback", "NONE", check_setblock_query_readback),
    ("world.fillCount", "NONE", check_fill_count),
    ("world.snapshotRestore", "NONE", check_snapshot_restore),
    ("obs.containerDurability", "NONE", check_container_durability),
    ("obs.playerAbsentPin", "NONE", check_player_absent_pin),
    ("obs.entityQuery", "NONE", check_entity_query),
    ("events.commandResult", "NONE", check_command_result_event),
    ("events.cursorMonotonic", "NONE", check_cursor_monotonic),
    ("wait.ticks", "NONE", check_wait_ticks),
    ("wait.conditionValue", "NONE", check_wait_condition_value),
    # P2a verb-pipeline + #280 closure checks (contract appendix ①-④)
    ("catalog.settingSchemaClosed", "NONE", check_setting_schema_closed),
    ("route.settingUnknownKey", "NONE", check_setting_unknown_key_rejected),
    ("route.testResetClientOnly", "NONE", check_test_reset_client_only),
    ("route.testResetSchemaPaired", "NONE", check_test_reset_schema_paired),
    ("canary.mustFail", "MUST_FAIL", canary_must_fail),
    ("canary.mustSwallow", "MUST_SWALLOW", None),  # registered, never executed
]

# P3a mc.test.run verb-contract checks. These run against the DOGFOOD server, NOT the plain
# contract server: mc.test.run is registered by the testkit runtime's own StageWrightVerbHook SPI,
# which only fires where StageWrightCommon.onServerStarted armed the harness (a testkit runtime =
# an autorun-armed server). The plain contract server never arms the harness, so the verb is
# (correctly) absent there — pinning its contract requires the armed topology. The dogfood suite
# runs in the background; these two checks touch only the verb (never a scene outcome), so they
# stay independent of the testkit assertion stack (spec §4.2) even while sharing its server.
DOGFOOD_CHECKS = [
    ("route.testRunPaired", "NONE", check_test_run_paired),
    ("route.testRunIdempotent", "NONE", check_test_run_idempotent),
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
    for leftover in ("worlddriver-rpc.port", "worlddriver-mcp.port", "instrument-results.jsonl"):
        p = os.path.join(rd, leftover)
        if os.path.exists(p):
            os.remove(p)


def sweep():
    for pid in platform_compat.find_processes("worlddriver.contractRun"):
        print(f"[instrument] killing leftover contract JVM pid={pid}")
        platform_compat.kill_pid(pid)


def wait_port_file(loader, wall):
    pf = os.path.join(run_dir(loader), "worlddriver-rpc.port")
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
    cmd = platform_compat.gradlew_cmd(task)
    print(f"[instrument] launching: {' '.join(cmd)} (wall={wall}s, waiting on worlddriver-rpc.port)")
    proc = subprocess.Popen(cmd, cwd=ROOT,
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    port = wait_port_file(loader, wall)
    if port is None:
        proc.kill()
        sweep()
        return None
    # RPC comes up (onServerStarting) before attachServer (onServerStarted) —
    # confirmed live on fabric (2026-07-16): mc.system.version answers with no
    # server attached at all (it doesn't call AgentApi.level()), so probing
    # with it raced attachServer and lost on a fast fabric boot (RPC-listen to
    # "Done" ~1s), producing "AgentApi not attached to a server" on every
    # check needing api.level() (ObserveApi.player() etc. call api.level() as
    # an explicit "assert attached" first line). mc.observe.player is
    # side-effect-free (a read-only PlayerList probe, {present:false} on an
    # empty dedicated server, never throws on content) and DOES call
    # api.level() first, so waiting on it is a true readiness gate, not just
    # a transport-up gate.
    deadline = time.time() + 120
    while time.time() < deadline:
        try:
            ws = Ws("127.0.0.1", port)
            ctx = Ctx(ws)
            ctx.run_dir = run_dir(loader)  # P2a: MCP tools/list reader resolves worlddriver-mcp.port here
            ctx.call("mc.observe.player")
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


# ---------- dogfood (autorun) server — for the mc.test.run verb-contract checks ----------
# mc.test.run is registered only where the testkit runtime armed the harness (StageWrightVerbHook SPI
# fires from StageWrightCommon.onServerStarted). The plain contract server never arms it, so the two
# route.testRun* checks run against the dogfood server (:<loader>:runDogfoodServer, -Dstagewright.autorun
# hard-true in its run config). We connect early — the harness arms at SERVER_STARTED (so mc.test.run
# already errors idempotently) and the autorun suite will eventually halt the server; the two checks
# are instant RPC round-trips done long before the ~minute-long suite finishes.
DOGFOOD_PORT = 25598


def dogfood_dir(loader):
    return os.path.join(ROOT, MODULE[loader], "run-dogfood")


def provision_dogfood(loader):
    rd = dogfood_dir(loader)
    os.makedirs(rd, exist_ok=True)
    with open(os.path.join(rd, "eula.txt"), "w") as f:
        f.write("eula=true\n")
    with open(os.path.join(rd, "server.properties"), "w") as f:
        f.write("\n".join([
            f"server-port={DOGFOOD_PORT}", "online-mode=false", "level-type=minecraft:flat",
            "sync-chunk-writes=false", "spawn-protection=0", "motd=instrument-dogfood",
        ]) + "\n")
    subprocess.run(["rm", "-rf", os.path.join(rd, "world")], check=True)
    for leftover in ("worlddriver-rpc.port", "worlddriver-mcp.port", "testkit-results.jsonl"):
        p = os.path.join(rd, leftover)
        if os.path.exists(p):
            os.remove(p)


def sweep_dogfood():
    for pid in platform_compat.find_processes("stagewright.autorun", "java"):
        print(f"[instrument] killing leftover dogfood JVM pid={pid}")
        platform_compat.kill_pid(pid)


def launch_dogfood(loader, wall):
    sweep_dogfood()
    provision_dogfood(loader)
    task = f":{MODULE[loader]}:runDogfoodServer"
    cmd = platform_compat.gradlew_cmd(task)
    print(f"[instrument] launching dogfood (autorun): {' '.join(cmd)} (wall={wall}s)")
    proc = subprocess.Popen(cmd, cwd=ROOT,
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    pf = os.path.join(dogfood_dir(loader), "worlddriver-rpc.port")
    deadline = time.time() + wall
    port = None
    while time.time() < deadline:
        if os.path.exists(pf):
            try:
                port = int(open(pf).read().strip())
                break
            except ValueError:
                pass
        time.sleep(2)
    if port is None:
        proc.kill()
        sweep_dogfood()
        return None
    deadline = time.time() + 120
    while time.time() < deadline:
        try:
            ws = Ws("127.0.0.1", port)
            ctx = Ctx(ws)
            ctx.run_dir = dogfood_dir(loader)  # MCP tools/list reader resolves worlddriver-mcp.port here
            ctx.call("mc.observe.player")      # readiness: api attached (harness armed same tick)
            return ctx
        except Exception:
            time.sleep(2)
    sweep_dogfood()
    return None


def stop_dogfood(ctx):
    try:
        ctx.call_raw("mc.action.runCommand", {"cmd": "stop"})
    except Exception:
        pass
    time.sleep(5)
    sweep_dogfood()


# ---------- run + judge ----------
def _run_one(ctx, name, fn):
    t0 = time.time()
    try:
        fn(ctx)
        outcome, reason = "PASS", ""
    except ContractFailure as e:
        outcome, reason = "FAIL", str(e)
    except Exception as e:  # transport/unexpected => ENV-grade, but record honestly
        outcome, reason = "ENV_FAIL", f"{type(e).__name__}: {e}"
    return {"type": "check", "name": name, "outcome": outcome,
            "ticks": 0, "wallMs": int((time.time() - t0) * 1000), "reason": reason}


def run_suite(loader, wall):
    all_checks = CHECKS + DOGFOOD_CHECKS
    registered = [{"name": n, "required": True, "canary": c} for n, c, _ in all_checks]
    records = []

    # ---- Phase 1: plain contract server (21 contract-face checks + 2 canaries) ----
    ctx = launch(loader, wall)
    if ctx is None:
        print("[instrument] ENV: contract server/RPC never came up")
        return 3
    try:
        for name, canary, fn in CHECKS:
            if fn is None:
                continue  # MUST_SWALLOW: registered, deliberately not executed
            records.append(_run_one(ctx, name, fn))
    finally:
        stop(ctx)

    # ---- Phase 2: dogfood (autorun) server — the mc.test.run verb contract ----
    dctx = launch_dogfood(loader, wall)
    if dctx is None:
        print("[instrument] ENV: dogfood server never came up for mc.test.run checks")
        for name, canary, fn in DOGFOOD_CHECKS:
            records.append({"type": "check", "name": name, "outcome": "ENV_FAIL",
                            "ticks": 0, "wallMs": 0, "reason": "dogfood server never came up"})
    else:
        try:
            for name, canary, fn in DOGFOOD_CHECKS:
                records.append(_run_one(dctx, name, fn))
        finally:
            stop_dogfood(dctx)

    lines = [json.dumps({"type": "suite", "loader": loader, "face": "instrument",
                         "registered": registered})]
    lines += [json.dumps(r) for r in records]
    lines.append(json.dumps({"type": "done", "scenes": len(records)}))
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
                                                rec("cs", "PASS"), {"type": "done", "scenes": 3}],
                                               record_type="check")[0] == 2),
        ("real check swallowed -> 1", judge([reg, rec("cf", "FAIL"), {"type": "done", "scenes": 1}],
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
