package net.magicterra.stagewright.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A topology with a companion used to switch the configuration cache off for its run, because the run
 * held the companion's task. It holds the values of the launch now; this runs one through a store and
 * a reuse and checks the companion started the same both times.
 */
class CompanionConfigurationCacheTest {

    @TempDir
    Path dir;

    /**
     * One main for both halves. The server waits for the companion's marker and then writes a green
     * results file; the companion writes what it was started with and waits to be stopped.
     */
    private static final String FAKE_GAME = """
            import java.lang.reflect.Method;
            import java.nio.file.*;

            public class Fake {
                public static void main(String[] args) throws Exception {
                    Path marker = Path.of(args[1]);
                    if (args[0].equals("client")) {
                        Files.writeString(marker, Path.of("").toAbsolutePath().getFileName() + "|"
                                + System.getenv("STAGED") + "|" + args[2] + "|" + System.getProperty("staged.prop"));
                        Thread.sleep(120_000);
                        return;
                    }
                    for (int i = 0; i < 300 && !Files.exists(marker); i++) Thread.sleep(100);
                    Path results = Path.of("stagewright", "stagewright-results.jsonl");
                    Files.createDirectories(results.getParent());
                    Files.writeString(results, \"\"\"
                            {"type":"suite","registered":[{"name":"a"}]}
                            {"type":"scene","name":"a","outcome":"PASS"}
                            {"type":"done"}
                            \"\"\");
                    Thread.sleep(1_000);
                    Class<?> control = Class.forName("net.magicterra.stagewright.agent.Control", true,
                            ClassLoader.getSystemClassLoader());
                    control.getMethod("done").invoke(null);
                }
            }
            """;

    private void consumer() throws Exception {
        Files.writeString(dir.resolve("settings.gradle"), "rootProject.name = 'consumer'\n");
        Files.createDirectories(dir.resolve("src/main/java"));
        Files.writeString(dir.resolve("src/main/java/Fake.java"), FAKE_GAME);
        Files.writeString(dir.resolve("build.gradle"), """
                import org.gradle.api.file.*
                import org.gradle.api.provider.*

                plugins {
                    id 'java'
                    id 'net.magicterra.stagewright'
                }

                // The two members of ModDevGradle's run task that matter here: what it binds in its own
                // action rather than at configuration.
                abstract class StagedRun extends JavaExec {
                    @Internal abstract ConfigurableFileCollection getClasspathProvider()
                    @Internal abstract MapProperty<String, String> getEnvironmentProperty()
                    @Internal abstract DirectoryProperty getGameDirectory()
                }

                def marker = layout.projectDirectory.file('marker.txt').asFile.absolutePath

                tasks.register('runServer', JavaExec) {
                    classpath = sourceSets.main.runtimeClasspath
                    mainClass = 'Fake'
                    args = ['server', marker]
                    workingDir = file('run-stagewright-dedicated')
                    doFirst { workingDir.mkdirs() }
                }
                tasks.register('runClient', StagedRun) {
                    getClasspathProvider().from(sourceSets.main.runtimeClasspath)
                    getEnvironmentProperty().put('STAGED', 'from-the-loader')
                    getEnvironmentProperty().put('DISPLAY', 'localhost:10.0')
                    getGameDirectory().set(layout.projectDirectory.dir('run-client'))
                    jvmArgs '-Dstaged.prop=a-jvm-arg'
                    mainClass = 'Fake'
                    args = ['client', marker, 'a-program-arg']
                }
                stagewright { topologies { dedicated {
                    runTask = 'runServer'
                    companionRunTask = 'runClient'
                } } }
                """);
        // A git work tree, so there is a build id to take: with none the plugin looks for one while
        // configuring, which a configuration cache refuses for a reason that has nothing to do with this.
        Files.writeString(dir.resolve(".gitignore"), "build/\\n.gradle/\\nrun-*/\\nmarker.txt\\n");
        git("init", "-q");
        git("add", ".");
        git("commit", "-q", "-m", "init");
    }

    private void git(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-c", "user.name=t", "-c", "user.email=t@t",
                "-c", "commit.gpgsign=false"));
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).directory(dir.toFile()).inheritIO().start();
        if (p.waitFor() != 0) throw new IllegalStateException("git " + String.join(" ", args) + " failed");
    }

    private BuildResult gate() {
        return GradleRunner.create()
                .withProjectDir(dir.toFile())
                .withPluginClasspath()
                .withArguments("stagewrightDedicated", "--configuration-cache")
                .build();
    }

    private String companionSaw() throws Exception {
        Path marker = dir.resolve("marker.txt");
        String seen = Files.readString(marker);
        Files.delete(marker);
        return seen;
    }

    @Test
    void aCompanionStartsFromTheCapturedLaunchBothWhenTheCacheIsStoredAndWhenItIsReused() throws Exception {
        consumer();
        String expected = "run-client|from-the-loader|a-program-arg|a-jvm-arg";

        BuildResult stored = gate();
        assertTrue(stored.getOutput().contains("Configuration cache entry stored"), stored.getOutput());
        assertFalse(stored.getOutput().contains("incompatible"), stored.getOutput());
        assertTrue(stored.getOutput().contains("VERDICT: GREEN"), stored.getOutput());
        assertEquals(expected, companionSaw());

        BuildResult reused = gate();
        assertTrue(reused.getOutput().contains("Reusing configuration cache"), reused.getOutput());
        assertTrue(reused.getOutput().contains("VERDICT: GREEN"), reused.getOutput());
        assertEquals(expected, companionSaw());
    }
}
