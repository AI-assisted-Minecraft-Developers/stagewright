package net.magicterra.stagewright.gradle;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.codehaus.groovy.runtime.InvokerHelper;
import org.gradle.api.GradleException;
import org.gradle.api.Named;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.Project;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;

/**
 * The runs a test suite with a {@link StageWrightTestSuite#getLoaderProject() loader project} gets: declared in that
 * project's loom or ModDevGradle, so the loader plugin builds each launch as it builds {@code runServer}'s, and read
 * back as {@link SuiteLaunch}es.
 *
 * <p>Both are reached by name, as a build script reaches them, so this plugin links against no version of either. A
 * run is declared once its project is evaluated, when the source set it starts from exists; both register the run
 * task as the run is added, and both name it {@code run} and the run's name.
 */
final class GeneratedRuns {

    /** ModDevGradle's extension, absent under loom. */
    static final String MOD_DEV = "neoForge";
    static final String DEDICATED_SERVER = "dedicatedServer";
    static final String DEDICATED_SERVER_WITH_CLIENT = "dedicatedServerWithClient";
    static final String INTEGRATED_SERVER = "integratedServer";
    /** Under ModDevGradle, the mod the run source set becomes. */
    static final String TESTS_MOD = "stagewright_tests";
    /** The singleplayer world the integrated server's client creates; the test engine deletes it before each scene. */
    static final String INTEGRATED_WORLD = "StageWright";
    static final int DEFAULT_SERVER_PORT = 25621;
    static final String TESTMOD = "testmod";

    private GeneratedRuns() {}

    /** Whether the plugin declares this suite's runs rather than borrowing topologies. */
    static boolean generates(StageWrightTestSuite suite) {
        return suite.getLoaderProject().isPresent() && suite.getTopologies().get().isEmpty();
    }

    /** Declare the suite's runs in its loader project, once that project is evaluated. */
    static void declare(Project plugin, StageWrightTestSuite suite) {
        Project loader = loader(plugin, suite);
        // The plugin's own project is in its afterEvaluate already, and another one nested there would be one level
        // too deep: ModDevGradle wires DevLaunch onto a run's classpath from an afterEvaluate of its own, which
        // Gradle then drops without a word, and the game dies on ClassNotFoundException: net.neoforged.devlaunch.Main.
        if (loader == plugin || loader.getState().getExecuted()) declareNow(loader, suite);
        else loader.afterEvaluate(p -> declareNow(p, suite));
    }

    /** The topologies the generated runs give the suite. */
    static List<SuiteLaunch> launches(Project plugin, StageWrightTestSuite suite) {
        Project loader = loader(plugin, suite);
        String path = loader.getPath().equals(":") ? "" : loader.getPath();
        int timeout = suite.getTimeoutMinutes().getOrElse(StageWrightPlugin.DEFAULT_TIMEOUT_MINUTES);
        String leaks = SuiteLaunch.leaks(suite.getLeaks().getOrNull(), "stagewright test suite '" + suite.getName() + "'");
        List<SuiteLaunch> launches = new ArrayList<>();
        launches.add(new SuiteLaunch(DEDICATED_SERVER, path + ":" + taskName(suite, "DedicatedServer"), null,
                loader.file(runDir(suite, "dedicated-server")), true, timeout, StageWrightPlugin.DEFAULT_STALL_MINUTES,
                true, leaks));
        launches.add(new SuiteLaunch(DEDICATED_SERVER_WITH_CLIENT,
                path + ":" + taskName(suite, "DedicatedServerWithClient"), path + ":" + taskName(suite, "JoiningClient"),
                loader.file(runDir(suite, "with-client")), true, timeout, StageWrightPlugin.DEFAULT_STALL_MINUTES, true,
                leaks));
        launches.add(new SuiteLaunch(INTEGRATED_SERVER, path + ":" + taskName(suite, "IntegratedServer"), null,
                loader.file(runDir(suite, "integrated-server")), true, timeout, StageWrightPlugin.DEFAULT_STALL_MINUTES,
                false, leaks));
        return launches;
    }

    static Project loader(Project plugin, StageWrightTestSuite suite) {
        String path = suite.getLoaderProject().get();
        Project loader = plugin.getRootProject().findProject(path);
        if (loader == null) {
            throw new GradleException("stagewright test suite '" + suite.getName() + "' names loaderProject '" + path
                    + "', which is not a project in this build");
        }
        return loader;
    }

    private static void declareNow(Project loader, StageWrightTestSuite suite) {
        Object loom = loader.getExtensions().findByName("loom");
        Object modDev = loom == null ? loader.getExtensions().findByName(MOD_DEV) : null;
        if (loom == null && modDev == null) {
            throw new GradleException("stagewright test suite '" + suite.getName() + "' names loaderProject '"
                    + loader.getPath() + "', which applies neither loom (Fabric Loom or Architectury Loom) nor"
                    + " ModDevGradle. Under another loader plugin, declare the runs there and map them with"
                    + " topologies instead");
        }
        @SuppressWarnings("unchecked")
        NamedDomainObjectContainer<Object> runs = (NamedDomainObjectContainer<Object>) InvokerHelper.invokeMethod(
                loom != null ? loom : modDev, "getRuns", null);
        SourceSet source = sourceSet(loader, suite);
        SuiteDependencies.attach(loader, suite, source);
        if (modDev != null) testsMod(loader, modDev, suite, source, runs);
        int port = suite.getServerPort().getOrElse(DEFAULT_SERVER_PORT);
        List<String> serverArgs = List.of("--port", String.valueOf(port));

        declare(loader, runs, runName(suite, "DedicatedServer"), "server", runDir(suite, "dedicated-server"),
                serverArgs, Map.of(), suite, source);
        // Holds its tests back until the client below has joined.
        declare(loader, runs, runName(suite, "DedicatedServerWithClient"), "server", runDir(suite, "with-client"),
                serverArgs, Map.of("stagewright.awaitPlayer", "true"), suite, source);
        // The tests' own content, blocks and items included, is on it too, or the server refuses it at registry sync.
        declare(loader, runs, runName(suite, "JoiningClient"), "client", runDir(suite, "joining-client"), List.of(),
                Map.of("stagewright.client.connect", "127.0.0.1:" + port), suite, source);
        // A client hosting the world: it creates the singleplayer world from the scene's settings and opens it, and the
        // tests wait until its player is in.
        declare(loader, runs, runName(suite, "IntegratedServer"), "client", runDir(suite, "integrated-server"),
                List.of(), Map.of("stagewright.client.world", INTEGRATED_WORLD, "stagewright.awaitPlayer", "true"),
                suite, source);
    }

    private static void declare(Project loader, NamedDomainObjectContainer<Object> runs, String name, String side,
                                String runDir, List<String> programArgs, Map<String, String> properties,
                                StageWrightTestSuite suite, SourceSet source) {
        if (runs.findByName(name) != null) {
            throw new GradleException("stagewright test suite '" + suite.getName() + "' would declare the run '" + name
                    + "', which the build already declares");
        }
        boolean loom = loader.getExtensions().findByName("loom") != null;
        runs.create(name, run -> {
            InvokerHelper.invokeMethod(run, side, null);
            if (loom) {
                InvokerHelper.invokeMethod(run, "runDir", runDir);
                InvokerHelper.invokeMethod(run, "ideConfigGenerated", false);
                InvokerHelper.invokeMethod(run, "source", source);
            } else {
                directoryProperty(run, "getGameDirectory").set(loader.file(runDir));
                InvokerHelper.invokeMethod(run, "disableIdeRun", null);
                property(run, "getSourceSet").set(source);
            }
            String programArg = loom ? "programArg" : "programArgument";
            String jvmArg = loom ? "vmArg" : "jvmArgument";
            String systemProperty = loom ? "property" : "systemProperty";
            for (String arg : programArgs) InvokerHelper.invokeMethod(run, programArg, arg);
            for (String arg : suite.getJvmArgs().get()) InvokerHelper.invokeMethod(run, jvmArg, arg);
            suite.getSystemProperties().get().forEach((k, v) -> InvokerHelper.invokeMethod(run, systemProperty,
                    new Object[] {k, v}));
            properties.forEach((k, v) -> InvokerHelper.invokeMethod(run, systemProperty, new Object[] {k, v}));
        });
    }

    /**
     * Under ModDevGradle, puts the run source set into the suite's runs only. NeoForge loads classes only from mods,
     * each mod a module, and tests usually share packages with the mod they test, which two modules may not. So the
     * tests join the project's mod, the one holding {@code main}, in a copy of it, {@value #TESTS_MOD}: the suite's
     * runs load the copy in its place, and every other run loads every mod but the copy unless the build says which
     * mods it loads. Which mod holds {@code main} is asked when a run is launched: a mod's source sets are fixed once
     * read, and reading them while the build is still configuring would fix them early.
     */
    private static void testsMod(Project loader, Object modDev, StageWrightTestSuite suite, SourceSet source,
                                 NamedDomainObjectContainer<Object> runs) {
        if (source.getName().equals(SourceSet.MAIN_SOURCE_SET_NAME)) return;
        @SuppressWarnings("unchecked")
        NamedDomainObjectContainer<Object> mods =
                (NamedDomainObjectContainer<Object>) InvokerHelper.invokeMethod(modDev, "getMods", null);
        if (mods.findByName(TESTS_MOD) != null) return;
        Object[] copy = new Object[1];
        copy[0] = mods.create(TESTS_MOD, mod -> listProperty(mod, "getModSourceSets").addAll(loader.provider(() -> {
            List<Object> sets = new ArrayList<>(listProperty(host(loader, suite, mods, copy[0]), "getModSourceSets")
                    .get());
            sets.add(source);
            return sets;
        })));
        Set<String> ours = Set.of(runName(suite, "DedicatedServer"), runName(suite, "DedicatedServerWithClient"),
                runName(suite, "JoiningClient"), runName(suite, "IntegratedServer"));
        runs.configureEach(run -> {
            boolean suites = ours.contains(((Named) run).getName());
            setProperty(run, "getLoadedMods").convention(loader.provider(() -> {
                Set<Object> loaded = new LinkedHashSet<>(mods);
                loaded.remove(suites ? host(loader, suite, mods, copy[0]) : copy[0]);
                return loaded;
            }));
        });
    }

    /**
     * The project's mod holding {@code main}, which the tests' copy stands in for. The project's own {@code main},
     * by identity: a mod may hold another project's source set, and every project's main is named {@code main}.
     */
    private static Object host(Project loader, StageWrightTestSuite suite, Iterable<Object> mods, Object copy) {
        SourceSet main = loader.getExtensions().getByType(JavaPluginExtension.class).getSourceSets()
                .getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        for (Object mod : mods) {
            if (mod == copy) continue;
            for (Object set : listProperty(mod, "getModSourceSets").get()) {
                if (set == main) return mod;
            }
        }
        throw new GradleException("stagewright test suite '" + suite.getName() + "': " + loader.getPath()
                + " declares no mod in neoForge.mods holding sourceSets.main, for the tests to join");
    }

    @SuppressWarnings("unchecked")
    private static Property<Object> property(Object owner, String getter) {
        return (Property<Object>) InvokerHelper.invokeMethod(owner, getter, null);
    }

    @SuppressWarnings("unchecked")
    private static SetProperty<Object> setProperty(Object owner, String getter) {
        return (SetProperty<Object>) InvokerHelper.invokeMethod(owner, getter, null);
    }

    @SuppressWarnings("unchecked")
    private static ListProperty<Object> listProperty(Object owner, String getter) {
        return (ListProperty<Object>) InvokerHelper.invokeMethod(owner, getter, null);
    }

    private static DirectoryProperty directoryProperty(Object owner, String getter) {
        return (DirectoryProperty) InvokerHelper.invokeMethod(owner, getter, null);
    }

    /**
     * The source set the suite's runs start from and its tests live in: {@link StageWrightTestSuite#getRunSourceSet()},
     * else {@code testmod}, created when the project has none and seeing {@code main} as the plugin's
     * {@code testmodSourceSet} would wire it.
     */
    static SourceSet sourceSet(Project loader, StageWrightTestSuite suite) {
        SourceSetContainer sets = loader.getExtensions().getByType(JavaPluginExtension.class).getSourceSets();
        if (!suite.getRunSourceSet().isPresent() && sets.findByName(TESTMOD) == null) {
            return StageWrightPlugin.testmodSourceSet(loader);
        }
        String name = suite.getRunSourceSet().getOrElse(TESTMOD);
        SourceSet set = sets.findByName(name);
        if (set == null) {
            throw new GradleException("stagewright test suite '" + suite.getName() + "': " + loader.getPath()
                    + " has no source set '" + name + "' to start its runs from (has: " + sets.getNames() + ")");
        }
        return set;
    }

    /** The suite generating runs in {@code project}, or null. */
    static StageWrightTestSuite suiteOf(Project project, Iterable<StageWrightTestSuite> suites) {
        for (StageWrightTestSuite suite : suites) {
            if (generates(suite) && loader(project, suite) == project) return suite;
        }
        return null;
    }

    private static String runName(StageWrightTestSuite suite, String what) {
        return "stagewright" + capitalize(suite.getName()) + what;
    }

    /** Loom's name for a run's task: {@code run} and the run's name, capitalized. */
    private static String taskName(StageWrightTestSuite suite, String what) {
        return "run" + capitalize(runName(suite, what));
    }

    private static String runDir(StageWrightTestSuite suite, String what) {
        return "run-stagewright/" + suite.getName() + "/" + what;
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }
}
