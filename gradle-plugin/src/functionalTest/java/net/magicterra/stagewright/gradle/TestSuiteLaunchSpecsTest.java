package net.magicterra.stagewright.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.magicterra.stagewright.engine.json.JsonObject;

/** {@code stagewright { tests { ... } }}: the launch specs the test engine reads, and the Test task. */
class TestSuiteLaunchSpecsTest {

    @TempDir
    Path dir;

    private void consumer(String suiteBody) throws Exception {
        Files.writeString(dir.resolve("settings.gradle"), "rootProject.name = 'consumer'\n");
        Files.writeString(dir.resolve("build.gradle"), """
                plugins {
                    id 'java'
                    id 'net.magicterra.stagewright'
                }

                tasks.register('runServer', JavaExec) {
                    classpath = files('server.jar')
                    mainClass = 'Server'
                    args = ['--nogui', '--port', '25601']
                    jvmArgs '-Dserver.flag=1'
                    workingDir = file('run-server')
                }
                tasks.register('runClient', JavaExec) {
                    classpath = files('client.jar')
                    mainClass = 'Client'
                    args = ['--join']
                    workingDir = file('run-client')
                }
                stagewright {
                    topologies { withClient {
                        runTask = 'runServer'
                        companionRunTask = 'runClient'
                        gameDirectory = file('run-server')
                        timeoutMinutes = 7
                    } }
                    tests { fabric {
                %s
                    } }
                }
                tasks.register('inspect') {
                    doLast {
                        println 'LAUNCHES=' + tasks.named('stagewrightTestFabric').get().systemProperties['stagewright.launches']
                    }
                }
                """.formatted(suiteBody));
    }

    private GradleRunner runner(String... args) {
        return GradleRunner.create().withProjectDir(dir.toFile()).withPluginClasspath().withArguments(args);
    }

    @Test
    void launchSpecsAreWrittenForEachMappedTopology() throws Exception {
        consumer("classes.from(files('classes'))\n"
                + "topologies.put('dedicatedServerWithClient', 'withClient')");

        runner("stagewrightLaunchSpecsFabric").build();

        Path file = dir.resolve("build/stagewright/launches/fabric/dedicatedServerWithClient.json");
        assertTrue(Files.exists(file), "the spec is named for the scene-facing topology");
        JsonObject json = JsonObject.readFrom(Files.readString(file));
        assertEquals("dedicatedServerWithClient", json.getString("topology", null));
        assertEquals(dir.resolve("run-server").toString(), json.getString("gameDirectory", null));
        assertTrue(json.getBoolean("cleanWorld", false));
        assertEquals(7, json.getInt("timeoutMinutes", 0));
        assertEquals(5, json.getInt("stallMinutes", 0));

        JsonObject primary = json.get("primary").asObject();
        assertEquals("Server", primary.getString("mainClass", null));
        assertEquals("[\"--nogui\",\"--port\",\"25601\"]", primary.get("args").toString());
        assertEquals(dir.resolve("run-server").toString(), primary.getString("workingDirectory", null));
        String jvmArgs = primary.get("jvmArgs").toString();
        assertTrue(jvmArgs.contains("-Dserver.flag=1"), jvmArgs);
        Path argFile = dir.resolve("build/stagewright/launches/fabric/classpath/dedicatedServerWithClient.primary.args");
        assertTrue(jvmArgs.contains("@" + argFile), "the classpath arrives as an argument file in jvmArgs: " + jvmArgs);
        assertTrue(Files.readString(argFile).contains("server.jar"));

        JsonObject companion = json.get("companion").asObject();
        assertEquals("Client", companion.getString("mainClass", null));
        assertEquals("[\"--join\"]", companion.get("args").toString());
        assertEquals(dir.resolve("run-client").toString(), companion.getString("workingDirectory", null));
    }

    @Test
    void theTestTaskIsRegisteredWithTheLaunchesDirectory() throws Exception {
        consumer("classes.from(files('classes'))\n"
                + "topologies.put('dedicatedServerWithClient', 'withClient')");

        BuildResult result = runner("inspect", "-q").build();
        assertTrue(result.getOutput().contains("LAUNCHES=" + dir.resolve("build/stagewright/launches/fabric")),
                result.getOutput());
        BuildResult tasks = runner("tasks", "--all").build();
        assertTrue(tasks.getOutput().contains("stagewrightTestFabric"), tasks.getOutput());
        assertTrue(tasks.getOutput().contains("stagewrightLaunchSpecsFabric"), tasks.getOutput());
    }

    @Test
    void anUndeclaredTopologyFailsNamingTheSuiteAndTheTopology() throws Exception {
        consumer("topologies.put('dedicatedServerWithClient', 'nowhere')");

        BuildResult result = runner("stagewrightLaunchSpecsFabric").buildAndFail();
        String out = result.getOutput();
        assertTrue(out.contains("test suite 'fabric'") && out.contains("'nowhere'"), out);
        assertFalse(Files.exists(dir.resolve("build/stagewright/launches/fabric/dedicatedServerWithClient.json")));
    }
}
