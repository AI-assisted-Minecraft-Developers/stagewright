package net.magicterra.stagewright.client;

import java.util.Locale;
import net.magicterra.stagewright.StageWrightCommon;
import net.magicterra.stagewright.harness.AgentBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.server.WorldStem;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.world.level.storage.LevelStorageSource;

/**
 * Drives a real game client from the title screen into a world, with no external process poking at it.
 *
 * <p>This replaces an out-of-process orchestrator that opened a websocket to the client, walked its widget tree,
 * computed the centre of each button and posted synthetic clicks — several hundred lines whose failure modes were all
 * in the seam between the two processes, and which needed the mod under test to expose an RPC surface just so its own
 * tests could press buttons. Inside the client, "open this world" and "join this server" are one vanilla call each:
 *
 * <ul>
 *   <li>{@code -Dstagewright.client.world=NAME} — open that singleplayer world, creating it first if it does not exist.
 *       World entry starts the integrated server, which runs the tests exactly as a dedicated server does.
 *   <li>{@code -Dstagewright.client.connect=HOST:PORT} — join a dedicated server. The tests run on the SERVER in this
 *       topology; this client's job is to be a real connected player while they do, which is what makes the run a
 *       production-shaped test rather than a single-JVM one.
 * </ul>
 *
 * <p>With neither property set {@link #arm(String)} returns false and the launcher does not drive the client.
 *
 * <p><b>Client-only.</b> This class imports {@code net.minecraft.client.*}. The rule is about LOADING, not presence:
 * each loader module reaches this class from a client-only branch, so a dedicated server never resolves it. Do not
 * reference it from anything a server touches.
 */
public final class ClientDirector {

    private static final String P_WORLD = "stagewright.client.world";

    private static final String P_CONNECT = "stagewright.client.connect";

    private static final String P_EXIT = "stagewright.client.exitWhenDone";

    /**
     * Ticks to let the title screen sit before driving it. The client finishes loading resource packs and mod client
     * setup after the screen first appears, and a world opened into that still-settling state has produced hangs that
     * look exactly like a broken scene.
     */
    private static final int TITLE_SETTLE_TICKS = 40;

    /**
     * How long to wait for the world/connection to come up before giving up. Generous, because the supervising wall
     * clock is the real backstop; this exists so the failure is REPORTED rather than silently eating the whole budget.
     */
    private static final int DRIVE_TIMEOUT_TICKS = 20 * 300;

    /**
     * Ticks between "the run is over" and stopping the client, so the integrated server finishes saving before the JVM
     * goes away.
     */
    private static final int EXIT_DELAY_TICKS = 60;

    private enum Phase {
        WAITING_FOR_TITLE,
        DRIVING,
        IN_WORLD,
        FINISHING
    }

    private static Phase phase = Phase.WAITING_FOR_TITLE;

    private static int settleTicks;

    /** The screen last reported while waiting for the title screen. */
    private static String waitingOn;

    private static int driveTicks;

    private static int exitCountdown = -1;

    /** Client ticks seen, which the supervision agent reads as this JVM's progress. */
    private static long clientTicks;

    private static final int AGENT_TICK_INTERVAL = 20;

    /** Set once the loading window has closed, so the log says so exactly once. */
    private static boolean loadReported;

    private ClientDirector() {}

    /**
     * True when a directive was given and the caller should start feeding {@link #tick()} from its loader's client-tick
     * event.
     */
    public static boolean arm() {
        String directive = directive();
        if (directive == null) {
            return false;
        }
        StageWrightCommon.LOG.info("[{}] client director armed: {}", StageWrightCommon.MOD_ID, directive);
        return true;
    }

    /** Whether this client is driven by a director — readable before {@link #arm} has run. */
    public static boolean directed() {
        return directive() != null;
    }

    private static String directive() {
        String connect = System.getProperty(P_CONNECT);
        if (connect != null && !connect.isBlank()) {
            return "connect=" + connect.trim();
        }
        String world = System.getProperty(P_WORLD);
        if (world != null && !world.isBlank()) {
            return "world=" + world.trim();
        }
        return null;
    }

    /** One client tick. Safe to call before the client has a screen or a level. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        // Before anything can return: a client that is stuck loading still ticks, and one that has
        // stopped ticking is what the supervisor needs to see. Once a second is plenty for that.
        if (joinsServer() && ++clientTicks % AGENT_TICK_INTERVAL == 0) {
            AgentBridge.system().ticks(clientTicks);
        }
        // Nothing before the game has finished loading itself — not driving, not counting, not even
        // reporting. See stillLoading: these ticks arrive from inside the loading window and are
        // about a game that does not exist yet.
        if (phase == Phase.WAITING_FOR_TITLE) {
            if (stillLoading(mc)) {
                return;
            }
            if (!loadReported) {
                loadReported = true;
                StageWrightCommon.LOG.info(
                        "[{}] the initial resource reload is complete — the" + " director takes over from here",
                        StageWrightCommon.MOD_ID);
            }
        }
        switch (phase) {
            case WAITING_FOR_TITLE -> {
                // Name the screen we are stuck behind, since from outside that looks like a hang. Only
                // when it changes: a repeated line would keep a stuck client's log growing, and a
                // supervisor reads a silent log as the stall it is.
                String screen =
                        mc.screen == null ? "no screen" : mc.screen.getClass().getName();
                if (!screen.equals(waitingOn)) {
                    waitingOn = screen;
                    StageWrightCommon.LOG.info(
                            "[{}] waiting for the title screen, currently on {}", StageWrightCommon.MOD_ID, screen);
                }
                // The accessibility onboarding screen stands in front of the title screen on every
                // first launch — which, now that each topology provisions a clean game directory, is
                // EVERY launch. onClose() is precisely what its Continue button does: mark onboarding
                // finished in the options and run the transition the client handed it. Pressing the
                // real control beats constructing a TitleScreen ourselves, which would skip the
                // options write and show the screen again on the next run.
                if (mc.screen instanceof AccessibilityOnboardingScreen onboarding) {
                    StageWrightCommon.LOG.info(
                            "[{}] dismissing the accessibility onboarding screen", StageWrightCommon.MOD_ID);
                    onboarding.onClose();
                    return;
                }
                if (mc.screen instanceof TitleScreen && ++settleTicks >= TITLE_SETTLE_TICKS) {
                    enter(Phase.DRIVING);
                    drive(mc);
                }
            }
            case DRIVING -> {
                if (mc.level != null) {
                    StageWrightCommon.LOG.info(
                            "[{}] client is in world after {} ticks", StageWrightCommon.MOD_ID, driveTicks);
                    enter(Phase.IN_WORLD);
                } else if (++driveTicks > DRIVE_TIMEOUT_TICKS) {
                    if (StageWrightCommon.LOG.isErrorEnabled()) {
                        StageWrightCommon.LOG.error(
                                "[{}] client never reached a world within {} ticks ({})",
                                StageWrightCommon.MOD_ID,
                                DRIVE_TIMEOUT_TICKS,
                                directive());
                    }
                    finish();
                } else if (shouldRedial(mc)) {
                    // Keep dialling. The E2E pair is two processes with no handshake between them,
                    // and a single attempt would make the whole topology depend on which one Gradle
                    // happened to start first: connect before the server is listening and the client
                    // lands on the disconnected screen and sits there while the server waits for a
                    // player that will never arrive. Retrying makes start order stop mattering.
                    StageWrightCommon.LOG.info(
                            "[{}] not connected yet ({} ticks) — dialling again", StageWrightCommon.MOD_ID, driveTicks);
                    drive(mc);
                }
            }
            case IN_WORLD -> {
                // The server ends the run by halting itself, integrated or dedicated, which drops this
                // client out of its world: losing the level is the completion signal in both topologies.
                if (mc.level == null) {
                    StageWrightCommon.LOG.info(
                            "[{}] the server ended the run; closing client", StageWrightCommon.MOD_ID);
                    finish();
                }
            }
            case FINISHING -> {
                if (exitCountdown > 0 && --exitCountdown == 0 && exitWhenDone()) {
                    mc.stop();
                }
            }
            default -> throw new IllegalStateException("unhandled director phase " + phase);
        }
    }

    private static void finish() {
        enter(Phase.FINISHING);
        exitCountdown = EXIT_DELAY_TICKS;
        // The client is about to stop itself; if a mod's thread keeps the JVM up after that, the agent
        // names the thread and ends the JVM instead of leaving the build to wait out its timeout.
        if (joinsServer() && exitWhenDone()) {
            AgentBridge.system().done();
        }
    }

    private static void enter(Phase next) {
        phase = next;
        if (joinsServer()) {
            AgentBridge.system()
                    .phase("client-" + next.name().toLowerCase(Locale.ROOT).replace('_', '-'));
        }
    }

    /**
     * Whether this client is only a player on someone else's server.
     *
     * <p>Only then does it report to the supervision agent: a client that opens a world runs the suite in this JVM,
     * where the harness already reports the server's ticks and the end of the run, and a second voice would overwrite
     * it.
     */
    private static boolean joinsServer() {
        String connect = System.getProperty(P_CONNECT);
        return connect != null && !connect.isBlank();
    }

    /**
     * True while the client is still assembling itself: the initial resource reload, which is also where each loader
     * finishes mod loading and loads its CLIENT-side mod configs.
     *
     * <p><b>A client ticks throughout that window.</b> {@code Minecraft.run} calls {@code runTick} from the first frame
     * and {@code runTick} calls {@code tick} on the timer, so client-tick events fire while the loading overlay is
     * still up and mods are still being constructed. Every number this director keeps is about the game AFTER that —
     * {@link #TITLE_SETTLE_TICKS} is a settling allowance for a title screen, not a loading budget — so counting from
     * tick one measured how long a 262-mod pack takes to load and spent the settle window inside it.
     *
     * <p>{@code isGameLoadFinished} is vanilla's own answer, flipped in {@code Minecraft.onResourceLoadFinished}
     * immediately before the initial screens are built; the overlay check covers the fade that follows it and any later
     * reload, because a title screen with a reload running over it is not a title screen worth driving.
     *
     * <p>This is a guard on what the DIRECTOR does, and deliberately not a claim to fix what other mods do in that
     * window. A mod whose own client-tick handler reads a config value before its config is loaded crashes on the first
     * tick of any launcher, with no StageWright frame on the stack; nothing here can reach that, and the CLI reports it
     * as the crash it is.
     */
    private static boolean stillLoading(Minecraft mc) {
        return !mc.isGameLoadFinished() || mc.getOverlay() != null;
    }

    /**
     * True when the connect attempt has visibly come to rest without a world: the client is showing the disconnected
     * screen, or fell back to the title/server-list screen.
     *
     * <p>Screen-based rather than timer-based on purpose. A retry fired on a plain interval would cut across an attempt
     * still in flight — the handshake for a server that is up but still loading chunks takes seconds — and each
     * cancellation would restart the clock, so a slow server would never be joined at all.
     */
    private static boolean shouldRedial(Minecraft mc) {
        String connect = System.getProperty(P_CONNECT);
        if (connect == null || connect.isBlank()) {
            return false; // singleplayer: world creation has no dial to retry
        }
        return mc.screen instanceof DisconnectedScreen
                || mc.screen instanceof TitleScreen
                || mc.screen instanceof JoinMultiplayerScreen;
    }

    /** Independent of the launcher: bind this client's API once it is in a world. */
    public static void onWorldReady() {
        Minecraft mc = Minecraft.getInstance();
        var connection = mc.getConnection();
        if (mc.level == null
                || mc.player == null
                || connection == null
                || !connection.getConnection().isConnected()) {
            return;
        }
        StageWrightCommon.onClientWorldReady();
    }

    private static boolean exitWhenDone() {
        return !"false".equalsIgnoreCase(System.getProperty(P_EXIT, "true"));
    }

    private static void drive(Minecraft mc) {
        String connect = System.getProperty(P_CONNECT);
        if (connect != null && !connect.isBlank()) {
            String address = connect.trim();
            StageWrightCommon.LOG.info("[{}] connecting to {}", StageWrightCommon.MOD_ID, address);
            ConnectScreen.startConnecting(
                    mc.screen,
                    mc,
                    ServerAddress.parseString(address),
                    new ServerData("StageWright", address, ServerData.Type.OTHER),
                    false,
                    null);
            return;
        }

        String world = System.getProperty(P_WORLD, "").trim();
        WorldOpenFlows flows = mc.createWorldOpenFlows();
        if (mc.getLevelSource().levelExists(world)) {
            StageWrightCommon.LOG.info("[{}] opening existing world '{}'", StageWrightCommon.MOD_ID, world);
            openExisting(mc, flows, world);
            return;
        }

        WorldCreation creation = WorldCreation.of(world, mc.gameDirectory.toPath());
        if (StageWrightCommon.LOG.isInfoEnabled()) {
            StageWrightCommon.LOG.info(
                    "[{}] creating world '{}': {}, {}, seed {}",
                    StageWrightCommon.MOD_ID,
                    world,
                    creation.settings().gameType().getName(),
                    creation.settings().difficulty().getKey(),
                    creation.options().seed());
        }
        flows.createFreshLevel(world, creation.settings(), creation.options(), creation.dimensions(), mc.screen);
    }

    /**
     * Load a save without the questions {@code WorldOpenFlows.openWorld} asks first: back it up before loading one from
     * another version, one with experimental settings (which any save made with worldgen mods loaded has), or on low
     * disk space. Nobody is there to answer them, and the save is the run's own copy, put there afresh for the scene. A
     * save that fails to load is logged; the run then ends as stalled.
     */
    private static void openExisting(Minecraft mc, WorldOpenFlows flows, String world) {
        LevelStorageSource.LevelStorageAccess access = null;
        try {
            access = mc.getLevelSource().validateAndCreateAccess(world);
            PackRepository packs = ServerPacksSource.createPackRepository(access);
            WorldStem stem = flows.loadWorldStem(access.getDataTag(), false, packs);
            mc.doWorldLoad(access, packs, stem, false);
        } catch (Exception e) {
            StageWrightCommon.LOG.error("[{}] opening world '{}' failed", StageWrightCommon.MOD_ID, world, e);
            if (access != null) {
                access.safeClose();
            }
        }
    }
}
