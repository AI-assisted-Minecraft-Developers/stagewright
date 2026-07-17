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
external-expectation gate** (the fabric manifest `expected-scenes-fabric.txt`
carries the identical governance): a checked-in manifest (one scene name per line,
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
implementation. Since P1.6 the provider lives in the loader-shared module so
ONE registration serves every loader, e.g.
`common/src/main/resources/META-INF/services/net.magicterra.testkit.scene.SceneProvider`:

    net.magicterra.agent.bot.testkit.AgentDriverScenes

Keep exactly one service file per provider across all source sets — a copy in
a loader module alongside the common one double-registers the provider on that
loader's dev classpath and trips the duplicate-scene-name gate (RED by design).

## Instrument contract (trust chain)

    python3 scripts/testkit/instrument.py --loader neoforge   # or fabric

Bare-RPC contract checks against a plain agent-driver dedicated server —
the instrument face testkit itself depends on (spec §4). Green here is the
precondition for trusting any scene's setup/assertions. Contract:
`../docs/testkit/instrument-contract-v0.md`.

## T1: client topology (fabric, under Xvfb) — P2b

T1 proves the same `ad.*` scene suite runs on a **real Fabric client hosting an
integrated (singleplayer) server**, not just the dedicated dogfood server T0
drives. The orchestrator self-manages a headless client end-to-end:

    python3 scripts/testkit/t1.py

It probes a free X display, launches its own **Xvfb** on it (PID-tracked, killed
by PID on exit — never `pkill`, never the live dev client's `:99`/`:97`), boots
`:fabric:runTestkitClient` (a **client** JVM, not a server), drives title →
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

    python3 scripts/testkit/instrument_client.py              # self-launch (reuses the t1.py shell, autorun OFF)
    python3 scripts/testkit/instrument_client.py --attach     # reuse an online `t1.py --hold` client
    python3 scripts/testkit/instrument_client.py --rounds 3   # client-pool reuse: quit-to-title → re-enter → mc.test.reset, N rounds
    python3 scripts/testkit/instrument_client.py --rounds 2 --fresh-process   # discard-and-relaunch fallback instead of in-place re-enter

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
`../docs/testkit/instrument-contract-v0.md`
（"P2b 附录 — 客户端仪表契约（T1 面）"）.

**偏差声明（P2b）**：T1 目前 **仅 fabric**（唯一有成熟客户端工装的 loader —
knot 客户端 + `into_world`/GUI 驱动先例）；neoforge 客户端对等延后。首批
**in-game UI 授权场景**（场景体内直接断言客户端 UI）随 **P2c** 再议——理由是
**场景体跨线程阻塞铁律**（场景体在服务器 tick 上 inline 跑，绝不可阻塞等客户端），
因此 P2b 的客户端断言全部经 `instrument_client.py` 仪表面交付，而非 in-game 场景体。

## JUnit 5 attach (out-of-process) — P2c

`mc-testkit/junit` (`:testkit-junit`) is a **pure-JVM** JUnit 5 module: no game
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
`../docs/testkit/orchestration-contract-v0.md`
（`## TESTKIT_ENDPOINT attach 契约（v0 附录，P2c T1）`）. `Endpoint.parse` rejects
a missing key **loudly** (`IllegalArgumentException`) rather than defaulting it —
a truncated descriptor never attaches to a wrong port.

**Fail-fast.** When no live endpoint is configured — `TESTKIT_ENDPOINT` unset (or
empty) and no `testkit.endpoint` property, or the named file is absent — `attach`
throws `TestkitAttachException` carrying the exact operator hint

    python3 scripts/testkit/t1.py --hold

so a developer who runs a UI test with no topology up gets the one command that
brings one up, never a silent hang or a mystery connection refusal.

**Serial lease.** One `--hold` topology serves **one** attach client. The
extension attaches a single shared `Testkit` **singleton** once per JVM (guarded
by a lock; a failed attach is re-thrown as a LOUD container-level error on every
later use, never downgraded to a skip), matching the `t1.py` serial-lease rule —
no second topology instance, no concurrent attach.

**Two-layer test structure.** The module's tests split into two layers that can
never silently shrink each other:

- **Pure self-tests** (`SelfTest`) — endpoint parse/round-trip, envelope codec,
  `pollUntil` timeout-vs-return, `TestkitTimeoutException` ≠ `AssertionError` —
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

    python3 scripts/testkit/t1.py --hold          # boots the T1 client (autorun OFF), stays online,
                                                  # prints:  export TESTKIT_ENDPOINT=<abs path>
    export TESTKIT_ENDPOINT=<abs path>            # eval the printed line (fabric: fabric/run-t1/testkit-endpoint.json)
    ./gradlew :testkit-junit:test                 # live UI tests attach and run; SIGINT the t1.py
                                                  # PID when done — it deletes the descriptor on exit.

`t1.py --hold --loader neoforge` writes the same schema-v1 descriptor for a
**neoforge** client (P2c closed the P2b deviation-1: the client topology now
generalizes across both loaders), so the identical JUnit module attaches to either
loader with no code change.

**⭐containerFurnace 延后（task#90，偏差延续）**：`ui.containerFurnace` 是一个
**可见、有论证的 `@Disabled` 标记**（绝非静默缩编）——它要**右键世界里的方块**打开
方块实体容器屏（`FurnaceScreen`），而仪表面上没有任何 verb 能做世界右键：
`mc.client.input.click` 只在已开屏内点 widget、`mc.client.input.key` 只走键盘绑定
（原版「使用/放置」绑在右键，`glfwKeyCode` 不映射），唯一能右键世界方块的
`mc.bot.useItem` 是模块纪律禁依赖的行为面 verb。**inventory / chat 屏已被覆盖**
（键盘可开），缺的只是**世界右键**这一维——真修 = 一个 instrument 级的 world-use
verb（`task#90` 双 verb 之一，与持键回读 verb 同批），归 controller 择期。完整证据见
`../../.superpowers/sdd/task-3-report.md`。

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
- `mc.test.*` — granted to the testkit runtime. `mc.test.yaml` is a
  grandfathered driver-layer harness verb.
- `<modid>.*` — everything third-party.
- **Hijack guard**: driver-owned names (the built-in curated + hidden catalog,
  e.g. `mc.test.yaml`) are rejected by `registerVerb` even when the name falls
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
`../docs/testkit/instrument-contract-v0.md`.
