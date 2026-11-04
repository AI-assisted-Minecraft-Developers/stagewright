package net.magicterra.stagewright.jupiter;

/**
 * Whether a test asked this game to end with {@code Game.quitNow()} or {@code Game.crashNow()}: from then on no Java
 * test of the round starts, so the run ends, and the game with it, once the tests already running return. After a crash
 * the server runs nothing more, so whatever would ask it for something is skipped instead.
 */
final class Quit {

    private static volatile String reason;

    private static volatile boolean crashed;

    private Quit() {}

    /** {@code test} asked to quit; the first one to ask is the one named. */
    static synchronized void request(String test) {
        if (reason == null) {
            reason = "the game quit after " + test + " called game.quitNow()";
        }
    }

    /** {@code test} crashed the game; once crashed it stays so, whoever asked to quit first. */
    static synchronized void crash(String test) {
        if (!crashed) {
            crashed = true;
            reason = "the game crashed when " + test + " called game.crashNow()";
        }
    }

    /** Why a test is not run now, or null while nobody asked to quit. */
    static String reason() {
        return reason;
    }

    /** Whether the server was stopped where it stood, so that nothing may ask it for anything now. */
    static boolean crashed() {
        return crashed;
    }

    /** A new run: nobody has asked yet. */
    static synchronized void reset() {
        reason = null;
        crashed = false;
    }
}
