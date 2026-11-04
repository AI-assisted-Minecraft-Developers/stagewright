package net.magicterra.stagewright.jupiter;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestIdentifier;

/**
 * Whether a test asked this game to end with {@code Game.quitNow()} or {@code Game.crashNow()}: from then on no Java
 * test of the round starts, so the run ends, and the game with it, once the tests already running return; the ones not
 * started are left to the next game. After a crash the server runs nothing more, so whatever would ask it for something
 * is skipped instead.
 */
final class Quit {

    private static volatile String reason;

    private static volatile boolean crashed;

    /** The tests, by key, and the classes, by name, the quit kept from starting. */
    private static final Set<String> LEFT = ConcurrentHashMap.newKeySet();

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

    /**
     * {@code test}, a test's key or a whole class's name, was not started because the game quit: it is left to the next
     * game ({@link Rounds}), so neither its skip nor its abort is its outcome.
     */
    static void leave(String test) {
        LEFT.add(test);
    }

    /** Whether the test {@code key} was left to the next game, by itself or with its class. */
    static boolean left(String key) {
        int hash = key.indexOf('#');
        return LEFT.contains(key) || hash > 0 && LEFT.contains(key.substring(0, hash));
    }

    /** {@link #left(String)} for a node of the plan: a test method's, or a class's. */
    static boolean left(TestIdentifier id) {
        return switch (id.getSource().orElse(null)) {
            case MethodSource m -> left(m.getClassName() + "#" + m.getMethodName());
            case ClassSource c -> LEFT.contains(c.getClassName());
            case null, default -> false;
        };
    }

    /** A new run: nobody has asked yet. */
    static synchronized void reset() {
        reason = null;
        crashed = false;
        LEFT.clear();
    }
}
