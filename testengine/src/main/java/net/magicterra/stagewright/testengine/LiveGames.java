package net.magicterra.stagewright.testengine;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The games this JVM started and that have not ended, ended when the JVM exits before they do.
 *
 * <p>A scene's own {@code finally} ends its games when the run ends normally, but not when the JVM running the engine
 * is stopped: a SIGTERM to the command-line runner, or to a Gradle test worker, runs shutdown hooks and no
 * {@code finally}. A game left then holds its port and its world's session lock, and a client hanging before its agent
 * connects has nobody else to end it. SIGKILL runs nothing, so that case is not covered.
 */
final class LiveGames {

    private static final Set<ProcessHandle> LIVE = ConcurrentHashMap.newKeySet();

    /** How long the games get to end on their own once asked, together, before they are killed. */
    private static final long GRACE_SECONDS = 10;

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(LiveGames::endAll, "stagewright-end-games"));
    }

    private LiveGames() {}

    /** Count {@code game} among this JVM's until it exits. */
    static void add(Process game) {
        ProcessHandle handle = game.toHandle();
        LIVE.add(handle);
        handle.onExit().thenRun(() -> LIVE.remove(handle));
    }

    /** Ask every game left to end, then kill what has not, and what each started, all at once rather than in turn. */
    static void endAll() {
        List<ProcessHandle> games = List.copyOf(LIVE);
        // Collected first: once a game is gone, what it started can no longer be found from here.
        List<ProcessHandle> descendants =
                games.stream().flatMap(ProcessHandle::descendants).toList();
        games.forEach(ProcessHandle::destroy);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(GRACE_SECONDS);
        for (ProcessHandle game : games) {
            try {
                game.onExit().get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (TimeoutException | ExecutionException e) {
                game.destroyForcibly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                game.destroyForcibly();
            }
        }
        for (ProcessHandle child : descendants) {
            if (child.isAlive()) {
                child.destroyForcibly();
            }
        }
    }
}
