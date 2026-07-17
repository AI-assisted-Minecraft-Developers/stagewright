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
        onDemandRequested = true;
        List<Scene> scenes = resolvedScenes;
        String loader = armedLoader;
        // Build on the server thread. Re-check under the monitor there so a race with autorun (which
        // sets `harness` on the server thread) cannot double-build.
        server.execute(() -> {
            synchronized (TestkitCommon.class) {
                if (harness == null) {
                    harness = new TestkitHarness(server, loader, scenes, new ResultsJsonl(Path.of(OUT_FILE)));
                }
            }
        });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accepted", true);
        result.put("scenes", scenes.size());
        return result;
    }

    public static void onServerTick(MinecraftServer server) {
        TestkitHarness h = harness;
        if (h != null) h.tick();
    }
}
