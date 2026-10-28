package net.magicterra.stagewright.test;

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import net.magicterra.stagewright.capability.Capabilities;
import net.magicterra.stagewright.capability.CapabilityProvider;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameRules;

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
     * server thread would wait for itself, so that is refused at once. Called on the server thread, as from an
     * {@link Await} condition, it runs {@code work} there and then.
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

    /** Run {@code measurement}'s phases in order; each phase's median window by name, in phase order. */
    Map<String, Perf.Window> perf(Perf measurement);

    /** What tests keep across the scene's restarts; see {@link DependsOn#afterRestart()}. */
    Store store();

    /**
     * Set a game rule for the rest of this test; it is put back when the test ends. Needs
     * {@code @ResourceLock(Locks.WORLD_RULES)}, as every override here needs the lock {@link Locks} names for it.
     */
    void override(GameRules.Key<GameRules.BooleanValue> rule, boolean value);

    /** {@link #override(GameRules.Key, boolean)} for an integer rule. */
    void override(GameRules.Key<GameRules.IntegerValue> rule, int value);

    /** Set the overworld's time of day for the rest of this test. Needs {@link Locks#WORLD_CLOCK}. */
    void overrideTime(long dayTime);

    /** Set the weather for the rest of this test. Needs {@link Locks#WEATHER}. */
    void overrideWeather(boolean raining, boolean thundering);

    /** Set the server's tick rate for the rest of this test. Needs {@link Locks#TICK_RATE}. */
    void overrideTickRate(float ticksPerSecond);

    /**
     * Run {@code undo} on the server thread when the calling test ends, passed or not; the last registered runs
     * first. For facets that change shared state, such as a player's inventory. Only from a test's own thread.
     */
    void afterTest(Consumer<MinecraftServer> undo);

    /**
     * A capability some mod in this run contributed (see {@link CapabilityProvider}): its facet, the same object for
     * the rest of the test. When this game does not offer it the test is aborted, not failed, with a reason naming
     * what is missing and what is offered instead.
     */
    Object capability(String name);

    /** {@link #capability(String)} with the facet's type checked, for a Java test that owns both sides. */
    default <T> T capability(String name, Class<T> type) {
        Object facet = capability(name);
        if (!type.isInstance(facet)) {
            throw new IllegalStateException("the '" + name + "' capability is a " + facet.getClass().getName()
                    + ", not a " + type.getName() + " — its provider and this test disagree about what that name means");
        }
        return type.cast(facet);
    }

    /** What this game offers: {@link Capabilities#has}, {@link Capabilities#available}, {@link Capabilities#registered}. */
    default Capabilities capabilities() {
        return Capabilities.in(this);
    }
}
