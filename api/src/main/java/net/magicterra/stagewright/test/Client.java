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
}
