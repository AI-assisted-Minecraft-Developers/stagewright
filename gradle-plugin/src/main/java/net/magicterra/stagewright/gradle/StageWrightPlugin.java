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

import net.magicterra.stagewright.engine.RunDirectory;

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
    static final String DEFAULT_RESULTS = RunDirectory.DEFAULT_RESULTS_FILE;
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

        // Registered once and fed by every topology below. It has to exist before the container is
        // iterated so that a topology added later still lands in it — the alternative, building the
        // map in afterEvaluate, would silently reconcile fewer runs than the build declares, which is
        // the exact failure this task exists to catch.
        TaskProvider<StageWrightCoverageTask> coverage = project.getTasks().register(
                "stagewrightCoverage", StageWrightCoverageTask.class, task -> {
                    task.setGroup(TASK_GROUP);
                    task.setDescription("Reconciles every topology's results: RED if a scene this"
                            + " suite registers executed in none of them. Run the topologies first.");
                });

        topologies.all(topology -> {
            topology.getResultsFile().convention(DEFAULT_RESULTS);
            topology.getCleanWorld().convention(true);
            topology.getTimeoutMinutes().convention(DEFAULT_TIMEOUT_MINUTES);
            topology.getVirtualDisplay().convention(false);
            topology.getGameDirectory().convention(project.getLayout().getProjectDirectory()
                    .dir("run-stagewright-" + topology.getName()));
            registerTopologyTasks(project, topology, sideProcesses);
            coverage.configure(task -> task.getResultsByTopology().put(topology.getName(),
                    topology.getGameDirectory().file(topology.getResultsFile())));
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
                    task.getInstallMods().from(topology.getInstallMods());
                    task.getCompanionResultsFile().set(topology.getCompanionResultsFile());
                });

        // Resolved after evaluation and stored as a plain File, not read lazily from the companion
        // task. The lazy version worked and then failed the configuration cache: a provider that
        // calls back into the task graph captures a Task, and Gradle refuses to serialize one. The
        // directory is a value; the task is not.
        project.afterEvaluate(evaluated -> {
            File companionDir = companionRunDirectory(evaluated, topology);
            if (companionDir != null) {
                provision.configure(task -> task.getCompanionGameDirectory().set(companionDir));
            }
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
                applyResultsName(topology, run);
                applySceneFilter(project, run);
                attachSideProcesses(project, topology, run, sideProcesses);
                return run;
            }));

            // A gate re-runs every invocation. Its input is a file the run rewrites, so letting
            // Gradle call it up to date would report the previous run's verdict for a run that never
            // happened.
            task.getOutputs().upToDateWhen(t -> false);
        });

        registerHoldTask(project, topology, suffix, provision, sideProcesses);
    }

    /** The property the game reads to know where to publish it; see {@code EndpointDescriptor}. */
    static final String ENDPOINT_PROPERTY = "stagewright.endpoint";

    /**
     * The property that puts a run into hold mode: arm everything, run nothing, close nothing.
     *
     * <p>Deliberately not {@code -Dstagewright.autorun=false}. Autorun and exitWhenDone are set by
     * the host build's run configuration, so overriding them here puts two {@code -D}s for one key
     * on one command line and makes the outcome depend on which the JVM reads last. That is not a
     * theoretical risk: the first version of this task did exactly that, and the held game ran all
     * 190 scenes underneath the tests that had attached to it.
     */
    static final String HOLD_PROPERTY = "stagewright.hold";

    /** Recorded in the descriptor so an attached test knows which face it is holding. */
    static final String TOPOLOGY_PROPERTY = "stagewright.topology";

    /**
     * Register {@code stagewright<Name>Hold} — the same topology, standing still.
     *
     * <p>A hold is this topology's run with three properties flipped: the suite does not autorun,
     * the client does not close itself when it has nothing left to do, and the game publishes a
     * {@code TESTKIT_ENDPOINT} descriptor once it is genuinely attachable. What it exists for is
     * everything that has to assert from OUTSIDE the game — {@code stagewright-junit}'s UI tests,
     * an interactive session, a bare-RPC contract suite that must not run through the harness it is
     * checking. Those cannot be scenes: a scene body runs inside the very runtime under test.
     *
     * <p>It ends when you stop it (Ctrl-C), which is not how a gate behaves and is the point — a
     * gate's verdict is "the suite finished"; a hold's verdict belongs to whatever attached to it.
     * So this task has no results file, no judging, and never appears in a gate's dependency graph.
     */
    private void registerHoldTask(Project project, StageWrightTopology topology, String suffix,
                                  TaskProvider<StageWrightProvisionTask> provision,
                                  org.gradle.api.provider.Provider<StageWrightSideProcessService> sideProcesses) {
        project.getTasks().register("stagewright" + suffix + "Hold", task -> {
            task.setGroup(TASK_GROUP);
            task.setDescription("Stands the " + topology.getName() + " topology up and holds it open,"
                    + " publishing a TESTKIT_ENDPOINT descriptor for out-of-process tests to attach to.");
            task.dependsOn(provision);
            task.getOutputs().upToDateWhen(t -> false);

            // Same lazy-resolution shape as the gate above, and for the same reason: the run task
            // belongs to the host's loader plugin and does not exist while this one is being
            // registered. Configuring it in here rather than at registration also means an ordinary
            // gate invocation never sees these properties — the run task is only rewritten when a
            // hold is actually in the graph.
            task.dependsOn(project.provider(() -> {
                String runTaskName = topology.getRunTask().getOrNull();
                if (runTaskName == null) {
                    throw new GradleException("stagewright topology '" + topology.getName()
                            + "' declares no runTask");
                }
                Task run = resolveRunTask(project, runTaskName);
                if (run == null) {
                    throw new GradleException("stagewright topology '" + topology.getName()
                            + "' points at run task '" + runTaskName + "', which "
                            + project.getPath() + " does not declare");
                }
                if (!(run instanceof JavaExec exec)) {
                    throw new GradleException("stagewright topology '" + topology.getName()
                            + "' cannot be held: run task '" + runTaskName + "' is a "
                            + run.getClass().getSimpleName() + " rather than a JavaExec, so there is"
                            + " no JVM to pass the hold properties to");
                }
                run.mustRunAfter(provision);
                // No timeout. The gate's exists because a suite that never drains has to be killed
                // by something; a hold is supposed to outlast the build's patience.
                exec.systemProperty(HOLD_PROPERTY, "true");
                exec.systemProperty(TOPOLOGY_PROPERTY, topology.getName());
                File endpoint = new File(topology.getGameDirectory().get().getAsFile(),
                        RunDirectory.ENDPOINT_FILE);
                exec.systemProperty(ENDPOINT_PROPERTY, endpoint.getAbsolutePath());
                holdCompanion(project, topology);
                attachSideProcesses(project, topology, run, sideProcesses);
                run.doFirst(t -> t.getLogger().lifecycle(
                        "[stagewright] holding '{}' — attach with TESTKIT_ENDPOINT={}\n"
                        + "[stagewright] the descriptor appears once the game is in a world; Ctrl-C ends the hold",
                        topology.getName(), endpoint.getAbsolutePath()));
                return run;
            }));
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
    /**
     * Tell the game which results file to WRITE, when a topology asked for a name that is not the
     * convention.
     *
     * <p>{@code resultsFile} used to rename only what the verdict OPENED — the harness went on
     * writing {@code stagewright-results.jsonl} because nothing carried the name into the game — so
     * a renamed topology produced a complete green run and a verdict of "no results", pointed at a
     * path nothing was ever going to write.
     *
     * <p>Conditional, so a topology on the convention produces a byte-identical command line to the
     * one it produced before this existed, and a run task that is not a {@code JavaExec} keeps
     * running rather than failing over a property it has nowhere to put.
     */
    private void applyResultsName(StageWrightTopology topology, Task run) {
        String name = topology.getResultsFile().getOrElse(DEFAULT_RESULTS);
        if (DEFAULT_RESULTS.equals(name)) return;
        if (!(run instanceof JavaExec exec)) {
            run.getLogger().warn("[stagewright] resultsFile '{}' cannot reach the game: run task"
                    + " '{}' is a {}, not a JavaExec, so the harness will write {} instead",
                    name, run.getName(), run.getClass().getSimpleName(), DEFAULT_RESULTS);
            return;
        }
        exec.systemProperty(RunDirectory.RESULTS_PROPERTY, name);
    }

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

    /**
     * Hold the companion too, and give it its own descriptor.
     *
     * <p>On the two-process topology the halves face opposite ways: the run task is the dedicated
     * server, so its descriptor is the server face, and the client — the only place a UI test can
     * assert anything — is the companion. Without this the companion would close itself the moment
     * the server it joined stopped having a suite to run, which under a hold is immediately.
     *
     * <p>The companion's descriptor lands beside its results file, the one piece of the companion's
     * run directory this plugin is told about. A topology that declares no companion results has no
     * second endpoint; that is a topology whose client is not addressable, not a failure.
     */
    /** Where a topology's companion client runs, or null if it has none — see the provision task's
     *  {@code getCompanionGameDirectory} for why this is derived rather than declared. */
    private File companionRunDirectory(Project project, StageWrightTopology topology) {
        String companionName = topology.getCompanionRunTask().getOrNull();
        if (companionName == null) return null;
        return resolveRunTask(project, companionName) instanceof JavaExec companion
                ? SideProcesses.runDirectoryOf(companion)
                : null;
    }

    private void holdCompanion(Project project, StageWrightTopology topology) {
        String companionName = topology.getCompanionRunTask().getOrNull();
        if (companionName == null) return;
        if (!(resolveRunTask(project, companionName) instanceof JavaExec companion)) return;
        companion.systemProperty(HOLD_PROPERTY, "true");
        companion.systemProperty(TOPOLOGY_PROPERTY, topology.getName() + "-client");
        File results = topology.getCompanionResultsFile().map(f -> f.getAsFile()).getOrNull();
        if (results == null || results.getParentFile() == null) return;
        File endpoint = new File(results.getParentFile(), RunDirectory.ENDPOINT_FILE);
        companion.systemProperty(ENDPOINT_PROPERTY, endpoint.getAbsolutePath());
        project.getLogger().lifecycle("[stagewright] companion held — client face at TESTKIT_ENDPOINT={}",
                endpoint.getAbsolutePath());
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

        // Declared, not discovered. Starting a companion means reading another run task's command
        // line at execution time, so this task action holds a Task — which the configuration cache
        // cannot serialize. Left undeclared, a project that enables the cache fails with Gradle's
        // own message naming the LOADER's run task class and nothing about companions, on a build
        // that is otherwise correct and whose verdict already printed GREEN. Saying so here costs
        // that one task its caching and keeps the failure from being someone else's mystery.
        //
        // The alternative is to capture the whole command as values at configuration time and start
        // the companion from those. That is the right end state and a bigger change than declaring
        // this, because several of the pieces are staged by the loader plugin and only final once
        // its own task action has run — which is the same fact that makes MOD_CLASSES late-bound.
        run.notCompatibleWithConfigurationCache("stagewright starts a companion run from another"
                + " task's JavaExec spec, which cannot be serialized into the configuration cache");

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
