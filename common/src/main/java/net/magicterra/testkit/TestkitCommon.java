package net.magicterra.testkit;

import com.mojang.logging.LogUtils;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import net.magicterra.testkit.harness.ResultsJsonl;
import net.magicterra.testkit.harness.TestkitHarness;
import net.magicterra.testkit.scene.Scene;
import net.magicterra.testkit.scene.Scenes;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

/** Common core of mc-testkit. Loader entries forward server lifecycle + tick here. */
public final class TestkitCommon {
    public static final String MOD_ID = "mc_testkit";
    public static final Logger LOG = LogUtils.getLogger();

    /** Results file, relative to the server's working directory (the loom runDir). */
    private static final String OUT_FILE = "testkit-results.jsonl";

    // Suite lifecycle state. All mutation is under this class's monitor (the static-synchronized
    // methods); `harness` is volatile so onServerTick reads it lock-free every tick.
    private static volatile TestkitHarness harness;
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
    // for the same real delay in the burst regime, which is exactly why ad.entityLeash needed
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

    private TestkitCommon() {}

    /**
     * Arm the runtime when the server reaches STARTED. Two confluent paths meet here:
     * <ul>
     *   <li><b>autorun</b> ({@code -Dtestkit.autorun=true}) — build the harness immediately, exactly
     *       as before (byte-identical; all existing T0/T1 dogfood paths are zero-touch);</li>
     *   <li><b>on-demand</b> ({@code testkit.autorun} unset) — arm but do NOT execute: record
     *       "armed, awaiting mc.test.run" and wait for the {@code mc.test.run} RPC verb to trigger
     *       {@link #triggerOnDemandRun()}.</li>
     * </ul>
     * In BOTH paths the {@code mc.test.*} verbs are registered here via the {@link TestkitVerbHook}
     * ServiceLoader SPI — so the verb surface (and its hidden-schema contract) is identical whether
     * or not the suite auto-runs. Runs on the server thread; called by every loader entry after
     * agent-driver has wired its route sink at SERVER_STARTING.
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
        // Register the mc.test.* verbs (mc.test.run) through the paired SPI — BOTH autorun states,
        // so the hidden-verb contract is topology-uniform.
        installVerbHooks();

        if (Boolean.getBoolean("testkit.autorun")) {
            harness = new TestkitHarness(server, loader, resolvedScenes, new ResultsJsonl(Path.of(OUT_FILE)));
        } else {
            LOG.info("[{}] armed, awaiting mc.test.run ({} scenes) — testkit.autorun not set",
                    MOD_ID, resolvedScenes.size());
        }
    }

    /** Discover + invoke every {@link TestkitVerbHook} once (idempotent). A hook that throws is
     *  logged but must not abort arming — the suite (autorun or on-demand) is independent of any
     *  single verb registration. */
    private static void installVerbHooks() {
        if (verbHooksInstalled) return;
        verbHooksInstalled = true;
        for (TestkitVerbHook hook : ServiceLoader.load(TestkitVerbHook.class)) {
            try {
                hook.registerVerbs();
            } catch (Throwable t) {
                LOG.error("[{}] testkit verb hook {} failed to register",
                        MOD_ID, hook.getClass().getName(), t);
            }
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
                    + "only exists where TestkitCommon.onServerStarted fired (a testkit runtime). "
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
            synchronized (TestkitCommon.class) {
                if (harness != null) return;
                try {
                    harness = new TestkitHarness(server, loader, scenes, new ResultsJsonl(Path.of(OUT_FILE)));
                } catch (Throwable t) {
                    // Hardening (Task-1 review Minor 1): the harness ctor can throw (duplicate scene
                    // name / origin-slot collision — see TestkitHarness). If it does, RELEASE the
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

    public static void onServerTick(MinecraftServer server) {
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
        TestkitHarness h = harness;
        if (h != null) h.tick();
    }
}
