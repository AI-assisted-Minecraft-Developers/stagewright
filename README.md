# StageWright

Cross-loader (Fabric + NeoForge) Minecraft mod test framework. Spec:
`../docs/superpowers/specs/2026-07-16-stagewright-design.md`. Orchestration
contract: `../docs/stagewright/orchestration-contract-v0.md`.

## T0: server-side scene suite

    python3 scripts/stagewright/t0.py --loader neoforge   # or fabric

Exit codes: 0 GREEN / 1 RED / 2 DEAD (canary mis-judged — framework broken,
results void) / 3 ENV. The orchestrator is the only verdict authority.

Scenes live in `common/src/main/java/net/magicterra/stagewright/scene/Scenes.java`
(explicit registry = single source for execution AND reconciliation). A scene
body runs once on its first tick, builds an origin-relative arena, asserts, and
may register `ctx.await(cond).within(ticks).then(action)` continuations. Bodies
never block, never sleep, never touch absolute coordinates. **Migration rule
(P1.5a pre-flight)**: a scene body's own synchronous loop (e.g. driving a
Walker in-body for N ticks, as every dogfood `wd.*` scene does) must be
bounded by a fixed tick cap — a scene body is not a test thread, it runs
inline on the server tick, so an unbounded loop hangs the dedicated server
itself, not just the one scene. 每个 `withRequired(false)` 场景必须在 javadoc
引用一个已立案的 task 编号，且在每个阶段验收时重审 optional 名单（防 carve-out
蠕变）。

Status: P1a walking skeleton done. P1b instrument-contract subset landed.
P1c dogfood wave 1 landed (below): downstream mods contribute scenes over
SPI, proven by porting worlddriver's historically-swallowed trio
(`wd.ascendDeadZoneWatchdog`/`wd.ascendMovementNoop`/`wd.diagonalAscentSpeed`)
to `wd.*` scenes running side-by-side with their legacy `@GameTest` twins
(dual-gate A/B; the legacy twins are deleted once both gates go green 3
runs in a row).

## Dogfood run: worlddriver scenes over SceneProvider SPI

The dogfood suite runs on **both loaders** — the canonical acceptance commands
are identical apart from loader name, run task, results path, and manifest
(P1.6 made fabric a first-class dogfood target alongside neoforge):

**NeoForge:**

    python3 scripts/stagewright/t0.py --loader neoforge \
        --run-task :neoforge:runDogfoodServer \
        --results neoforge/run-dogfood/stagewright-results.jsonl \
        --expect-file scripts/stagewright/expected-scenes-neoforge.txt

**Fabric:**

    python3 scripts/stagewright/t0.py --loader fabric \
        --run-task :fabric:runDogfoodServer \
        --results fabric/run-dogfood/stagewright-results.jsonl \
        --expect-file scripts/stagewright/expected-scenes-fabric.txt

Each boots a full dedicated server with **both** worlddriver and stagewright
loaded (the loader's `build.gradle` run config `dogfoodServer`, `stagewright.autorun`
armed) — this is what proves the T0 orchestrator generalizes beyond its own
bare-bones testkit-`<loader>` module to a real, feature-loaded mod. Same exit
codes as plain T0 above; the suite header's `registered[]` carries the
built-in scenes plus every downstream `wd.*` scene.

The `wd.*` scenes live in `common` behind a loader-injected body-factory seam
(neoforge injects `FakePlayerFactory`; fabric injects a vanilla-only
`AvatarFakePlayer`), so both loaders register the **same** scenes via the **same**
common `SceneProvider` service file. P1.6's dual-loader ×3 determinism matrix
found every `wd.*` scene metric **byte-identical across both loaders** (fabric ==
neoforge; the sole timing variance is `wd.entityLeash`'s await tick count — an
entity-indexing wait sensitive to server startup tick-debt, both within the
`within(180)` liveness bound. The startup tick-debt catch-up burst that made a
tight wall-clock bound flaky is fixed at the source by the harness **settle
barrier** (D1: drains startup tick-debt before arming scenes); `within(180)` is
retained as a pure liveness guard after `within(120)` was falsified by a wild
`TIMEOUT@121` under external box load — task#88 **closed**).

**Wall-clock (130-scene dogfood suite, P4c acceptance, 2026-07-18).** T0 dedicated-server
runs land at **~80 s neoforge / ~75 s fabric** per full-suite run (measured 83.6/81.6 s
neoforge ×2, 73.6/79.6 s fabric ×2), and are byte-identical `(name, outcome)` within each
loader and cross-loader. The T1 integrated-client run pays a one-time client cold boot
(~28-30 s) on top of the suite. `instrument.py`'s bare-RPC contract suite is ~43 s per loader.

`--expect-file scripts/stagewright/expected-scenes-neoforge.txt` is the **canonical
external-expectation gate** (the fabric manifest `expected-scenes-fabric.txt`
carries the identical governance): a checked-in manifest (one scene name per line,
`#` comments and comma-separated names allowed) naming every `wd.*` scene the
orchestrator expects to see in `registered[]`. Each migrated `wd.*` scene MUST
be added to this file **in the same commit** that adds the scene — the manifest
lives beside the code and reviews with it, so a scene missing from *both* the
file and `registered[]` is exactly the silent-composition hole the gate exists
to close. If the resolved expectation set is empty (file missing, or present but
containing no names after stripping comments/blanks) the orchestrator **fails
loudly** — `--expect-file not found` / `expectation source given but contains no
scene names`, argparse exit 2 — rather than silently degrading to "expect
nothing". See `docs/stagewright/orchestration-contract-v0.md`'s appendix for why
this is load-bearing (it is the precondition for deleting the legacy
`@GameTest` twins: without it, a broken `ServiceLoader` discovery chain would
silently drop `wd.*` from `registered[]` and the suite would self-consistently
go GREEN on fewer scenes than intended).

`--expect-scene name1,name2,...` remains supported as an **ad-hoc** override for
one-off runs (e.g. asserting a subset while iterating on a single new scene);
when both are given they are **unioned and de-duplicated**. The checked-in
`--expect-file` is the canonical form for acceptance — prefer it so the
expectation set is version-controlled and can never drift from the migrated
scene list.

Downstream mods contribute scenes via the `SceneProvider` SPI in three
lines — see `docs/stagewright/orchestration-contract-v0.md` for the full
appendix (discovery order, name-uniqueness enforcement, canary ownership):

    public final class WorldDriverScenes implements SceneProvider {
        public List<Scene> scenes() { return List.of(Scene.of("ad.myScene", ..., ctx -> { ... })); }
    }

...discovered via a `META-INF/services` file whose single line names the
implementation. Since P1.6 the provider lives in the loader-shared module so
ONE registration serves every loader; since P4a both the provider class and its
service file live in the `testmod` source set (out of the production jar), e.g.
`common/src/testmod/resources/META-INF/services/net.magicterra.stagewright.scene.SceneProvider`:

    net.magicterra.worlddriver.bot.stagewright.scene.WorldDriverScenes

Keep exactly one service file per provider across all source sets — a copy in
a loader module alongside the common one double-registers the provider on that
loader's dev classpath and trips the duplicate-scene-name gate (RED by design).

### Scene library structure (dogfood suite: 131 `wd.*` scenes, by family)

As of P4c the **entire** legacy `@GameTest` suite has been migrated to `wd.*`
dogfood scenes and deleted (`migrate-then-delete`; the drift log
[`../docs/stagewright/migration-log.md`](../docs/stagewright/migration-log.md) records
every retirement). `grep -rn "@GameTest(" common/src neoforge/src fabric/src`
now returns **zero** test-method call sites. The dogfood suite is
**131 `wd.*` scenes** across **13 `SceneProvider` classes** — the original seed
provider plus one per migrated family — all in
`common/src/testmod/java/net/magicterra/worlddriver/bot/stagewright/scene/`, all listed
(one line each) in the single common service file
`common/src/testmod/resources/META-INF/services/net.magicterra.stagewright.scene.SceneProvider`:

| provider class | family | scenes | migrated from (legacy class) |
|---|---|---:|---|
| `WorldDriverScenes` | core seed (dogfood wave-1/2a/2b + `wd.entityLeashLowY` task#87 D2) | 10 | (seeded, P1c–P2a; +1 D2) |
| `WorldDriverTerrainScenes` | Terrain | 12 | `AgentGameTestTerrain` (deleted) |
| `WorldDriverBiasScenes` | Bias (planner cost/constraint) | 13 | `AgentGameTestBias` (deleted) |
| `WorldDriverWaterBankScenes` | WaterBank | 11 | `AgentGameTestWaterBank` (deleted) |
| `WorldDriverWaterCrossScenes` | WaterCross | 10 | `AgentGameTestWaterCross` (deleted) |
| `WorldDriverCoreScenes` | Core (main `AgentGameTest`) | 12 | `AgentGameTest` (deleted) |
| `WorldDriverCombatScenes` | CombatSense | 2 | `AgentGameTestCombatSense` (deleted) |
| `WorldDriverBuildScenes` | BuildBlock | 2 | `AgentGameTestBuildBlock` (deleted) |
| `WorldDriverStationScenes` | Station (craft / smelt / recipe / observe) | 15 | `AgentGameTestServer` (deleted) |
| `WorldDriverSchedulerScenes` | Scheduler semantics (matrices) | 11 | `AgentGameTestServer` (deleted) |
| `WorldDriverSurvivalScenes` | Survival (reflex / autos) | 13 | `AgentGameTestServer` (deleted) |
| `WorldDriverAvatarScenes` | Avatar (server-body capability) | 5 | `AgentGameTestServer` (deleted) |
| `WorldDriverProcessScenes` | Process core (driver / process / combat) | 15 | `AgentGameTestServer` (deleted) |

**Total 131** (10 seed + 121 migrated 1:1). One legacy arena, `descentDriftArena`,
was retired-without-scene (controller-adjudicated, P4b wave 2 — see migration-log)
and two scenes are net-new (0 legacy twin): `wd.settingRegistryClosed` (P2a) and
`wd.entityLeashLowY` (the task#87 D2 low-Y leash probe, promoted to `required` after
void-moat isolation cleared the engine — see migration-log); the remaining 121 map
1:1. No legacy `@GameTest` class survives —
`AgentGameTestServer`, `AgentGameTestRegistrar` and `AgentGameTestSupport` were all
deleted at the P4c finale.

**Two deliberate optional-FAIL sensors.** Two scenes are registered
`.withRequired(false)` on purpose — they are *visible* live-bug / false-green
signatures, kept red-on-purpose and **never tuned to green** (per the module rule
that every `withRequired(false)` scene must cite a filed task in its javadoc and be
re-audited each acceptance to prevent carve-out creep):

- **`wd.vineClingFidelityProbe`** (WaterBank) — legacy `required=false`; runs
  optional-**PASS** (wall-backed vine cling fidelity).
- **`wd.vineOverWaterClimb`** (WaterBank) — the live **−711** bug against the
  clean `walkerVineFreeHangClimb`-OFF baseline; deterministic optional-**FAIL**
  (`pocketTicks=29`). Its RED *is* the proof the live bug reproduces (task ref:
  the −711 live record cited in the scene javadoc).

**Two D2 sensors closed and promoted to `required`** (both were optional-FAIL
signatures until D2 fixed/cleared their engine debt — they now assert green as
first-class gates):

- **`wd.riverSheerBank`** (WaterBank) — **task#91 CLOSED, promoted to `required`.**
  The gap #48 shared-body FALSE-GREEN it surfaced was a real EXECUTOR gap: A* always
  routed the correct far-lateral exit (low bank +5 EAST across open water), but the
  climb-out executor misread that laterally-distant, only-+1-higher waypoint as a
  climb-HERE intent and trenched the +5 sheer wall (`wallPressTicks≈51`). Fixed
  structurally by `walkerWaterClimbLateralGate` (default ON, baseline-EXEMPT — a
  correctness invariant): the climb-out engages only when the waypoint is horizontally
  BESIDE the bot, so the swim-drive carries it to the real walk-out. K≥6 A/B both
  loaders: gate OFF 6/6 wedge, gate ON 6/6 ashore (byte-identical ARRIVED); 10 sibling
  water families byte-unchanged. Acceptance: `ashore=true wallPressTicks=54` both loaders.
- **`wd.entityLeashLowY`** (core seed) — **task#87 CLOSED, promoted to `required`.**
  A net-new low-Y (y=-58/-59) twin of `wd.entityLeash` built to reproduce the deleted
  `entityLeashRepathArena`'s y≈-60 phase-2 stall. Round-1 RED was a terrain confound;
  void-moat isolation (fill the whole rig footprint to air at low Y) produced GREEN ×6
  **byte-identical with the y=200 twin**, proving the engine has no low-Y defect — the
  legacy stall was **rig-disease**, not an engine bug. Closed, probe kept as a `required`
  low-Y liveness gate. Acceptance: phase2 `reached=true sceneTicks=2` both loaders.

A dogfood run is GREEN with these two optional sensors present (one optional-PASS,
one optional-FAIL) — the acceptance gate
requires all *required* scenes PASS and the `(name, outcome)` set be identical
across runs and loaders, so an optional sensor flipping to green (a silent fix or a
tuned rig) would itself be caught by the cross-run/cross-loader identity check.

**One REQUIRED scene runs a topology-portable validation suite: `wd.agentRpcSmoke`** (task#92,
**closed** in D1). Its JS RPC/YAML validation suite (`WorldDriverCommon.runValidation()`) runs **in
full on both topologies** — the earlier blanket early-PASS guard on any non-dedicated topology was
**removed**. The ~35 client-face checks each self-skip a single "no client" placeholder on the
dedicated path but run their full real branch on integrated, so the suite is **147 checks on dedicated
(T0)** and **259 on integrated (T1/T2)** (integrated ⊃ dedicated — a measured, topology-aware total,
not an assumption). After the worker completes the scene asserts, on whichever topology it is on:
`FAIL == 0` ∧ `TOTAL ==` that topology's expected count (`RPC_SMOKE_EXPECTED_TOTAL_DEDICATED=147` /
`RPC_SMOKE_EXPECTED_TOTAL_INTEGRATED=259`) ∧ every `SKIP(task#92)` result ∈ the named allow-list.
Exactly **one named topology-skip** is sanctioned: `42_combat: melee engage clears a zombie pack` —
full area-clear needs a flat, entity-clean arena, so it records a **counted** `SKIP(task#92)` on the
integrated path (the whole `42_combat` file self-skips on dedicated for want of a client); the offence
itself is covered deterministically by dogfood `wd.serverCombat*`. Pinned by the scene's
`RPC_SMOKE_NAMED_SKIPS` allow-list — not deleted, not swallowed. The `passNote` reports the topology,
the total, and the named-skip list into the results-JSONL `reason`, so a run proves it actually ran
the suite (e.g. T1 `wd.agentRpcSmoke` runs ~800 ticks / ~35 s wall, not an early-PASS).

### How to add a scene (single-place how-to)

Adding one dogfood scene touches at most four spots — do all of them **in the same
commit**, and two independent gates catch a slip:

1. **Write the scene body** in the family's provider class under
   `common/src/testmod/.../scene/` (e.g. `WorldDriverTerrainScenes`), and register
   it in that provider's `scenes()` list:
   `Scene.of("ad.myScene", … , ctx -> { … })` (append `.withRequired(false)` +
   a task-citing javadoc **only** if it is a deliberate optional sensor). A scene
   body runs once on its first server tick, builds an origin-relative arena, and
   asserts; its own synchronous loops **must** be tick-bounded (a scene body is not
   a test thread — it runs inline on the server tick), and it never touches absolute
   coordinates. If it needs a new provider **class**, add one line for it to the
   common service file
   `common/src/testmod/resources/META-INF/services/net.magicterra.stagewright.scene.SceneProvider`
   (keep exactly one service file across all source sets — a duplicate trips the
   duplicate-scene-name gate).
2. **Add the scene name to BOTH manifests** —
   `scripts/stagewright/expected-scenes-neoforge.txt` **and**
   `scripts/stagewright/expected-scenes-fabric.txt` (they are identical by construction:
   the scenes live in `common` and register for both loaders through the same
   service file). This is the same-commit rule the manifest exists to enforce.

**The two gates that catch a mistake:** (a) the **`--expect-file` reconcile gate** —
a name in a manifest but missing from `registered[]` (bad service wiring / typo), or
a scene in `registered[]` but absent from the manifest, fails the run loudly instead
of silently degrading (the #85 silent-composition hole); (b) the **duplicate-name
gate** — two providers (or a stray second service file) claiming the same scene name
is RED by design. So a half-done addition — scene added but manifest not updated,
or manifest updated but service file not wired — cannot slip through as a
self-consistent false-green.

## Instrument contract (trust chain)

    python3 scripts/stagewright/instrument.py --loader neoforge   # or fabric

Bare-RPC contract checks against a plain worlddriver dedicated server —
the instrument face testkit itself depends on (spec §4). Green here is the
precondition for trusting any scene's setup/assertions. Contract:
`../docs/stagewright/instrument-contract-v0.md`.

## T1: client topology (fabric, under Xvfb) — P2b

T1 proves the same `wd.*` scene suite runs on a **real Fabric client hosting an
integrated (singleplayer) server**, not just the dedicated dogfood server T0
drives. The orchestrator self-manages a headless client end-to-end:

    python3 scripts/stagewright/t1.py

It probes a free X display, launches its own **Xvfb** on it (PID-tracked, killed
by PID on exit — never `pkill`, never the live dev client's `:99`/`:97`), boots
`:fabric:runStageWrightClient` (a **client** JVM, not a server), drives title →
singleplayer → world by **label-matched widget clicks** (`guidrive.py`, RPC-driven,
no window manager), lets world-entry start the integrated server which arms the
harness, then judges the run through the **reused** `verdict.py` against
`--expect-file`. The template world is copied in before launch and the copy is
deleted after — the cached template archive is the only persistent artifact.
Exit codes: **0 GREEN / 1 RED / 2 DEAD** (a canary landed on the wrong outcome —
framework void) **/ 3 ENV** (client never came up / GUI drive failed / no footer).

`--hold` boots the shell with `-Pt1Autorun=false` (no scenes) and leaves the
in-world client online for a second tool to attach; `--attach` reuses that
already-online client instead of self-launching.

### Client instrument contract (`instrument_client.py`)

The T1 counterpart of `instrument.py`: bare-RPC contract checks that need a
**real player in the integrated server's PlayerList** (so #41 full 36-slot
inventory, #45 attack cooldown, #55 damage source — which a dedicated-server
FakePlayer cannot exercise), plus the #280 unknown-key live E2E and
`mc.test.reset` client-entry reset behavior.

    python3 scripts/stagewright/instrument_client.py              # self-launch (reuses the t1.py shell, autorun OFF)
    python3 scripts/stagewright/instrument_client.py --attach     # reuse an online `t1.py --hold` client
    python3 scripts/stagewright/instrument_client.py --rounds 3   # client-pool reuse: quit-to-title → re-enter → mc.test.reset, N rounds
    python3 scripts/stagewright/instrument_client.py --rounds 2 --fresh-process   # discard-and-relaunch fallback instead of in-place re-enter

Cold client boot is the expensive step (~28-30s); `--rounds` reuse re-enters
the same world (a `mc.test.reset` between rounds) at roughly **≈7× cheaper**
per extra round, which is what proves the reset restores a clean per-round
state. `--wall N` caps self-launch (default 900). Exit codes: **0/1/2/3** as
above, plus **4 = BLOCKED** on multi-round runs when a CHECK's outcome drifts
across rounds (per-check inter-round drift — the reuse contract is not
deterministic). A reuse-transition exception (quit-to-title → re-enter)
occurring AFTER at least one round has cleanly completed is also classified
BLOCKED rather than ENV — a client that finished a round but can no longer
re-enter is reuse-residue-suspect, not environment; a genuine environment
cause would reproduce in single-round mode, which still reports ENV.
First-entry failures (before any round completes) and `--fresh-process`
transitions remain ENV. The full contract (checks, canaries, `--hold`
autorun-OFF topology, reuse semantics) is the **client appendix** of
`../docs/stagewright/instrument-contract-v0.md`
（"P2b 附录 — 客户端仪表契约（T1 面）"）.

**偏差声明（P2b）**：T1 目前 **仅 fabric**（唯一有成熟客户端工装的 loader —
knot 客户端 + `into_world`/GUI 驱动先例）；neoforge 客户端对等延后。首批
**in-game UI 授权场景**（场景体内直接断言客户端 UI）随 **P2c** 再议——理由是
**场景体跨线程阻塞铁律**（场景体在服务器 tick 上 inline 跑，绝不可阻塞等客户端），
因此 P2b 的客户端断言全部经 `instrument_client.py` 仪表面交付，而非 in-game 场景体。

## JUnit 5 attach (out-of-process) — P2c

`stagewright/junit` (`:stagewright-junit`) is a **pure-JVM** JUnit 5 module: no game
classes, no Minecraft on its classpath. Its live UI tests **attach** to an
already-online T1 topology over RPC, so a UI scene body runs on the JUnit test
thread — free to `await` an asynchronous client screen — instead of inline on a
server tick (the cross-thread blocking rule that kept in-game UI scenes out of
P2b). It is the correct home for the first-batch UI scenes P2b deferred.

**Attach contract.** The module discovers the live topology through the
`TESTKIT_ENDPOINT` environment variable, which names an absolute path to a
descriptor file written by `t1.py --hold`. The descriptor is a frozen schema-v1
JSON record — `{version, topology, loader, rpcHost, rpcPort, worldName, holdPid,
writtenAtEpochMs}`, written atomically (`.tmp` → `os.replace`) so an attaching
reader never sees a partial file. The authoritative schema and key-by-key
semantics live in the **attach appendix** of
`../docs/stagewright/orchestration-contract-v0.md`
（`## TESTKIT_ENDPOINT attach 契约（v0 附录，P2c T1）`）. `Endpoint.parse` rejects
a missing key **loudly** (`IllegalArgumentException`) rather than defaulting it —
a truncated descriptor never attaches to a wrong port.

**Fail-fast.** When no live endpoint is configured — `TESTKIT_ENDPOINT` unset (or
empty) and no `stagewright.endpoint` property, or the named file is absent — `attach`
throws `StageWrightAttachException` carrying the exact operator hint

    python3 scripts/stagewright/t1.py --hold

so a developer who runs a UI test with no topology up gets the one command that
brings one up, never a silent hang or a mystery connection refusal.

**Attach latency budget.** With an endpoint present, `attach()` is bounded at
worst-case **~10s** — a 5s websocket connect window plus a 5s `mc.system.version`
liveness probe — before it fails loudly. The far larger cost sits BEFORE attach:
`t1.py --hold` cold-boots the client in ~28-30s prior to the endpoint file
landing; budget for that in any wrapper that starts the topology itself.

**Serial lease.** One `--hold` topology serves **one** attach client. The
extension attaches a single shared `StageWright` **singleton** once per JVM (guarded
by a lock; a failed attach is re-thrown as a LOUD container-level error on every
later use, never downgraded to a skip), matching the `t1.py` serial-lease rule —
no second topology instance, no concurrent attach.

**Two-layer test structure.** The module's tests split into two layers that can
never silently shrink each other:

- **Pure self-tests** (`SelfTest`) — endpoint parse/round-trip, envelope codec,
  `pollUntil` timeout-vs-return, `StageWrightTimeoutException` ≠ `AssertionError` —
  always run; they need no game and no socket.
- **Live UI tests** (`ui.*`) — gated `@EnabledIfEnvironmentVariable(TESTKIT_ENDPOINT)`;
  they run only when a live endpoint is present.

The two attach **fail-fast** self-tests are the symmetric counterpart: they are
`@DisabledIfEnvironmentVariable(TESTKIT_ENDPOINT)`, because the "no endpoint
configured" branch they assert is only reachable when the env is **absent** (with
it present, `attach` succeeds and defeats the `assertThrows`). So the gating is a
mirror, not a hole: **env-off** ⇒ the 2 fail-fast self-tests run + the 6 live UI
tests skip; **env-on** ⇒ the 2 fail-fast self-tests skip + the 5 runnable live UI
tests run. Every test runs in exactly one of the two modes and JUnit reports the
skips honestly — a test can never fall through both gates and vanish.

**Command walkthrough.**

    python3 scripts/stagewright/t1.py --hold          # boots the T1 client (autorun OFF), stays online,
                                                  # prints:  export TESTKIT_ENDPOINT=<abs path>
    export TESTKIT_ENDPOINT=<abs path>            # eval the printed line (fabric: fabric/run-t1/testkit-endpoint.json)
    ./gradlew :stagewright-junit:test --rerun-tasks   # live UI tests attach and run; SIGINT the t1.py
                                                  # PID when done — it deletes the descriptor on exit.
                                                  # --rerun-tasks is MANDATORY: TESTKIT_ENDPOINT is an
                                                  # env var, not a gradle task input, so a plain re-run
                                                  # is UP-TO-DATE and silently skips every live test.

`t1.py --hold --loader neoforge` writes the same schema-v1 descriptor for a
**neoforge** client (P2c closed the P2b deviation-1: the client topology now
generalizes across both loaders), so the identical JUnit module attaches to either
loader with no code change.

**✅ containerFurnace — task#90 收案（D1）**：`ui.containerFurnace` 要**右键世界里的方块**
打开方块实体容器屏（`FurnaceScreen`），而仪表面曾缺这一维——`mc.client.input.click` 只在
已开屏内点 widget、`mc.client.input.key` 只走键盘绑定（原版「使用/放置」绑右键，`glfwKeyCode`
不映射），唯一能右键世界方块的 `mc.bot.useItem` 是模块纪律禁依赖的行为面 verb。task#90 落了
instrument 级 **`mc.test.input.useOnBlock`**（世界右键，合成 `BlockHitResult` 直调 `gameMode`）
+ 配套 **`mc.test.input.heldKeys`**（持键回读），补上了缺的世界右键维度。`ContainerFurnaceTest`
因此从 `@Disabled` **转为启用**（`@EnabledIfEnvironmentVariable(TESTKIT_ENDPOINT)`），经 attach
在 live 世界真开炉屏。完整证据见 `../.superpowers/sdd/task-2-report.md`（task#90 = D1-T2）。

## T2: production topology (dedicated + client) — P3a

T2 is the **production-isomorphic** topology: a **dedicated server** JVM and a
**real client** JVM, wired over a genuine multiplayer connection — the same shape
a `SurvivalTest` run has (real client on a real dedicated server), not the
integrated single-JVM server T1 hosts. Two worlddriver RPC sockets are live at
once: one on the **client** face and one on the **dedicated server** face. This
is the topology that closes P1b's headless gap for real — the server-side
observation/assertion checks now run against a **dedicated** `PlayerList` holding
a real `ServerPlayer` that arrived over the network, not a FakePlayer stand-in.

    python3 scripts/stagewright/t2.py                     # scored, fabric (default)
    python3 scripts/stagewright/t2.py --loader neoforge   # scored, neoforge
    python3 scripts/stagewright/t2.py --hold              # stand the topology up, run NO scenes, stay online for attach

The scored run boots `:<loader>:runT2Server` (a dedicated server on a pinned port
— fabric 25597, neoforge 25596, dogfood's 25599 all distinct) and the T1
`runStageWrightClient` under its own PID-tracked Xvfb, drives the client
**title → Multiplayer → Direct Connection → 127.0.0.1:<port>** by label-matched
widget clicks (`guidrive.py`), runs a **dual-end probe** (client `mc.client.player`
AND server `mc.observe.player` must both see the same player at the same position),
then triggers the scene suite and harvests the same `verdict.py` footer as T0/T1.
The template world is copied in before launch and the copy deleted after; the
cached per-loader template archive (`.t2-world-template-<loader>`) is the only
persistent artifact. Exit codes: **0 GREEN / 1 RED / 2 DEAD / 3 ENV**, as T1.

### `mc.test.run` — on-demand scene trigger

T2 does not autorun the scene suite at world-load the way the dogfood/T1 servers
do. Instead the orchestrator, once the dual-end probe passes, calls **`mc.test.run`**
on the **server** face: an on-demand trigger that runs the registered scene suite
and appends its footer to `stagewright-results.jsonl`. It is **idempotent** — a
second call while a run is in flight is rejected by an in-flight latch rather than
starting an overlapping run — and it is the **first testkit consumer of the P2a
`registerVerb` SPI** (the product's own paired-registration entry, dogfooded).
The **autorun path is untouched**: T0/T1 still arm and run at world-entry exactly
as before (the fabric/neoforge dogfood gates regression-prove the footer still
emits on the autorun path), so `mc.test.run` is an additive second door, not a
rewrite of the trigger.

Because production `runServer` now arms the `mc.test.*` verbs (the loader
forwarding is unconditional — see the adjudication note in `TODO.md`), the trigger
is reachable on any dedicated server, not only the testkit run configuration; this
is trust-model-consistent (the RPC surface is already a first-party capability
face) and recorded as a testkit-wiring reclassification rather than a behavior change.

### Dual-socket instrument (`instrument_client.py --topology t2`)

The client instrument contract has a T2 face that speaks to **both** sockets. The
#41/#45/#55 permanent assertions (full 36-slot inventory, attack cooldown, damage
source) stage-and-observe on the **server** face — the dedicated `PlayerList`,
which is exactly where P1b's headless gap lived; the #280 unknown-key live E2E
and `mc.test.reset` land on the **client** face. **#55 crosses the real packet
boundary** here: the damage is dealt server-side and its attribution is observed
across the genuine network round-trip a production client sees, not the in-process
shortcut a headless/integrated run takes — the headless gap is closed *in the
production topology itself*, not merely simulated.

    export TESTKIT_ENDPOINT=<abs>                                        # from `t2.py --hold`
    python3 scripts/stagewright/instrument_client.py --topology t2 --attach            # one pass
    python3 scripts/stagewright/instrument_client.py --topology t2 --attach --rounds 3 # resident-server reuse, N rounds

`--rounds` on T2 **disconnects the client and re-connects it to the SAME resident
dedicated server** (the server is **never restarted** — the per-round
resident-server PID is recorded per round as reuse evidence — a change is printed
loudly but the verdict itself keys on check outcomes), then `mc.test.reset`s;
per-check outcomes must be identical across all rounds or the run is **BLOCKED**
(a reset-completeness gap). T2 is **attach-only** — it **requires** `--attach` and
has **no `--fresh-process`** mode: the reuse surface T2 exercises is precisely
*resident-server reuse across a real reconnect*, which is the seed of P3b's client
process pool. (T1's `--fresh-process` discard-restart fallback needs process
ownership T2's attach-only face does not hold.)

### JUnit attach on T2

The **same** `:stagewright-junit` UI tests attach to a T2 topology with **no code
change**: `t2.py --hold` writes the same `TESTKIT_ENDPOINT` descriptor, tagged
`topology: "dedicated_plus_client"` and carrying one extra key — **`serverRpcPort`**
(the dedicated server's RPC port, alongside the client `rpcPort`). `serverRpcPort`
is an **optional** key: the frozen schema-v1 required-8 set is unchanged, a T1
descriptor omits it and still parses, and `Endpoint.parse` tolerates its absence —
so backward compatibility with v1 T1 endpoints is preserved.

    python3 scripts/stagewright/t2.py --hold          # prints: export TESTKIT_ENDPOINT=<abs path>
    export TESTKIT_ENDPOINT=<abs path>            # fabric/run-t2/testkit-endpoint.json
    ./gradlew :stagewright-junit:test --rerun-tasks   # same UI tests attach over the dedicated_plus_client endpoint

### 世界模板两端一致性声明

Per spec §11, both ends run the **same jar** on the **same machine**: the client
and the dedicated server are the identical worlddriver build (one `./gradlew`
tree, one loader per run), and the T2 world derives from a single per-loader
template archive copied into the server's run directory. There is no cross-machine
version skew and no template divergence between the two ends — the client joins a
server whose world, mod set, and protocol are byte-identical to what the same
checkout would produce standalone.

## Verbs & namespace policy (P2a)

The driver exposes a public paired-registration entry so a mod (or the testkit
runtime) can add its own RPC verb with a schema that is validated identically to
every built-in verb:

    ToolCatalog.registerVerb(schema, handler);   // schema + route, atomically

`registerVerb` installs the MCP `ToolSchema` and the `route()` handler in one
step, so a verb can never exist without a schema — a route reached at dispatch
time with no schema is a loud `IllegalStateException`, not a silent skip (that
was the #280-shaped hole). It also enforces the namespace policy at registration
time (throws on violation):

- `mc.*` — reserved for the driver layer.
- `mc.test.*` — granted to the StageWright runtime, and now granted *wholly*: the
  driver's own `mc.test.yaml` (previously grandfathered into this namespace) was
  retired along with the YAML harness, so the grant has a single claimant.
- `<modid>.*` — everything third-party.
- **Hijack guard**: driver-owned names (the built-in curated + hidden catalog) are
  rejected by `registerVerb` even when the name falls
  inside a granted namespace — a third party cannot shadow a driver verb through
  the paired entry. Within the third-party/extra space, last-wins applies
  (same-classpath trust boundary; two mods colliding on one `<modid>.<verb>` is
  not arbitrated).

`mc.test.reset` is the first consumer of this SPI: a hidden (RPC-only, absent
from MCP `tools/list`) client-entry reset for testkit client-pool reuse — it
releases held movement keys, closes any open screen, clears the chat readback
log, and cancels a residual smooth-look process. Client-only: a dedicated server
rejects it loudly.

#280 is closed on the same wave: `mc.bot.setting`'s schema is now CLOSED
(`additionalProperties(false)`) and built from the single-source
`SettingsRegistry`, so an unknown key is rejected loudly, all-or-nothing (nothing
is applied) instead of being silently dropped. The full contract — namespace
policy, paired-registration semantics, #280 closure, and the four headless
checks (18-21) that pin them — is in the P2a appendix of
`../docs/stagewright/instrument-contract-v0.md`.

## Gradle plugin: task entry points (P3b T1)

The `net.magicterra.stagewright` gradle plugin turns the frozen python
orchestration contract into three first-class gradle tasks on the project it is
applied to. This repo's **root project is the first dogfood consumer** (the
plugin lives in a standalone included build wired through `settings.gradle`), so
these tasks run from the repo root:

| task | shells | topology |
|---|---|---|
| `stagewrightServer` | `scripts/stagewright/t0.py` | **T0: server-side scene suite** — dedicated-server dogfood |
| `stagewrightClient` | `scripts/stagewright/t1.py` | **T1: client topology** — real client + integrated server |
| `stagewrightE2E`    | `scripts/stagewright/t2.py` | **T2: production topology** — dedicated server + real client |

(See the **T0**, **T1**, and **T2** sections above for what each orchestrator
does.) Each task **shells** its orchestrator, streams its stdout/stderr live to
the gradle console, and **propagates the exit code verbatim** — the plugin never
parses JSONL nor re-judges; the orchestrator remains the sole verdict authority
(exit legend `0 GREEN / 1 RED / 2 DEAD / 3 ENV / 4 BLOCKED-multiround`). A
non-zero code becomes a `GradleException` carrying the full, copy-paste
re-runnable command line.

**Loader selection.** The plugin's convention is `fabric`; its only override
mechanism is the `stagewright { loader }` extension (there is no built-in `-P`
binding). This repo's root `build.gradle` adds a one-line bridge threading the
`stagewright.loader` project property onto that extension, so the loader dimension is
selectable per invocation while `fabric` stays the default:

    ./gradlew stagewrightServer -Pstagewright.loader=neoforge   # neoforge T0 dogfood
    ./gradlew stagewrightClient                             # fabric T1 (default)
    ./gradlew stagewrightE2E -Pstagewright.loader=neoforge      # neoforge T2

The `stagewright { }` extension also carries `pythonExecutable`, `scriptsDir`,
`expectFile`, `extraArgs`, and the `testmodSourceSet` flag documented in the
next section.

To attach a second tool (`instrument_client.py --attach`, or the JUnit module)
to a `--hold` topology **without** paying a cold boot per invocation, keep the
topology alive with the **Client process pool** (below); the pool prints the same
`export TESTKIT_ENDPOINT=…` line the **JUnit 5 attach** flow consumes.

## Gradle plugin: testmod source-set convention (P3b T3)

This is a second, opt-in facet of the same `net.magicterra.stagewright` plugin
whose three task entry points are documented above; the flag lives in the same
`stagewright { }` extension block.

**Why**: tests belong out of the production jar. A mod that ships gametest /
testkit scenes bundled into `main` ships test-only code (and test-only deps)
to players. The convention is a `testmod` source set — compiled separately,
never packaged into the mod jar — that the gradle plugin `net.magicterra.stagewright`
can register on request.

**How**: opt in via the extension flag (default off, zero impact):

    plugins {
        id 'java'
        id 'net.magicterra.stagewright'
    }
    stagewright {
        testmodSourceSet = true
    }

When `java` is applied (checked via a `Plugins.withType(JavaPlugin)` reaction —
see "boundaries" below) and the flag resolves `true`, the plugin registers a
`testmod` `SourceSet` whose compile **and** runtime classpaths extend
`main`'s output directory plus `main`'s own compile/runtime classpaths, so
`testmod` code can see `main` code and all of `main`'s dependencies. The
registered `SourceSet` is exposed read-only as `testkit.testmodSourceSetRef`
(null when the flag is off, or when `java` was never applied) — consumers wire
it into THEIR OWN loom run config, e.g.:

    loom {
        runs {
            client {
                // sketch — exact loom API varies by loom/fabric-loom version;
                // this repo's own testmod migration (LANDED in P4a) is where a
                // concrete wiring is now proven out — see "Realized wiring" below.
                source(testkit.testmodSourceSetRef)
            }
        }
    }

**Boundaries (binding for v1)**:
- The plugin registers the source set and wires its classpath ONLY. It never
  touches loom run configs, never adds dependencies beyond `main`'s own output
  + classpaths, and never changes jar packaging (`testmod` output is not added
  to any jar task). Auto-wiring the source set into a loom run config is
  **v2** scope — loom's run-config API differs enough across versions that
  baking it into the plugin now would be premature coupling.
- No-java-plugin behavior: registration reacts to `Plugins.withType(JavaPlugin.class, ...)`,
  which fires immediately if `java` is already applied, later if it is applied
  afterwards, and never if it is never applied — so a plain non-java consumer
  with the flag left on does not crash; the source set is simply never
  created. The create-or-not decision itself is deferred to `afterEvaluate` so
  it reads the flag's FINAL value regardless of whether `stagewright { }` is
  configured before or after the `plugins { }` block finishes applying this
  plugin.
- worlddriver's own legacy `@GameTest` tests used to live in `main` and
  violated this convention themselves. Migrating them onto `testmod` source sets
  was a parked, separate task at P3b T3 — **it has since LANDED (P4a)**; the
  concrete, per-loader wiring it produced is recorded in **Realized wiring**
  below. P3b T3 landed only the plugin-side building block; P4a proved it out on
  a real dual-loop (fabric + neoforge) mod.

## Realized wiring: worlddriver's own testmod migration (P4a)

P3b T3 (above) is the plugin-side convention in the abstract; **P4a moved
worlddriver's own tests out of the production jars and into `testmod` source
sets**, which turned every "v2 / consumer figures it out" hand-wave above into a
concrete, byte-gated wiring. This section is the standing record of what that
took — it is loader-mechanism reality, not the plugin flag.

> **⚑ Retired at P4-final.** The migration campaign closed at P4c (legacy
> `@GameTest` 130 → 0; every arena is now an `wd.*` scene) and **P4-final retired
> the old dedicated-server run machinery itself** (the manifest, its run config,
> and the two run/reconcile scripts) — deleted in commit `9f506d1` plus the
> P4-final docs close. The full deleted-machinery inventory with commit pointers
> lives in the drift log's P4-final note:
> [`../docs/stagewright/migration-log.md`](../docs/stagewright/migration-log.md). The
> per-loader wiring below is kept as the standing loader-mechanism record, but
> two present-tense details are now **historical**: the `neoforge` and `fabric`
> `testmod` sets no longer hold any `.java` sources — both are **empty-source
> bridges** that carry only `:common`'s scenes into their loom runs (deleting
> either bridge would silently drop all scene delivery). The dogfood suite is
> armed by `-Dstagewright.autorun` on the `runDogfoodServer` run; stagewright is the
> sole test gate.

**Three testmod source sets, hand-wired (not via the plugin flag).** The plugin's
`testmodSourceSet = true` registers *one* source set and wires its classpath
only — it deliberately never touches loom run configs (v2 scope). worlddriver
needs the source set attached to loom runs across **three** modules, so P4a wires
them directly in each `build.gradle`:

- **`common`** — the dogfood scenes (`WorldDriverScenes`) + probes (`SimProbes`)
  + the `net.magicterra.stagewright.scene.SceneProvider` service file. `testmod`
  compile/runtime classpaths extend `main`'s output + `main`'s own classpaths.
- **`neoforge`** — at P4a this held the 8 legacy `@GameTest` arena classes (+
  `Support`); **since P4c all are migrated to `wd.*` scenes and deleted**, so the
  set is now an **empty-source bridge** (retained: deleting it drops scene
  delivery). Same classpath extension, plus `:common`'s `testmod` **output** on
  the compile classpath (so `SimProbes` delegates resolve).
- **`fabric`** — an (always source-empty) `testmod` set that exists purely as
  the run `source` carrier for `:common`'s testmod output+resources — the same
  empty-source-bridge role neoforge's set now also plays.

**The `.scene` sub-package JPMS lesson.** The scenes could **not** stay in
`net.magicterra.worlddriver.bot.stagewright` when moved to `testmod`: `main` still owns
that package (the production verbs `TestResetVerb` / `TestRunVerb` live there and
must ship). A package owned by two source sets that both feed the same mod module
is a **split package** — the loader's module layer rejects it. The fix was to
move the scenes into a dedicated sub-package
`net.magicterra.worlddriver.bot.stagewright.scene` (the service file becomes
`META-INF/services/net.magicterra.stagewright.scene.SceneProvider`). Lesson: when
relocating classes from `main` into a `testmod` set that is folded into the same
mod, they must occupy a package `main` does not also populate.

**loom `named('main')` vs `maybeCreate('main')` — a real mechanism difference.**
Both loaders fold `:common`'s testmod output into the worlddriver mod via loom's
`mods { }` block, but the API call differs by loom platform:

- **neoforge** can write `mods { named('main') { sourceSet …, project(':common') } }`
  — architectury's neoforge path has already created the default `main`
  ModSettings entry by the time the script body runs.
- **fabric** cannot: fabric-loom creates its default `main` entry in an
  `afterEvaluate` that runs *after* this script body, so `named('main')` throws
  *"ModSettings with name 'main' not found"*. fabric therefore replicates loom's
  own default explicitly — `def mainMod = maybeCreate('main'); mainMod.sourceSet
  sourceSets.main` — and only then adds `:common`'s testmod.

**Per-run scoping: fabric runtimeClasspath vs neoforge global modFolders.** How
scene discovery is *scoped to only the runs that want it* also differs:

- **neoforge**: `:common`'s testmod is deliberately kept **off**
  `runtimeClasspath` and delivered only through the `mods { }` `modFolders`
  group. loom emits modFolders only for source sets on a given run's classpath,
  so a run that does not say `source sourceSets.testmod` (e.g. `contractServer`,
  `server`) never receives the scenes. Putting testmod on the global
  runtimeClasspath instead would leak the scenes into *every* run.
- **fabric**: the opposite is safe — `:common`'s testmod output goes directly on
  **this fabric testmod source set's** `runtimeClasspath`, and only runs whose
  `source` is that testmod set carry it. `contractServer` (whose `main` set never
  carries `:common`'s testmod) stays scene-free.

In both loaders the instrument-contract run (`contractServer`) is intentionally
**not** given the scenes — the instrumentation contract is independent of the
dogfood scenes by design.

**Production-jar byte gate — a standing acceptance convention.** Because "tests
belong out of the production jar" is now enforced by structure rather than by
discipline, P4a promoted it to a *gate that every classpath-touching change must
re-run*. After `./gradlew :fabric:build :neoforge:build`, `unzip -l` each
remapped production jar and assert:

- **ZERO** entries for `AgentGameTest*`, `WorldDriverScenes`, `SimProbes`, and
  `META-INF/services/net.magicterra.stagewright.scene.SceneProvider`;
- **still present**: the production verbs `TestResetVerb` / `TestRunVerb` and
  `META-INF/services/net.magicterra.stagewright.StageWrightVerbHook` (the byte gate is
  bidirectional — it also guards against *accidentally deleting* the production
  verbs that P3a deliberately keeps in `main`).

The same assertion is re-run against the **published** mod jars in `~/.m2`
(`publishToMavenLocal`) so the maven face and the build face agree. (The mod
artifacts publish under `worlddriver-*` (the driver mod) and `mc_testkit-*`
(the testkit family) coordinates — the historical `worlddriver-testkit-*`
naming residual was fixed 2026-07-19, see the maven section above.)

**Migrate-then-delete.** Scenes and their legacy `@GameTest` twins are kept side
by side until a scene is proven a byte-faithful replacement, then the twin is
retired in bounded waves. Wave 1 (P4a) retired 8 twins (legacy registered
130 → 122); **the campaign ran to completion — P4b/P4c retired the rest, legacy
`@GameTest` reached 0 at the P4c finale, and P4-final retired the GameTestServer
run machinery itself. stagewright is now the sole test gate.** The full policy,
per-twin provenance, the reframed legacy acceptance formula, and the P4-final
machinery-retirement note live in the drift log:
[`../docs/stagewright/migration-log.md`](../docs/stagewright/migration-log.md).

## Client process pool (`pool.py`) — P3b T2

`t1.py --hold` / `t2.py --hold` stand a topology up and idle so a second tool can
attach (the **JUnit 5 attach** and **Instrument contract** flows above) — but
every consumer otherwise pays a fresh cold boot (~30-90s T1, minutes T2). The
pool amortizes that across invocations: it keeps a `--hold` topology alive and
lets attachers **reuse** it in ~1s.

    python3 scripts/stagewright/pool.py ensure --topology t2   # reuse a live hold, else launch one DETACHED
    python3 scripts/stagewright/pool.py status                 # probe every topology×loader
    python3 scripts/stagewright/pool.py stop   --topology t2   # release the hold this pool started

`ensure` probes the topology's `TESTKIT_ENDPOINT` descriptor (the same file the
`--hold` shells publish — see the **JUnit 5 attach** section's attach contract)
plus a `mc.system.version` liveness probe against its `rpcPort` (T2 also probes
`serverRpcPort`). Live → it prints `export TESTKIT_ENDPOINT=<path>` + `reused` and
exits 0 in ~1s. Otherwise it cleans stale residue, launches the topology's
`--hold` **detached** (own session, log in the run dir), records
`{pid, topology, loader, startedAtEpochMs, log}` in
`scripts/stagewright/.pool-state.json` (flock-guarded), bounded-polls for the
endpoint file (t1 240s / t2 360s), verifies liveness, and prints `started`. Feed
the printed line straight into an attacher:

    eval "$(python3 scripts/stagewright/pool.py ensure --topology t2 | grep '^export')"
    python3 scripts/stagewright/instrument_client.py --topology t2 --attach   # or: ./gradlew :stagewright-junit:test --rerun-tasks

`stop` SIGINTs the recorded hold PID (its `finally` deletes the endpoint file),
bounded-waits for the descriptor to vanish (SIGKILL after grace), then drops the
state entry. It **refuses loudly** to stop a live endpoint it did not start (a
manual `--hold` orphan whose PID is not the pool's to guess) and only deletes a
probe-dead orphan descriptor — never `pkill`; every process op targets an
explicit recorded PID. The T2 resident-server reuse the pool builds on is the
same surface `instrument_client.py --topology t2 --rounds N` exercises across a
reconnect (see the **Dual-socket instrument** section under T2). This gradle-free
pool is the process-lifecycle counterpart to the plugin task entry points above:
the plugin *runs* a topology to a verdict, the pool *keeps one warm* for attach.

## Maven publishing (P3b T4)

The **Client process pool** and **gradle plugin** sections above cover running
and reusing topologies; this section covers shipping the framework itself. The
`mc_stagewright-junit` artifact below is what an out-of-process **JUnit 5 attach**
consumer depends on.

`./gradlew publishToMavenLocal` from the repo root publishes seven artifacts
to `~/.m2/repository/net/magicterra/`:

| artifactId | module | POM dependencies |
|---|---|---|
| `worlddriver-common` | root `common` | none (cleansed) |
| `worlddriver-fabric` | root `fabric` | none (cleansed) |
| `worlddriver-neoforge` | root `neoforge` | none (cleansed) |
| `mc_stagewright-common` | `stagewright/common` | none (cleansed) |
| `mc_stagewright-fabric` | `stagewright/fabric` | none (cleansed) |
| `mc_stagewright-neoforge` | `stagewright/neoforge` | none (cleansed) |
| `mc_stagewright-junit` | `stagewright/junit` | gson, junit-jupiter-api |

**Two opposite POM rules, and why.** The six mod-jar publications (root
`subprojects{}` block in the top-level `build.gradle`) nest their runtime
dependencies (Rhino, netty-codec-http, and for the testkit
mod-jars each other) via Jar-in-Jar and are remapped, self-contained
artifacts — a POM that re-declared those deps would hand a naive consumer a
second, unremapped copy of the same classes on their classpath
(`docs/feedback/2026-06-04`, bug #2). So those POMs are stripped of every
`<dependency>` entry and `GenerateModuleMetadata` is disabled outright.
`stagewright/junit` is the opposite case: a thin plain-JVM library with no
shading, so its POM **must** declare its real compile-time deps (gson,
junit-jupiter-api) or a consumer's build tool has no way to resolve them
transitively. Its `.module` Gradle metadata is left enabled (unlike the mod
jars) because, with no Jar-in-Jar split to reconcile, the variant graph and
the POM already agree.

**Naming quirk — FIXED (2026-07-19):** the three
`stagewright/{common,fabric,neoforge}` build.gradle files set
`base.archivesName = 'mc_testkit-*'`, but the root `subprojects{}` publishing
block used to read `artifactId = base.archivesName.get()` with an eager
`.get()` that resolved *before* those child scripts ran, so the published
Maven artifactId came out as `worlddriver-testkit-{common,fabric,neoforge}`.
The root build now defers the read to `afterEvaluate`, so the child override
wins and the published coordinates match the jar file names:
`mc_testkit-{common,fabric,neoforge}` (verified via `publishToMavenLocal`;
POMs remain dependency-cleansed; the stale `worlddriver-testkit-*` mavenLocal
directories were removed — only mavenLocal ever carried them, no remote
consumers existed).

**Consuming `mc_stagewright-junit` from an external Gradle project:**

    repositories {
        mavenLocal()
    }
    dependencies {
        implementation 'net.magicterra:mc_stagewright-junit:0.1.0+1.21.1'
    }

### Support matrix

| | |
|---|---|
| Minecraft | 1.21.1 |
| Fabric Loader | ≥ 0.16.14 |
| NeoForge | 21.1.230 line (`[21,)`) |
| Java | 21 |

### Compatibility promise

- The **instrumentation contract v0** frozen surface (see
  `../docs/stagewright/instrument-contract-v0.md` and
  `../docs/stagewright/orchestration-contract-v0.md`) is backward-compatible:
  code written against it keeps working across patch/minor releases of this
  module.
- The **behavioral surface and internal APIs** (scene execution timing,
  internal classes not part of the frozen contract, `StageWrightRpc` wire
  details) carry **no compatibility promise** and may change without notice.
- Published artifact versions track `mod_version` in the root
  `gradle.properties` — there is no independent versioning scheme for
  `stagewright` or `mc_stagewright-junit`.
