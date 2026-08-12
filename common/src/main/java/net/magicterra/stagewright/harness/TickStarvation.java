package net.magicterra.stagewright.harness;

/**
 * "The world is not delivering ticks" — one criterion, two consumers.
 *
 * <p>Every budget this framework counts is denominated in TICKS, which is the right unit for
 * everything a scene asserts about and the wrong unit for the one failure a scene cannot survive: a
 * world whose ticks stopped. A tick budget in a stopped world never expires, so the scene never
 * ends, so no result is written, so the run dies as a wall-clock kill with no verdict and no name.
 * This class is the unit that survives that — wall clock, measured against ticks delivered.
 *
 * <p>Two places need it and they must agree. {@link StageWrightHarness} uses it to end the scene it
 * is inside, so the suite keeps going and the row says which scene the world starved. {@link
 * StallWatchdog} uses it from its own thread to end the RUN when even that is no longer possible,
 * because the harness's check lives on the tick and a tick that never comes cannot run it. Two
 * copies of a threshold is how those two answers start disagreeing about the same run.
 *
 * <h2>Why a rate and not "the counter stopped"</h2>
 *
 * <p>"The counter has not moved in 90 s" was the whole criterion once, and it stayed silent through
 * three real wedges: the server sat at 0% CPU parked in {@code MinecraftServer.waitUntilNextTick},
 * having spent 16 ms of CPU in 200 s, and the counter was still <i>creeping</i> — a tick every so
 * often is not a frozen counter, and it is not a working server either. A server delivering fewer
 * than {@value #MIN_TICKS} ticks in a window is wedged by every measure that matters here, whatever
 * it is doing between them.
 *
 * <h2>Why this floor cannot fire on "busy"</h2>
 *
 * <p>Nominal is 1 800 ticks per scene-window. The floor is twenty — ninety times slower than nominal, and
 * about a hundredth of what a heavily modded pack still delivers while it labours through worldgen.
 * Single ticks legitimately run for seconds, and a threshold tight enough to catch a deadlock
 * quickly would be far tighter than those; killing a healthy run that was merely busy would be a
 * worse failure than the one this prevents, because it would be blamed on the mod under test. A
 * single tick that takes ninety seconds trips this — and is itself worth reporting.
 */
final class TickStarvation {

    /** The window the harness judges ONE SCENE over. */
    static final long SCENE_WINDOW_MS = 90_000;

    /**
     * The window the watchdog judges the WHOLE RUN over — deliberately a multiple of the scene's.
     *
     * <p>The two must not share a deadline. They ask the same question and take opposite actions:
     * the harness ends the scene and the suite carries on, the watchdog ends the run. Given one
     * threshold they race, and the watchdog wins it systematically — its window opens once at arm
     * time while the harness's reopens at every scene boundary, so the watchdog is always further
     * into a window than the harness is. A run that stalled would then die as DEAD with one named
     * scene, which is the exact outcome the harness's check was added to replace.
     *
     * <p>At three scene-windows the harness gets three turns first: three starved scenes are ended,
     * named and recorded before the run is declared unrecoverable. If the world recovers in any of
     * them, the watchdog's window closes healthily and nothing is killed. If it does not, the two
     * deadlines converge and the watchdog fires — and by then it is right to, because a suite that
     * can only produce starvation rows for hours is not a suite that should be left running.
     */
    static final long RUN_WINDOW_MS = 3 * SCENE_WINDOW_MS;

    /** The fewest ticks a live server may deliver in one window. */
    static final long MIN_TICKS = 20;

    private final long windowMs;
    private long windowStartedMs;
    private long windowStartTicks;

    /** The harness's, ending one starved scene so the rest stay reachable. */
    static TickStarvation forScene(long ticksNow) {
        return new TickStarvation(SCENE_WINDOW_MS, ticksNow);
    }

    /** The watchdog's, ending a run no scene-level rescue has brought back. */
    static TickStarvation forRun(long ticksNow) {
        return new TickStarvation(RUN_WINDOW_MS, ticksNow);
    }

    private TickStarvation(long windowMs, long ticksNow) {
        this.windowMs = windowMs;
        reset(ticksNow);
    }

    /**
     * Open a fresh window at {@code ticksNow}.
     *
     * <p>Called when a window closes healthily, and by the harness at every scene boundary — so the
     * verdict is always about the scene it names, and a scene cannot inherit its predecessor's
     * partial window.
     */
    void reset(long ticksNow) {
        windowStartedMs = System.currentTimeMillis();
        windowStartTicks = ticksNow;
    }

    long elapsedMs() {
        return System.currentTimeMillis() - windowStartedMs;
    }

    long delivered(long ticksNow) {
        return ticksNow - windowStartTicks;
    }

    /**
     * @return null while the world is keeping up — including for the whole of an open window, which
     *         is why callers must keep calling. Otherwise, how it fell short, phrased for a reader
     *         who has to decide which half of the process to go looking in: "not advanced" is a
     *         deadlock holding something, "crawled" is a loop being told it has time it does not.
     */
    String starved(long ticksNow) {
        long elapsed = elapsedMs();
        if (elapsed < windowMs) return null;
        long delivered = delivered(ticksNow);
        if (delivered >= MIN_TICKS) {
            reset(ticksNow);
            return null;
        }
        return describe(delivered, elapsed);
    }

    static String describe(long delivered, long elapsedMs) {
        return delivered == 0
                ? "the server tick has not advanced in " + (elapsedMs / 1000) + "s"
                : "the server tick has crawled — " + delivered + " tick(s) in " + (elapsedMs / 1000)
                  + "s, against 20/s nominal";
    }
}
