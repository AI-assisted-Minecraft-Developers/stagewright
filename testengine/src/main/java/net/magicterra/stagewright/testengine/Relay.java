package net.magicterra.stagewright.testengine;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.junit.platform.engine.EngineExecutionListener;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.reporting.ReportEntry;
import org.junit.platform.engine.support.descriptor.MethodSource;

/**
 * Turns one game's test events into the outer tree's: a game node is matched to an outer one by class, method and
 * parameter types, or by script file and test name, and reported under the outer node's id. Called on the supervisor's reader thread while the game
 * runs, then once more from the engine's thread to close out what the game never reported, hence synchronized.
 */
final class Relay implements Consumer<Map<String, Object>> {

    private final EngineExecutionListener listener;
    private final String sceneName;
    private final Coverage coverage;
    private final Map<String, Nodes.TestClass> classes = new HashMap<>();
    private final Map<String, Nodes.TestMethod> methods = new HashMap<>();
    private final Map<String, TestDescriptor> scriptFiles = new HashMap<>();
    private final Map<String, TestDescriptor> scriptTests = new HashMap<>();
    private final Map<String, TestDescriptor> byGameId = new HashMap<>();
    private final Set<TestDescriptor> planned = new HashSet<>();
    private final Set<TestDescriptor> started = new HashSet<>();
    private final Set<TestDescriptor> finished = new HashSet<>();
    /** Classes and script files a round finished unsuccessfully, held until close-out. */
    private final Map<TestDescriptor, TestExecutionResult> containers = new HashMap<>();
    private boolean plan;
    private boolean ended;
    private int restart = -1;
    private Throwable error;

    Relay(Nodes.Scene scene, EngineExecutionListener listener, Coverage coverage) {
        this.listener = listener;
        this.sceneName = scene.name;
        this.coverage = coverage;
        for (TestDescriptor c : scene.getChildren()) {
            if (c instanceof Nodes.ScriptFile file) {
                scriptFiles.put(file.resource, file);
                for (TestDescriptor t : file.getChildren()) scriptTests.put(((Nodes.ScriptTest) t).key(), t);
                continue;
            }
            Nodes.TestClass tc = (Nodes.TestClass) c;
            classes.put(tc.className, tc);
            for (TestDescriptor m : tc.getChildren()) {
                Nodes.TestMethod tm = (Nodes.TestMethod) m;
                methods.put(tm.key(), tm);
            }
        }
    }

    @Override
    public synchronized void accept(Map<String, Object> event) {
        switch (String.valueOf(event.get("type"))) {
            case "planned" -> {
                plan = true;
                if (event.get("nodes") instanceof List<?> nodes) {
                    for (Object n : nodes) if (n instanceof Map<?, ?> node) match(node);
                }
            }
            case "dynamic" -> {
                if (event.get("node") instanceof Map<?, ?> node) register(node);
            }
            case "started" -> {
                TestDescriptor d = byGameId.get(String.valueOf(event.get("id")));
                String key = key(d);
                if (key != null) coverage.ran(key);
                start(d);
            }
            case "skipped" -> {
                TestDescriptor d = byGameId.get(String.valueOf(event.get("id")));
                String reason = String.valueOf(event.get("reason"));
                if (d != null && !started.contains(d) && finished.add(d)) {
                    listener.executionSkipped(d, reason);
                    if (key(d) != null) coverage.skipped(key(d), sceneName, reason);
                    // Skipping a node skips what is under it without an event of its own; closing those out
                    // later would report them as never run.
                    for (TestDescriptor child : d.getDescendants()) {
                        finished.add(child);
                        if (key(child) != null) coverage.skipped(key(child), sceneName, reason);
                    }
                }
            }
            case "finished" -> {
                TestDescriptor d = byGameId.get(String.valueOf(event.get("id")));
                if (d == null || finished.contains(d)) return;
                String status = String.valueOf(event.get("status"));
                Map<?, ?> failure = event.get("failure") instanceof Map<?, ?> f ? f : null;
                TestExecutionResult result = switch (status) {
                    case "SUCCESSFUL" -> TestExecutionResult.successful();
                    case "ABORTED" -> TestExecutionResult.aborted(failure == null ? null : Relayed.failure(failure, true));
                    default -> TestExecutionResult.failed(failure == null
                            ? new AssertionError("the game reported a failure without a throwable")
                            : Relayed.failure(failure, false));
                };
                // A class or script file may have tests in a later round, after a restart: it finishes at close-out,
                // with the first failure any round reported for it.
                if (d instanceof Nodes.TestClass || d instanceof Nodes.ScriptFile) {
                    if (result.getStatus() != TestExecutionResult.Status.SUCCESSFUL) containers.putIfAbsent(d, result);
                    return;
                }
                // Children end before their parent: what the game never ran under this node is closed out first.
                for (TestDescriptor child : d.getChildren()) {
                    if (!finished.contains(child)) closeOut(child, null, "the game finished its parent without running it");
                }
                finish(d, result);
            }
            case "report" -> {
                TestDescriptor d = byGameId.get(String.valueOf(event.get("id")));
                if (d != null && event.get("entries") instanceof Map<?, ?> entries && !entries.isEmpty()) {
                    Map<String, String> values = new LinkedHashMap<>();
                    entries.forEach((k, v) -> values.put(String.valueOf(k), String.valueOf(v)));
                    listener.reportingEntryPublished(d, ReportEntry.from(values));
                }
            }
            case "error" -> error =event.get("failure") instanceof Map<?, ?> f ? Relayed.failure(f, false)
                    : new IllegalStateException("the game's test run could not start");
            case "restart" -> restart = event.get("round") instanceof Number n ? n.intValue() : -1;
            case "ended" -> ended = true;
            default -> {
                // A newer game may send more; what this version does not know it leaves alone.
            }
        }
    }

    /** Whether the game finished its run, reporting everything it was going to report. */
    synchronized boolean ended() {
        return ended;
    }

    /** The round the game asked to be started again for, once its run ended; -1 when it asked for none. */
    synchronized int restart() {
        return restart;
    }

    /** The next round's game is about to start: what this one said about its end no longer applies. */
    synchronized void nextRound() {
        ended = false;
        restart = -1;
    }

    /**
     * Report every outer node the game did not finish as failed, saying why: the run's own error, the failure of the
     * class or file around it, a test the game never discovered, or {@code gameEnded} — why the game stopped before
     * reaching it.
     */
    synchronized void closeOut(Nodes.Scene scene, String gameEnded) {
        for (TestDescriptor c : scene.getChildren()) {
            TestExecutionResult own = containers.get(c);
            Throwable around = own == null ? null : own.getThrowable().orElse(null);
            boolean anyClosed = false;
            for (TestDescriptor m : c.getChildren()) anyClosed |= closeOut(m, around, gameEnded);
            if (finished.contains(c)) continue;
            start(c);
            if (own != null) finish(c, own);
            else finish(c, anyClosed ? TestExecutionResult.failed(new AssertionError("not every test in it ran"))
                    : TestExecutionResult.successful());
        }
    }

    private boolean closeOut(TestDescriptor d, Throwable around, String gameEnded) {
        boolean closed = false;
        for (TestDescriptor child : d.getChildren()) closed |= closeOut(child, around, gameEnded);
        if (finished.contains(d)) return closed;
        start(d);
        finish(d, TestExecutionResult.failed(around != null ? around : why(d, gameEnded)));
        return true;
    }

    private Throwable why(TestDescriptor d, String gameEnded) {
        if (error != null) return error;
        if (plan && !planned.contains(d) && (d instanceof Nodes.ScriptFile || d instanceof Nodes.ScriptTest)) {
            return new AssertionError("the game did not register this script test: the file failed to evaluate there,"
                    + " registered it only outside the game, or is not the file read here. The game's log says which");
        }
        if (plan && !planned.contains(d) && !(d instanceof Nodes.Dynamic)) {
            return new AssertionError("the game did not discover this test: its class failed to load there, or the"
                    + " class the game has is not the one scanned here. The game's log says which");
        }
        return new AssertionError("the game ended before this test finished: " + gameEnded);
    }

    /** A test's key for coverage, the same in every scene; null for a container. */
    private static String key(TestDescriptor d) {
        if (d instanceof Nodes.TestMethod m) return m.key();
        if (d instanceof Nodes.ScriptTest t) return t.key();
        return null;
    }

    private void match(Map<?, ?> node) {
        if (node.get("script") instanceof String script) {
            TestDescriptor d = Boolean.TRUE.equals(node.get("test"))
                    ? scriptTests.get(script + "#" + node.get("name"))
                    : scriptFiles.get(script);
            if (d == null) return;
            byGameId.put(String.valueOf(node.get("id")), d);
            planned.add(d);
            return;
        }
        String className = node.get("class") instanceof String s ? s : null;
        if (className == null) return;
        TestDescriptor d = node.get("method") instanceof String method
                ? methods.get(className + "#" + method + "(" + node.get("parameters") + ")")
                : classes.get(className);
        if (d == null) return;
        byGameId.put(String.valueOf(node.get("id")), d);
        planned.add(d);
    }

    private void register(Map<?, ?> node) {
        TestDescriptor parent = byGameId.get(String.valueOf(node.get("parent")));
        if (parent == null) return;
        String gameId = String.valueOf(node.get("id"));
        UniqueId.Segment last = UniqueId.parse(gameId).getLastSegment();
        Nodes.Dynamic d = new Nodes.Dynamic(parent.getUniqueId().append(last.getType(), last.getValue()),
                String.valueOf(node.get("name")), parent.getSource().filter(MethodSource.class::isInstance).orElse(null),
                Boolean.TRUE.equals(node.get("test")));
        parent.addChild(d);
        byGameId.put(gameId, d);
        listener.dynamicTestRegistered(d);
    }

    private void start(TestDescriptor d) {
        if (d == null || started.contains(d) || finished.contains(d)) return;
        d.getParent().filter(p -> !(p instanceof Nodes.Scene)).ifPresent(this::start);
        started.add(d);
        listener.executionStarted(d);
    }

    private void finish(TestDescriptor d, TestExecutionResult result) {
        if (!started.contains(d) || !finished.add(d)) return;
        listener.executionFinished(d, result);
    }
}
