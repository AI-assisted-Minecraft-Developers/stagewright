package net.magicterra.stagewright.client;

import java.util.function.BooleanSupplier;
import net.magicterra.stagewright.StageWrightCommon;
import net.magicterra.stagewright.test.DependsOn;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * A test leaving the client's world for the title screen and opening another save, in this JVM, as a player does: for
 * what one client keeps from one world to the next. The tests' run lives on past the integrated server it started on.
 * Called from a test's thread, never the render thread, which does the work.
 *
 * <p><b>Client-only</b>, like {@link ClientDirector}: only an integrated server's game loads it.
 */
public final class WorldSwitch {

    /** How long leaving or opening a world may take, a new world's spawn generation included. */
    private static final long TIMEOUT_MS = 300_000;

    private static final long POLL_MS = 50;

    /** While a test has the client between worlds, losing the world does not end the run. */
    private static volatile boolean active;

    private static volatile String current;

    private WorldSwitch() {}

    static boolean active() {
        return active;
    }

    /** The folder of the save the client has open, or last had. */
    public static String current() {
        String c = current;
        return c != null ? c : ClientDirector.firstWorld();
    }

    /** Save and leave the world for the title screen; returns once its integrated server has stopped. */
    public static void leave() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread()) {
            throw new IllegalStateException("leaving a world waits for the render thread, so not from it");
        }
        active = true;
        mc.execute(() -> {
            if (mc.level != null) {
                // What the pause screen's quit button does.
                mc.level.disconnect();
                mc.disconnect(new GenericMessageScreen(Component.translatable("menu.savingLevel")));
                mc.setScreen(new TitleScreen());
            }
        });
        await(
                () -> mc.level == null && mc.getSingleplayerServer() == null && mc.screen instanceof TitleScreen,
                "leaving world " + current());
        StageWrightCommon.LOG.info("[{}] a test left world '{}'", StageWrightCommon.MOD_ID, current());
    }

    /**
     * Open the save {@code name}, the run's first world for {@link DependsOn#FIRST_WORLD}, creating it as the scene
     * declares its world when there is none; returns its server once the player is in it and StageWright armed it.
     */
    public static MinecraftServer open(String name) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread()) {
            throw new IllegalStateException("opening a world waits for the render thread, so not from it");
        }
        String save = DependsOn.FIRST_WORLD.equals(name) ? ClientDirector.firstWorld() : name;
        // Still active from leave(), which every caller goes through first.
        mc.execute(() -> {
            if (mc.level == null) {
                ClientDirector.openOrCreate(mc, save);
            }
        });
        await(
                () -> {
                    IntegratedServer s = mc.getSingleplayerServer();
                    return s != null
                            && mc.level != null
                            && mc.player != null
                            && StageWrightCommon.armedServer() == s
                            && !(mc.screen instanceof LevelLoadingScreen)
                            && !(mc.screen instanceof ReceivingLevelScreen);
                },
                "opening world " + save);
        current = save;
        active = false;
        StageWrightCommon.LOG.info("[{}] a test opened world '{}'", StageWrightCommon.MOD_ID, save);
        return mc.getSingleplayerServer();
    }

    /** The run ended with the client between worlds: the director ends the client, as after any run. */
    public static void end() {
        active = false;
    }

    private static void await(BooleanSupplier done, String what) {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (!done.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException(what + " did not finish in " + TIMEOUT_MS / 1000 + " s");
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while " + what, e);
            }
        }
    }
}
