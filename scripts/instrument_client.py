#!/usr/bin/env python3
"""Client-side instrument contract (T1 face) — bare-RPC checks against a REAL,
in-world Fabric client (integrated server), the client sibling of instrument.py.

Where instrument.py proves the driver's instrument face on a headless dedicated
server, this proves the parts that only exist with a live client + a real player
in the PlayerList — the three "engine knew a fact, handed the agent zero bytes of
it" permanent assertions that instrument.py explicitly deferred to P2 (contract v0
"已知缺口"):

  * #41 full 36-slot inventory (server-authoritative, main-inventory slots 9-35
    that the old verb hid — three quarters of the bag),
  * #45 melee attack-cooldown surface (AttackSnap in observe.player.attack),
  * #55 damage-source attribution (player.hurt carries a real source, not an HP
    delta) — needs a hurtable player, so it is staged on a live client here.

Plus the #280 live end-to-end the P2a appendix owed (a bogus mc.bot.setting key
rejected by the closed schema on a REAL client, not just a dedicated server), the
known-key round-trip, and mc.test.reset behaviour (screen closed + keys released +
chat readback cleared).

Two run modes:
  * self-launch (default): drive t1.py's shell — Xvfb, gradle testkitClient with
    autorun OFF (no scenes → the integrated server stays up), template world
    lifecycle, GUI into-world — then reconnect a plain RPC socket and run the checks.
  * --attach: a `t1.py --hold` is already in-world and online; read run-t1's
    agent-rpc.port, connect, run the checks, leave the client running.

Verdict/JSONL/exit-code discipline is instrument.py's, judged through the shared
verdict module (record_type="check"; canary mis-judgement => DEAD). Every check
leaves the world state it found (staging self-cleans) so the checks are
order-independent and the run is deterministic (the plan's two-run gate).
"""
import argparse
import asyncio
import json
import os
import sys
import time
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from verdict import parse, judge          # noqa: E402 — REUSED judging, not forked
import instrument as inst                  # noqa: E402 — REUSE Ws / Ctx / ContractFailure
import guidrive as gd                      # noqa: E402 — REUSE connect / discover_port / Rpc
import t1                                  # noqa: E402 — REUSE the T1 shell (launch/drive/teardown)

ContractFailure = inst.ContractFailure
Ws = inst.Ws
Ctx = inst.Ctx

RUN_DIR = t1.RUN_DIR
PORT_FILE = t1.PORT_FILE
RESULTS = os.path.join(RUN_DIR, "instrument-client-results.jsonl")


class CanaryTimeout(Exception):
    """Sentinel: a check whose designed outcome is TIMEOUT (the MUST_TIMEOUT canary).
    run_suite maps it to a TIMEOUT record so the shared verdict's canary gate can prove
    it can distinguish TIMEOUT from PASS/FAIL — the framework-liveness half of the
    canary contract (spec §5)."""


# --------------------------------------------------------------- helpers ------
def _cmd(ctx, cmd, require_success=True):
    """Run a vanilla command through mc.action.runCommand. `ok` = dispatched without a
    throw; `success` = the command's own Brigadier success callback fired true (P1b:
    /give /damage /clear /item go through the Brigadier branch, so assert `success`, not
    the setblock fast-path's bare `ok`). require_success=False for cleanup commands whose
    no-op (e.g. `/clear` on an already-empty inventory) legitimately reports success=false."""
    r = ctx.call("mc.action.runCommand", {"cmd": cmd})
    if not r.get("ok"):
        raise ContractFailure(f"command not dispatched: {cmd!r} -> {r!r}")
    if require_success and not r.get("success"):
        raise ContractFailure(f"command reported failure: {cmd!r} -> {r!r}")
    return r


def _poll(fn, ok, *, what, timeout=8.0, poll=0.4):
    """Poll fn() until ok(result) is True; return the result. Raise ContractFailure on
    timeout. Polling-until-condition keeps the OUTCOME deterministic (PASS iff the
    condition is reached within the window) while tolerating tick-latency in wallMs."""
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        last = fn()
        if ok(last):
            return last
        time.sleep(poll)
    raise ContractFailure(f"{what}: condition not reached within {timeout}s; last={last!r}")


def _attack(ctx):
    return (ctx.call("mc.observe.player") or {}).get("attack") or {}


# ----------------------------------------------------------------- checks -----
def check_in_world(ctx):
    # attach gate: the client is genuinely in a world with a real player both client-side
    # (mc.client.player, the LocalPlayer) and server-side (mc.observe.player finds the
    # ServerPlayer in the integrated PlayerList — the precondition #41/#45/#55 all need).
    cp = ctx.call("mc.client.player")
    if cp.get("present") is not True:
        raise ContractFailure(f"mc.client.player not in-world: {cp!r}")
    pos = cp.get("pos") or {}
    if not all(isinstance(pos.get(k), (int, float)) for k in ("x", "y", "z")):
        raise ContractFailure(f"client player has no position: {cp!r}")
    sp = ctx.call("mc.observe.player")
    if sp.get("present") is not True:
        raise ContractFailure(
            f"server observe.player not present — the real player is not in the "
            f"integrated PlayerList: {sp!r}")


def check_full_inventory(ctx):
    # #41 permanent assertion: the historical bug showed 9 of 36 slots through THIS verb —
    # the hotbar only. Stage three items squarely in the MAIN inventory (container.9-35,
    # the three quarters that were invisible) and prove the server-authoritative snapshot
    # reports every one at its exact slot/id/count.
    _cmd(ctx, "clear @p", require_success=False)
    want = {9: ("minecraft:diamond", 5), 20: ("minecraft:emerald", 7),
            35: ("minecraft:gold_ingot", 3)}
    for slot, (iid, cnt) in want.items():
        _cmd(ctx, f"item replace entity @p container.{slot} with {iid} {cnt}")
    try:
        inv = _poll(lambda: (ctx.call("mc.observe.player") or {}).get("inventory") or [],
                    lambda rows: {e.get("slot") for e in rows if isinstance(e, dict)} >= set(want),
                    what="#41 staged main-inventory slots visible in observe.player",
                    timeout=6.0)
        by_slot = {e.get("slot"): e for e in inv if isinstance(e, dict)}
        for slot, (iid, cnt) in want.items():
            e = by_slot.get(slot)
            if e is None:
                raise ContractFailure(
                    f"#41 regression: main-inventory slot {slot} invisible in "
                    f"observe.player (the 9/36 bug) — inventory={inv!r}")
            if e.get("id") != iid or e.get("count") != cnt:
                raise ContractFailure(f"#41 slot {slot} drifted: {e!r} != {iid} x{cnt}")
        if max(by_slot) < 9:
            raise ContractFailure(
                f"#41: no slot >= 9 in the snapshot — the full-36 picture is not "
                f"server-authoritative: {inv!r}")
    finally:
        _cmd(ctx, "clear @p", require_success=False)  # restore: leave the bag as found


def check_attack_cooldown(ctx):
    # #45 permanent assertion: the melee cooldown CombatProcess gates every swing on
    # (AttackSnap) reached the agent as zero bytes. Assert its surface in observe.player:
    #   (a) the four fields exist with an internally-consistent idle (fully-recharged)
    #       reading — ready, cooldownTicks==0, strengthScale>=1, fullCooldownTicks==5
    #       (bare-hand attack speed 4.0/s → ceil(20/4)=5 ticks; a live attribute read, not
    #       a hardcoded constant), and
    #   (b) LIVE weapon-dependence: a slower netherite sword must LENGTHEN fullCooldownTicks
    #       (proves the snapshot reads the held weapon's attack-speed attribute, the whole
    #       point of #45, not a fixed number).
    # The dynamic "strengthScale drops right after a swing" is deliberately NOT asserted:
    # it is timing-racy against RPC latency vs the ~5-tick bare-hand recharge and would
    # break the determinism gate. The static idle invariant + weapon-dependence are the
    # deterministic permanent assertions. (See report for the surface-choice rationale.)
    _cmd(ctx, "item replace entity @p weapon.mainhand with minecraft:air", require_success=False)
    snap = _poll(lambda: _attack(ctx), lambda a: a.get("ready") is True,
                 what="#45 idle attack snapshot recharged (ready)", timeout=6.0)
    for k in ("strengthScale", "ready", "cooldownTicks", "fullCooldownTicks"):
        if k not in snap:
            raise ContractFailure(f"#45 AttackSnap missing field {k!r}: {snap!r}")
    if snap["cooldownTicks"] != 0:
        raise ContractFailure(f"#45 idle cooldownTicks != 0: {snap!r}")
    if not isinstance(snap["strengthScale"], (int, float)) or snap["strengthScale"] < 1.0:
        raise ContractFailure(f"#45 idle strengthScale < 1.0: {snap!r}")
    bare = snap["fullCooldownTicks"]
    if bare != 5:
        raise ContractFailure(f"#45 bare-hand fullCooldownTicks {bare!r} != 5 "
                              "(attack speed 4.0 → ceil(20/4))")
    try:
        _cmd(ctx, "item replace entity @p weapon.mainhand with minecraft:netherite_sword")
        sword = _poll(lambda: _attack(ctx).get("fullCooldownTicks"),
                      lambda v: isinstance(v, int) and v > bare,
                      what="#45 sword lengthens fullCooldownTicks (weapon-dependent)",
                      timeout=6.0)
        if sword <= bare:
            raise ContractFailure(
                f"#45 not weapon-dependent: sword fullCooldownTicks {sword} <= bare {bare}")
    finally:
        _cmd(ctx, "item replace entity @p weapon.mainhand with minecraft:air",
             require_success=False)


def check_damage_source(ctx):
    # #55 permanent assertion: a fall in a self-dug pit and a mob bite are the same HP
    # delta — without source attribution the combat chain and the agent engage phantoms.
    # /damage @p carries a real DamageSource; player.hurt must surface it (not just an HP
    # diff). Creative-mode resolution: the template world is creative → the player is
    # invulnerable to ordinary damage, so a plain `/damage @p 2` (generic) is a no-op.
    # minecraft:out_of_world is in the BYPASSES_INVULNERABILITY damage-type tag, so it
    # hurts a creative player WITHOUT toggling gamemode (more idempotent than
    # /gamemode survival + restore). We then heal back to full.
    cur = ctx.call("mc.observe.cursor")
    _cmd(ctx, "damage @p 2 minecraft:out_of_world")
    try:
        def fetch():
            evs = ctx.call("mc.observe.eventsSince", {"cursor": cur, "types": ["player.hurt"]})
            return evs if isinstance(evs, list) else []
        evs = _poll(fetch, lambda l: len(l) > 0,
                    what="#55 player.hurt event emitted after /damage", timeout=8.0)
        e = evs[0]
        data = e.get("data")
        payload = json.loads(data) if isinstance(data, str) else data
        src = payload.get("source")
        if not isinstance(src, str) or not src:
            raise ContractFailure(
                f"#55 player.hurt carries no source attribution (HP-diff only): {payload!r}")
        lost = payload.get("lost")
        if not isinstance(lost, (int, float)) or lost <= 0:
            raise ContractFailure(f"#55 damage did not register (lost={lost!r}): {payload!r}")
    finally:
        # restore full health so the check is idempotent (out_of_world took ~2 HP)
        _cmd(ctx, "effect give @p minecraft:instant_health 1 5 true", require_success=False)


def check_setting_unknown_key_live(ctx):
    # #280 LIVE end-to-end (the P2a appendix owed this): a bogus mc.bot.setting key must
    # be rejected by the CLOSED schema on a REAL, in-world client — not just the dedicated
    # server instrument.py covers. route() runs the SchemaValidator before the handler, so
    # even though mc.bot.setting is client-only and the handler WOULD run here, the
    # validator's unexpected-key error fires first. Bare RPC (MCP schema cache would
    # silently drop the key — the #280 病史).
    result, error = ctx.call_raw("mc.bot.setting", {"definitelyNotAKnob": True})
    if error is None:
        raise ContractFailure(f"unknown setting key accepted on a LIVE client: {result!r}")
    if "unexpected key" not in error or "definitelyNotAKnob" not in error:
        raise ContractFailure(
            f"expected the closed-schema validator's unexpected-key error, got: {error!r}")


def check_setting_known_key_live(ctx):
    # The closed schema must not over-reach: a KNOWN key (autoEat) applies and reads back
    # consistently through the same snapshot the call returns and a fresh read. Idempotent:
    # the ORIGINAL value is observed, flipped, verified, then RESTORED (so the check leaves
    # the setting as found regardless of run order or starting value).
    orig = (ctx.call("mc.bot.setting", {}).get("settings") or {}).get("autoEat")
    if not isinstance(orig, bool):
        raise ContractFailure(f"autoEat not a bool in the settings snapshot: {orig!r}")
    target = not orig
    try:
        r = ctx.call("mc.bot.setting", {"autoEat": target})
        if "autoEat" not in (r.get("applied") or []):
            raise ContractFailure(f"autoEat not in applied[]: {r!r}")
        if (r.get("settings") or {}).get("autoEat") != target:
            raise ContractFailure(f"autoEat not reflected in the returned snapshot: {r!r}")
        rb = (ctx.call("mc.bot.setting", {}).get("settings") or {}).get("autoEat")
        if rb != target:
            raise ContractFailure(f"autoEat readback inconsistent: {rb!r} != {target!r}")
    finally:
        ctx.call("mc.bot.setting", {"autoEat": orig})
    final = (ctx.call("mc.bot.setting", {}).get("settings") or {}).get("autoEat")
    if final != orig:
        raise ContractFailure(f"autoEat not restored to original {orig!r}: {final!r}")


def check_reset_behavior(ctx):
    # mc.test.reset (the client-pool entry reset) must, in one call: close any open screen,
    # release held keys, and clear the chat readback log. Dirty the client entry, then reset,
    # then assert via INDEPENDENT readbacks where a surface exists:
    #   * screen: independently verified — screen.info.hasScreen flips true→false;
    #   * chat:   independently verified — chat.history count goes >0 → 0;
    #   * keys:   there is NO read-back verb for held keys (every mc.client.input.* verb is a
    #             setter; searched — none reads key state), so the reset[] manifest token
    #             "keys" is the authoritative signal, which is exactly what TestResetVerb was
    #             designed to expose ("reset[] names exactly what changed"). Documented, not
    #             a silent shrink.
    ctx.call("mc.client.screen.close")  # defensive: start from no screen
    ctx.call("mc.client.chat.send", {"text": f"reset-probe-{int(time.time() * 1000) % 100000}"})
    ctx.call("mc.client.input.key", {"key": "E"})  # E keybind opens the inventory screen
    _poll(lambda: ctx.call("mc.client.screen.info"),
          lambda i: bool(i.get("hasScreen")),
          what="inventory screen opened via E key", timeout=6.0)
    _poll(lambda: ctx.call("mc.client.chat.history", {"limit": 50}),
          lambda h: (h.get("count") or 0) >= 1,
          what="chat readback recorded the sent line", timeout=6.0)

    r = ctx.call("mc.test.reset")
    if not r.get("ok"):
        raise ContractFailure(f"mc.test.reset not ok: {r!r}")
    reset = r.get("reset") or []
    if "screen" not in reset:
        raise ContractFailure(f"reset did not close the open screen: reset={reset!r}")
    if "keys" not in reset:
        raise ContractFailure(f"reset did not release keys: reset={reset!r}")
    if not any(isinstance(t, str) and t.startswith("chat:") for t in reset):
        raise ContractFailure(f"reset did not clear the chat log: reset={reset!r}")

    info = ctx.call("mc.client.screen.info")
    if info.get("hasScreen"):
        raise ContractFailure(f"screen still open after reset: {info!r}")
    if info.get("worldOpen") is not True:
        raise ContractFailure(f"reset knocked us out of the world: {info!r}")
    hist = ctx.call("mc.client.chat.history", {"limit": 50})
    if (hist.get("count") or 0) != 0:
        raise ContractFailure(f"chat readback not cleared after reset: {hist!r}")


def canary_must_fail(ctx):
    raise ContractFailure("canary: this check must be reported as FAIL")


def canary_must_timeout(ctx):
    raise CanaryTimeout("canary: this check must be reported as TIMEOUT")


CHECKS = [
    ("t1.inWorld", "NONE", check_in_world),
    ("obs.fullInventory", "NONE", check_full_inventory),
    ("obs.attackCooldown", "NONE", check_attack_cooldown),
    ("obs.damageSource", "NONE", check_damage_source),
    ("route.settingUnknownKeyLive", "NONE", check_setting_unknown_key_live),
    ("route.settingKnownKeyLive", "NONE", check_setting_known_key_live),
    ("reset.behavior", "NONE", check_reset_behavior),
    ("canary.mustFail", "MUST_FAIL", canary_must_fail),
    ("canary.mustTimeout", "MUST_TIMEOUT", canary_must_timeout),
]


# ---------------------------------------------------- launch / attach / run ---
def _connect_checks(port):
    """Open a plain (synchronous) RPC socket for the check phase — instrument.py's Ws/Ctx,
    reused verbatim. Separate from guidrive's async drive socket; same /rpc port."""
    ws = Ws("127.0.0.1", port)
    ctx = Ctx(ws)
    ctx.run_dir = RUN_DIR
    return ctx


async def _drive_into_world(port):
    """Reuse t1/guidrive to enter the reused template world (autorun already OFF, so no
    scenes run and the integrated server stays up). Closes its own async socket."""
    ws = await gd.connect(port)
    async with ws:
        rpc = gd.Rpc(ws)
        await gd.wait_api_ready(rpc)
        await t1.drive_into_world_selfheal(rpc, reuse=True)


def self_launch(wall):
    """Self-launch mode: stand up the T1 client shell with autorun OFF (server stays up),
    enter the reused template world, and return (client_proc, xvfb_proc, port). Mirrors
    t1.run()'s setup but launches autorun=False and does NOT harvest scenes."""
    t1.sweep_client_jvms()
    display = t1.probe_free_display()
    xvfb = t1.start_xvfb(display)
    env = dict(os.environ, DISPLAY=f":{display}")
    if not t1.template_reuse(t1.TEMPLATE_DIR):
        if not t1.mint_template(env, wall):
            t1.kill_pid(xvfb.pid, "Xvfb")
            raise ContractFailure("ENV: T1 template mint failed")
    t1.provision(reuse=True)
    client, _ = t1.launch_client(env, wall, autorun=False)  # autorun OFF → server stays up
    try:
        port = asyncio.run(gd.discover_port(Path(PORT_FILE), timeout=max(30, wall)))
        asyncio.run(_drive_into_world(port))
    except Exception:
        t1.stop_client(client)
        t1.kill_pid(xvfb.pid, "Xvfb")
        raise
    return client, xvfb, port


def run_checks(ctx):
    """Run the check family, return the JSONL lines (suite header + check records + footer)."""
    lines = [json.dumps({"type": "suite", "loader": "fabric", "face": "instrument-client",
                         "registered": [{"name": n, "required": True, "canary": c}
                                        for n, c, _ in CHECKS]})]
    for name, canary, fn in CHECKS:
        t0 = time.time()
        try:
            fn(ctx)
            outcome, reason = "PASS", ""
        except CanaryTimeout as e:
            outcome, reason = "TIMEOUT", str(e)
        except ContractFailure as e:
            outcome, reason = "FAIL", str(e)
        except Exception as e:  # transport/unexpected → ENV-grade, recorded honestly
            outcome, reason = "ENV_FAIL", f"{type(e).__name__}: {e}"
        lines.append(json.dumps({"type": "check", "name": name, "outcome": outcome,
                                 "ticks": 0, "wallMs": int((time.time() - t0) * 1000),
                                 "reason": reason}))
        print(f"[instrument-client] {name}: {outcome}"
              + (f" — {reason}" if reason else ""))
    lines.append(json.dumps({"type": "done", "scenes": len(CHECKS)}))
    return lines


def run_suite(attach, wall):
    client = xvfb = None
    try:
        if attach:
            port = gd.read_port(Path(PORT_FILE))
            if port is None:
                print(f"[instrument-client] ENV: no agent-rpc.port in {RUN_DIR} "
                      "(is `t1.py --hold` running?)")
                return 3
            print(f"[instrument-client] --attach: reusing online client at port {port}")
        else:
            try:
                client, xvfb, port = self_launch(wall)
            except ContractFailure as e:
                print(f"[instrument-client] {e}")
                return 3
            print(f"[instrument-client] self-launch in-world at port {port}")

        try:
            ctx = _connect_checks(port)
        except Exception as e:  # noqa: BLE001
            print(f"[instrument-client] ENV: could not open RPC socket: {e}")
            return 3
        lines = run_checks(ctx)
    finally:
        # Self-launch owns the client lifecycle; --attach leaves the user's client running.
        if not attach:
            if client is not None:
                t1.stop_client(client)
            if xvfb is not None:
                t1.kill_pid(xvfb.pid, "Xvfb")
            import shutil
            shutil.rmtree(t1.WORLD_DIR, ignore_errors=True)
            print(f"[instrument-client] torn down; deleted world copy {t1.WORLD_DIR}")

    os.makedirs(RUN_DIR, exist_ok=True)
    with open(RESULTS, "w") as f:
        f.write("\n".join(lines) + "\n")
    code, report = judge(parse(RESULTS), record_type="check")
    for r in report:
        print(f"[instrument-client] {r}")
    print(f"[instrument-client] VERDICT: {['GREEN', 'RED', 'DEAD', 'ENV'][code]}")
    return code


# -------------------------------------------------------------- self-test -----
def self_test():
    def rec(name, outcome):
        return {"type": "check", "name": name, "outcome": outcome, "ticks": 0,
                "wallMs": 0, "reason": ""}
    reg = {"type": "suite", "loader": "fabric", "registered": [
        {"name": "a", "required": True, "canary": "NONE"},
        {"name": "cf", "required": True, "canary": "MUST_FAIL"},
        {"name": "ct", "required": True, "canary": "MUST_TIMEOUT"}]}
    done = {"type": "done", "scenes": 3}
    checks = [
        ("all good -> 0", judge([reg, rec("a", "PASS"), rec("cf", "FAIL"),
                                 rec("ct", "TIMEOUT"), done], record_type="check")[0] == 0),
        ("real check FAIL -> 1", judge([reg, rec("a", "FAIL"), rec("cf", "FAIL"),
                                        rec("ct", "TIMEOUT"), done], record_type="check")[0] == 1),
        ("must-fail canary PASS -> 2", judge([reg, rec("a", "PASS"), rec("cf", "PASS"),
                                              rec("ct", "TIMEOUT"), done], record_type="check")[0] == 2),
        ("must-timeout canary PASS -> 2", judge([reg, rec("a", "PASS"), rec("cf", "FAIL"),
                                                 rec("ct", "PASS"), done], record_type="check")[0] == 2),
        ("real check swallowed -> 1", judge([reg, rec("cf", "FAIL"), rec("ct", "TIMEOUT"),
                                             {"type": "done", "scenes": 2}], record_type="check")[0] == 1),
        ("registry has 7 real + 2 canary",
         sum(1 for _, c, _ in CHECKS if c == "NONE") == 7
         and sum(1 for _, c, _ in CHECKS if c != "NONE") == 2),
        ("every check fn callable or canary",
         all(callable(f) for _, _, f in CHECKS)),
        ("canary names present",
         {"canary.mustFail", "canary.mustTimeout"} <= {n for n, _, _ in CHECKS}),
    ]
    failed = [n for n, ok in checks if not ok]
    for n, ok in checks:
        print(f"[self-test] {'PASS' if ok else 'FAIL'}: {n}")
    print(f"[self-test] {len(checks) - len(failed)}/{len(checks)} PASS")
    return 1 if failed else 0


def main():
    ap = argparse.ArgumentParser(description="client-side instrument contract (T1 face)")
    ap.add_argument("--attach", action="store_true",
                    help="reuse an online `t1.py --hold` client (read run-t1/agent-rpc.port); "
                         "default self-launches the T1 shell")
    ap.add_argument("--wall", type=int, default=900, help="wall-clock cap in seconds (self-launch)")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    sys.exit(self_test() if args.self_test else run_suite(args.attach, args.wall))


if __name__ == "__main__":
    main()
