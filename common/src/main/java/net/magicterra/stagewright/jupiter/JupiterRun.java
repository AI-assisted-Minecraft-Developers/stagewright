package net.magicterra.stagewright.jupiter;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.magicterra.stagewright.StageWrightCommon;
import net.magicterra.stagewright.harness.AgentBridge;
import net.minecraft.server.MinecraftServer;
import org.junit.platform.engine.ConfigurationParameters;
import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.FilterResult;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.EngineFilter;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.PostDiscoveryFilter;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherConfig;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;
import org.junit.platform.reporting.legacy.xml.LegacyXmlReportGeneratingListener;

/**
 * Runs JUnit tests inside the game, on a thread of their own, once the server's tick cadence has settled.
 *
 * <p>Which tests: the classes in {@value #CLASSES} and the script resources in {@value #SCRIPTS} (each comma-separated)
 * when either is set, else every entry of every {@value #INDEX} resource on the game's class path, one per line, a name
 * ending {@code .js} or {@code .yaml} being a script ({@link ScriptTestEngine}). Nothing runs unless one of
 * {@value #CLASSES}, {@value #SCRIPTS} or {@value #ENABLED} is set. Reports go to {@code <game
 * dir>/stagewright/junit/}: JUnit XML and a {@code summary.json}. With {@value #EXIT} the server stops once the run is
 * over.
 */
public final class JupiterRun {

    static final String ENABLED = "stagewright.junit";
    static final String CLASSES = "stagewright.junit.classes";
    static final String SCRIPTS = "stagewright.junit.scripts";
    static final String EXIT = "stagewright.junit.exitWhenDone";
    static final String INDEX = "META-INF/stagewright/tests";
    static final String AWAIT_PLAYER = "stagewright.awaitPlayer";

    private static volatile boolean started;

    private JupiterRun() {}

    /**
     * Whether the test engine chose this game's tests. Such a game runs them and not the scene suite: the run task's
     * own {@code stagewright.autorun} cannot be overridden from the command line, since a loader may set its run
     * properties after the JVM has read the command line's.
     */
    public static boolean selected() {
        return System.getProperty(CLASSES) != null || System.getProperty(SCRIPTS) != null;
    }

    /** Server thread, every settled tick: answer pending waits, and start the run the first time it may. */
    public static void tick(MinecraftServer server) {
        ServerTicks.tick();
        if (started) return;
        if (!selected() && !Boolean.getBoolean(ENABLED)) return;
        // A topology with a real client holds the tests until it has joined, as it holds the scene suite.
        if (Boolean.getBoolean(AWAIT_PLAYER) && server.getPlayerCount() == 0) return;
        started = true;
        // The server thread's context loader, the one scene discovery's ServiceLoader uses.
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        Thread thread = new Thread(() -> run(server, loader), "stagewright-tests");
        thread.setDaemon(true);
        // Jupiter loads test classes and finds extensions through the context loader.
        thread.setContextClassLoader(loader);
        thread.start();
    }

    /** Server thread, as the server stops. */
    public static void stop() {
        ServerTicks.stop();
        ClientConnection.close();
    }

    private static void run(MinecraftServer server, ClassLoader loader) {
        Path reports = server.getServerDirectory().resolve("stagewright/junit");
        AgentEvents relay = new AgentEvents(AgentBridge.system());
        try {
            Files.createDirectories(reports);
            List<String> entries = selected()
                    ? concat(split(System.getProperty(CLASSES, "")), split(System.getProperty(SCRIPTS, "")))
                    : indexed(loader);
            List<String> classes = entries.stream().filter(e -> !isScript(e)).toList();
            List<String> scripts = entries.stream().filter(JupiterRun::isScript).toList();
            StageWrightCommon.LOG.info(
                    "[{}] running {} JUnit test classes and {} test scripts in the game",
                    StageWrightCommon.MOD_ID,
                    classes.size(),
                    scripts.size());
            GameImpl game = new GameImpl(server);
            String world = System.getProperty(SceneWorld.PROPERTY);
            if (world != null) {
                JsonObject declared = JsonParser.parseString(world).getAsJsonObject();
                StageWrightCommon.LOG.info(
                        "[{}] scene world: {}",
                        StageWrightCommon.MOD_ID,
                        game.onServer(s -> SceneWorld.apply(s, declared)));
            }
            GameExtension.bind(game);
            // Handed over, not listed in META-INF/services: under Fabric's Knot a services file in this jar hides
            // the one in junit-jupiter-engine, and Jupiter goes missing.
            Path artifacts = server.getServerDirectory().resolve("stagewright");
            Rounds.load(artifacts);
            Handovers.load(artifacts, Rounds.current());
            ScriptTestEngine scriptEngine = new ScriptTestEngine();
            Launcher launcher = LauncherFactory.create(
                    LauncherConfig.builder().addTestEngines(scriptEngine).build());
            // Two plans, run side by side so a test of either kind may wait for one of the other. The scripts are
            // discovered first: the Java tests' order, decided as they are discovered, follows dependencies through
            // them.
            TestPlan scriptPlan = launcher.discover(request(
                    scripts.stream()
                            .map(DiscoverySelectors::selectClasspathResource)
                            .toList(),
                    ScriptTestEngine.ID));
            Dependencies.scripts(scriptEngine.discoveredTests());
            TestPlan javaPlan = launcher.discover(request(
                    classes.stream().map(DiscoverySelectors::selectClass).toList(), "junit-jupiter"));
            List<String> cycles = Dependencies.prepare(javaPlan);
            if (!cycles.isEmpty()) {
                throw new IllegalStateException("@DependsOn cycles, so no test can go first: " + cycles);
            }
            SummaryGeneratingListener javaSummary = new SummaryGeneratingListener();
            SummaryGeneratingListener scriptSummary = new SummaryGeneratingListener();
            StringWriter xmlLog = new StringWriter();
            Thread scriptThread = new Thread(
                    () -> {
                        try {
                            launcher.execute(
                                    scriptPlan,
                                    scriptSummary,
                                    relay,
                                    new LegacyXmlReportGeneratingListener(reports, new PrintWriter(xmlLog)));
                        } finally {
                            Dependencies.scriptsOver();
                        }
                    },
                    "stagewright-scripts");
            scriptThread.setDaemon(true);
            scriptThread.setContextClassLoader(loader);
            scriptThread.start();
            try {
                launcher.execute(
                        javaPlan,
                        javaSummary,
                        relay,
                        Dependencies.ENDINGS,
                        new LegacyXmlReportGeneratingListener(reports, new PrintWriter(xmlLog)));
            } finally {
                Dependencies.javaOver();
                scriptThread.join();
            }
            boolean restarting = Rounds.latest() > Rounds.current();
            Handovers.sweep(artifacts, restarting);
            Rounds.save(artifacts, Dependencies.outcomes());
            if (restarting) {
                StageWrightCommon.LOG.info(
                        "[{}] round {} is over; tests remain for round {}, after a restart",
                        StageWrightCommon.MOD_ID,
                        Rounds.current(),
                        Rounds.current() + 1);
                relay.restart(Rounds.current() + 1);
            }
            relay.ended();
            List<TestExecutionSummary> summaries = List.of(javaSummary.getSummary(), scriptSummary.getSummary());
            writeSummary(reports.resolve("summary.json"), entries, summaries);
            StringWriter text = new StringWriter();
            for (TestExecutionSummary s : summaries) {
                s.printTo(new PrintWriter(text));
                s.printFailuresTo(new PrintWriter(text), 20);
            }
            StageWrightCommon.LOG.info("[{}] JUnit run finished:\n{}", StageWrightCommon.MOD_ID, text);
        } catch (Throwable t) {
            StageWrightCommon.LOG.error("[{}] the JUnit run could not complete", StageWrightCommon.MOD_ID, t);
            relay.error(t);
            try {
                Files.writeString(
                        reports.resolve("summary.json"),
                        "{\"error\":" + new com.google.gson.Gson().toJson(String.valueOf(t)) + "}");
            } catch (IOException ignored) {
                // the log line above is the record
            }
        } finally {
            if (Boolean.getBoolean(EXIT)) server.execute(() -> halt(server));
        }
    }

    /** Grace between the server halting and forcing the JVM down: long enough for the save {@code halt} triggers. */
    private static final long EXIT_GRACE_MS = 30_000;

    /**
     * End the game once its results are sent: halt the server, and make sure the JVM goes with it. A mod's leftover
     * non-daemon thread keeps a JVM up after its world has saved; the supervision agent ends such a JVM and says which
     * thread held it, so only a dedicated server without the agent needs this watchdog. A client's integrated server is
     * left to the client's own orderly shutdown.
     */
    private static void halt(MinecraftServer server) {
        // Before the halt: the agent's grace period for a JVM that will not end starts here.
        AgentBridge.system().done();
        server.halt(false);
        if (!server.isDedicatedServer() || AgentBridge.system().attached()) return;
        Thread watchdog = new Thread(
                () -> {
                    try {
                        Thread.sleep(EXIT_GRACE_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    StageWrightCommon.LOG.warn(
                            "[{}] the JVM is still up {}s after the run halted the server: a mod is holding"
                                    + " a non-daemon thread. Forcing exit.",
                            StageWrightCommon.MOD_ID,
                            EXIT_GRACE_MS / 1000);
                    Runtime.getRuntime().halt(0);
                },
                "stagewright-exit-watchdog");
        // Daemon, so it cannot itself become the thread that keeps the JVM alive on a clean shutdown.
        watchdog.setDaemon(true);
        watchdog.start();
    }

    /** Keeps the Java tests of the round this game runs ({@link Rounds}); script tests are kept by their engine. */
    private static final PostDiscoveryFilter THIS_ROUND = descriptor -> {
        if (!(descriptor.getSource().orElse(null) instanceof MethodSource m))
            return FilterResult.included("not a test");
        int round = Rounds.of(m.getClassName() + "#" + m.getMethodName());
        return round == Rounds.current()
                ? FilterResult.included("this round")
                : FilterResult.excluded("runs in round " + round + " of the scene's restarts");
    };

    /** What one engine discovers from {@code selectors}, configured as every in-game run is. */
    private static LauncherDiscoveryRequest request(List<? extends DiscoverySelector> selectors, String engine) {
        return LauncherDiscoveryRequestBuilder.request()
                .selectors(selectors)
                .filters(EngineFilter.includeEngines(engine), THIS_ROUND)
                .configurationParameter("junit.jupiter.extensions.autodetection.enabled", "true")
                // Below a junit-platform.properties on the game's class path, or a -D, so either may change them.
                .parentConfigurationParameters(new Defaults(Map.of(
                        "junit.jupiter.testmethod.order.default", DependencyOrder.class.getName(),
                        "junit.jupiter.testclass.order.default", DependencyOrder.class.getName(),
                        "junit.jupiter.execution.parallel.enabled", "true",
                        "junit.jupiter.execution.parallel.mode.default", "concurrent",
                        "junit.jupiter.execution.parallel.mode.classes.default", "concurrent")))
                .build();
    }

    private static List<String> split(String listed) {
        return Arrays.stream(listed.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static boolean isScript(String entry) {
        return entry.endsWith(".js") || ScriptTestEngine.isYaml(entry);
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    /** Every entry every {@value #INDEX} resource names, in class-path order, once each. */
    private static List<String> indexed(ClassLoader loader) throws IOException {
        Set<String> names = new LinkedHashSet<>();
        Enumeration<URL> resources = loader.getResources(INDEX);
        while (resources.hasMoreElements()) {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(resources.nextElement().openStream(), StandardCharsets.UTF_8))) {
                in.lines()
                        .map(String::trim)
                        .filter(s -> !s.isEmpty() && !s.startsWith("#"))
                        .forEach(names::add);
            }
        }
        return new ArrayList<>(names);
    }

    private static void writeSummary(Path file, List<String> selected, List<TestExecutionSummary> summaries)
            throws IOException {
        JsonObject o = new JsonObject();
        JsonArray listed = new JsonArray();
        selected.forEach(listed::add);
        o.add("selected", listed);
        o.addProperty(
                "found",
                summaries.stream()
                        .mapToLong(TestExecutionSummary::getTestsFoundCount)
                        .sum());
        o.addProperty(
                "succeeded",
                summaries.stream()
                        .mapToLong(TestExecutionSummary::getTestsSucceededCount)
                        .sum());
        o.addProperty(
                "failed",
                summaries.stream()
                        .mapToLong(TestExecutionSummary::getTestsFailedCount)
                        .sum());
        o.addProperty(
                "aborted",
                summaries.stream()
                        .mapToLong(TestExecutionSummary::getTestsAbortedCount)
                        .sum());
        o.addProperty(
                "skipped",
                summaries.stream()
                        .mapToLong(TestExecutionSummary::getTestsSkippedCount)
                        .sum());
        o.addProperty(
                "containersFailed",
                summaries.stream()
                        .mapToLong(TestExecutionSummary::getContainersFailedCount)
                        .sum());
        JsonArray failures = new JsonArray();
        for (TestExecutionSummary s : summaries) {
            for (TestExecutionSummary.Failure f : s.getFailures()) {
                JsonObject row = new JsonObject();
                row.addProperty("test", f.getTestIdentifier().getUniqueId());
                row.addProperty("message", String.valueOf(f.getException()));
                failures.add(row);
            }
        }
        o.add("failures", failures);
        Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(o));
    }

    /** StageWright's defaults, consulted after everything the run's own configuration says. */
    private record Defaults(Map<String, String> values) implements ConfigurationParameters {
        @Override
        public Optional<String> get(String key) {
            return Optional.ofNullable(values.get(key));
        }

        @Override
        public Optional<Boolean> getBoolean(String key) {
            return get(key).map(Boolean::parseBoolean);
        }

        @Override
        @SuppressWarnings("deprecation")
        public int size() {
            return values.size();
        }

        @Override
        public Set<String> keySet() {
            return values.keySet();
        }
    }
}
