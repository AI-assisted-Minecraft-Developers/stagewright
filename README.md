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
never block, never sleep, never touch absolute coordinates. **Migration rule
(P1.5a pre-flight)**: a scene body's own synchronous loop (e.g. driving a
Walker in-body for N ticks, as every dogfood `ad.*` scene does) must be
bounded by a fixed tick cap — a scene body is not a test thread, it runs
inline on the server tick, so an unbounded loop hangs the dedicated server
itself, not just the one scene. 每个 `withRequired(false)` 场景必须在 javadoc
引用一个已立案的 task 编号，且在每个阶段验收时重审 optional 名单（防 carve-out
蠕变）。

Status: P1a walking skeleton done. P1b instrument-contract subset landed.
P1c dogfood wave 1 landed (below): downstream mods contribute scenes over
SPI, proven by porting agent-driver's historically-swallowed trio
(`ad.ascendDeadZoneWatchdog`/`ad.ascendMovementNoop`/`ad.diagonalAscentSpeed`)
to `ad.*` scenes running side-by-side with their legacy `@GameTest` twins
(dual-gate A/B; the legacy twins are deleted once both gates go green 3
runs in a row).

## Dogfood run: agent-driver scenes over SceneProvider SPI

The dogfood suite runs on **both loaders** — the canonical acceptance commands
are identical apart from loader name, run task, results path, and manifest
(P1.6 made fabric a first-class dogfood target alongside neoforge):

**NeoForge:**

    python3 scripts/testkit/t0.py --loader neoforge \
        --run-task :neoforge:runDogfoodServer \
        --results neoforge/run-dogfood/testkit-results.jsonl \
        --expect-file scripts/testkit/expected-scenes-neoforge.txt

**Fabric:**

    python3 scripts/testkit/t0.py --loader fabric \
        --run-task :fabric:runDogfoodServer \
        --results fabric/run-dogfood/testkit-results.jsonl \
        --expect-file scripts/testkit/expected-scenes-fabric.txt

Each boots a full dedicated server with **both** agent_driver and mc-testkit
loaded (the loader's `build.gradle` run config `dogfoodServer`, `testkit.autorun`
armed) — this is what proves the T0 orchestrator generalizes beyond its own
bare-bones testkit-`<loader>` module to a real, feature-loaded mod. Same exit
codes as plain T0 above; the suite header's `registered[]` carries the
built-in scenes plus every downstream `ad.*` scene.

The `ad.*` scenes live in `common` behind a loader-injected body-factory seam
(neoforge injects `FakePlayerFactory`; fabric injects a vanilla-only
`AgentFakePlayer`), so both loaders register the **same** scenes via the **same**
common `SceneProvider` service file. P1.6's dual-loader ×3 determinism matrix
found every `ad.*` scene metric **byte-identical across both loaders** (fabric ==
neoforge; the sole timing variance is `ad.entityLeash`'s await tick count — an
entity-indexing wait sensitive to server startup tick-debt, both within the
widened `within(120)` bound, root fix tracked as task#88).

`--expect-file scripts/testkit/expected-scenes-neoforge.txt` is the **canonical
external-expectation gate**: a checked-in manifest (one scene name per line,
`#` comments and comma-separated names allowed) naming every `ad.*` scene the
orchestrator expects to see in `registered[]`. Each migrated `ad.*` scene MUST
be added to this file **in the same commit** that adds the scene — the manifest
lives beside the code and reviews with it, so a scene missing from *both* the
file and `registered[]` is exactly the silent-composition hole the gate exists
to close. If the resolved expectation set is empty (file missing, or present but
containing no names after stripping comments/blanks) the orchestrator **fails
loudly** — `--expect-file not found` / `expectation source given but contains no
scene names`, argparse exit 2 — rather than silently degrading to "expect
nothing". See `docs/testkit/orchestration-contract-v0.md`'s appendix for why
this is load-bearing (it is the precondition for deleting the legacy
`@GameTest` twins: without it, a broken `ServiceLoader` discovery chain would
silently drop `ad.*` from `registered[]` and the suite would self-consistently
go GREEN on fewer scenes than intended).

`--expect-scene name1,name2,...` remains supported as an **ad-hoc** override for
one-off runs (e.g. asserting a subset while iterating on a single new scene);
when both are given they are **unioned and de-duplicated**. The checked-in
`--expect-file` is the canonical form for acceptance — prefer it so the
expectation set is version-controlled and can never drift from the migrated
scene list.

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
