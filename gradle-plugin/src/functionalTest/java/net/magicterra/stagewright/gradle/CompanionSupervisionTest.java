package net.magicterra.stagewright.gradle;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The companion client is a second game process, and gets its own agent: what holds it up after it
 * is done is named, under its own label, just as for the game being judged.
 */
class CompanionSupervisionTest {

    @TempDir
    Path dir;

    /**
     * The client reports through the agent, is done, and leaves a thread behind. The server outlasts
     * the agent's grace period, so the client has been ended and named by the time it is judged.
     */
    private static final String FAKE_GAME = """
            import java.lang.reflect.Method;
            import java.nio.file.*;

            public class Fake {
                public static void main(String[] args) throws Exception {
                    if (args[0].equals("client")) {
                        call("phase", String.class, "client-in-world");
                        call("ticks", long.class, 7L);
                        Thread pool = new Thread(() -> {
                            try { Thread.sleep(600_000); } catch (InterruptedException e) { }
                        }, "client-mod-pool-1");
                        pool.setDaemon(false);
                        pool.start();
                        Thread.sleep(1_000);
                        call("done", null, null);
                        return;
                    }
                    Thread.sleep(45_000);
                    Path results = Path.of("stagewright", "stagewright-results.jsonl");
                    Files.createDirectories(results.getParent());
                    Files.writeString(results, \"\"\"
                            {"type":"suite","registered":[{"name":"a"}]}
                            {"type":"scene","name":"a","outcome":"PASS"}
                            {"type":"done"}
                            \"\"\");
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

    @Test
    void aClientHeldUpAfterItIsDoneIsEndedAndNamedUnderItsOwnLabel() throws Exception {
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
                    args = ['server']
                    workingDir = file('run-stagewright-dedicated')
                    doFirst { workingDir.mkdirs() }
                }
                tasks.register('runClient', JavaExec) {
                    classpath = sourceSets.main.runtimeClasspath
                    mainClass = 'Fake'
                    args = ['client']
                    environment 'DISPLAY', 'localhost:10.0'
                    workingDir = file('run-client')
                    doFirst { workingDir.mkdirs() }
                }
                stagewright { topologies { dedicated {
                    runTask = 'runServer'
                    companionRunTask = 'runClient'
                } } }
                """);

        BuildResult result = GradleRunner.create()
                .withProjectDir(dir.toFile())
                .withPluginClasspath()
                .withArguments("stagewrightDedicated")
                .build();
        String output = result.getOutput();

        assertTrue(output.contains("[stagewright:dedicated-client]"), output);
        assertTrue(output.contains("client-mod-pool-1"), output);
        assertTrue(output.contains("VERDICT: GREEN"), output);
    }
}
