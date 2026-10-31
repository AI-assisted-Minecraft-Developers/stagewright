package net.magicterra.stagewright.jupiter;

/**
 * Whether a test asked this game to end with {@code Game.quitNow()}: from then on no Java test of the round starts, so
 * the run ends, and the game with it, once the tests already running return.
 */
final class Quit {

    private static volatile String by;

    private Quit() {}

    /** {@code test} asked to quit; the first one to ask is the one named. */
    static synchronized void request(String test) {
        if (by == null) {
            by = test;
        }
    }

    /** Why a test is not run now, or null while nobody asked to quit. */
    static String reason() {
        String test = by;
        return test == null ? null : "the game quit after " + test + " called game.quitNow()";
    }

    /** A new run: nobody has asked yet. */
    static void reset() {
        by = null;
    }
}
