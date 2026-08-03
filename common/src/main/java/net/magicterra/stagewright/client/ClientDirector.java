package net.magicterra.stagewright.client;

import net.magicterra.stagewright.StageWrightCommon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/**
 * Drives a real game client from the title screen into a world, with no external process poking at
 * it.
 *
 * <p>This replaces an out-of-process orchestrator that opened a websocket to the client, walked its
 * widget tree, computed the centre of each button and posted synthetic clicks — several hundred
 * lines whose failure modes were all in the seam between the two processes, and which needed the
 * mod under test to expose an RPC surface just so its own tests could press buttons. Inside the
 * client, "open this world" and "join this server" are one vanilla call each:
 *
 * <ul>
 *   <li>{@code -Dstagewright.client.world=NAME} — open that singleplayer world, creating it first if
 *       it does not exist. World entry starts the integrated server, which arms the harness exactly
 *       as a dedicated server does, so the SAME scenes run under the client topology.</li>
 *   <li>{@code -Dstagewright.client.connect=HOST:PORT} — join a dedicated server. The suite runs on
 *       the SERVER in this topology; this client's job is to be a real connected player while it
 *       does, which is what makes the run a production-shaped test rather than a single-JVM one.</li>
 * </ul>
 *
 * <p>With neither property set {@link #arm()} returns false, no tick hook is registered, and an
 * ordinary {@code runClient} is untouched.
 *
 * <p><b>Client-only.</b> This is the one class in {@code :common} that imports
 * {@code net.minecraft.client.*} — everything else there is careful not to, so that the shared
 * module stays loadable on a dedicated server. The rule is about LOADING, not presence: each loader
 * module reaches this class from a client-only branch, so a dedicated server never resolves it. Do
 * not reference it from anything a server touches.
 */
public final class ClientDirector {

    private static final String P_WORLD = "stagewright.client.world";
    private static final String P_CONNECT = "stagewright.client.connect";
    private static final String P_EXIT = "stagewright.client.exitWhenDone";

    /** Ticks to let the title screen sit before driving it. The client finishes loading resource
     *  packs and mod client setup after the screen first appears, and a world opened into that
     *  still-settling state has produced hangs that look exactly like a broken scene. */
    private static final int TITLE_SETTLE_TICKS = 40;

    /** How long to wait for the world/connection to come up before giving up. Generous, because the
     *  supervising wall clock is the real backstop; this exists so the failure is REPORTED rather
     *  than silently eating the whole budget. */
    private static final int DRIVE_TIMEOUT_TICKS = 20 * 300;

    /** Ticks between "the suite is over" and killing the client, so the integrated server finishes
     *  saving and the results file is flushed before the JVM goes away. */
    private static final int EXIT_DELAY_TICKS = 60;

    /** Fixed seed so the client topology generates the same world every run. Scene arenas are
     *  force-loaded at a fixed origin far from spawn, so terrain barely matters — but "barely" is
     *  not "not at all", and a random seed would make a one-off failure unreproducible. */
    private static final long WORLD_SEED = 5471L;

    private enum Phase { WAITING_FOR_TITLE, DRIVING, IN_WORLD, FINISHING }

    private static Phase phase = Phase.WAITING_FOR_TITLE;
    private static int settleTicks;
    private static int waitingTicks;
    private static int driveTicks;
    private static int exitCountdown = -1;

    private ClientDirector() {}

    /**
     * True when a directive was given and the caller should start feeding {@link #tick()} from its
     * loader's client-tick event.
     */
    public static boolean arm() {
        String directive = directive();
        if (directive == null) return false;
        StageWrightCommon.LOG.info("[{}] client director armed: {}", StageWrightCommon.MOD_ID, directive);
        return true;
    }

    private static String directive() {
        String connect = System.getProperty(P_CONNECT);
        if (connect != null && !connect.isBlank()) return "connect=" + connect.trim();
        String world = System.getProperty(P_WORLD);
        if (world != null && !world.isBlank()) return "world=" + world.trim();
        return null;
    }

    /** One client tick. Safe to call before the client has a screen or a level. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        switch (phase) {
            case WAITING_FOR_TITLE -> {
                // Report what we ARE looking at every few seconds. A director that waits forever for
                // a screen the client never shows is indistinguishable from a hung client from the
                // outside, and the log is the only place that distinction can be made cheaply. This
                // line is what identified the onboarding screen below within one run.
                if (++waitingTicks % 100 == 0) {
                    StageWrightCommon.LOG.info("[{}] waiting for the title screen, currently on {}",
                            StageWrightCommon.MOD_ID,
                            mc.screen == null ? "no screen" : mc.screen.getClass().getName());
                }
                // The accessibility onboarding screen stands in front of the title screen on every
                // first launch — which, now that each topology provisions a clean game directory, is
                // EVERY launch. onClose() is precisely what its Continue button does: mark onboarding
                // finished in the options and run the transition the client handed it. Pressing the
                // real control beats constructing a TitleScreen ourselves, which would skip the
                // options write and show the screen again on the next run.
                if (mc.screen instanceof AccessibilityOnboardingScreen onboarding) {
                    StageWrightCommon.LOG.info("[{}] dismissing the accessibility onboarding screen",
                            StageWrightCommon.MOD_ID);
                    onboarding.onClose();
                    return;
                }
                if (mc.screen instanceof TitleScreen && ++settleTicks >= TITLE_SETTLE_TICKS) {
                    phase = Phase.DRIVING;
                    drive(mc);
                }
            }
            case DRIVING -> {
                if (mc.level != null) {
                    StageWrightCommon.LOG.info("[{}] client is in world after {} ticks",
                            StageWrightCommon.MOD_ID, driveTicks);
                    phase = Phase.IN_WORLD;
                } else if (++driveTicks > DRIVE_TIMEOUT_TICKS) {
                    StageWrightCommon.LOG.error("[{}] client never reached a world within {} ticks ({})",
                            StageWrightCommon.MOD_ID, DRIVE_TIMEOUT_TICKS, directive());
                    finish();
                } else if (shouldRedial(mc)) {
                    // Keep dialling. The E2E pair is two processes with no handshake between them,
                    // and a single attempt would make the whole topology depend on which one Gradle
                    // happened to start first: connect before the server is listening and the client
                    // lands on the disconnected screen and sits there while the server waits for a
                    // player that will never arrive. Retrying makes start order stop mattering.
                    StageWrightCommon.LOG.info("[{}] not connected yet ({} ticks) — dialling again",
                            StageWrightCommon.MOD_ID, driveTicks);
                    drive(mc);
                }
            }
            case IN_WORLD -> {
                // Two different endings, one per topology. Integrated: the suite runs in THIS JVM,
                // so the harness's own finished flag is authoritative. Connected: the suite runs on
                // the dedicated server, which halts itself when done and drops us — so losing the
                // level IS the completion signal, and the results file lives on that side.
                if (StageWrightCommon.suiteFinished() || mc.level == null) {
                    StageWrightCommon.LOG.info("[{}] suite over (finished={}, connected={}) — closing client",
                            StageWrightCommon.MOD_ID, StageWrightCommon.suiteFinished(), mc.level != null);
                    finish();
                }
            }
            case FINISHING -> {
                if (exitCountdown > 0 && --exitCountdown == 0 && exitWhenDone()) {
                    mc.stop();
                }
            }
        }
    }

    private static void finish() {
        phase = Phase.FINISHING;
        exitCountdown = EXIT_DELAY_TICKS;
    }

    /**
     * True when the connect attempt has visibly come to rest without a world: the client is showing
     * the disconnected screen, or fell back to the title/server-list screen.
     *
     * <p>Screen-based rather than timer-based on purpose. A retry fired on a plain interval would
     * cut across an attempt still in flight — the handshake for a server that is up but still
     * loading chunks takes seconds — and each cancellation would restart the clock, so a slow server
     * would never be joined at all.
     */
    private static boolean shouldRedial(Minecraft mc) {
        String connect = System.getProperty(P_CONNECT);
        if (connect == null || connect.isBlank()) {
            return false;                       // singleplayer: world creation has no dial to retry
        }
        return mc.screen instanceof DisconnectedScreen
                || mc.screen instanceof TitleScreen
                || mc.screen instanceof JoinMultiplayerScreen;
    }

    private static boolean exitWhenDone() {
        return !"false".equalsIgnoreCase(System.getProperty(P_EXIT, "true"));
    }

    private static void drive(Minecraft mc) {
        String connect = System.getProperty(P_CONNECT);
        if (connect != null && !connect.isBlank()) {
            String address = connect.trim();
            StageWrightCommon.LOG.info("[{}] connecting to {}", StageWrightCommon.MOD_ID, address);
            ConnectScreen.startConnecting(mc.screen, mc, ServerAddress.parseString(address),
                    new ServerData("StageWright", address, ServerData.Type.OTHER), false, null);
            return;
        }

        String world = System.getProperty(P_WORLD, "").trim();
        WorldOpenFlows flows = mc.createWorldOpenFlows();
        if (mc.getLevelSource().levelExists(world)) {
            StageWrightCommon.LOG.info("[{}] opening existing world '{}'", StageWrightCommon.MOD_ID, world);
            flows.openWorld(world, () -> StageWrightCommon.LOG.error(
                    "[{}] opening world '{}' failed", StageWrightCommon.MOD_ID, world));
            return;
        }

        StageWrightCommon.LOG.info("[{}] creating world '{}'", StageWrightCommon.MOD_ID, world);
        // Survival, easy, cheats on — the defaults a dedicated server boots with
        // (gamemode=survival, difficulty=easy, spawn-monsters=true), and deliberately not the
        // quieter creative/peaceful pair a sandbox would pick.
        //
        // The whole reason to run scenes on an integrated server as well as a dedicated one is that
        // the two must agree; a world that differs in gamemode or difficulty is not a second
        // topology, it is a second product. Creative/peaceful silently disabled every scene about
        // being attacked — hostile mobs never spawn, and a creative player is immune to the damage
        // half of them assert on, so they did not report "the world is wrong", they reported
        // timeouts and "mock player refused mobAttack damage" and looked like bot bugs.
        LevelSettings settings = new LevelSettings(world, GameType.SURVIVAL, false, Difficulty.EASY,
                true, new GameRules(), WorldDataConfiguration.DEFAULT);
        flows.createFreshLevel(world, settings, new WorldOptions(WORLD_SEED, true, false),
                WorldPresets::createNormalWorldDimensions, mc.screen);
    }
}
