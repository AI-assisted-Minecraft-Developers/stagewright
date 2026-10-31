package net.magicterra.stagewright.test;

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import net.magicterra.stagewright.capability.Capabilities;
import net.magicterra.stagewright.capability.CapabilityProvider;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;

/**
 * The game instance a test runs in, injected as a test method or constructor parameter. Tests run on a test thread, not
 * the server thread, so every method here may block: a test reads as the sequence of things it does.
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
     * Run {@code work} on the server thread and answer its result here, so a group of reads and writes happens between
     * two ticks with nothing else interleaved. Must not itself call a blocking {@code Game} method: the server thread
     * would wait for itself, so that is refused at once. Called on the server thread, as from an {@link Await}
     * condition, it runs {@code work} there and then. The work runs as part of the calling test: {@link #check} counts
     * for it there, while an override or {@link #afterTest}, which the test must undo, is refused.
     */
    <T> T onServer(Function<MinecraftServer, T> work);

    /**
     * Whether {@code condition} holds, asked in one server task as {@link #onServer(Function)} asks: for
     * {@code assertTrue(game.onServerHolds(s -> …))}, which does not compile with {@code onServer}, since JUnit's
     * {@code BooleanSupplier} overload leaves its result type unknown.
     */
    default boolean onServerHolds(Predicate<MinecraftServer> condition) {
        return onServer(condition::test);
    }

    /** {@link #onServer(Function)} for work with no result. */
    default void runOnServer(Consumer<MinecraftServer> work) {
        onServer(server -> {
            work.accept(server);
            return null;
        });
    }

    /** Let {@code n} server ticks pass. */
    void ticks(int n);

    /**
     * Where {@code pos} in {@code from} corresponds to in {@code to}, as a portal maps it: x and z scaled by the two
     * dimensions' coordinate scales and kept inside the world border, y kept. The Nether's scale is 8, so overworld
     * {@code (800, 64, -80)} is Nether {@code (100, 64, -10)}. Refuses a dimension this game does not have.
     */
    BlockPos translate(BlockPos pos, ResourceKey<Level> from, ResourceKey<Level> to);

    /** Wait for a condition on the server; see {@link Await}. */
    Await await();

    /**
     * Spawn a {@code type} mob standing in {@code feet} in {@code dimension}, as a spawn command would, and hand it to
     * WorldDriver to drive; it is removed when the calling test ends. Refuses a mob WorldDriver cannot drive. Only from
     * the test's own thread.
     */
    Npc npc(EntityType<? extends Mob> type, ResourceKey<Level> dimension, BlockPos feet);

    /** {@link #npc(EntityType, ResourceKey, BlockPos)} in the overworld. */
    default Npc npc(EntityType<? extends Mob> type, BlockPos feet) {
        return npc(type, Level.OVERWORLD, feet);
    }

    /**
     * A soft assertion: when {@code holds} is false, {@code what} is kept and the test goes on. Every check that failed
     * is reported together once the test ends, and fails it, so a test probing many things sees all that are wrong, not
     * the first. From the test's own thread, or inside an {@link #onServer} task or {@link Await} condition the test
     * started.
     */
    void check(boolean holds, String what);

    /**
     * {@link #check(boolean, String)} for any assertion: an {@link AssertionError} {@code assertion} throws, as every
     * JUnit assertion does, is kept instead of thrown; any other exception is thrown as it is.
     */
    void check(Assertion assertion);

    /** An assertion for {@link #check(Assertion)}, typically {@code () -> assertEquals(expected, actual)}. */
    @FunctionalInterface
    interface Assertion {
        void run() throws Throwable;
    }

    /** Run {@code measurement}'s phases in order; each phase's median window by name, in phase order. */
    Map<String, Perf.Window> perf(Perf measurement);

    /** What tests keep across the scene's restarts; see {@link DependsOn#afterRestart()}. */
    Store store();

    /**
     * End this game as soon as the calling test returns, as a player saving and quitting would, with whatever the world
     * is in the middle of. Tests of this round not started by then are skipped, and a later round starts where this one
     * stopped ({@link DependsOn#afterRestart()}). Tests still running beside it hold the game until they end, so it
     * belongs in a class marked {@code @Isolated}. Java tests only; a script test running beside it is not stopped.
     */
    void quitNow();

    /**
     * On an integrated server only: save and leave this world for the title screen, as a player quitting does, and
     * return once its server has stopped. The client and the tests' run go on; nothing that needs a server answers
     * until {@link #openWorld} has opened one. For what one client keeps from one world to the next; a dedicated
     * server's world is its process, so there a test uses {@link DependsOn#world()} after a restart instead.
     */
    void leaveWorld();

    /**
     * On an integrated server only: open the save {@code name} in this client, leaving the current world first if one
     * is open, and return once the player is in it. {@link DependsOn#FIRST_WORLD} is the scene's own; a name with no
     * save yet is a new world, made as the scene declares its world. From then on this game is that world's server.
     * When the test ends, after its overrides are undone, the world it started on is opened again. Nothing else may run
     * meanwhile, so it belongs in a class marked {@code @Isolated}, without plots or bots, which belong to one world.
     */
    void openWorld(String name);

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
     * Freeze the world for the rest of this test, as {@code /tick freeze} does; it thaws when the test ends, failed or
     * not. While frozen, {@link #ticks} and {@link Await} step the world instead of waiting: {@code ticks(1)} advances
     * it by exactly one tick, and an await steps it one tick at a time until its condition holds. Needs
     * {@link Locks#TICK_RATE}; calling it again changes nothing.
     */
    void freezeTicks();

    /**
     * Run {@code undo} on the server thread when the calling test ends, passed or not; the last registered runs first.
     * For facets that change shared state, such as a player's inventory. Only from a test's own thread.
     */
    void afterTest(Consumer<MinecraftServer> undo);

    /**
     * A capability some mod in this run contributed (see {@link CapabilityProvider}): its facet, the same object for
     * the rest of the test. When this game does not offer it the test is aborted, not failed, with a reason naming what
     * is missing and what is offered instead.
     */
    Object capability(String name);

    /** {@link #capability(String)} with the facet's type checked, for a Java test that owns both sides. */
    default <T> T capability(String name, Class<T> type) {
        Object facet = capability(name);
        if (!type.isInstance(facet)) {
            throw new IllegalStateException(
                    "the '" + name + "' capability is a " + facet.getClass().getName() + ", not a " + type.getName()
                            + " — its provider and this test disagree about what that name means");
        }
        return type.cast(facet);
    }

    /**
     * What this game offers: {@link Capabilities#has}, {@link Capabilities#available}, {@link Capabilities#registered}.
     */
    default Capabilities capabilities() {
        return Capabilities.in(this);
    }
}
