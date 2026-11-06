package net.magicterra.stagewright.test;

import java.util.function.Function;
import net.magicterra.worlddriver.api.registry.ApiRegistry;

/**
 * The real client of the game a test runs in, injected as a test method parameter: its screen, its input, what it
 * renders and hears. Reached through WorldDriver's client methods, called exactly as on {@link Game}:
 * {@code client.api(ScreenBlockingApi::of).info(...)}. On an integrated server they run in this JVM; where a topology
 * joins a client to a dedicated server they travel to that client over its WorldDriver connection, and the test reads
 * the same either way. A game without a client refuses the parameter, so a test taking it should carry
 * {@code @Requires(CLIENT)}.
 */
public interface Client {

    /** A registry whose methods the client answers. */
    ApiRegistry registry();

    /** A caller built over {@link #registry}, typically a generated {@code XxxBlockingApi::of}. */
    default <T> T api(Function<ApiRegistry, T> caller) {
        return caller.apply(registry());
    }

    /**
     * Disconnect the client from the game's server as a player who presses Disconnect does, and return once the server
     * has taken its player off the list: every loader's logout event fired and the player saved. The client stays up,
     * on the server list screen, and its methods still answer; until {@link #reconnect} there is no {@link Player} in
     * the game. A test that ends with the client disconnected has it reconnected before the next test takes it.
     *
     * <p>Only where the client joined a dedicated server. A client that opened a world hosts the game's server, and
     * leaving would stop it.
     *
     * @throws IllegalStateException when the client hosts the game, or is not in it
     */
    Client disconnect();

    /**
     * Join the game's server again after {@link #disconnect}, under the same account, as a player reconnecting does:
     * the same player, by UUID, from its save, with the login event fired and whatever the server sends a joining
     * player sent. Returns once the client is in the world, past the loading screen, and the server has the player.
     *
     * @throws IllegalStateException when the client is in the game, or did not get back in
     */
    Client reconnect();
}
