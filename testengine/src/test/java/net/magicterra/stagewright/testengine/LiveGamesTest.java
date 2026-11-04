package net.magicterra.stagewright.testengine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

class LiveGamesTest {

    /** Stands for the engine's JVM: starts a "game" that would outlive it, prints its pid, and waits to be stopped. */
    public static void main(String[] args) throws Exception {
        Process game = new ProcessBuilder("sleep", "300").start();
        LiveGames.add(game);
        System.out.println(game.pid());
        System.out.flush();
        Thread.sleep(300_000);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aGameIsEndedWhenTheJvmThatStartedItIsTerminated() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process engine = new ProcessBuilder(
                        java, "-cp", System.getProperty("java.class.path"), LiveGamesTest.class.getName())
                .redirectErrorStream(true)
                .start();
        long gamePid;
        try (BufferedReader out =
                new BufferedReader(new InputStreamReader(engine.getInputStream(), StandardCharsets.UTF_8))) {
            gamePid = Long.parseLong(out.readLine().trim());
        }
        Optional<ProcessHandle> game = ProcessHandle.of(gamePid);
        assertTrue(game.isPresent() && game.get().isAlive(), "the game runs");

        engine.toHandle().destroy(); // SIGTERM, as a supervisor or a shell's kill sends
        assertTrue(engine.waitFor(30, TimeUnit.SECONDS), "the engine's JVM exits");

        game.get().onExit().get(5, TimeUnit.SECONDS);
        assertFalse(game.get().isAlive(), "the game " + gamePid + " ended with the JVM that started it");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void endingAllKillsAGameThatIgnoresBeingAsked() throws IOException, InterruptedException {
        // A shell that ignores SIGTERM, as a JVM wedged before its signal handling does.
        Process game = new ProcessBuilder(List.of("sh", "-c", "trap '' TERM; while :; do sleep 1; done")).start();
        LiveGames.add(game);
        long started = System.nanoTime();
        LiveGames.endAll();
        assertTrue(game.waitFor(5, TimeUnit.SECONDS), "killed once the grace ran out");
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(20), "within the grace and not much more");
    }
}
