package net.magicterra.stagewright.test;

import java.util.function.Consumer;
import java.util.function.Function;

import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.minecraft.server.MinecraftServer;

/**
 * The game instance a test runs in, injected as a test method or constructor parameter. Tests run on a test
 * thread, not the server thread, so every method here may block: a test reads as the sequence of things it does.
 *
 * <p>Game operations are WorldDriver's: {@link #api} hands out a family's caller, for example
 * {@code game.api(WorldBlockingApi::of).block(...)}, which answers on this thread.
 */
public interface Game {

    /** WorldDriver's registry: every method any transport can call. */
    ApiRegistry registry();

    /** A caller built over {@link #registry}, typically a generated {@code XxxBlockingApi::of}. */
    default <T> T api(Function<ApiRegistry, T> caller) {
        return caller.apply(registry());
    }

    /**
     * Run {@code work} on the server thread and answer its result here, so a group of reads and writes happens
     * between two ticks with nothing else interleaved. Must not itself call a blocking {@code Game} method: the
     * server thread would wait for itself, so that is refused at once.
     */
    <T> T onServer(Function<MinecraftServer, T> work);

    /** {@link #onServer(Function)} for work with no result. */
    default void runOnServer(Consumer<MinecraftServer> work) {
        onServer(server -> {
            work.accept(server);
            return null;
        });
    }

    /** Let {@code n} server ticks pass. */
    void ticks(int n);

    /** Wait for a condition on the server; see {@link Await}. */
    Await await();
}
