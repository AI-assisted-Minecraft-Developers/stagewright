package net.magicterra.stagewright.gradle;

import java.util.ArrayList;
import java.util.List;

import org.gradle.api.Project;
import org.gradle.api.tasks.JavaExec;
import org.gradle.process.CommandLineArgumentProvider;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

class BuildIdArgumentTest {

    @TempDir
    java.nio.file.Path dir;

    @Test
    void theRunIsHandedAnArgumentThatIsOnlyTakenWhenItStarts() {
        Project project = ProjectBuilder.builder().withProjectDir(dir.toFile()).build();
        project.getPluginManager().apply(StageWrightPlugin.class);
        JavaExec run = project.getTasks().register("runServer", JavaExec.class).get();
        project.getExtensions().getByType(StageWrightExtension.class).getTopologies().create("dedicated",
                t -> t.getRunTask().set("runServer"));
        var gate = project.getTasks().getByName("stagewrightDedicated");
        gate.getTaskDependencies().getDependencies(gate);   // resolves the provider that wires the run

        assertFalse(run.getSystemProperties().containsKey("stagewright.build"),
                "a value fixed while configuring is what a configuration cache hit would replay");
        List<CommandLineArgumentProvider> providers = new ArrayList<>(run.getJvmArgumentProviders());
        assertEquals(1, providers.stream().filter(p -> p instanceof BuildIdArgument).count());
    }

    @Test
    void aRunTaskTwoTopologiesShareCarriesTheArgumentOnce() {
        Project project = ProjectBuilder.builder().withProjectDir(dir.toFile()).build();
        project.getPluginManager().apply(StageWrightPlugin.class);
        JavaExec run = project.getTasks().register("runServer", JavaExec.class).get();
        var topologies = project.getExtensions().getByType(StageWrightExtension.class).getTopologies();
        topologies.create("fabric", t -> t.getRunTask().set("runServer"));
        topologies.create("again", t -> t.getRunTask().set("runServer"));
        for (String name : List.of("stagewrightFabric", "stagewrightAgain", "stagewrightFabric")) {
            var gate = project.getTasks().getByName(name);
            gate.getTaskDependencies().getDependencies(gate);
        }
        assertEquals(1, run.getJvmArgumentProviders().stream().filter(p -> p instanceof BuildIdArgument).count());
    }

    @Test
    void eachRunIsCheckedAgainstWhatItLoadsItselfIncludingWhatItsLoaderBindsLate() throws Exception {
        Project project = ProjectBuilder.builder().withProjectDir(dir.toFile()).build();
        project.getPluginManager().apply(StageWrightPlugin.class);
        JavaExec run = project.getTasks().register("runServer", JavaExec.class).get();
        run.classpath(project.file("server-classes"));
        LateRun client = project.getTasks().register("runClient", LateRun.class).get();
        client.getClasspathProvider().from(project.file("client-classes"));
        client.getInternalEnvironmentVars().put("MOD_CLASSES", "scenes%%" + project.file("scene-classes"));
        project.getExtensions().getByType(StageWrightExtension.class).getTopologies().create("dedicated", t -> {
            t.getRunTask().set("runServer");
            t.getCompanionRunTask().set("runClient");
        });
        var gate = project.getTasks().getByName("stagewrightDedicated");
        gate.getTaskDependencies().getDependencies(gate);

        assertEquals(List.of(project.file("server-classes")), argument(run).loaded());
        assertEquals(List.of(project.file("client-classes"), project.file("scene-classes")),
                argument(client).loaded());
    }

    /** A loader's run task that, like ModDevGradle and loom, stages its classpath and environment late. */
    public abstract static class LateRun extends JavaExec {
        private final org.gradle.api.file.ConfigurableFileCollection late = getProject().files();
        private final org.gradle.api.provider.MapProperty<String, String> env =
                getProject().getObjects().mapProperty(String.class, String.class);

        public org.gradle.api.file.ConfigurableFileCollection getClasspathProvider() {
            return late;
        }

        public org.gradle.api.provider.MapProperty<String, String> getInternalEnvironmentVars() {
            return env;
        }
    }

    @Test
    void aCompanionWiredAfterItsRunSharesTheRunsService() {
        Project project = ProjectBuilder.builder().withProjectDir(dir.toFile()).build();
        project.getPluginManager().apply(StageWrightPlugin.class);
        JavaExec run = project.getTasks().register("runServer", JavaExec.class).get();
        JavaExec client = project.getTasks().register("runClient", JavaExec.class).get();
        var topologies = project.getExtensions().getByType(StageWrightExtension.class).getTopologies();
        topologies.create("plain", t -> t.getRunTask().set("runServer"));
        topologies.create("withClient", t -> {
            t.getRunTask().set("runServer");
            t.getCompanionRunTask().set("runClient");
        });
        for (String name : List.of("stagewrightPlain", "stagewrightWithClient")) {
            var gate = project.getTasks().getByName(name);
            gate.getTaskDependencies().getDependencies(gate);
        }
        // One object, so both sides are checked against the one id the build's first run took.
        assertSame(only(run), only(client));
    }

    @Test
    void aCompanionThatIsAnotherTopologysRunStillSharesItsRunsService() {
        // runClient is wired as integrated's run before dedicated names it as a companion.
        Project project = ProjectBuilder.builder().withProjectDir(dir.toFile()).build();
        project.getPluginManager().apply(StageWrightPlugin.class);
        JavaExec server = project.getTasks().register("runServer", JavaExec.class).get();
        JavaExec client = project.getTasks().register("runClient", JavaExec.class).get();
        var topologies = project.getExtensions().getByType(StageWrightExtension.class).getTopologies();
        topologies.create("plain", t -> t.getRunTask().set("runServer"));
        topologies.create("integrated", t -> t.getRunTask().set("runClient"));
        topologies.create("dedicated", t -> {
            t.getRunTask().set("runServer");
            t.getCompanionRunTask().set("runClient");
        });
        for (String name : List.of("stagewrightPlain", "stagewrightIntegrated", "stagewrightDedicated")) {
            var gate = project.getTasks().getByName(name);
            gate.getTaskDependencies().getDependencies(gate);
        }
        assertSame(only(server), only(client));
    }

    /** The service behind the one build-id argument this task carries: sharing it is what checks every
     *  task against one first id, including across a configuration cache round trip. */
    private static StageWrightBuildIdService only(JavaExec exec) {
        return argument(exec).service().get();
    }

    private static BuildIdArgument argument(JavaExec exec) {
        List<CommandLineArgumentProvider> found = exec.getJvmArgumentProviders().stream()
                .filter(p -> p instanceof BuildIdArgument).toList();
        assertEquals(1, found.size());
        return (BuildIdArgument) found.get(0);
    }
}
