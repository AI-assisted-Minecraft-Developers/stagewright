package net.magicterra.stagewright.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.JavaExec;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What a topology hangs off its run task, and in which order it runs. */
class SideProcessWiringTest {

    @TempDir
    java.nio.file.Path dir;

    private Project project() {
        Project project = ProjectBuilder.builder().withProjectDir(dir.toFile()).build();
        project.getPluginManager().apply(StageWrightPlugin.class);
        // No DISPLAY for either, so a display check refuses; nothing here may start a game.
        project.getTasks().register("runClient", JavaExec.class).get().setEnvironment(Map.of());
        project.getTasks().register("runOtherClient", JavaExec.class).get().setEnvironment(Map.of());
        return project;
    }

    private static void resolve(Project project, String... tasks) {
        for (String name : tasks) {
            Task task = project.getTasks().getByName(name);
            task.getTaskDependencies().getDependencies(task);
        }
    }

    /** The run's first action, which must be the display check refusing it, not a companion starting. */
    private static void refusedFirst(JavaExec run, String topologies) {
        GradleException first = assertThrows(GradleException.class, () -> run.getActions().get(0).execute(run));
        assertTrue(first.getMessage().contains("ENV — topology '" + topologies + "'"), first.getMessage());
    }

    private static void linuxOnly() {
        assumeTrue(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux"));
    }

    @Test
    void aClientRunIsRefusedBeforeItsCompanionStartsAndOnlyOnce() {
        linuxOnly();
        Project project = project();
        JavaExec run = (JavaExec) project.getTasks().getByName("runClient");
        project.getExtensions().getByType(StageWrightExtension.class).getTopologies().create("pair", t -> {
            t.getRunTask().set("runClient");
            t.getCompanionRunTask().set("runOtherClient");
            t.getClient().set(true);
        });
        int own = run.getActions().size();

        resolve(project, "stagewrightPair", "stagewrightPair");

        assertEquals(own + 1, run.getActions().size());
        assertEquals(Set.of(":runOtherClient"), RunSideProcesses.of(run).companionPaths());
        refusedFirst(run, "pair");
    }

    @Test
    void twoTopologiesOnOneRunAndCompanionStartItOnce() {
        Project project = project();
        JavaExec run = (JavaExec) project.getTasks().getByName("runClient");
        var topologies = project.getExtensions().getByType(StageWrightExtension.class).getTopologies();
        for (String name : new String[] {"pair", "strict"}) {
            topologies.create(name, t -> {
                t.getRunTask().set("runClient");
                t.getCompanionRunTask().set("runOtherClient");
                t.getClient().set(true);
            });
        }
        int own = run.getActions().size();

        resolve(project, "stagewrightPair", "stagewrightStrict");

        assertEquals(own + 1, run.getActions().size());
        assertEquals(Set.of(":runOtherClient"), RunSideProcesses.of(run).companionPaths());
    }

    @Test
    void theDisplayIsCheckedFirstWhicheverTopologyResolvesFirst() {
        linuxOnly();
        for (List<String> order : List.of(List.of("stagewrightScreen", "stagewrightPair"),
                                          List.of("stagewrightPair", "stagewrightScreen"))) {
            Project project = project();
            JavaExec run = (JavaExec) project.getTasks().getByName("runClient");
            var topologies = project.getExtensions().getByType(StageWrightExtension.class).getTopologies();
            topologies.create("screen", t -> {
                t.getRunTask().set("runClient");
                t.getClient().set(true);
            });
            topologies.create("pair", t -> {
                t.getRunTask().set("runClient");
                t.getCompanionRunTask().set("runOtherClient");
            });
            int own = run.getActions().size();

            resolve(project, order.toArray(String[]::new));

            assertEquals(own + 1, run.getActions().size(), order.toString());
            assertEquals(Set.of(":runOtherClient"), RunSideProcesses.of(run).companionPaths(), order.toString());
            refusedFirst(run, "screen");
        }
    }

    /** ModDevGradle's run task: part of the game's environment is a property read at launch. */
    public abstract static class EnvironmentPropertyRun extends JavaExec {
        @Internal
        public abstract MapProperty<String, String> getEnvironmentProperty();
    }

    /** Loom's run task, which binds its own share late under another name, behind a protected getter. */
    public abstract static class InternalEnvironmentRun extends JavaExec {
        @Internal
        protected abstract MapProperty<String, Object> getInternalEnvironmentVars();
    }

    private static final List<Class<? extends JavaExec>> LOADER_RUNS =
            List.of(EnvironmentPropertyRun.class, InternalEnvironmentRun.class);

    /** A loader's run task with {@code own} as its environment and {@code late} bound the loader's way. */
    private JavaExec loaderRun(Class<? extends JavaExec> type, Map<String, String> own, Map<String, String> late) {
        Project project = ProjectBuilder.builder().withProjectDir(dir.resolve(type.getSimpleName()).toFile()).build();
        JavaExec run = project.getTasks().register("runClient", type).get();
        run.setEnvironment(own);
        if (run instanceof EnvironmentPropertyRun e) e.getEnvironmentProperty().putAll(late);
        else ((InternalEnvironmentRun) run).getInternalEnvironmentVars().putAll(late);
        return run;
    }

    @Test
    void theDisplayCheckSeesTheEnvironmentTheLoaderBindsLateOverTheTasksOwn() {
        linuxOnly();
        for (Class<? extends JavaExec> type : LOADER_RUNS) {
            // Blank in the task's own environment, which alone would be refused; the loader's value
            // is what the game gets. A remote display is not asked, so it passes only if that is read.
            JavaExec run = loaderRun(type, Map.of("DISPLAY", ""), Map.of("DISPLAY", "localhost:10.0"));
            Project project = run.getProject();
            project.getPluginManager().apply(StageWrightPlugin.class);
            project.getExtensions().getByType(StageWrightExtension.class).getTopologies().create("screen", t -> {
                t.getRunTask().set("runClient");
                t.getClient().set(true);
            });

            resolve(project, "stagewrightScreen");

            run.getActions().get(0).execute(run);
        }
    }

    @Test
    void aCompanionGetsItsTasksEnvironmentAndNothingElse() {
        for (Class<? extends JavaExec> type : LOADER_RUNS) {
            JavaExec companion = loaderRun(type, Map.of("DISPLAY", "", "XAUTHORITY", "/tasks/own"),
                                           Map.of("DISPLAY", "localhost:10.0", "MOD_CLASSES", "late"));
            ProcessBuilder builder = new ProcessBuilder("true");
            builder.environment().put("WAYLAND_DISPLAY", "only-in-this-daemon");

            SideProcesses.useEnvironmentOf(companion, builder);

            assertEquals(Map.of("DISPLAY", "localhost:10.0", "XAUTHORITY", "/tasks/own", "MOD_CLASSES", "late"),
                         builder.environment(), type.getSimpleName());
        }
    }

    @Test
    void aHoldAnnouncesItselfOnceAndAfterTheDisplayCheck() {
        linuxOnly();
        Project project = project();
        JavaExec run = (JavaExec) project.getTasks().getByName("runClient");
        project.getExtensions().getByType(StageWrightExtension.class).getTopologies().create("pair", t -> {
            t.getRunTask().set("runClient");
            t.getCompanionRunTask().set("runOtherClient");
            t.getCompanionResultsFile().set(dir.resolve("client/stagewright/results.jsonl").toFile());
            t.getClient().set(true);
        });
        int own = run.getActions().size();

        resolve(project, "stagewrightPairHold", "stagewrightPairHold");

        assertEquals(own + 1, run.getActions().size());
        // The run's own banner and the held companion's, each once.
        assertEquals(2, RunSideProcesses.of(run).banners().size(), RunSideProcesses.of(run).banners().toString());
        refusedFirst(run, "pair");
    }
}
