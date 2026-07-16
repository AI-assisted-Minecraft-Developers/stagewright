# mc-testkit

Cross-loader (Fabric + NeoForge) Minecraft mod test framework. Spec:
`../docs/superpowers/specs/2026-07-16-mc-testkit-design.md`. Orchestration
contract: `../docs/testkit/orchestration-contract-v0.md`.

## T0: server-side scene suite

    python3 scripts/testkit/t0.py --loader neoforge   # or fabric

Exit codes: 0 GREEN / 1 RED / 2 DEAD (canary mis-judged — framework broken,
results void) / 3 ENV. The orchestrator is the only verdict authority.

Scenes live in `common/src/main/java/net/magicterra/testkit/scene/Scenes.java`
(explicit registry = single source for execution AND reconciliation). A scene
body runs once on its first tick, builds an origin-relative arena, asserts, and
may register `ctx.await(cond).within(ticks).then(action)` continuations. Bodies
never block, never sleep, never touch absolute coordinates.

Status: P1a walking skeleton done. P1b instrument-contract subset landed
(below). Next: P1c dogfood migration of agent-driver arenas.

## Instrument contract (trust chain)

    python3 scripts/testkit/instrument.py --loader neoforge   # or fabric

Bare-RPC contract checks against a plain agent-driver dedicated server —
the instrument face testkit itself depends on (spec §4). Green here is the
precondition for trusting any scene's setup/assertions. Contract:
`../docs/testkit/instrument-contract-v0.md`.
