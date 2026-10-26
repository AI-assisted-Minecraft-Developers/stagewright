package net.magicterra.stagewright.test;

/**
 * Names for Jupiter's {@code @ResourceLock} that cover the game's global state. A test that changes one through
 * {@link Game}'s {@code override} methods must hold its lock read-write (or the class must be {@code @Isolated}),
 * which keeps two tests from changing the same thing at once; the override is refused otherwise.
 */
public final class Locks {

    /** Every game rule. */
    public static final String WORLD_RULES = "stagewright.worldRules";

    /** The time of day. */
    public static final String WORLD_CLOCK = "stagewright.worldClock";

    /** Rain and thunder. */
    public static final String WEATHER = "stagewright.weather";

    /** The server's tick rate. */
    public static final String TICK_RATE = "stagewright.tickRate";

    private Locks() {}

    /** One player, by name. */
    public static String player(String name) {
        return "stagewright.player:" + name;
    }
}
