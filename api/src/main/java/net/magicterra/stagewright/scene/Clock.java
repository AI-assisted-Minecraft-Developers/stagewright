package net.magicterra.stagewright.scene;

/**
 * The time of day a scene runs at.
 *
 * <p><b>Why a scene gets to choose, and why the default is night.</b> The world clock is one value
 * shared by every scene in a run, and left alone it advances — so what time a scene sees depends on
 * how long the scenes before it took, which is a property of the machine, not of the test. Worse,
 * a suite is short: measured on both loaders, all 191 worlddriver scenes finish inside
 * {@code dayTime}≈130, which is sunrise — exactly where sky brightness is climbing through the
 * threshold that decides whether a sun-sensitive mob catches fire. Vanilla rolls dice on that
 * threshold every tick, so "does the zombie ignite" became a coin flip decided by which tick a
 * scene happened to land on. Three separate scenes had hand-rolled a defence against it before this
 * existed.
 *
 * <p>{@link #MIDNIGHT} is the default because it is the only value that is <i>decided</i> rather
 * than merely fixed: {@code isDay()} is false, so the burn check never even reaches its dice roll.
 * Pinning to noon would not buy that — at full brightness the roll still fires, just quickly, so
 * "when does it ignite" stays random.
 *
 * <p>The harness applies this before every scene, not once per suite. Per-scene is what makes a
 * scene's clock independent of the scene before it — including one that asked for {@link #RUNNING}.
 *
 * <p><b>This does not make the world vanilla-default</b>, and it is not meant to. See
 * {@code WorldPin} for the full list of what a run pins and why each one is on it; the run logs the
 * list and writes it into the results header, so a green suite always says what world it was green
 * in.
 */
public enum Clock {

    /** Midnight ({@code dayTime} 18000). The default: no daylight, so nothing sun-sensitive can
     *  ignite and no scene has to defend itself against the sun. */
    MIDNIGHT(18_000L, false),

    /** Noon ({@code dayTime} 6000), clock still frozen. For a scene whose subject IS daylight —
     *  without this, pinning the suite to night would quietly hide every daylight-only bug. */
    NOON(6_000L, false),

    /**
     * Midnight, with the daylight cycle RUNNING for the duration of this scene.
     *
     * <p>For the rare scene whose subject is the passage of time. The harness restores the frozen
     * default afterwards, so this cannot leak into the next scene — but note that the arena audit
     * sees {@code doDaylightCycle} flip, which is why the harness restores it before taking the
     * audit's closing snapshot rather than leaving the scene to be blamed for it.
     */
    RUNNING(18_000L, true);

    private final long dayTime;
    private final boolean cycleRuns;

    Clock(long dayTime, boolean cycleRuns) {
        this.dayTime = dayTime;
        this.cycleRuns = cycleRuns;
    }

    /** The {@code dayTime} value to set before the scene body runs. */
    public long dayTime() { return dayTime; }

    /** Whether {@code doDaylightCycle} is on while this scene runs. */
    public boolean cycleRuns() { return cycleRuns; }

    /** Parse the name a scene file writes ({@code 'noon'}), case-insensitively. Unknown names throw
     *  with the full list — a typo that silently fell back to the default would run the scene at the
     *  wrong time and fail an assertion with nothing pointing at the cause. */
    public static Clock parse(String s) {
        for (Clock c : values()) {
            if (c.name().equalsIgnoreCase(s)) return c;
        }
        throw new IllegalArgumentException("unknown clock '" + s + "' — expected one of "
                + java.util.Arrays.toString(values()));
    }
}
