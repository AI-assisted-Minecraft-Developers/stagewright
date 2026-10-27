package net.magicterra.stagewright.gradle;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.codehaus.groovy.runtime.InvokerHelper;
import org.gradle.api.GradleException;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;

/**
 * The runs a test suite with a {@link StageWrightTestSuite#getLoaderProject() loader project} gets: declared in that
 * project's loom, so loom builds each launch as it builds {@code runServer}'s, and read back as {@link SuiteLaunch}es.
 *
 * <p>Loom is reached by name, as a build script reaches it, so this plugin links against no loom version. A run is
 * declared once its project is evaluated, when the source set it starts from exists; loom registers the run task as
 * the run is added.
 */
final class GeneratedRuns {

    static final String DEDICATED_SERVER = "dedicatedServer";
    static final String DEDICATED_SERVER_WITH_CLIENT = "dedicatedServerWithClient";
    static final int DEFAULT_SERVER_PORT = 25621;

    private GeneratedRuns() {}

    /** Whether the plugin declares this suite's runs rather than borrowing topologies. */
    static boolean generates(StageWrightTestSuite suite) {
        return suite.getLoaderProject().isPresent() && suite.getTopologies().get().isEmpty();
    }

    /** Declare the suite's runs in its loader project, once that project is evaluated. */
    static void declare(Project plugin, StageWrightTestSuite suite) {
        Project loader = loader(plugin, suite);
        if (loader.getState().getExecuted()) declareNow(loader, suite);
        else loader.afterEvaluate(p -> declareNow(p, suite));
    }

    /** The topologies the generated runs give the suite. */
    static List<SuiteLaunch> launches(Project plugin, StageWrightTestSuite suite) {
        Project loader = loader(plugin, suite);
        String path = loader.getPath().equals(":") ? "" : loader.getPath();
        int timeout = suite.getTimeoutMinutes().getOrElse(StageWrightPlugin.DEFAULT_TIMEOUT_MINUTES);
        List<SuiteLaunch> launches = new ArrayList<>();
        launches.add(new SuiteLaunch(DEDICATED_SERVER, path + ":" + taskName(suite, "DedicatedServer"), null,
                loader.file(runDir(suite, "dedicated-server")), true, timeout, StageWrightPlugin.DEFAULT_STALL_MINUTES,
                true));
        launches.add(new SuiteLaunch(DEDICATED_SERVER_WITH_CLIENT,
                path + ":" + taskName(suite, "DedicatedServerWithClient"), path + ":" + taskName(suite, "JoiningClient"),
                loader.file(runDir(suite, "with-client")), true, timeout, StageWrightPlugin.DEFAULT_STALL_MINUTES, true));
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
        if (loom == null) {
            throw new GradleException("stagewright test suite '" + suite.getName() + "' names loaderProject '"
                    + loader.getPath() + "', which does not apply loom (Fabric Loom or Architectury Loom). Under"
                    + " another loader plugin, declare the runs there and map them with topologies instead");
        }
        @SuppressWarnings("unchecked")
        NamedDomainObjectContainer<Object> runs =
                (NamedDomainObjectContainer<Object>) InvokerHelper.invokeMethod(loom, "getRuns", null);
        SourceSet source = sourceSet(loader, suite);
        int port = suite.getServerPort().getOrElse(DEFAULT_SERVER_PORT);
        List<String> serverArgs = List.of("--port", String.valueOf(port));

        declare(runs, runName(suite, "DedicatedServer"), "server", runDir(suite, "dedicated-server"), serverArgs,
                Map.of(), suite, source);
        // Holds its tests back until the client below has joined.
        declare(runs, runName(suite, "DedicatedServerWithClient"), "server", runDir(suite, "with-client"), serverArgs,
                Map.of("stagewright.awaitPlayer", "true"), suite, source);
        // The tests' own content, blocks and items included, is on it too, or the server refuses it at registry sync.
        declare(runs, runName(suite, "JoiningClient"), "client", runDir(suite, "joining-client"), List.of(),
                Map.of("stagewright.client.connect", "127.0.0.1:" + port), suite, source);
    }

    private static void declare(NamedDomainObjectContainer<Object> runs, String name, String side, String runDir,
                                List<String> programArgs, Map<String, String> properties, StageWrightTestSuite suite,
                                SourceSet source) {
        if (runs.findByName(name) != null) {
            throw new GradleException("stagewright test suite '" + suite.getName() + "' would declare the run '" + name
                    + "', which the build already declares");
        }
        runs.create(name, run -> {
            InvokerHelper.invokeMethod(run, side, null);
            InvokerHelper.invokeMethod(run, "runDir", runDir);
            InvokerHelper.invokeMethod(run, "ideConfigGenerated", false);
            InvokerHelper.invokeMethod(run, "source", source);
            for (String arg : programArgs) InvokerHelper.invokeMethod(run, "programArg", arg);
            for (String arg : suite.getJvmArgs().get()) InvokerHelper.invokeMethod(run, "vmArg", arg);
            suite.getSystemProperties().get().forEach((k, v) -> InvokerHelper.invokeMethod(run, "property",
                    new Object[] {k, v}));
            properties.forEach((k, v) -> InvokerHelper.invokeMethod(run, "property", new Object[] {k, v}));
        });
    }

    private static SourceSet sourceSet(Project loader, StageWrightTestSuite suite) {
        SourceSetContainer sets = loader.getExtensions().getByType(JavaPluginExtension.class).getSourceSets();
        String name = suite.getRunSourceSet().getOrElse(sets.findByName("testmod") != null ? "testmod" : "main");
        SourceSet set = sets.findByName(name);
        if (set == null) {
            throw new GradleException("stagewright test suite '" + suite.getName() + "': " + loader.getPath()
                    + " has no source set '" + name + "' to start its runs from (has: " + sets.getNames() + ")");
        }
        return set;
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
