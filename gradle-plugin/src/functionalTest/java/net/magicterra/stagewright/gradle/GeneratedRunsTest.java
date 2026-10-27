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

/**
 * A suite with a loader project: the runs the plugin declares in that project's loom, and the launch specs read back
 * from the run tasks loom makes of them. The loom here is a stand-in with the members the plugin calls, which
 * registers a {@code run<Name>} task per run as loom does.
 */
class GeneratedRunsTest {

    @TempDir
    Path dir;

    private void consumer(String suiteBody) throws Exception {
        consumer(suiteBody, "sourceSets { testmod {} }");
    }

    private void consumer(String suiteBody, String loaderSourceSets) throws Exception {
        Files.writeString(dir.resolve("settings.gradle"), "rootProject.name = 'consumer'\ninclude 'loader'\n");
        Files.writeString(dir.resolve("build.gradle"), """
                plugins { id 'net.magicterra.stagewright' }
                stagewright { tests { fabric {
                %s
                } } }
                """.formatted(suiteBody));
        Files.createDirectories(dir.resolve("loader"));
        Files.writeString(dir.resolve("loader/build.gradle"), """
                plugins { id 'java' }
                %s

                class FakeRun implements Named {
                    final String name
                    String side
                    String dir
                    boolean ide = true
                    SourceSet src
                    List<String> args = []
                    List<String> vm = []
                    Map<String, String> props = [:]
                    FakeRun(String name) { this.name = name }
                    String getName() { name }
                    void server() { side = 'server' }
                    void client() { side = 'client' }
                    void runDir(String d) { dir = d }
                    void ideConfigGenerated(boolean b) { ide = b }
                    void source(SourceSet s) { src = s }
                    void programArg(String a) { args << a }
                    void vmArg(String a) { vm << a }
                    void property(String k, String v) { props[k] = v }
                }
                class FakeLoom {
                    NamedDomainObjectContainer<FakeRun> runs
                    NamedDomainObjectContainer<FakeRun> getRuns() { runs }
                }
                def runs = objects.domainObjectContainer(FakeRun) { n -> new FakeRun(n) }
                extensions.add('loom', new FakeLoom(runs: runs))
                runs.whenObjectAdded { FakeRun r ->
                    tasks.register('run' + r.name.capitalize(), JavaExec) {
                        mainClass = r.side
                        classpath = files(r.side + '.jar')
                        args = r.args
                        jvmArgs = r.vm + r.props.collect { k, v -> "-D" + k + "=" + v }
                        workingDir = file(r.dir)
                    }
                }
                tasks.register('inspectRuns') {
                    doLast { runs.each { println 'RUN ' + it.name + ' ide=' + it.ide + ' source=' + it.src.name } }
                }
                """.formatted(loaderSourceSets));
    }

    private GradleRunner runner(String... args) {
        return GradleRunner.create().withProjectDir(dir.toFile()).withPluginClasspath().withArguments(args);
    }

    private JsonObject spec(String topology) throws Exception {
        return JsonObject.readFrom(Files.readString(dir.resolve("build/stagewright/launches/fabric/" + topology + ".json")));
    }

    @Test
    void theRunsAreDeclaredInTheLoaderProjectAndTheSpecsReadFromThem() throws Exception {
        consumer("classes.from(files('classes'))\nloaderProject = ':loader'\njvmArgs.add('-Xmx1g')\n"
                + "systemProperties.put('a.b', 'c')");

        runner("stagewrightLaunchSpecsFabric").build();

        JsonObject alone = spec("dedicatedServer");
        assertTrue(alone.getBoolean("server", false), "the engine prepares a server's directory");
        assertEquals(null, alone.get("companion"));
        JsonObject primary = alone.get("primary").asObject();
        assertEquals("server", primary.getString("mainClass", null));
        assertEquals("[\"--port\",\"25621\"]", primary.get("args").toString());
        assertEquals(dir.resolve("loader/run-stagewright/fabric/dedicated-server").toString(),
                primary.getString("workingDirectory", null));
        String jvmArgs = primary.get("jvmArgs").toString();
        assertTrue(jvmArgs.contains("-Xmx1g") && jvmArgs.contains("-Da.b=c"), jvmArgs);
        assertFalse(jvmArgs.contains("awaitPlayer"), jvmArgs);

        JsonObject withClient = spec("dedicatedServerWithClient");
        assertEquals(dir.resolve("loader/run-stagewright/fabric/with-client").toString(),
                withClient.getString("gameDirectory", null));
        assertTrue(withClient.get("primary").asObject().get("jvmArgs").toString()
                .contains("-Dstagewright.awaitPlayer=true"));
        JsonObject companion = withClient.get("companion").asObject();
        assertEquals("client", companion.getString("mainClass", null));
        String clientArgs = companion.get("jvmArgs").toString();
        assertTrue(clientArgs.contains("-Dstagewright.client.connect=127.0.0.1:25621") && clientArgs.contains("-Da.b=c"),
                clientArgs);
        assertEquals(dir.resolve("loader/run-stagewright/fabric/joining-client").toString(),
                companion.getString("workingDirectory", null));

        JsonObject integrated = spec("integratedServer");
        assertFalse(integrated.getBoolean("server", true), "a client's directory is not prepared as a server's");
        assertEquals(null, integrated.get("companion"));
        JsonObject host = integrated.get("primary").asObject();
        assertEquals("client", host.getString("mainClass", null));
        String hostArgs = host.get("jvmArgs").toString();
        assertTrue(hostArgs.contains("-Dstagewright.client.world=StageWright")
                && hostArgs.contains("-Dstagewright.awaitPlayer=true") && hostArgs.contains("-Da.b=c"), hostArgs);
        assertEquals(dir.resolve("loader/run-stagewright/fabric/integrated-server").toString(),
                host.getString("workingDirectory", null));

        String runs = runner(":loader:inspectRuns", "-q").build().getOutput();
        for (String name : new String[] {"stagewrightFabricDedicatedServer", "stagewrightFabricDedicatedServerWithClient",
                "stagewrightFabricJoiningClient", "stagewrightFabricIntegratedServer"}) {
            assertTrue(runs.contains("RUN " + name + " ide=false source=testmod"), runs);
        }
    }

    @Test
    void theRunSourceSetAndPortCanBeChosen() throws Exception {
        consumer("classes.from(files('classes'))\nloaderProject = ':loader'\nrunSourceSet = 'main'\nserverPort = 25700");

        runner("stagewrightLaunchSpecsFabric").build();

        assertEquals("[\"--port\",\"25700\"]", spec("dedicatedServer").get("primary").asObject().get("args").toString());
        assertTrue(runner(":loader:inspectRuns", "-q").build().getOutput()
                .contains("RUN stagewrightFabricJoiningClient ide=false source=main"));
    }

    @Test
    void withoutATestmodOneIsCreatedAndItsClassesAreTheSuites() throws Exception {
        consumer("loaderProject = ':loader'", "");
        Files.writeString(dir.resolve("build.gradle"), Files.readString(dir.resolve("build.gradle")) + """
                tasks.register('inspectSuite') {
                    def dirs = tasks.named('stagewrightTestFabric').map { it.testClassesDirs.files }
                    doLast { dirs.get().each { println 'CLASSES ' + it } }
                }
                """);

        String out = runner("inspectSuite", ":loader:inspectRuns", "-q").build().getOutput();
        assertTrue(out.contains("RUN stagewrightFabricDedicatedServer ide=false source=testmod"), out);
        assertTrue(out.contains("CLASSES " + dir.resolve("loader/build/classes/java/testmod")), out);
    }

    @Test
    void aSuiteWithNoGamesFailsSayingWhatItNeeds() throws Exception {
        consumer("classes.from(files('classes'))");

        BuildResult result = runner("stagewrightLaunchSpecsFabric").buildAndFail();
        assertTrue(result.getOutput().contains("set loaderProject"), result.getOutput());
    }

    @Test
    void aLoaderProjectWithoutLoomOrModDevGradleIsRefused() throws Exception {
        consumer("classes.from(files('classes'))\nloaderProject = ':'");

        BuildResult result = runner("help").buildAndFail();
        assertTrue(result.getOutput().contains("applies neither loom"), result.getOutput());
    }
}
