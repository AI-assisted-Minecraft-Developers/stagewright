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
import java.util.Set;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.magicterra.stagewright.StageWrightCommon;
import net.magicterra.stagewright.harness.StageWrightHarness;
import net.minecraft.server.MinecraftServer;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;
import org.junit.platform.reporting.legacy.xml.LegacyXmlReportGeneratingListener;

/**
 * Runs JUnit tests inside the game, on a thread of their own, once the server's tick cadence has settled.
 *
 * <p>Which classes: {@value #CLASSES} (comma-separated) when set, else every class named in a
 * {@value #INDEX} resource on the game's class path, one per line. Nothing runs unless one of the two properties
 * {@value #CLASSES} or {@value #ENABLED} is set. Reports go to {@code <game dir>/stagewright/junit/}: JUnit XML
 * and a {@code summary.json}. With {@value #EXIT} the server stops once the run is over.
 */
public final class JupiterRun {

    static final String ENABLED = "stagewright.junit";
    static final String CLASSES = "stagewright.junit.classes";
    static final String EXIT = "stagewright.junit.exitWhenDone";
    static final String INDEX = "META-INF/stagewright/tests";

    private static volatile boolean started;

    private JupiterRun() {}

    /** Server thread, every settled tick: answer pending waits, and start the run the first time it may. */
    public static void tick(MinecraftServer server) {
        ServerTicks.tick();
        if (started) return;
        String listed = System.getProperty(CLASSES);
        if (listed == null && !Boolean.getBoolean(ENABLED)) return;
        started = true;
        // The server thread's context loader, the one scene discovery's ServiceLoader uses.
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        Thread thread = new Thread(() -> run(server, listed, loader), "stagewright-tests");
        thread.setDaemon(true);
        // Jupiter loads test classes and finds extensions through the context loader.
        thread.setContextClassLoader(loader);
        thread.start();
    }

    /** Server thread, as the server stops. */
    public static void stop() {
        ServerTicks.stop();
    }

    private static void run(MinecraftServer server, String listed, ClassLoader loader) {
        Path reports = server.getServerDirectory().resolve("stagewright/junit");
        try {
            Files.createDirectories(reports);
            List<String> classes = listed != null ? split(listed) : indexed(loader);
            StageWrightCommon.LOG.info("[{}] running {} JUnit test classes in the game", StageWrightCommon.MOD_ID,
                    classes.size());
            GameExtension.bind(new GameImpl(server));
            LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                    .selectors(classes.stream().map(DiscoverySelectors::selectClass).toList())
                    .configurationParameter("junit.jupiter.extensions.autodetection.enabled", "true")
                    .configurationParameter("junit.jupiter.testmethod.order.default", DependencyOrder.class.getName())
                    .configurationParameter("junit.jupiter.testclass.order.default", DependencyOrder.class.getName())
                    .build();
            SummaryGeneratingListener summary = new SummaryGeneratingListener();
            StringWriter xmlLog = new StringWriter();
            Launcher launcher = LauncherFactory.create();
            TestPlan plan = launcher.discover(request);
            List<String> cycles = Dependencies.prepare(plan);
            if (!cycles.isEmpty()) {
                throw new IllegalStateException("@DependsOn cycles, so no test can go first: " + cycles);
            }
            launcher.execute(plan, summary, new LegacyXmlReportGeneratingListener(reports, new PrintWriter(xmlLog)));
            writeSummary(reports.resolve("summary.json"), classes, summary.getSummary());
            StringWriter text = new StringWriter();
            summary.getSummary().printTo(new PrintWriter(text));
            summary.getSummary().printFailuresTo(new PrintWriter(text), 20);
            StageWrightCommon.LOG.info("[{}] JUnit run finished:\n{}", StageWrightCommon.MOD_ID, text);
        } catch (Throwable t) {
            StageWrightCommon.LOG.error("[{}] the JUnit run could not complete", StageWrightCommon.MOD_ID, t);
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

    /** Every class every {@value #INDEX} resource names, in class-path order, once each. */
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

    private static void writeSummary(Path file, List<String> classes, TestExecutionSummary s) throws IOException {
        JsonObject o = new JsonObject();
        JsonArray listed = new JsonArray();
        classes.forEach(listed::add);
        o.add("classes", listed);
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
}
