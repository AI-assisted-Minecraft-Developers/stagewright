package net.magicterra.stagewright.engine.supervise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import net.magicterra.stagewright.engine.supervise.Supervisor.Ended;
import net.magicterra.stagewright.engine.supervise.Supervisor.Limits;
import org.junit.jupiter.api.Test;

/**
 * The supervisor against a real agent in a real JVM, and against hand-written clients for the cases
 * a real agent will not produce on request: silence, a wrong token, an unknown event.
 */
class SupervisorTest {

    private static final Limits FAST = limits(5, 5, 60, 60);

    private static Limits limits(int connect, int heartbeat, int stall, int total) {
        return new Limits(Duration.ofSeconds(connect), Duration.ofSeconds(heartbeat), Duration.ofSeconds(stall),
                Duration.ofSeconds(total));
    }

    private static Process start(Supervisor supervisor, int graceSeconds, String mode) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add(supervisor.agentArgument(Path.of(System.getProperty("stagewright.agentJar")), graceSeconds));
        command.add("-cp");
        command.add(System.getProperty("stagewright.testClasses"));
        command.add(FakeGame.class.getName());
        command.add(mode);
        return new ProcessBuilder(command).inheritIO().start();
    }

    /** A live JVM that never loaded the agent: what a hand-played client needs as the game it is "of". */
    private static Process bare() throws IOException {
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("stagewright.testClasses"), FakeGame.class.getName(), "bare")
                .inheritIO().start();
    }

    @Test
    void aGameThatEndsByItselfIsDoneWithNothingLeaked() throws Exception {
        try (Supervisor supervisor = Supervisor.open()) {
            Process game = start(supervisor, 20, "clean");
            try {
                assertEquals(Ended.DONE, supervisor.await(game.toHandle(), FAST));
                assertTrue(supervisor.awaitExit(game.toHandle(), Duration.ofSeconds(15)), "the JVM should end itself");
                assertEquals(0, game.exitValue());
                assertEquals(List.of(), supervisor.leaks());
                assertEquals("harness-ready", supervisor.phase());
                assertEquals(7, supervisor.ticks());
                assertEquals(game.pid(), supervisor.agentPid());
            } finally {
                Supervisor.kill(game.toHandle());
            }
        }
    }

    @Test
    void aThreadThatHoldsTheJvmUpIsNamedAndTheJvmIsEnded() throws Exception {
        try (Supervisor supervisor = Supervisor.open()) {
            Process game = start(supervisor, 1, "leak");
            try {
                assertEquals(Ended.DONE, supervisor.await(game.toHandle(), FAST));
                assertTrue(supervisor.awaitExit(game.toHandle(), Duration.ofSeconds(15)),
                        "the agent should end a JVM that outlives the grace period");
                assertEquals(0, game.exitValue());
                List<Supervisor.Leak> leaks = supervisor.leaks();
                assertEquals(1, leaks.size(), leaks.toString());
                Supervisor.Leak leak = leaks.get(0);
                assertEquals("mod-pool-1", leak.thread());
                assertTrue(leak.stack().stream().anyMatch(f -> f.contains("FakeGame$Holder")), leak.stack().toString());
                assertFalse(leak.jars().isEmpty(), "the report should say where the thread's code came from");
                // Who started it: the JDK's own frames are left out, and the code that did is named.
                assertTrue(leak.origin().stream().anyMatch(f -> f.contains("FakeGame.main")), leak.origin().toString());
                assertFalse(leak.originJars().isEmpty(), "the starter's code location should be named");
            } finally {
                Supervisor.kill(game.toHandle());
            }
        }
    }

    @Test
    void aProcessThatNeverLoadedTheAgentIsNeverConnected() throws Exception {
        try (Supervisor supervisor = Supervisor.open()) {
            Process game = bare();
            try {
                assertEquals(Ended.NEVER_CONNECTED, supervisor.await(game.toHandle(), limits(1, 5, 60, 60)));
            } finally {
                Supervisor.kill(game.toHandle());
            }
        }
    }

    @Test
    void aProcessThatDiesWithoutSayingDoneIsDead() throws Exception {
        try (Supervisor supervisor = Supervisor.open()) {
            Process game = start(supervisor, 20, "silent");
            try {
                // Wait until it has connected, then take it away.
                long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                while (supervisor.agentPid() < 0 && System.nanoTime() < deadline) Thread.sleep(50);
                assertTrue(supervisor.agentPid() > 0, "the agent should have said hello");
                game.destroyForcibly();
                assertEquals(Ended.PROCESS_DIED, supervisor.await(game.toHandle(), FAST));
            } finally {
                Supervisor.kill(game.toHandle());
            }
        }
    }

    @Test
    void aConnectionThatGoesSilentIsAHeartbeatLost() throws Exception {
        Process game = bare();
        try (Supervisor supervisor = Supervisor.open(); Socket client = connect(supervisor)) {
            send(client, hello(supervisor.token()));
            assertEquals(Ended.HEARTBEAT_LOST, supervisor.await(game.toHandle(), limits(5, 1, 60, 60)));
        } finally {
            Supervisor.kill(game.toHandle());
        }
    }

    @Test
    void aGameThatBeatsButMovesNothingIsStalled() throws Exception {
        Process game = bare();
        try (Supervisor supervisor = Supervisor.open(); Socket client = connect(supervisor)) {
            send(client, hello(supervisor.token()));
            Thread beats = new Thread(() -> {
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        send(client, "{\"event\":\"heartbeat\",\"phase\":\"loading\",\"ticks\":0,\"classes\":9000}");
                        Thread.sleep(200);
                    }
                } catch (IOException | InterruptedException ignored) {
                    // The test is over.
                }
            });
            beats.setDaemon(true);
            beats.start();
            try {
                assertEquals(Ended.STALLED, supervisor.await(game.toHandle(), limits(5, 5, 1, 60)));
            } finally {
                beats.interrupt();
            }
        } finally {
            Supervisor.kill(game.toHandle());
        }
    }

    @Test
    void aGameThatKeepsLoadingClassesIsNotStalled() throws Exception {
        Process game = bare();
        try (Supervisor supervisor = Supervisor.open(); Socket client = connect(supervisor)) {
            send(client, hello(supervisor.token()));
            Thread beats = new Thread(() -> {
                try {
                    for (int classes = 9000; !Thread.currentThread().isInterrupted(); classes += 10) {
                        send(client, "{\"event\":\"heartbeat\",\"phase\":\"loading\",\"ticks\":0,\"classes\":" + classes + "}");
                        Thread.sleep(200);
                    }
                } catch (IOException | InterruptedException ignored) {
                    // The test is over.
                }
            });
            beats.setDaemon(true);
            beats.start();
            try {
                assertEquals(Ended.TIMED_OUT, supervisor.await(game.toHandle(), limits(5, 5, 1, 3)));
            } finally {
                beats.interrupt();
            }
        } finally {
            Supervisor.kill(game.toHandle());
        }
    }

    @Test
    void aGameThatStopsMovingSaysWhereItsThreadsAre() throws Exception {
        try (Supervisor supervisor = Supervisor.open()) {
            List<String> command = new ArrayList<>();
            command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            command.add(supervisor.agentArgument(Path.of(System.getProperty("stagewright.agentJar")), 20, 1));
            command.addAll(List.of("-cp", System.getProperty("stagewright.testClasses"), FakeGame.class.getName(),
                    "silent"));
            Process game = new ProcessBuilder(command).inheritIO().start();
            try {
                long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                while (supervisor.stacks().isEmpty() && System.nanoTime() < deadline) Thread.sleep(100);
                List<Supervisor.ThreadStack> stacks = supervisor.stacks();
                assertEquals(1, stacks.size(), stacks.toString());
                assertEquals("main", stacks.get(0).thread());
                assertTrue(stacks.get(0).frames().stream().anyMatch(f -> f.contains("FakeGame.main")),
                        stacks.get(0).frames().toString());
            } finally {
                Supervisor.kill(game.toHandle());
            }
        }
    }

    @Test
    void peekAnswersWithoutWaiting() throws Exception {
        Process game = bare();
        try (Supervisor supervisor = Supervisor.open(); Socket client = connect(supervisor)) {
            send(client, hello(supervisor.token()));
            Thread.sleep(300);
            assertEquals(null, supervisor.peek(game.toHandle(), FAST));
            send(client, "{\"event\":\"done\"}");
            Thread.sleep(300);
            assertEquals(Ended.DONE, supervisor.peek(game.toHandle(), FAST));
        } finally {
            Supervisor.kill(game.toHandle());
        }
    }

    @Test
    void theCallersProbeEndsTheWait() throws Exception {
        Process game = bare();
        try (Supervisor supervisor = Supervisor.open(); Socket client = connect(supervisor)) {
            send(client, hello(supervisor.token()));
            assertEquals(Ended.CRASHED, supervisor.await(game.toHandle(), FAST, () -> Ended.CRASHED));
        } finally {
            Supervisor.kill(game.toHandle());
        }
    }

    @Test
    void aClientWithTheWrongTokenDoesNotCount() throws Exception {
        Process game = bare();
        try (Supervisor supervisor = Supervisor.open(); Socket client = connect(supervisor)) {
            send(client, hello("not-the-token"));
            send(client, "{\"event\":\"done\"}");
            assertEquals(Ended.NEVER_CONNECTED, supervisor.await(game.toHandle(), limits(1, 5, 60, 60)));
        } finally {
            Supervisor.kill(game.toHandle());
        }
    }

    @Test
    void anEventThisVersionDoesNotKnowIsIgnored() throws Exception {
        Process game = bare();
        try (Supervisor supervisor = Supervisor.open(); Socket client = connect(supervisor)) {
            send(client, hello(supervisor.token()));
            send(client, "{\"event\":\"from-the-future\",\"x\":1}");
            send(client, "not json at all");
            send(client, "{\"event\":\"done\"}");
            assertEquals(Ended.DONE, supervisor.await(game.toHandle(), FAST));
        } finally {
            Supervisor.kill(game.toHandle());
        }
    }

    @Test
    void aHeldGameThatHasItsResultsEndsTheWaitAndIsNotEndedItself() throws Exception {
        try (Supervisor supervisor = Supervisor.open()) {
            Process game = start(supervisor, 1, "held");
            try {
                assertEquals(Ended.RESULTS, supervisor.await(game.toHandle(), FAST));
                // Longer than the one-second grace a done would have started: a hold is not ended.
                assertFalse(supervisor.awaitExit(game.toHandle(), Duration.ofSeconds(4)), "the hold should stay up");
                assertEquals(List.of(), supervisor.leaks());
            } finally {
                Supervisor.kill(game.toHandle());
            }
        }
    }

    @Test
    void doneOutranksResultsComplete() throws Exception {
        Process game = bare();
        try (Supervisor supervisor = Supervisor.open(); Socket client = connect(supervisor)) {
            send(client, hello(supervisor.token()));
            send(client, "{\"event\":\"results\"}");
            send(client, "{\"event\":\"done\"}");
            Thread.sleep(300);
            assertEquals(Ended.DONE, supervisor.peek(game.toHandle(), FAST));
        } finally {
            Supervisor.kill(game.toHandle());
        }
    }

    @Test
    void aTimeoutTooLargeForNanosecondsHasNotRunOut() throws Exception {
        Process game = bare();
        try (Supervisor supervisor = Supervisor.open(); Socket client = connect(supervisor)) {
            send(client, hello(supervisor.token()));
            Thread.sleep(300);
            Duration huge = Duration.ofMinutes(999_999_999);
            assertEquals(null, supervisor.peek(game.toHandle(), new Limits(huge, huge, huge, huge)));
        } finally {
            Supervisor.kill(game.toHandle());
        }
    }

    private static Socket connect(Supervisor supervisor) throws IOException {
        return new Socket(InetAddress.getLoopbackAddress(), supervisor.port());
    }

    private static String hello(String token) {
        return "{\"event\":\"hello\",\"token\":\"" + token + "\",\"pid\":1}";
    }

    private static void send(Socket socket, String line) throws IOException {
        OutputStream out = socket.getOutputStream();
        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
