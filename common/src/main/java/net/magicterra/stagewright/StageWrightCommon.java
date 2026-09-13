package net.magicterra.stagewright;

import com.mojang.logging.LogUtils;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.magicterra.stagewright.harness.EndpointDescriptor;
import net.magicterra.stagewright.harness.ResultsJsonl;
import net.magicterra.stagewright.harness.StageWrightHarness;
import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.verbs.TestInputVerbs;
import net.magicterra.stagewright.verbs.TestResetVerb;
import net.magicterra.stagewright.verbs.TestRunVerb;
import net.magicterra.stagewright.harness.SceneFilter;
import net.magicterra.stagewright.harness.Scenes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

/** Common core of stagewright. Loader entries forward server lifecycle + tick here. */
public final class StageWrightCommon {
    public static final String MOD_ID = "mc_testkit";
    public static final Logger LOG = LogUtils.getLogger();

    /** Results file, relative to the server's working directory (the loom runDir). Repeated in the
     *  engine's {@code RunDirectory.DEFAULT_RESULTS_FILE}: that module judges runs and never sees a
     *  Minecraft classpath, so the two processes agree on the literal rather than on a class. */
    private static final String DEFAULT_OUT_FILE = "stagewright-results.jsonl";

    /** The property a supervisor renames the results file with; see {@link #outFile()}. */
    private static final String RESULTS_PROPERTY = "stagewright.results";

    /**
     * Where this run writes its results.
     *
     * <p>Renaming has to reach the WRITER, which is the half that used to be missing. The CLI's
     * {@code --results} and the plugin's {@code resultsFile} only ever changed which file the
     * verdict opened; the harness kept writing the default name, so a renamed run produced a
     * complete green results file and a verdict of "the run wrote no results" pointing at a path
     * nothing had written.
     *
     * <p>Read at the moment the harness is built rather than cached in a static, because this class
     * initialises during mod construction — before a loader that sets the property later has set it.
     * The cost is one property read per suite.
     */
    private static String outFile() {
        String named = System.getProperty(RESULTS_PROPERTY, "").trim();
        return named.isEmpty() ? DEFAULT_OUT_FILE : named;
    }

    // Suite lifecycle state. All mutation is under this class's monitor (the static-synchronized
    // methods); `harness` is volatile so onServerTick reads it lock-free every tick.
    private static volatile StageWrightHarness harness;
    private static MinecraftServer armedServer;
    private static String armedLoader;
    private static List<Scene> resolvedScenes;   // resolved once at arm time on the server thread
    private static boolean armed;
    private static boolean verbHooksInstalled;
    private static boolean onDemandRequested;

    // ---- Startup tick-debt settle barrier (task#88) ----
    // A freshly-STARTED MinecraftServer carries accumulated tick DEBT and runs unthrottled
    // catch-up ticks (~3 ms/tick instead of the steady 50 ms cadence) until it is caught up.
    // Arming the scene harness during that burst makes tick-budgeted awaits fragile: entity
    // promotion (and similar wall-clock-bound work the scenes await) costs 2-2.3x more ticks
    // for the same real delay in the burst regime, which is exactly why wd.entityLeash needed
    // repeated stop-bleeds (within 60→120→180). This barrier gates the harness.tick() FORWARD
    // until the tick cadence has stabilized to the real ~50 ms rhythm — 10 consecutive server
    // ticks spaced >=40 ms apart. Only the FORWARDING is gated: harness construction, the
    // tick-pure await contract (SceneContext.within counts pure observed ticks) and
    // PREP_BUDGET_TICKS are all untouched. Because harness.tick() is simply never called before
    // settle, every tick budget naturally counts from the first post-settle tick. Safety valve:
    // after 1200 observed ticks with no settle, arm anyway (WARN) so a pathological host can
    // never hang the suite forever. All of this state is touched only from onServerTick (server
    // thread), so it needs no synchronization.
    private static final long CADENCE_NANOS = 40_000_000L;   // 40 ms — floor of a real ~50 ms tick
    private static final int CADENCE_STREAK = 10;            // consecutive on-cadence ticks to settle
    private static final int SETTLE_SAFETY_TICKS = 1200;     // never hang: force-arm after this many
    private static boolean settled;
    private static int settleTickCount;
    private static int consecutiveCadenceTicks;
    private static long lastTickNanos;

    private StageWrightCommon() {}

    /**
     * True when this run is a HOLD: stand the topology up and leave it standing, for whatever
     * attaches from outside.
     *
     * <p>A separate property rather than {@code -Dstagewright.autorun=false}, because the two are
     * set by different people. Autorun belongs to the host build's run configuration — a checked-in
     * line in someone's {@code build.gradle} — and a hold is a thing you decide at the command line,
     * about a run config you are not editing. Overriding the first from the second means two
     * {@code -D}s for one key on one command line and a silent dependence on which the JVM reads
     * last; getting that wrong does not fail, it runs the whole suite underneath whatever attached,
     * which is how this was found.
     */
    public static boolean holding() {
        return Boolean.getBoolean("stagewright.hold");
    }

    /**
     * Arm the runtime when the server reaches STARTED. Two confluent paths meet here:
     * <ul>
     *   <li><b>autorun</b> ({@code -Dstagewright.autorun=true}) — build the harness immediately, exactly
     *       as before (byte-identical; all existing T0/T1 dogfood paths are zero-touch);</li>
     *   <li><b>on-demand</b> ({@code stagewright.autorun} unset) — arm but do NOT execute: record
     *       "armed, awaiting mc.test.run" and wait for the {@code mc.test.run} RPC verb to trigger
     *       {@link #triggerOnDemandRun()}.</li>
     * </ul>
     * In BOTH paths the {@code mc.test.*} verbs are registered here, directly — so the verb surface
     * (and its hidden-schema contract) is identical whether
     * or not the suite auto-runs. Runs on the server thread; called by every loader entry after
     * worlddriver has wired its route sink at SERVER_STARTING.
     */
    public static synchronized void onServerStarted(MinecraftServer server, String loader) {
        if (armed) {
            LOG.warn("[{}] harness already armed — ignoring duplicate onServerStarted", MOD_ID);
            return;
        }
        armed = true;
        armedServer = server;
        armedLoader = loader;
        // Resolve the registry once, on the server thread (correct ServiceLoader context class
        // loader for SceneProvider discovery), and cache it: autorun builds the harness from it now;
        // on-demand builds from the SAME list later, and needs its size synchronously to answer
        // {scenes:N} without re-running ServiceLoader off the server thread.
        resolvedScenes = Scenes.all();
        // Narrowing happens HERE, before the harness exists, so the suite header's registered list
        // is the filtered one and every downstream rule (SWALLOWED, DRIFTED, the canary gates) keeps
        // working against what this run actually meant to do rather than against the full registry.
        String filter = SceneFilter.pattern();
        if (filter != null) {
            int before = resolvedScenes.size();
            resolvedScenes = SceneFilter.apply(resolvedScenes, filter);
            LOG.warn("[{}] FILTERED to '{}' — {} of {} scenes. This run is NOT a gate result.",
                    MOD_ID, filter, resolvedScenes.size(), before);
        }
        // Register the mc.test.* verbs — BOTH autorun states, so the hidden-verb contract is
        // topology-uniform.
        installVerbHooks();

        if (holding()) {
            LOG.info("[{}] HELD — the suite will not run itself ({} scenes registered, mc.test.run"
                    + " still works). Stop the run to end the hold.", MOD_ID, resolvedScenes.size());
        } else if (Boolean.getBoolean("stagewright.autorun")) {
            if (Boolean.getBoolean(AWAIT_PLAYER)) {
                // Production topology: a dedicated server with a real client connected to it. The
                // scenes must not start before that client is actually in — otherwise the run
                // proves nothing the single-JVM topology did not already prove, and any scene that
                // observes a player would be racing the login sequence.
                LOG.info("[{}] armed, deferring the suite until a player joins (-D{}) — {} scenes",
                        MOD_ID, AWAIT_PLAYER, resolvedScenes.size());
            } else {
                harness = new StageWrightHarness(server, loader, resolvedScenes, new ResultsJsonl(Path.of(outFile())));
            }
        } else {
            LOG.info("[{}] armed, awaiting mc.test.run ({} scenes) — stagewright.autorun not set",
                    MOD_ID, resolvedScenes.size());
        }

        // Only where no client shares this JVM. An integrated server has one, and its ClientDirector
        // publishes the same port later, once there is a world to attach to.
        if (server.isDedicatedServer()) {
            EndpointDescriptor.writeIfRequested(loader, server.getWorldData().getLevelName());
        }
    }

    /** Fully-qualified probe for the driver's verb registry, resolved BY NAME and deliberately not
     *  imported: the whole point is to answer "is worlddriver on this classpath" without linking
     *  against it. */
    private static final String DRIVER_PROBE = "net.magicterra.worlddriver.mcp.ToolCatalog";

    /** Register StageWright's own {@code mc.test.*} verbs into the driver's ToolCatalog (idempotent),
     *  when a driver is present at all. A verb that throws is logged but must not abort arming — the
     *  suite (autorun or on-demand) is independent of any single verb registration.
     *
     *  <p>This used to go through a {@code StageWrightVerbHook} ServiceLoader SPI implemented on the
     *  worlddriver side, because stagewright-common could not import {@code ToolCatalog} without a
     *  circular module dependency. StageWright now depends on the driver it drives, so it registers
     *  its own verbs and the SPI is deleted.
     *
     *  <p><b>Why the presence probe.</b> The three classes under {@code verbs/} are the ONLY part of
     *  StageWright that touches worlddriver; the scene API, the harness and the loader entries need
     *  nothing but Minecraft. That separation only buys anything if arming survives worlddriver's
     *  ABSENCE — which is the normal case the moment StageWright is loaded into a third-party mod's
     *  dev runtime, since such a mod depends on neither repo.
     *
     *  <p>The probe must come first, and must go by name. {@code List.of(TestRunVerb::register, …)}
     *  resolves all three classes at the lambda-metafactory bootstrap, i.e. while building the list
     *  and therefore BEFORE the loop body — so a try/catch around {@code reg.run()} never sees the
     *  resulting NoClassDefFoundError. Loading {@code TestRunVerb} alone is already fatal: its
     *  {@code SCHEMA} initializer calls into worlddriver's {@code Schemas}. */
    private static void installVerbHooks() {
        if (verbHooksInstalled) return;
        verbHooksInstalled = true;
        if (!driverPresent()) {
            LOG.info("[{}] no worlddriver on this classpath — skipping the mc.test.* verbs. Scenes still "
                    + "run; what is absent is the on-demand trigger and the bot input verbs, so this "
                    + "runtime is autorun-only.", MOD_ID);
            return;
        }
        try {
            for (Runnable reg : List.<Runnable>of(
                    TestRunVerb::register, TestResetVerb::register, TestInputVerbs::register)) {
                try {
                    reg.run();
                } catch (Throwable t) {
                    LOG.error("[{}] stagewright verb registration failed", MOD_ID, t);
                }
            }
        } catch (Throwable t) {
            // A driver that is PRESENT but incompatible (ToolCatalog moved, Schemas signature changed)
            // fails while the list is being built, outside the inner catch. Land it here rather than
            // aborting the arm: the verbs are a convenience, the suite is the product.
            LOG.error("[{}] verb wiring failed against the driver on this classpath — continuing "
                    + "without the mc.test.* verbs", MOD_ID, t);
        }
    }

    private static boolean driverPresent() {
        try {
            Class.forName(DRIVER_PROBE, false, StageWrightCommon.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * On-demand suite trigger — the {@code mc.test.run} handler. Runs on the RPC socket thread:
     * it idempotency-checks and SCHEDULES the harness build onto the server thread (the same
     * execution context the autorun SERVER_STARTED path uses — the suite runs on server ticks, so
     * the harness must never be built inline on the socket thread). Returns the accept envelope, or
     * throws a loud {@link IllegalStateException} — never a silent re-run.
     *
     * @return {@code {accepted:true, scenes:N}} where N is the registered scene count
     * @throws IllegalStateException if the runtime is not armed, or the suite already ran / is running
     */
    public static synchronized Map<String, Object> triggerOnDemandRun() {
        MinecraftServer server = armedServer;
        if (!armed || server == null || resolvedScenes == null) {
            throw new IllegalStateException(
                    "mc.test.run: the testkit runtime is not armed on this server — the scene harness "
                    + "only exists where StageWrightCommon.onServerStarted fired (a testkit runtime). "
                    + "Nothing to trigger here.");
        }
        if (harness != null || onDemandRequested) {
            boolean done = harness != null && harness.isFinished();
            throw new IllegalStateException(
                    "mc.test.run: the scene suite has already " + (done ? "run" : "been started")
                    + " on this server — refusing to re-run (idempotent; the JSONL done footer is the "
                    + "sole completion signal).");
        }
        // Hardening (Task-1 review Minor 2): guard the accept against a stopping server. A stopping
        // MinecraftServer silently DROPS anything handed to server.execute() — so accepting here would
        // return {accepted:true} while the scheduled harness build never ran, latching onDemandRequested
        // forever with no suite. Check isRunning() BEFORE accepting and fail loudly instead (minimal
        // correct form — a pre-accept guard rather than a post-accept best-effort in the lambda, since
        // once we've returned {accepted:true} the RPC caller has no further error channel).
        if (!server.isRunning()) {
            throw new IllegalStateException(
                    "mc.test.run: the server is stopping — refusing to accept (a stopping server.execute() "
                    + "queue would silently drop the scheduled harness build, latching the suite as "
                    + "\"already started\" with nothing ever running).");
        }
        onDemandRequested = true;
        List<Scene> scenes = resolvedScenes;
        String loader = armedLoader;
        // Build on the server thread. Re-check under the monitor there so a race with autorun (which
        // sets `harness` on the server thread) cannot double-build.
        server.execute(() -> {
            synchronized (StageWrightCommon.class) {
                if (harness != null) return;
                try {
                    harness = new StageWrightHarness(server, loader, scenes, new ResultsJsonl(Path.of(outFile())));
                } catch (Throwable t) {
                    // Hardening (Task-1 review Minor 1): the harness ctor can throw (duplicate scene
                    // name / origin-slot collision — see StageWrightHarness). If it does, RELEASE the
                    // on-demand latch so a later mc.test.run can retry instead of being permanently told
                    // "already been started" when nothing was ever built. harness stays null.
                    onDemandRequested = false;
                    LOG.error("[{}] mc.test.run: harness build failed on the server thread — "
                            + "released the on-demand latch for retry", MOD_ID, t);
                }
            }
        });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accepted", true);
        result.put("scenes", scenes.size());
        return result;
    }

    /**
     * True once the suite has drained its registry and written the done footer.
     *
     * <p>Read by the client director to know when an INTEGRATED-server run is over. It is
     * deliberately the harness's own flag rather than a poll of the results file: the file is the
     * orchestrator's contract with the outside world, and having the in-JVM client race the
     * orchestrator to read it would make the client's exit depend on filesystem timing.
     */
    public static boolean suiteFinished() {
        StageWrightHarness h = harness;
        return h != null && h.isFinished();
    }

    /**
     * Tell every level it is not empty, every tick, for as long as this mod is loaded.
     *
     * <p>Vanilla stops ticking a {@code ServerLevel} that nobody is in. {@code ServerLevel.tick}
     * computes {@code !players.isEmpty() || !getForcedChunks().isEmpty()} and, when that has been
     * false for 300 consecutive ticks, jumps <b>over both the entity loop and
     * {@code tickBlockEntities()}</b>. The level keeps ticking in every other respect — the server
     * loop runs, chunks stay loaded, commands work, blocks can be placed and read back — so nothing
     * about such a level looks stopped.
     *
     * <p>A dedicated topology has no player by definition, and the arena's chunks are pinned with a
     * runtime {@code TicketType.FORCED} region ticket — which is NOT what {@code getForcedChunks()}
     * returns. That reads the {@code /forceload} saved data, so the pin that keeps the arena loaded
     * contributes nothing to the emptiness test. Fifteen seconds in, nothing moves on its own.
     *
     * <p>Everything that made this look like several unrelated bugs follows from WHEN a scene runs
     * rather than from what it does: worlddriver's entity scene is seventh and passed inside the
     * window, its block-entity probe is around the hundred-and-ninetieth and never ticked; a heavy
     * modpack boots slowly enough that even the entity scene lands outside, so there the ENTITY half
     * failed too and the two halves were investigated as different problems. Every chunk assertion
     * answers true throughout, correctly — chunk status is not what is being tested.
     *
     * <p><b>Here rather than on the harness, and that placement is the whole point.</b> The first fix
     * called this from {@code StageWrightHarness.observeServerTick}, which is only reached once a
     * harness exists — and under {@code holding()} no harness is built at all until {@code mc.test.run}
     * asks for one. A hold takes far longer than fifteen seconds to boot, publish its descriptor and
     * be attached to, so everything that attaches to a hold before triggering a suite — today
     * {@code :stagewright-junit}, tomorrow attached scripts — would have driven a world where nothing
     * moved. Keeping a level awake is a property of "StageWright is loaded", not of "a suite is
     * mid-run", and this mod is only ever present in a test run.
     *
     * <p>{@code resetEmptyTime()} is public and is exactly vanilla's own escape hatch: it is what
     * {@code ServerChunkCache} calls when a chunk is force-loaded through the supported path. All
     * levels rather than the arena's, because PREP generates terrain before a scene's level is
     * chosen and a scene may name any dimension — and because the cost is one field write each.
     */
    private static void keepLevelsAwake(MinecraftServer server) {
        for (net.minecraft.server.level.ServerLevel level : server.getAllLevels()) {
            level.resetEmptyTime();
        }
    }

    public static void onServerTick(MinecraftServer server) {
        keepLevelsAwake(server);

        // Liveness for the stall watchdog, ABOVE the settle barrier: this must count every server
        // tick, including the catch-up ticks the barrier below deliberately swallows. A watchdog fed
        // from the other side of that `return` would see a draining tick debt as a wedged server.
        StageWrightHarness live = harness;
        if (live != null) live.observeServerTick();

        // Settle barrier (task#88): drain startup tick debt before arming scenes. See the field
        // block above for the full rationale. Until the cadence settles we track tick spacing and
        // forward NOTHING to the harness — so all tick budgets count from the first post-settle tick.
        if (!settled) {
            long now = System.nanoTime();
            settleTickCount++;
            if (lastTickNanos != 0L && (now - lastTickNanos) >= CADENCE_NANOS) {
                if (++consecutiveCadenceTicks >= CADENCE_STREAK) {
                    settled = true;
                    LOG.info("[{}] testkit: tick cadence settled after {} server ticks (tick debt drained)",
                            MOD_ID, settleTickCount);
                }
            } else if (lastTickNanos != 0L) {
                consecutiveCadenceTicks = 0;
            }
            lastTickNanos = now;
            if (!settled && settleTickCount >= SETTLE_SAFETY_TICKS) {
                settled = true;
                LOG.warn("[{}] testkit: tick cadence did NOT settle within {} server ticks — arming "
                        + "anyway (tick debt may still be draining; awaits may run in the burst regime)",
                        MOD_ID, SETTLE_SAFETY_TICKS);
            }
            if (!settled) return;
        }
        if (harness == null && armed && !holding() && Boolean.getBoolean("stagewright.autorun")
                && Boolean.getBoolean(AWAIT_PLAYER) && server.getPlayerCount() > 0) {
            armDeferredSuite(server);
        }

        StageWrightHarness h = harness;
        if (h != null) h.tick();
    }

    /** Property that holds the suite back until a player is on the server. */
    private static final String AWAIT_PLAYER = "stagewright.awaitPlayer";

    /** Build the deferred harness, on the server thread, exactly once. */
    private static synchronized void armDeferredSuite(MinecraftServer server) {
        if (harness != null) return;
        LOG.info("[{}] a player joined — starting the deferred suite ({} scenes)",
                MOD_ID, resolvedScenes.size());
        opTestPlayers(server);
        harness = new StageWrightHarness(server, armedLoader, resolvedScenes,
                new ResultsJsonl(Path.of(outFile())));
    }

    /**
     * Give every player on this server operator rights.
     *
     * <p>Only ever reached on the await-player path, which is the multiplayer TEST topology: this
     * server was launched by the harness, generates a throwaway world, and the sole player on it is
     * the companion client the harness started. That client is a test actor, not a guest — the half
     * of the suite that runs in its JVM has to be able to drive the game, and a dev-launched
     * dedicated server ships an empty {@code ops.json} with {@code online-mode=false}, so without
     * this every command it tries is refused for permissions.
     *
     * <p>Deliberately not done by writing {@code ops.json} at provision time: an offline-mode UUID
     * is derived from the player's NAME, and the name a loader's dev client picks is generated per
     * launch, so there is nothing to write until the player is actually here.
     */
    private static void opTestPlayers(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (server.getPlayerList().isOp(player.getGameProfile())) continue;
            server.getPlayerList().op(player.getGameProfile());
            LOG.info("[{}] op'd the test client '{}' so client-side probes can run commands",
                    MOD_ID, player.getGameProfile().getName());
        }
    }
}
