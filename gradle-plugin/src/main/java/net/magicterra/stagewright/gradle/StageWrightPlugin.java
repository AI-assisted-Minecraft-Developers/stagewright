package net.magicterra.stagewright.gradle;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.gradle.api.GradleException;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.testing.Test;

/**
 * Registers the {@code stagewright { ... }} block and, per test suite, a {@code stagewrightTest<Name>} {@code Test}
 * task that runs the suite on the StageWright test engine, which starts a game per scene from the launch specs
 * {@code stagewrightLaunchSpecs<Name>} writes.
 *
 * <p><b>What this plugin deliberately does not do: build a launch.</b> Each launch is read off a run task the host's
 * loader plugin declared — one the build names as a topology, or one {@link GeneratedRuns} declared for the suite —
 * because that plugin is the only thing that knows how to start its game.
 */
public class StageWrightPlugin implements Plugin<Project> {

    static final String TASK_GROUP = "verification";
    static final int DEFAULT_TIMEOUT_MINUTES = 20;
    static final int DEFAULT_STALL_MINUTES = 5;

    @Override
    public void apply(Project project) {
        NamedDomainObjectContainer<StageWrightTopology> topologies = project.getObjects()
                .domainObjectContainer(
                        StageWrightTopology.class,
                        name -> project.getObjects().newInstance(StageWrightTopology.class, name));

        NamedDomainObjectContainer<StageWrightTestSuite> tests = project.getObjects()
                .domainObjectContainer(
                        StageWrightTestSuite.class,
                        name -> project.getObjects().newInstance(StageWrightTestSuite.class, name));

        StageWrightExtension ext =
                project.getExtensions().create("stagewright", StageWrightExtension.class, topologies, tests);
        ext.getTestmodSourceSet().convention(false);

        // A reaction, not a point-in-time check: this fires whether `java` was applied before or
        // after this plugin, and never fires at all for a non-java consumer — so the flag being on
        // in such a build is a no-op rather than a crash.
        project.getPlugins()
                .withType(
                        JavaPlugin.class,
                        java -> project.afterEvaluate(p -> {
                            if (Boolean.TRUE.equals(ext.getTestmodSourceSet().getOrElse(false))) {
                                registerTestmodSourceSet(p, ext);
                            }
                        }));

        topologies.all(topology -> {
            topology.getCleanWorld().convention(true);
            topology.getTimeoutMinutes().convention(DEFAULT_TIMEOUT_MINUTES);
            topology.getClient().convention(false);
            topology.getStallMinutes().convention(DEFAULT_STALL_MINUTES);
            topology.getLeaks().convention(SuiteLaunch.LEAKS_FAIL);
            topology.getGameDirectory()
                    .convention(project.getLayout().getProjectDirectory().dir("run-stagewright-" + topology.getName()));
        });

        tests.all(suite -> {
            suite.getServerPort().convention(GeneratedRuns.DEFAULT_SERVER_PORT);
            suite.getTimeoutMinutes().convention(DEFAULT_TIMEOUT_MINUTES);
            suite.getLeaks().convention(SuiteLaunch.LEAKS_FAIL);
            // A single-loader project is its own loader; null leaves it unset elsewhere.
            suite.getLoaderProject()
                    .convention(project.provider(() -> project.getExtensions().findByName("loom") != null
                                    || project.getExtensions().findByName(GeneratedRuns.MOD_DEV) != null
                            ? project.getPath()
                            : null));
            suite.getIncludeWorldDriver().convention(true);
            suite.getWorldDriverVersion().convention(PluginVersion.worldDriver());
            suite.getTestDependencies().convention(true);
            // The tests are the run source set's, unless the build points elsewhere, as a multi-loader one does.
            suite.getClasses()
                    .convention(project.provider(() -> GeneratedRuns.generates(suite)
                            ? GeneratedRuns.sourceSet(GeneratedRuns.loader(project, suite), suite)
                                    .getOutput()
                            : project.files()));
            suite.getClasspath()
                    .convention(project.provider(() -> GeneratedRuns.generates(suite)
                            ? GeneratedRuns.sourceSet(GeneratedRuns.loader(project, suite), suite)
                                    .getCompileClasspath()
                            : project.files()));
            registerTestSuiteTasks(project, suite, topologies);
        });
        // Once this build script has set the suites up; each loader project's runs follow its own evaluation.
        project.afterEvaluate(p -> tests.forEach(suite -> {
            if (GeneratedRuns.generates(suite)) {
                GeneratedRuns.declare(p, suite);
            }
        }));
        DevJUnitRuntime.install(project, tests, topologies);
        SuiteDependencies.install(project, tests);
        // Once a build, before any loom project is evaluated, which is when loom remaps.
        var extra = project.getRootProject().getExtensions().getExtraProperties();
        if (!extra.has(STALE_REMAPS_SWEPT)) {
            extra.set(STALE_REMAPS_SWEPT, true);
            StaleRemaps.evict(project.getRootProject());
        }
    }

    static final String TEST_ENGINE_CONFIGURATION = "stagewrightTestEngine";
    private static final String STALE_REMAPS_SWEPT = "stagewright.staleRemapsSwept";
    static final String LAUNCHES_PROPERTY = "stagewright.launches";
    static final String TEST_ROOTS_PROPERTY = "stagewright.testRoots";
    /** The Gradle property whose whitespace-separated arguments every game the plugin starts gets last. */
    static final String JVM_ARGS_PROPERTY = "stagewright.jvmArgs";

    /**
     * Per suite: {@code stagewrightLaunchSpecs<Name>}, which writes one launch spec per topology the suite maps, and
     * {@code stagewrightTest<Name>}, a {@code Test} task that runs the outer engine against them. Not hooked into
     * {@code check}: it starts games.
     */
    private void registerTestSuiteTasks(
            Project project, StageWrightTestSuite suite, NamedDomainObjectContainer<StageWrightTopology> topologies) {
        String suffix = capitalize(suite.getName());
        var launchesDir = project.getLayout().getBuildDirectory().dir("stagewright/launches/" + suite.getName());

        Configuration engine = project.getConfigurations().findByName(TEST_ENGINE_CONFIGURATION);
        if (engine == null) {
            engine = project.getConfigurations().create(TEST_ENGINE_CONFIGURATION, c -> {
                c.setCanBeConsumed(false);
                c.setCanBeResolved(true);
                c.setDescription("The StageWright test engine, at the version of this plugin.");
                c.getDependencies()
                        .add(project.getDependencies()
                                .create("net.magicterra:stagewright-testengine:" + PluginVersion.get()));
                // Gradle 9 no longer puts the platform launcher on a Test task's runtime by itself.
                c.getDependencies()
                        .add(project.getDependencies().platform("org.junit:junit-bom:" + PluginVersion.junit()));
                c.getDependencies().add(project.getDependencies().create("org.junit.platform:junit-platform-launcher"));
            });
        }
        Configuration engineConfiguration = engine;

        TaskProvider<StageWrightLaunchSpecsTask> specs = project.getTasks()
                .register("stagewrightLaunchSpecs" + suffix, StageWrightLaunchSpecsTask.class, task -> {
                    task.setGroup(TASK_GROUP);
                    task.setDescription(
                            "Writes the launch specs the " + suite.getName() + " test suite's games are started from.");
                    task.getLaunchesDirectory().set(launchesDir);
                    task.getEntries().set(project.provider(() -> launchEntries(project, suite, topologies)));
                    // Resolved when the graph is built: the host's loader plugin registers its run
                    // tasks after this one is applied. Both runs' own prerequisites, as for a companion.
                    task.dependsOn(project.provider(() -> {
                        List<Object> needs = new ArrayList<>();
                        for (JavaExec run : launchRuns(project, suite, topologies)) {
                            // A bare task name in a run's dependsOn means a task of the run's project, which
                            // need not be this one ('configureLaunch' under loom, a provider of that name in
                            // newer loom), so it is qualified here.
                            String prefix = run.getProject().getPath().replaceAll(":$", "") + ":";
                            for (Object need : run.getDependsOn()) {
                                needs.add(
                                        need instanceof Provider<?> provider
                                                ? provider.map(v -> qualify(v, prefix))
                                                : qualify(need, prefix));
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
            task.getJvmArgumentProviders()
                    .add(() -> List.of("-D" + TEST_ROOTS_PROPERTY + "="
                            + suite.getClasses().getAsPath()));
            // Script tests are matched by --tests as `<path>.<name>`; a pattern naming a path must reach them.
            task.doFirst(ScriptPatterns.INSTANCE);
            task.addTestListener(new SceneSummary());
            task.getOutputs().upToDateWhen(t -> false);
            // Set here, not left to the java plugin's conventions: the suite may live on a project without it.
            var build = project.getLayout().getBuildDirectory();
            task.getBinaryResultsDirectory().set(build.dir("test-results/" + task.getName() + "/binary"));
            task.getReports().getJunitXml().getOutputLocation().set(build.dir("test-results/" + task.getName()));
            task.getReports().getHtml().getOutputLocation().set(build.dir("reports/tests/" + task.getName()));
        });
    }

    /** The declared topology for each scene-facing name, or a failure naming the suite and the topology. */
    private static StageWrightTopology declared(
            StageWrightTestSuite suite,
            NamedDomainObjectContainer<StageWrightTopology> topologies,
            String sceneName,
            String declaredName) {
        StageWrightTopology topology = topologies.findByName(declaredName);
        if (topology == null) {
            throw new GradleException("stagewright test suite '" + suite.getName() + "' maps topology '"
                    + sceneName + "' to '" + declaredName + "', which is not declared in stagewright.topologies"
                    + " (declared: " + String.join(", ", topologies.getNames()) + ")");
        }
        return topology;
    }

    private static JavaExec javaExec(
            Project project, StageWrightTestSuite suite, String topology, String taskName, String role) {
        Task task = resolveRunTask(project, taskName);
        if (!(task instanceof JavaExec exec)) {
            throw new GradleException("stagewright test suite '" + suite.getName() + "': topology '"
                    + topology + "' names " + role + " '" + taskName + "', which is "
                    + (task == null
                            ? "not a task in this build"
                            : "a " + task.getClass().getSimpleName() + " rather than a JavaExec")
                    + " — a launch can only be taken from a dev run");
        }
        return exec;
    }

    /** The topologies a suite's scenes may name: generated for it, or the build's, as it maps them. */
    static List<SuiteLaunch> launches(
            Project project, StageWrightTestSuite suite, NamedDomainObjectContainer<StageWrightTopology> topologies) {
        if (GeneratedRuns.generates(suite)) {
            return GeneratedRuns.launches(project, suite);
        }
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
            launches.add(new SuiteLaunch(
                    mapping.getKey(),
                    runTask,
                    topology.getCompanionRunTask().getOrNull(),
                    topology.getGameDirectory().get().getAsFile(),
                    topology.getCleanWorld().getOrElse(true),
                    topology.getTimeoutMinutes().getOrElse(DEFAULT_TIMEOUT_MINUTES),
                    topology.getStallMinutes().getOrElse(DEFAULT_STALL_MINUTES),
                    !topology.getClient().getOrElse(false),
                    SuiteLaunch.leaks(
                            topology.getLeaks().getOrNull(), "stagewright topology '" + topology.getName() + "'")));
        }
        return launches;
    }

    /** A bare task name made a path under {@code prefix}; anything else as it is. */
    private static Object qualify(Object need, String prefix) {
        return need instanceof CharSequence name && !name.toString().startsWith(":") ? prefix + name : need;
    }

    private List<JavaExec> launchRuns(
            Project project, StageWrightTestSuite suite, NamedDomainObjectContainer<StageWrightTopology> topologies) {
        List<JavaExec> runs = new ArrayList<>();
        for (SuiteLaunch launch : launches(project, suite, topologies)) {
            runs.add(javaExec(project, suite, launch.topology(), launch.runTask(), "runTask"));
            if (launch.companionRunTask() != null) {
                runs.add(javaExec(project, suite, launch.topology(), launch.companionRunTask(), "companionRunTask"));
            }
        }
        return runs;
    }

    /**
     * {@value #JVM_ARGS_PROPERTY}, split on whitespace: what this machine needs on every game it starts (a heap size, a
     * temporary directory, a patched native library) and the build should not have to carry.
     */
    static List<String> extraJvmArgs(Project project) {
        String value = project.getProviders()
                .gradleProperty(JVM_ARGS_PROPERTY)
                .getOrElse("")
                .trim();
        return value.isEmpty() ? List.of() : List.of(value.split("\\s+"));
    }

    private List<StageWrightLaunchSpecsTask.Entry> launchEntries(
            Project project, StageWrightTestSuite suite, NamedDomainObjectContainer<StageWrightTopology> topologies) {
        List<String> extra = extraJvmArgs(project);
        List<StageWrightLaunchSpecsTask.Entry> entries = new ArrayList<>();
        for (SuiteLaunch launch : launches(project, suite, topologies)) {
            CompanionLaunch primary =
                    CompanionLaunch.of(javaExec(project, suite, launch.topology(), launch.runTask(), "runTask"));
            CompanionLaunch companion = launch.companionRunTask() == null
                    ? null
                    : CompanionLaunch.of(
                            javaExec(project, suite, launch.topology(), launch.companionRunTask(), "companionRunTask"));
            entries.add(new StageWrightLaunchSpecsTask.Entry(
                    launch.topology(),
                    launch.gameDirectory(),
                    launch.cleanWorld(),
                    launch.timeoutMinutes(),
                    launch.stallMinutes(),
                    launch.server(),
                    launch.leaks(),
                    primary,
                    companion,
                    extra));
        }
        return entries;
    }

    /**
     * Find the run task, by plain name in this project or by full path in another.
     *
     * <p>Path support is not a convenience. Under loom and ModDevGradle alike, a multi-loader build declares its runs
     * on the per-loader SUBprojects while the plugin is applied on the root.
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

    /**
     * v1 boundary: classpath wiring only — testmod sees main's output plus main's own compile and runtime classpaths,
     * and nothing else changes.
     */
    private void registerTestmodSourceSet(Project project, StageWrightExtension ext) {
        SourceSet testmod = project.getExtensions()
                .getByType(JavaPluginExtension.class)
                .getSourceSets()
                .maybeCreate("testmod");
        seeMain(project, testmod);
        ext.setTestmodSourceSetRef(testmod);
    }

    /** {@code testmod}, created once and wired to see {@code main}; an existing one is returned as it is. */
    static SourceSet testmodSourceSet(Project project) {
        SourceSetContainer sourceSets =
                project.getExtensions().getByType(JavaPluginExtension.class).getSourceSets();
        SourceSet existing = sourceSets.findByName("testmod");
        if (existing != null) {
            return existing;
        }
        SourceSet testmod = sourceSets.create("testmod");
        seeMain(project, testmod);
        return testmod;
    }

    private static void seeMain(Project project, SourceSet testmod) {
        SourceSet main = project.getExtensions()
                .getByType(JavaPluginExtension.class)
                .getSourceSets()
                .getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        testmod.setCompileClasspath(testmod.getCompileClasspath()
                .plus(main.getOutput())
                .plus(project.getConfigurations().getByName(main.getCompileClasspathConfigurationName())));
        testmod.setRuntimeClasspath(testmod.getRuntimeClasspath()
                .plus(main.getOutput())
                .plus(project.getConfigurations().getByName(main.getRuntimeClasspathConfigurationName())));
    }

    private static String capitalize(String s) {
        if (s.isEmpty()) {
            return s;
        }
        return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }
}
