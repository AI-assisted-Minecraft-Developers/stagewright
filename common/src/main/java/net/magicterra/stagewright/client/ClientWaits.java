package net.magicterra.stagewright.client;

import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;

/**
 * What a test's thread waits for while the render thread moves the client between worlds or servers:
 * {@link WorldSwitch} and the director's session. Client-only, like both.
 */
final class ClientWaits {

    private static final long POLL_MS = 50;

    private ClientWaits() {}

    /** The client is in a world with its player, and no loading screen is over it. */
    static boolean inWorld(Minecraft mc) {
        return mc.level != null
                && mc.player != null
                && !(mc.screen instanceof LevelLoadingScreen)
                && !(mc.screen instanceof ReceivingLevelScreen);
    }

    /**
     * Poll {@code done} until it holds; throws after {@code timeoutMs}, naming {@code what}. Never the render thread.
     */
    static void until(BooleanSupplier done, long timeoutMs, String what) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!done.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException(what + " did not finish in " + timeoutMs / 1000 + " s");
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
