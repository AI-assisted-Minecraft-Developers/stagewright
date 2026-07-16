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

Status: P1a walking skeleton done. P1b instrument-contract subset landed.
P1c dogfood wave 1 landed (below): downstream mods contribute scenes over
SPI, proven by porting agent-driver's historically-swallowed trio
(`ad.ascendDeadZoneWatchdog`/`ad.ascendMovementNoop`/`ad.diagonalAscentSpeed`)
to `ad.*` scenes running side-by-side with their legacy `@GameTest` twins
(dual-gate A/B; the legacy twins are deleted once both gates go green 3
runs in a row).

## Dogfood run: agent-driver scenes over SceneProvider SPI

    python3 scripts/testkit/t0.py --loader neoforge \
        --run-task :neoforge:runDogfoodServer \
        --results neoforge/run-dogfood/testkit-results.jsonl

Boots a full dedicated server with **both** agent_driver and mc-testkit
loaded (`neoforge/build.gradle` run config `dogfoodServer`, `testkit.autorun`
armed) — this is what proves the T0 orchestrator generalizes beyond its own
bare-bones testkit-neoforge module to a real, feature-loaded mod. Same exit
codes as plain T0 above; the suite header's `registered[]` carries the
built-in scenes plus every downstream `ad.*` scene.

Downstream mods contribute scenes via the `SceneProvider` SPI in three
lines — see `docs/testkit/orchestration-contract-v0.md` for the full
appendix (discovery order, name-uniqueness enforcement, canary ownership):

    public final class AgentDriverScenes implements SceneProvider {
        public List<Scene> scenes() { return List.of(Scene.of("ad.myScene", ..., ctx -> { ... })); }
    }

...discovered via a `META-INF/services` file whose single line names the
implementation, e.g.
`neoforge/src/main/resources/META-INF/services/net.magicterra.testkit.scene.SceneProvider`:

    net.magicterra.agent.neoforge.testkit.AgentDriverScenes

## Instrument contract (trust chain)

    python3 scripts/testkit/instrument.py --loader neoforge   # or fabric

Bare-RPC contract checks against a plain agent-driver dedicated server —
the instrument face testkit itself depends on (spec §4). Green here is the
precondition for trusting any scene's setup/assertions. Contract:
`../docs/testkit/instrument-contract-v0.md`.
