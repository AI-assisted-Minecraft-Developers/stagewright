package net.magicterra.stagewright.jupiter;

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

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.magicterra.stagewright.StageWrightCommon;
import net.magicterra.stagewright.harness.AgentBridge;
import net.magicterra.stagewright.harness.StageWrightHarness;
import net.minecraft.server.MinecraftServer;
import org.junit.platform.engine.ConfigurationParameters;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
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
 * <p>Which tests: the classes in {@value #CLASSES} and the script resources in {@value #SCRIPTS} (each
 * comma-separated) when either is set, else every entry of every {@value #INDEX} resource on the game's class path,
 * one per line, a name ending {@code .js} or {@code .yaml} being a script ({@link ScriptTestEngine}). Nothing runs unless one of
 * {@value #CLASSES}, {@value #SCRIPTS} or {@value #ENABLED} is set. Reports go to {@code <game dir>/stagewright/junit/}: JUnit XML
 * and a {@code summary.json}. With {@value #EXIT} the server stops once the run is over.
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
            StageWrightCommon.LOG.info("[{}] running {} JUnit test classes and {} test scripts in the game",
                    StageWrightCommon.MOD_ID, classes.size(), scripts.size());
            GameImpl game = new GameImpl(server);
            String world = System.getProperty(SceneWorld.PROPERTY);
            if (world != null) {
                JsonObject declared = JsonParser.parseString(world).getAsJsonObject();
                StageWrightCommon.LOG.info("[{}] scene world: {}", StageWrightCommon.MOD_ID,
                        game.onServer(s -> SceneWorld.apply(s, declared)));
            }
            GameExtension.bind(game);
            LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                    .selectors(classes.stream().map(DiscoverySelectors::selectClass).toList())
                    .selectors(scripts.stream().map(DiscoverySelectors::selectClasspathResource).toList())
                    .configurationParameter("junit.jupiter.extensions.autodetection.enabled", "true")
                    // Below a junit-platform.properties on the game's class path, or a -D, so either may change them.
                    .parentConfigurationParameters(new Defaults(Map.of(
                            "junit.jupiter.testmethod.order.default", DependencyOrder.class.getName(),
                            "junit.jupiter.testclass.order.default", DependencyOrder.class.getName(),
                            "junit.jupiter.execution.parallel.enabled", "true",
                            "junit.jupiter.execution.parallel.mode.default", "concurrent",
                            "junit.jupiter.execution.parallel.mode.classes.default", "concurrent")))
                    .build();
            SummaryGeneratingListener summary = new SummaryGeneratingListener();
            StringWriter xmlLog = new StringWriter();
            // Handed over, not listed in META-INF/services: under Fabric's Knot a services file in this jar hides
            // the one in junit-jupiter-engine, and Jupiter goes missing.
            Launcher launcher = LauncherFactory.create(LauncherConfig.builder()
                    .addTestEngines(new ScriptTestEngine()).build());
            TestPlan plan = launcher.discover(request);
            List<String> cycles = Dependencies.prepare(plan);
            if (!cycles.isEmpty()) {
                throw new IllegalStateException("@DependsOn cycles, so no test can go first: " + cycles);
            }
            launcher.execute(plan, summary, relay, Dependencies.ENDINGS,
                    new LegacyXmlReportGeneratingListener(reports, new PrintWriter(xmlLog)));
            writeSummary(reports.resolve("summary.json"), entries, summary.getSummary());
            StringWriter text = new StringWriter();
            summary.getSummary().printTo(new PrintWriter(text));
            summary.getSummary().printFailuresTo(new PrintWriter(text), 20);
            StageWrightCommon.LOG.info("[{}] JUnit run finished:\n{}", StageWrightCommon.MOD_ID, text);
        } catch (Throwable t) {
            StageWrightCommon.LOG.error("[{}] the JUnit run could not complete", StageWrightCommon.MOD_ID, t);
            relay.error(t);
            try {
                Files.writeString(reports.resolve("summary.json"), "{\"error\":" + new com.google.gson.Gson().toJson(
                        String.valueOf(t)) + "}");
            } catch (IOException ignored) {
                // the log line above is the record
            }
        } finally {
            if (Boolean.getBoolean(EXIT)) server.execute(() -> StageWrightHarness.haltAfterResults(server));
        }
    }

    private static List<String> split(String listed) {
        return Arrays.stream(listed.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
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
                in.lines().map(String::trim).filter(s -> !s.isEmpty() && !s.startsWith("#")).forEach(names::add);
            }
        }
        return new ArrayList<>(names);
    }

    private static void writeSummary(Path file, List<String> selected, TestExecutionSummary s) throws IOException {
        JsonObject o = new JsonObject();
        JsonArray listed = new JsonArray();
        selected.forEach(listed::add);
        o.add("selected", listed);
        o.addProperty("found", s.getTestsFoundCount());
        o.addProperty("succeeded", s.getTestsSucceededCount());
        o.addProperty("failed", s.getTestsFailedCount());
        o.addProperty("aborted", s.getTestsAbortedCount());
        o.addProperty("skipped", s.getTestsSkippedCount());
        o.addProperty("containersFailed", s.getContainersFailedCount());
        JsonArray failures = new JsonArray();
        for (TestExecutionSummary.Failure f : s.getFailures()) {
            JsonObject row = new JsonObject();
            row.addProperty("test", f.getTestIdentifier().getUniqueId());
            row.addProperty("message", String.valueOf(f.getException()));
            failures.add(row);
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
