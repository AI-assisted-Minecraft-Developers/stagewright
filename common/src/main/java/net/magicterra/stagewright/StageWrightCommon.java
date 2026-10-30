package net.magicterra.stagewright;

import com.mojang.logging.LogUtils;
import net.magicterra.stagewright.driver.DriverRuntime;
import net.magicterra.stagewright.harness.AgentBridge;
import net.magicterra.stagewright.jupiter.JupiterRun;
import net.magicterra.stagewright.jupiter.LeasedPlots;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

/** Common core of stagewright. Loader entries forward server lifecycle + tick here. */
public final class StageWrightCommon {
    public static final String MOD_ID = "mc_testkit";
    public static final Logger LOG = LogUtils.getLogger();

    /** Set when a dedicated server should wait for a real client to join before its tests start. */
    private static final String AWAIT_PLAYER = "stagewright.awaitPlayer";

    private static MinecraftServer armedServer;

    // ---- Startup tick-debt settle barrier ----
    // A freshly-STARTED MinecraftServer carries accumulated tick DEBT and runs unthrottled
    // catch-up ticks (~3 ms/tick instead of the steady 50 ms cadence) until it is caught up.
    // Starting the tests during that burst makes tick-budgeted waits fragile: entity promotion
    // (and similar wall-clock-bound work tests wait for) costs 2-2.3x more ticks for the same real
    // delay in the burst regime. So the tests start once the cadence has stabilized to the real
    // ~50 ms rhythm — 10 consecutive server ticks spaced >=40 ms apart — or after 1200 ticks with
    // no settle, so a pathological host can never hang the run. Server thread only.
    private static final long CADENCE_NANOS = 40_000_000L; // 40 ms — floor of a real ~50 ms tick
    private static final int CADENCE_STREAK = 10; // consecutive on-cadence ticks to settle
    private static final int SETTLE_SAFETY_TICKS = 1200; // never hang: start anyway after this many
    private static boolean settled;
    private static int settleTickCount;
    private static int consecutiveCadenceTicks;
    private static long lastTickNanos;

    /** Server ticks seen, which the supervision agent reads as this JVM's progress. */
    private static long serverTicks;

    private static final int AGENT_TICK_INTERVAL = 20;
    private static boolean playersOpped;

    /** Why the client's verbs last failed to register, so a tick-by-tick retry logs each reason once. */
    private static volatile String clientVerbFailure;

    private StageWrightCommon() {}

    /**
     * A joining client has its own API and never receives a local SERVER_STARTED event. Called every client tick, so a
     * failure is logged and retried rather than thrown into the game's tick.
     */
    public static void onClientWorldReady() {
        try {
            DriverRuntime.registerVerbs();
            clientVerbFailure = null;
        } catch (RuntimeException e) {
            String why = e.toString();
            if (why.equals(clientVerbFailure)) {
                return;
            }
            clientVerbFailure = why;
            LOG.error("[{}] the mc.test.* verbs are not registered on this client; retrying each tick", MOD_ID, e);
        }
    }

    /**
     * The server reached STARTED: register the {@code mc.test.*} verbs. The tests themselves start from
     * {@link #onServerTick} once the tick cadence has settled. Server thread; called by every loader entry after
     * worlddriver has wired its route sink at SERVER_STARTING.
     */
    public static synchronized void onServerStarted(MinecraftServer server) {
        if (armedServer == server) {
            LOG.warn("[{}] already armed; ignoring duplicate onServerStarted", MOD_ID);
            return;
        }
        DriverRuntime.registerVerbs();
        armedServer = server;
        // Where a supervisor learns the game got this far, whatever happens next.
        AgentBridge.system().phase("armed");
        if (JupiterRun.selected()) {
            LOG.info("[{}] running the tests the test engine selected", MOD_ID);
        }
    }

    /** Called on the server thread while the world is still available for cleanup. */
    public static synchronized void onServerStopping(MinecraftServer server) {
        if (server != armedServer) {
            return;
        }
        try {
            JupiterRun.stop();
        } finally {
            armedServer = null;
            settled = false;
            settleTickCount = 0;
            consecutiveCadenceTicks = 0;
            lastTickNanos = 0;
            playersOpped = false;
        }
    }

    /** Server thread, as an entity joins a server level; every loader entry forwards it. */
    public static void onEntityJoin(Entity entity, Level level) {
        LeasedPlots.onEntityJoin(entity, level);
    }

    /** The server the verbs were registered on, while it runs. */
    public static synchronized MinecraftServer armedServer() {
        return armedServer;
    }

    /**
     * Server thread, every tick. A test server never empties its levels' idle timers — nobody may be online for the
     * whole run — so the levels keep ticking entities.
     */
    public static void onServerTick(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            level.resetEmptyTime();
        }
        if (++serverTicks % AGENT_TICK_INTERVAL == 0) {
            AgentBridge.system().ticks(serverTicks);
        }
        if (!settled && !settle()) {
            return;
        }
        if (Boolean.getBoolean(AWAIT_PLAYER) && !playersOpped && server.getPlayerCount() > 0) {
            playersOpped = true;
            opTestPlayers(server);
        }
        JupiterRun.tick(server);
    }

    /** One tick of the settle barrier above; true once the cadence has settled. */
    private static boolean settle() {
        long now = System.nanoTime();
        settleTickCount++;
        if (lastTickNanos != 0L && (now - lastTickNanos) >= CADENCE_NANOS) {
            if (++consecutiveCadenceTicks >= CADENCE_STREAK) {
                settled = true;
                LOG.info(
                        "[{}] tick cadence settled after {} server ticks (tick debt drained)", MOD_ID, settleTickCount);
            }
        } else if (lastTickNanos != 0L) {
            consecutiveCadenceTicks = 0;
        }
        lastTickNanos = now;
        if (!settled && settleTickCount >= SETTLE_SAFETY_TICKS) {
            settled = true;
            LOG.warn(
                    "[{}] tick cadence did NOT settle within {} server ticks; starting anyway (tick debt may still"
                            + " be draining, so waits may run in the burst regime)",
                    MOD_ID,
                    SETTLE_SAFETY_TICKS);
        }
        return settled;
    }

    /** The real client joining a test server may need to run commands, as a test drives it. */
    private static void opTestPlayers(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (server.getPlayerList().isOp(player.getGameProfile())) {
                continue;
            }
            server.getPlayerList().op(player.getGameProfile());
            LOG.info(
                    "[{}] op'd the test client '{}'",
                    MOD_ID,
                    player.getGameProfile().getName());
        }
    }
}
