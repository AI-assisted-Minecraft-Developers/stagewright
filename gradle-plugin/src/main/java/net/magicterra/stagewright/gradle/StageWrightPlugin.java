package net.magicterra.stagewright.gradle;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.gradle.api.artifacts.Configuration;
import org.gradle.api.tasks.testing.Test;

import org.gradle.api.GradleException;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Provider;
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
    static final int DEFAULT_STALL_MINUTES = 5;

    @Override
    public void apply(Project project) {
        NamedDomainObjectContainer<StageWrightTopology> topologies =
                project.getObjects().domainObjectContainer(StageWrightTopology.class,
                        name -> project.getObjects().newInstance(StageWrightTopology.class, name));

        NamedDomainObjectContainer<StageWrightTestSuite> tests =
                project.getObjects().domainObjectContainer(StageWrightTestSuite.class,
                        name -> project.getObjects().newInstance(StageWrightTestSuite.class, name));

        StageWrightExtension ext = project.getExtensions()
                .create("stagewright", StageWrightExtension.class, topologies, tests);
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
        var supervision = project.getGradle().getSharedServices().registerIfAbsent(
                "stagewrightSupervision", StageWrightSupervisionService.class, spec -> {});

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
            topology.getClient().convention(false);
            topology.getStallMinutes().convention(DEFAULT_STALL_MINUTES);
            topology.getFailOnLeak().convention(false);
            topology.getGameDirectory().convention(project.getLayout().getProjectDirectory()
                    .dir("run-stagewright-" + topology.getName()));
            registerTopologyTasks(project, topology, sideProcesses, supervision);
            coverage.configure(task -> {
                task.getResultsByTopology().put(topology.getName(),
                        topology.getGameDirectory().file(topology.getResultsFile()));
                // orElse: an absent provider put into a MapProperty would empty the whole map.
                task.getCompanionResultsByTopology().putAll(topology.getCompanionResultsFile()
                        .map(f -> Map.of(companionLabel(topology), f)).orElse(Map.of()));
            });
        });

        tests.all(suite -> {
            suite.getServerPort().convention(GeneratedRuns.DEFAULT_SERVER_PORT);
            suite.getTimeoutMinutes().convention(DEFAULT_TIMEOUT_MINUTES);
            // A single-loader project is its own loader; null leaves it unset elsewhere.
            suite.getLoaderProject().convention(project.provider(() ->
                    project.getExtensions().findByName("loom") != null ? project.getPath() : null));
            suite.getIncludeWorldDriver().convention(true);
            suite.getTestDependencies().convention(true);
            // The tests are the run source set's, unless the build points elsewhere, as a multi-loader one does.
            suite.getClasses().convention(project.provider(() -> GeneratedRuns.generates(suite)
                    ? GeneratedRuns.sourceSet(GeneratedRuns.loader(project, suite), suite).getOutput()
                    : project.files()));
            suite.getClasspath().convention(project.provider(() -> GeneratedRuns.generates(suite)
                    ? GeneratedRuns.sourceSet(GeneratedRuns.loader(project, suite), suite).getCompileClasspath()
                    : project.files()));
            registerTestSuiteTasks(project, suite, topologies);
        });
        // Once this build script has set the suites up; each loader project's runs follow its own evaluation.
        project.afterEvaluate(p -> tests.forEach(suite -> {
            if (GeneratedRuns.generates(suite)) GeneratedRuns.declare(p, suite);
        }));
        DevJUnitRuntime.install(project, tests, topologies);
        SuiteDependencies.install(project, tests);
    }

    static final String TEST_ENGINE_CONFIGURATION = "stagewrightTestEngine";
    static final String LAUNCHES_PROPERTY = "stagewright.launches";
    static final String TEST_ROOTS_PROPERTY = "stagewright.testRoots";

    /**
     * Per suite: {@code stagewrightLaunchSpecs<Name>}, which writes one launch spec per topology the
     * suite maps, and {@code stagewrightTest<Name>}, a {@code Test} task that runs the outer engine
     * against them. Not hooked into {@code check}: it starts games.
     */
    private void registerTestSuiteTasks(Project project, StageWrightTestSuite suite,
                                        NamedDomainObjectContainer<StageWrightTopology> topologies) {
        String suffix = capitalize(suite.getName());
        var launchesDir = project.getLayout().getBuildDirectory()
                .dir("stagewright/launches/" + suite.getName());

        Configuration engine = project.getConfigurations().findByName(TEST_ENGINE_CONFIGURATION);
        if (engine == null) {
            engine = project.getConfigurations().create(TEST_ENGINE_CONFIGURATION, c -> {
                c.setCanBeConsumed(false);
                c.setCanBeResolved(true);
                c.setDescription("The StageWright test engine, at the version of this plugin.");
                c.getDependencies().add(project.getDependencies().create(
                        "net.magicterra:stagewright-testengine:" + PluginVersion.get()));
            });
        }
        Configuration engineConfiguration = engine;

        TaskProvider<StageWrightLaunchSpecsTask> specs = project.getTasks().register(
                "stagewrightLaunchSpecs" + suffix, StageWrightLaunchSpecsTask.class, task -> {
                    task.setGroup(TASK_GROUP);
                    task.setDescription("Writes the launch specs the " + suite.getName()
                            + " test suite's games are started from.");
                    task.getLaunchesDirectory().set(launchesDir);
                    task.getEntries().set(project.provider(() -> launchEntries(project, suite, topologies)));
                    // Resolved when the graph is built: the host's loader plugin registers its run
                    // tasks after this one is applied. Both runs' own prerequisites, as for a companion.
                    task.dependsOn(project.provider(() -> {
                        List<Object> needs = new ArrayList<>();
                        for (JavaExec run : launchRuns(project, suite, topologies)) {
                            // A bare task name in a run's dependsOn means a task of the run's project, which
                            // need not be this one ('configureLaunch' under loom), so it is qualified here.
                            for (Object need : run.getDependsOn()) {
                                needs.add(need instanceof CharSequence name && !name.toString().startsWith(":")
                                        ? run.getProject().getPath().replaceAll(":$", "") + ":" + name
                                        : need);
                            }
                            needs.add(run.getInputs().getFiles());
                        }
                        return needs;
                    }));
                });

        project.getTasks().register("stagewrightTest" + suffix, Test.class, task -> {
                    task.setGroup(TASK_GROUP);
                    task.setDescription("Runs the " + suite.getName()
                            + " test suite on the StageWright test engine, starting a game per scene.");
                    task.useJUnitPlatform(options -> options.includeEngines("stagewright"));
                    task.setTestClassesDirs(suite.getClasses());
                    // The engine first: the suite's classpath may carry another copy of what it depends on.
                    task.setClasspath(engineConfiguration.plus(suite.getClasses()).plus(suite.getClasspath()));
                    task.dependsOn(specs);
                    task.systemProperty(LAUNCHES_PROPERTY, launchesDir.get().getAsFile().getAbsolutePath());
                    // Gradle selects classes only; the engine finds the script tests under these itself.
                    task.getJvmArgumentProviders().add(() -> List.of("-D" + TEST_ROOTS_PROPERTY + "="
                            + suite.getClasses().getAsPath()));
                    task.getOutputs().upToDateWhen(t -> false);
                    // Set here, not left to the java plugin's conventions: the suite may live on a project without it.
                    var build = project.getLayout().getBuildDirectory();
                    task.getBinaryResultsDirectory().set(build.dir("test-results/" + task.getName() + "/binary"));
                    task.getReports().getJunitXml().getOutputLocation().set(build.dir("test-results/" + task.getName()));
                    task.getReports().getHtml().getOutputLocation().set(build.dir("reports/tests/" + task.getName()));
                });
    }

    /** The declared topology for each scene-facing name, or a failure naming the suite and the topology. */
    private static StageWrightTopology declared(StageWrightTestSuite suite,
                                                NamedDomainObjectContainer<StageWrightTopology> topologies,
                                                String sceneName, String declaredName) {
        StageWrightTopology topology = topologies.findByName(declaredName);
        if (topology == null) {
            throw new GradleException("stagewright test suite '" + suite.getName() + "' maps topology '"
                    + sceneName + "' to '" + declaredName + "', which is not declared in stagewright.topologies"
                    + " (declared: " + String.join(", ", topologies.getNames()) + ")");
        }
        return topology;
    }

    private static JavaExec javaExec(Project project, StageWrightTestSuite suite, String topology,
                                     String taskName, String role) {
        Task task = resolveRunTask(project, taskName);
        if (!(task instanceof JavaExec exec)) {
            throw new GradleException("stagewright test suite '" + suite.getName() + "': topology '"
                    + topology + "' names " + role + " '" + taskName + "', which is "
                    + (task == null ? "not a task in this build"
                                    : "a " + task.getClass().getSimpleName() + " rather than a JavaExec")
                    + " — a launch can only be taken from a dev run");
        }
        return exec;
    }

    /** The topologies a suite's scenes may name: generated for it, or the build's, as it maps them. */
    static List<SuiteLaunch> launches(Project project, StageWrightTestSuite suite,
                                      NamedDomainObjectContainer<StageWrightTopology> topologies) {
        if (GeneratedRuns.generates(suite)) return GeneratedRuns.launches(project, suite);
        if (suite.getTopologies().get().isEmpty()) {
            throw new GradleException("stagewright test suite '" + suite.getName() + "' has no games to run in: set"
                    + " loaderProject, for runs the plugin declares, or map topologies the build declares");
        }
        List<SuiteLaunch> launches = new ArrayList<>();
        for (Map.Entry<String, String> mapping : suite.getTopologies().get().entrySet()) {
            StageWrightTopology topology = declared(suite, topologies, mapping.getKey(), mapping.getValue());
            String runTask = topology.getRunTask().getOrNull();
            if (runTask == null) {
                throw new GradleException("stagewright topology '" + topology.getName()
                        + "' declares no runTask (used by test suite '" + suite.getName() + "')");
            }
            launches.add(new SuiteLaunch(mapping.getKey(), runTask, topology.getCompanionRunTask().getOrNull(),
                    topology.getGameDirectory().get().getAsFile(), topology.getCleanWorld().getOrElse(true),
                    topology.getTimeoutMinutes().getOrElse(DEFAULT_TIMEOUT_MINUTES),
                    topology.getStallMinutes().getOrElse(DEFAULT_STALL_MINUTES),
                    !topology.getClient().getOrElse(false)));
        }
        return launches;
    }

    private List<JavaExec> launchRuns(Project project, StageWrightTestSuite suite,
                                      NamedDomainObjectContainer<StageWrightTopology> topologies) {
        List<JavaExec> runs = new ArrayList<>();
        for (SuiteLaunch launch : launches(project, suite, topologies)) {
            runs.add(javaExec(project, suite, launch.topology(), launch.runTask(), "runTask"));
            if (launch.companionRunTask() != null) {
                runs.add(javaExec(project, suite, launch.topology(), launch.companionRunTask(), "companionRunTask"));
            }
        }
        return runs;
    }

    private List<StageWrightLaunchSpecsTask.Entry> launchEntries(
            Project project, StageWrightTestSuite suite,
            NamedDomainObjectContainer<StageWrightTopology> topologies) {
        List<StageWrightLaunchSpecsTask.Entry> entries = new ArrayList<>();
        for (SuiteLaunch launch : launches(project, suite, topologies)) {
            CompanionLaunch primary = CompanionLaunch.of(
                    javaExec(project, suite, launch.topology(), launch.runTask(), "runTask"));
            CompanionLaunch companion = launch.companionRunTask() == null ? null : CompanionLaunch.of(
                    javaExec(project, suite, launch.topology(), launch.companionRunTask(), "companionRunTask"));
            entries.add(new StageWrightLaunchSpecsTask.Entry(launch.topology(), launch.gameDirectory(),
                    launch.cleanWorld(), launch.timeoutMinutes(), launch.stallMinutes(), launch.server(),
                    primary, companion));
        }
        return entries;
    }

    /** The companion client's topology label, and its key in coverage: one name so the two match. */
    static String companionLabel(StageWrightTopology topology) {
        return topology.getName() + "-client";
    }

    private void registerTopologyTasks(Project project, StageWrightTopology topology,
                                       Provider<StageWrightSideProcessService> sideProcesses,
                                       Provider<StageWrightSupervisionService> supervision) {
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
            task.getSupervision().set(supervision);
            task.getSupervisedRun().set(supervisionKey(project, topology));
            task.getFailOnLeak().set(topology.getFailOnLeak());
            task.usesService(supervision);
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
                applyBuildId(project, topology, run);
                applySupervision(project, topology, run, supervision);
                attachSideProcesses(project, topology, run, sideProcesses, supervision);
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
                holdCompanion(project, topology, run);
                attachSideProcesses(project, topology, run, sideProcesses, null);
                RunSideProcesses.of(run).banner("[stagewright] holding '" + topology.getName()
                        + "' — attach with TESTKIT_ENDPOINT=" + endpoint.getAbsolutePath() + "\n"
                        + "[stagewright] the descriptor appears once the game is in a world; Ctrl-C ends the hold");
                return run;
            }));
        });
    }

    /**
     * Hang the display check and the companion off the run task itself.
     *
     * <p>{@code doFirst} rather than a task of their own, because the companion has to be live for the
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
     * <p>What the companion launch needs is taken from its task when the graph is built and held as
     * values ({@link CompanionLaunch}), so the run task keeps no reference to another task and the
     * build stays configuration-cacheable.
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

    /** Tell the game, and its companion, which code this run tests. From the work tree, not the jars:
     *  Fabric and NeoForge runs of one source are reconciled together, and their jars never match. */
    private void applyBuildId(Project project, StageWrightTopology topology, Task run) {
        JavaExec exec = run instanceof JavaExec e ? e : null;
        if (exec == null) {
            run.getLogger().warn("[stagewright] run task '{}' is a {}, not a JavaExec, so its results"
                    + " record no build and stagewrightCoverage reports MIXED BUILDS against any"
                    + " topology that does", run.getName(), run.getClass().getSimpleName());
        }
        String companionName = topology.getCompanionRunTask().getOrNull();
        JavaExec companion = companionName != null
                && resolveRunTask(project, companionName) instanceof JavaExec c ? c : null;
        // A build service, so every run and companion in the build is checked against one first id,
        // configuration cache or not.
        var service = project.getGradle().getSharedServices().registerIfAbsent("stagewrightBuildId",
                StageWrightBuildIdService.class,
                spec -> spec.getParameters().getWorkTree().set(project.getRootDir()));
        if (exec != null) addOnce(exec, service);
        if (companion != null) addOnce(companion, service);
    }

    /** This runs each time the gate's dependencies are resolved, and a run task may serve two
     *  topologies; a second provider would put the property on the command line twice. Its own
     *  provider per task, since each is checked against its own classpath. */
    private static void addOnce(JavaExec exec, Provider<StageWrightBuildIdService> service) {
        if (exec.getJvmArgumentProviders().stream().noneMatch(p -> p instanceof BuildIdArgument)) {
            // MOD_CLASSES alone: the rest of the environment would be stored in the configuration cache.
            Object modClasses = exec.getEnvironment().get("MOD_CLASSES");
            exec.getJvmArgumentProviders().add(new BuildIdArgument(service, exec.getPath(),
                    SideProcesses.resolveClasspath(exec),
                    modClasses == null ? Map.of() : Map.of("MOD_CLASSES", String.valueOf(modClasses)),
                    SideProcesses.lateBoundEnvironmentProperties(exec)));
            exec.usesService(service);
        }
    }

    /** The run as the supervision service and the verdict task both name it. */
    static String supervisionKey(Project project, StageWrightTopology topology) {
        return project.getPath() + "#" + topology.getName();
    }

    /** Appended to {@link #supervisionKey} for the topology's companion client, which has its own agent. */
    static final String COMPANION_KEY_SUFFIX = "#companion";

    /**
     * Attach the supervision agent to a gate's game, so the run ends when its agent says the suite is
     * over or the game has stopped, instead of when the task's timeout does.
     *
     * <p>Gates only: a hold is meant to outlast the build, and says nothing that ends it. A run task
     * that is not a {@code JavaExec} has no JVM to put the argument on and is left as it was.
     */
    private void applySupervision(Project project, StageWrightTopology topology, Task run,
                                  Provider<StageWrightSupervisionService> supervision) {
        if (!(run instanceof JavaExec exec)) {
            run.getLogger().warn("[stagewright] run task '{}' is a {}, not a JavaExec, so its game is not"
                    + " supervised and a game that stops moving costs the whole timeout",
                    run.getName(), run.getClass().getSimpleName());
            return;
        }
        if (exec.getJvmArgumentProviders().stream().noneMatch(p -> p instanceof SupervisionArgument)) {
            File artifacts = project.getLayout().getBuildDirectory()
                    .dir("stagewright/" + topology.getName()).get().getAsFile();
            exec.getJvmArgumentProviders().add(new SupervisionArgument(supervision,
                    supervisionKey(project, topology), topology.getName(), "game", artifacts,
                    topology.getStallMinutes().getOrElse(DEFAULT_STALL_MINUTES)));
            exec.usesService(supervision);
        }
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
                ? CompanionLaunch.runDirectory(companion)
                : null;
    }

    private void holdCompanion(Project project, StageWrightTopology topology, Task run) {
        String companionName = topology.getCompanionRunTask().getOrNull();
        if (companionName == null) return;
        if (!(resolveRunTask(project, companionName) instanceof JavaExec companion)) return;
        companion.systemProperty(HOLD_PROPERTY, "true");
        companion.systemProperty(TOPOLOGY_PROPERTY, companionLabel(topology));
        File results = topology.getCompanionResultsFile().map(f -> f.getAsFile()).getOrNull();
        if (results == null || results.getParentFile() == null) return;
        File endpoint = new File(results.getParentFile(), RunDirectory.ENDPOINT_NAME);
        companion.systemProperty(ENDPOINT_PROPERTY, endpoint.getAbsolutePath());
        RunSideProcesses.of(run).banner("[stagewright] companion held — client face at TESTKIT_ENDPOINT="
                + endpoint.getAbsolutePath());
    }

    /**
     * @param supervision the service that listens to the companion's agent, or null for a hold, which
     *                    outlasts the build and is not supervised
     */
    private void attachSideProcesses(Project project, StageWrightTopology topology, Task run,
                                     org.gradle.api.provider.Provider<StageWrightSideProcessService> sideProcesses,
                                     Provider<StageWrightSupervisionService> supervision) {
        String companionName = topology.getCompanionRunTask().getOrNull();
        if (companionName != null) {
            Task resolved = resolveRunTask(project, companionName);
            if (!(resolved instanceof JavaExec companion)) {
                throw new GradleException("stagewright topology '" + topology.getName()
                        + "' names companionRunTask '" + companionName + "', which is "
                        + (resolved == null ? "not a task in this build"
                                            : "a " + resolved.getClass().getSimpleName()
                                              + " rather than a JavaExec — a companion has to be a"
                                              + " dev run this plugin can take a launch from"));
            }
            File companionLog = new File(topology.getGameDirectory().get().getAsFile(),
                    "companion-" + companionName.replace(':', '-') + ".log");
            // Its own directory: the primary game stages the same jar for itself, and a jar replaced
            // under a JVM that has it open is a launch that fails on the platforms that lock files.
            SupervisionArgument companionAgent = supervision == null ? null : new SupervisionArgument(
                    supervision, supervisionKey(project, topology) + COMPANION_KEY_SUFFIX,
                    topology.getName() + "-client", "client",
                    project.getLayout().getBuildDirectory()
                            .dir("stagewright/" + topology.getName() + "/client").get().getAsFile(),
                    topology.getStallMinutes().getOrElse(DEFAULT_STALL_MINUTES));
            if (RunSideProcesses.of(run).startCompanion(companion, companionLog, sideProcesses, companionAgent)) {
                dependOnCompanion(run, companion);
            }
        }
        if (Boolean.TRUE.equals(topology.getClient().getOrElse(false))) {
            RunSideProcesses.of(run).requireDisplay(topology.getName());
        }
    }

    private void dependOnCompanion(Task run, JavaExec companion) {
        run.dependsOn(companion.getDependsOn());
        // ...and on whatever produces its inputs. A run task's classpath is a file collection
        // carrying its own build dependencies, which are not in dependsOn — so a companion
        // launched without this can be handed a path to a jar nothing has built yet.
        run.dependsOn(companion.getInputs().getFiles());
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
        SourceSet testmod = project.getExtensions().getByType(JavaPluginExtension.class).getSourceSets()
                .maybeCreate("testmod");
        seeMain(project, testmod);
        ext.setTestmodSourceSetRef(testmod);
    }

    /** {@code testmod}, created once and wired to see {@code main}; an existing one is returned as it is. */
    static SourceSet testmodSourceSet(Project project) {
        SourceSetContainer sourceSets =
                project.getExtensions().getByType(JavaPluginExtension.class).getSourceSets();
        SourceSet existing = sourceSets.findByName("testmod");
        if (existing != null) return existing;
        SourceSet testmod = sourceSets.create("testmod");
        seeMain(project, testmod);
        return testmod;
    }

    private static void seeMain(Project project, SourceSet testmod) {
        SourceSet main = project.getExtensions().getByType(JavaPluginExtension.class).getSourceSets()
                .getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        testmod.setCompileClasspath(testmod.getCompileClasspath()
                .plus(main.getOutput())
                .plus(project.getConfigurations().getByName(main.getCompileClasspathConfigurationName())));
        testmod.setRuntimeClasspath(testmod.getRuntimeClasspath()
                .plus(main.getOutput())
                .plus(project.getConfigurations().getByName(main.getRuntimeClasspathConfigurationName())));
    }

    private static String capitalize(String s) {
        if (s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }
}
