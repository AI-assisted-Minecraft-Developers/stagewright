package net.magicterra.stagewright.gradle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A gate's run task is a {@code JavaExec}, which waits for its process; these run one against a game
 * that behaves the way the agent is meant to cope with, and check the build stops waiting.
 */
class SupervisionTest {

    @TempDir
    Path dir;

    /**
     * A stand-in for a game: writes a green results file, reports through the agent, and then does
     * what {@code mode} says.
     */
    private static final String FAKE_GAME = """
            import java.lang.reflect.Method;
            import java.nio.file.*;

            public class Fake {
                public static void main(String[] args) throws Exception {
                    String mode = args[0];
                    call("phase", String.class, "harness-ready");
                    call("ticks", long.class, 7L);
                    if (mode.equals("silent")) {
                        Thread.sleep(300_000);
                        return;
                    }
                    if (mode.equals("crash")) {
                        Thread.sleep(1_500);
                        Path report = Path.of("crash-reports", "crash-new.txt");
                        Files.createDirectories(report.getParent());
                        Files.writeString(report, "---- Minecraft Crash Report ----");
                        Thread.sleep(300_000);
                        return;
                    }
                    Path results = Path.of("stagewright", "stagewright-results.jsonl");
                    Files.createDirectories(results.getParent());
                    Files.writeString(results, \"\"\"
                            {"type":"suite","registered":[{"name":"a"}]}
                            {"type":"scene","name":"a","outcome":"PASS"}
                            {"type":"done"}
                            \"\"\");
                    if (mode.equals("leak")) {
                        Thread pool = new Thread(() -> {
                            try { Thread.sleep(600_000); } catch (InterruptedException e) { }
                        }, "mod-pool-1");
                        pool.setDaemon(false);
                        pool.start();
                    }
                    Thread.sleep(1_500);
                    call("done", null, null);
                }

                private static void call(String method, Class<?> parameter, Object argument) throws Exception {
                    Class<?> control = Class.forName("net.magicterra.stagewright.agent.Control", true,
                            ClassLoader.getSystemClassLoader());
                    Method m = parameter == null ? control.getMethod(method) : control.getMethod(method, parameter);
                    if (parameter == null) m.invoke(null); else m.invoke(null, argument);
                }
            }
            """;

    private void consumer(String mode, String topologyBody) throws Exception {
        Files.writeString(dir.resolve("settings.gradle"), "rootProject.name = 'consumer'\n");
        Files.createDirectories(dir.resolve("src/main/java"));
        Files.writeString(dir.resolve("src/main/java/Fake.java"), FAKE_GAME);
        Files.writeString(dir.resolve("build.gradle"), """
                plugins {
                    id 'java'
                    id 'net.magicterra.stagewright'
                }
                tasks.register('runServer', JavaExec) {
                    classpath = sourceSets.main.runtimeClasspath
                    mainClass = 'Fake'
                    args = ['%s']
                    workingDir = file('run-stagewright-dedicated')
                    doFirst { workingDir.mkdirs() }
                }
                stagewright { topologies { dedicated {
                    runTask = 'runServer'
                    %s
                } } }
                """.formatted(mode, topologyBody));
    }

    private GradleRunner gate() {
        return GradleRunner.create()
                .withProjectDir(dir.toFile())
                .withPluginClasspath()
                .withArguments("stagewrightDedicated");
    }

    @Test
    void aGameThatEndsItselfIsJudgedWithNothingToWarnAbout() throws Exception {
        consumer("clean", "");
        BuildResult result = gate().build();
        assertTrue(result.getOutput().contains("VERDICT: GREEN"), result.getOutput());
        assertFalse(result.getOutput().contains("WARNING"), result.getOutput());
    }

    @Test
    void aJvmHeldUpAfterTheSuiteIsEndedAndNamedAndTheRunStillJudgesGreen() throws Exception {
        consumer("leak", "");
        long started = System.nanoTime();
        BuildResult result = gate().build();
        long seconds = (System.nanoTime() - started) / 1_000_000_000L;
        String output = result.getOutput();
        // The thread sleeps for ten minutes; the agent's grace is thirty seconds.
        assertTrue(seconds < 120, seconds + "s\n" + output);
        assertTrue(output.contains("the suite finished but the game's JVM did not end"), output);
        assertTrue(output.contains("mod-pool-1"), output);
        // The warning comes before the verdict it does not change.
        assertTrue(output.indexOf("mod-pool-1") < output.indexOf("VERDICT: GREEN"), output);
    }

    @Test
    void failOnLeakMakesTheLeakARed() throws Exception {
        consumer("leak", "failOnLeak = true");
        BuildResult result = gate().buildAndFail();
        assertTrue(result.getOutput().contains("failOnLeak"), result.getOutput());
        assertTrue(result.getOutput().contains("RED"), result.getOutput());
    }

    @Test
    void aGameThatCrashesButStaysUpIsEndedAndTheReportIsNamed() throws Exception {
        consumer("crash", "");
        long started = System.nanoTime();
        BuildResult result = gate().buildAndFail();
        long seconds = (System.nanoTime() - started) / 1_000_000_000L;
        String output = result.getOutput();
        // It would sleep for five minutes, and the task's own timeout is twenty.
        assertTrue(seconds < 120, seconds + "s\n" + output);
        assertTrue(output.contains("crashed and its JVM is still up"), output);
        assertTrue(output.contains("crash-new.txt"), output);
    }

    @Test
    void aGameThatStopsMovingIsEndedWithTheStateItWasIn() throws Exception {
        consumer("silent", "stallMinutes = 1");
        long started = System.nanoTime();
        BuildResult result = gate().buildAndFail();
        long seconds = (System.nanoTime() - started) / 1_000_000_000L;
        String output = result.getOutput();
        // It would sleep for five minutes, and the task's own timeout is twenty.
        assertTrue(seconds < 150, seconds + "s\n" + output);
        assertTrue(output.contains("nothing it reports moved for 1 minute"), output);
        assertTrue(output.contains("harness-ready"), output);
    }
}
