package net.magicterra.stagewright.gradle;

import java.io.File;
import java.util.Locale;

import org.gradle.api.GradleException;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;

/**
 * Registers the {@code stagewright { ... }} block and, per declared topology, a
 * {@code stagewright<Name>} task that provisions a clean run directory, runs the host build's own
 * run task, and judges the results.
 *
 * <p><b>What this plugin deliberately does not do: start the game.</b> It used to shell a Python
 * orchestrator which itself shelled {@code ./gradlew}, so one gate ran Gradle inside Gradle — two
 * daemons, the outer holding a lock while the inner built, and a process tree that had to be killed
 * by pattern differently on each platform. Most of that Python was not orchestration; it was the
 * cost of a process boundary that never needed crossing. Here the run is an ordinary task
 * dependency, so Gradle supervises a process it always knew how to supervise, and the platform
 * differences — path separators, how to kill a child, wrapper script versus batch file — go back to
 * being Gradle's problem.
 *
 * <p>This works because the suite ends itself: the harness halts the server once its registry
 * drains, so "the run task returned" is a real completion signal rather than a timeout.
 */
public class StageWrightPlugin implements Plugin<Project> {

    static final String TASK_GROUP = "verification";
    static final String DEFAULT_RESULTS = "stagewright-results.jsonl";
    static final int DEFAULT_TIMEOUT_MINUTES = 20;

    @Override
    public void apply(Project project) {
        NamedDomainObjectContainer<StageWrightTopology> topologies =
                project.getObjects().domainObjectContainer(StageWrightTopology.class,
                        name -> project.getObjects().newInstance(StageWrightTopology.class, name));

        StageWrightExtension ext = project.getExtensions()
                .create("stagewright", StageWrightExtension.class, topologies);
        ext.getTestmodSourceSet().convention(false);

        // A reaction, not a point-in-time check: this fires whether `java` was applied before or
        // after this plugin, and never fires at all for a non-java consumer — so the flag being on
        // in such a build is a no-op rather than a crash.
        project.getPlugins().withType(JavaPlugin.class, java ->
                project.afterEvaluate(p -> {
                    if (Boolean.TRUE.equals(ext.getTestmodSourceSet().getOrElse(false))) {
                        registerTestmodSourceSet(p, ext);
                    }
                }));

        // One service per build, shared by every topology. Gradle closes it on every exit path, which
        // is what makes "the companion client is always killed" a guarantee rather than a hope.
        var sideProcesses = project.getGradle().getSharedServices().registerIfAbsent(
                "stagewrightSideProcesses", StageWrightSideProcessService.class, spec -> {});

        topologies.all(topology -> {
            topology.getResultsFile().convention(DEFAULT_RESULTS);
            topology.getCleanWorld().convention(true);
            topology.getTimeoutMinutes().convention(DEFAULT_TIMEOUT_MINUTES);
            topology.getVirtualDisplay().convention(false);
            topology.getGameDirectory().convention(project.getLayout().getProjectDirectory()
                    .dir("run-stagewright-" + topology.getName()));
            registerTopologyTasks(project, topology, sideProcesses);
        });
    }

    private void registerTopologyTasks(Project project, StageWrightTopology topology,
                                       org.gradle.api.provider.Provider<StageWrightSideProcessService> sideProcesses) {
        String suffix = capitalize(topology.getName());

        TaskProvider<StageWrightProvisionTask> provision = project.getTasks().register(
                "stagewright" + suffix + "Provision", StageWrightProvisionTask.class, task -> {
                    task.setGroup(TASK_GROUP);
                    task.setDescription("Clears the " + topology.getName()
                            + " run directory so the next scene run cannot inherit its world.");
                    task.getGameDirectory().set(topology.getGameDirectory());
                    task.getResultsFile().set(topology.getResultsFile());
                    task.getCleanWorld().set(topology.getCleanWorld());
                    task.getSceneScripts().set(topology.getSceneScripts());
                });

        project.getTasks().register("stagewright" + suffix, StageWrightVerdictTask.class, task -> {
            task.setGroup(TASK_GROUP);
            task.setDescription("Runs the " + topology.getName()
                    + " scene topology and judges its results against the orchestration contract.");
            task.getTopologyName().set(topology.getName());
            task.getResults().set(topology.getGameDirectory().file(topology.getResultsFile()));
            task.getExpectFile().set(topology.getExpectFile());
            task.getCompanionResults().set(topology.getCompanionResultsFile());
            task.dependsOn(provision);

            // Resolved lazily. The host loader plugin normally registers its run tasks after this
            // plugin is applied, so naming one eagerly would fail on a task that does not exist yet.
            task.dependsOn(project.provider(() -> {
                String runTaskName = topology.getRunTask().getOrNull();
                if (runTaskName == null) {
                    throw new GradleException("stagewright topology '" + topology.getName()
                            + "' declares no runTask");
                }
                Task run = resolveRunTask(project, runTaskName);
                if (run == null) {
                    throw new GradleException("stagewright topology '" + topology.getName()
                            + "' points at run task '" + runTaskName
                            + "', which " + project.getPath() + " does not declare"
                            + " — a task in another project needs its full path,"
                            + " e.g. ':neoforge:runDogfoodServer'");
                }
                // The world must be gone BEFORE the game opens it. Without this the two tasks are
                // merely both-required and unordered, so provisioning can land after the run and
                // tidy up for a run that already read the dirty world.
                run.mustRunAfter(provision);
                run.getTimeout().set(java.time.Duration.ofMinutes(
                        topology.getTimeoutMinutes().getOrElse(DEFAULT_TIMEOUT_MINUTES)));
                applySceneFilter(project, run);
                attachSideProcesses(project, topology, run, sideProcesses);
                return run;
            }));

            // A gate re-runs every invocation. Its input is a file the run rewrites, so letting
            // Gradle call it up to date would report the previous run's verdict for a run that never
            // happened.
            task.getOutputs().upToDateWhen(t -> false);
        });
    }

    /**
     * Hang the display and the companion off the run task itself.
     *
     * <p>{@code doFirst} rather than a task of their own, because both have to be live for the
     * duration of THIS process and Gradle runs tasks in a project's graph one after another — a
     * companion started by an earlier task would have to outlive its own task, which is exactly the
     * ownership problem that produced orphaned game JVMs in the first place. Started here, owned by
     * the build service, closed by Gradle whatever happens.
     *
     * <p>The companion's own task dependencies are added to the run, not the companion task itself:
     * ModDevGradle and loom both generate argfiles from those tasks, and the companion cannot start
     * without them — but running the companion TASK would block, since Gradle would wait for the
     * game to exit before starting the one it is supposed to run alongside.
     *
     * <p>Note for later: reading another task's state from an execution-time action is not
     * configuration-cache friendly. Only the topologies that declare a companion are affected; the
     * plain server topology stays cacheable.
     */
    /** The property the game reads to narrow a run; see {@code SceneFilter}. */
    static final String SCENE_FILTER_PROPERTY = "stagewright.scenes";

    /**
     * Forward {@code -Pstagewright.scenes=<patterns>} onto the run JVM as a system property, so a
     * developer iterating on one scene does not pay for the whole suite.
     *
     * <p>Only applied when the property is actually set, so an ordinary gate invocation produces a
     * byte-identical command line to the one it produced before this existed. The run task belongs
     * to the host build (loom / ModDevGradle), so this reaches in and sets a property on it — which
     * is safe precisely because it is conditional: nothing is mutated on the normal path.
     *
     * <p>A run task that is not a {@code JavaExec} is left alone rather than failed. The filter is a
     * convenience, and a host that models its run differently should lose the convenience, not the
     * ability to run its gates.
     */
    private void applySceneFilter(Project project, Task run) {
        Object raw = project.findProperty(SCENE_FILTER_PROPERTY);
        String patterns = raw == null ? null : raw.toString().trim();
        if (patterns == null || patterns.isEmpty()) return;
        if (!(run instanceof JavaExec exec)) {
            run.getLogger().warn("[stagewright] -P{}={} ignored: run task '{}' is a {}, not a"
                    + " JavaExec, so there is no JVM to pass it to",
                    SCENE_FILTER_PROPERTY, patterns, run.getName(), run.getClass().getSimpleName());
            return;
        }
        exec.systemProperty(SCENE_FILTER_PROPERTY, patterns);
        run.getLogger().lifecycle("[stagewright] FILTERED to '{}' — this run is NOT a gate result",
                patterns);
    }

    private void attachSideProcesses(Project project, StageWrightTopology topology, Task run,
                                     org.gradle.api.provider.Provider<StageWrightSideProcessService> sideProcesses) {
        String companionName = topology.getCompanionRunTask().getOrNull();
        boolean wantsDisplay = Boolean.TRUE.equals(topology.getVirtualDisplay().getOrElse(false));
        if (companionName == null && !wantsDisplay) return;

        JavaExec companion = null;
        if (companionName != null) {
            Task resolved = resolveRunTask(project, companionName);
            if (!(resolved instanceof JavaExec exec)) {
                throw new GradleException("stagewright topology '" + topology.getName()
                        + "' names companionRunTask '" + companionName + "', which is "
                        + (resolved == null ? "not a task in this build"
                                            : "a " + resolved.getClass().getSimpleName()
                                              + " rather than a JavaExec — a companion has to be a"
                                              + " dev run this plugin can copy a command line from"));
            }
            companion = exec;
            run.dependsOn(companion.getDependsOn());
            // ...and on whatever produces its inputs. A run task's classpath is a file collection
            // carrying its own build dependencies, which are not in dependsOn — so a companion
            // launched without this can be handed a path to a jar nothing has built yet.
            run.dependsOn(companion.getInputs().getFiles());
        }

        JavaExec companionSpec = companion;
        File companionLog = new File(topology.getGameDirectory().get().getAsFile(),
                "companion-" + (companionName == null ? "none" : companionName.replace(':', '-')) + ".log");
        run.doFirst(t -> {
            StageWrightSideProcessService service = sideProcesses.get();
            String display = wantsDisplay ? service.ensureVirtualDisplay(t.getLogger()) : null;
            if (display != null && t instanceof JavaExec exec) {
                exec.environment("DISPLAY", display);
            }
            if (companionSpec != null) {
                companionLog.getParentFile().mkdirs();
                service.startCompanion(companionSpec, companionLog, display, t.getLogger());
            }
        });
    }

    /**
     * Find the run task, by plain name in this project or by full path in another.
     *
     * <p>Path support is not a convenience. Under loom and ModDevGradle alike, a multi-loader build
     * declares its runs on the per-loader SUBprojects while the thing a developer wants to type
     * (`./gradlew stagewrightServer`) belongs on the root — so a plugin that could only see its own
     * project would force every multi-loader consumer to apply it once per loader and then remember
     * which of the near-identical task names it wanted.
     */
    private static Task resolveRunTask(Project project, String runTaskName) {
        int lastColon = runTaskName.lastIndexOf(':');
        if (lastColon < 0) {
            return project.getTasks().findByName(runTaskName);
        }
        String projectPath = lastColon == 0 ? ":" : runTaskName.substring(0, lastColon);
        String taskName = runTaskName.substring(lastColon + 1);
        Project owner = project.getRootProject().findProject(projectPath);
        return owner == null ? null : owner.getTasks().findByName(taskName);
    }

    /** v1 boundary: classpath wiring only — testmod sees main's output plus main's own compile and
     *  runtime classpaths, and nothing else changes. */
    private void registerTestmodSourceSet(Project project, StageWrightExtension ext) {
        SourceSetContainer sourceSets =
                project.getExtensions().getByType(JavaPluginExtension.class).getSourceSets();
        SourceSet main = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        SourceSet testmod = sourceSets.maybeCreate("testmod");

        testmod.setCompileClasspath(testmod.getCompileClasspath()
                .plus(main.getOutput())
                .plus(project.getConfigurations().getByName(main.getCompileClasspathConfigurationName())));
        testmod.setRuntimeClasspath(testmod.getRuntimeClasspath()
                .plus(main.getOutput())
                .plus(project.getConfigurations().getByName(main.getRuntimeClasspathConfigurationName())));

        ext.setTestmodSourceSetRef(testmod);
    }

    private static String capitalize(String s) {
        if (s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }
}
