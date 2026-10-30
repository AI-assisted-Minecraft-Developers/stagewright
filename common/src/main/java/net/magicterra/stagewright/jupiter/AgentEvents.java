package net.magicterra.stagewright.jupiter;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.magicterra.stagewright.harness.AgentBridge;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.reporting.ReportEntry;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.ClasspathResourceSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

/**
 * Relays the in-game run, event by event, to whoever started the game, through the supervision agent's {@code test}
 * event. The outer engine reports these as its own tests. The objects are described in
 * {@code docs/reference/supervision-protocol.md}, "Test events".
 */
final class AgentEvents implements TestExecutionListener {

    /** Frames of a failure's stack sent; the rest are in the game's log. */
    private static final int MAX_FRAMES = 64;

    private static final Gson GSON = new Gson();

    /** Causes followed below a failure; a cycle of causes stops here too. */
    private static final int MAX_CAUSES = 4;

    private final AgentBridge agent;

    AgentEvents(AgentBridge agent) {
        this.agent = agent;
    }

    /** The run failed before or instead of executing: no test result will follow. */
    void error(Throwable t) {
        JsonObject o = event("error");
        o.add("failure", failure(t));
        send(o);
    }

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        JsonObject o = event("planned");
        JsonArray nodes = new JsonArray();
        for (TestIdentifier root : testPlan.getRoots()) {
            nodes.add(node(root));
            for (TestIdentifier id : testPlan.getDescendants(root)) nodes.add(node(id));
        }
        o.add("nodes", nodes);
        send(o);
    }

    @Override
    public void dynamicTestRegistered(TestIdentifier id) {
        JsonObject o = event("dynamic");
        o.add("node", node(id));
        send(o);
    }

    @Override
    public void executionStarted(TestIdentifier id) {
        JsonObject o = event("started");
        o.addProperty("id", id.getUniqueId());
        send(o);
    }

    @Override
    public void executionSkipped(TestIdentifier id, String reason) {
        JsonObject o = event("skipped");
        o.addProperty("id", id.getUniqueId());
        o.addProperty("reason", reason);
        send(o);
    }

    @Override
    public void executionFinished(TestIdentifier id, TestExecutionResult result) {
        JsonObject o = event("finished");
        o.addProperty("id", id.getUniqueId());
        o.addProperty("status", result.getStatus().name());
        result.getThrowable().ifPresent(t -> o.add("failure", failure(t)));
        send(o);
    }

    /** A Java test's {@code TestReporter} entry: each of its pairs a record, as a script's {@code t.record} is. */
    @Override
    public void reportingEntryPublished(TestIdentifier id, ReportEntry entry) {
        String label = Records.label(id.getSource().orElse(null), id.getDisplayName());
        entry.getKeyValuePairs().forEach((name, value) -> Records.publish(id.getUniqueId(), label, name, value));
    }

    /**
     * Tests remain for a later round: whoever started this game starts it again on the same world, with
     * {@code -Dstagewright.junit.round=<round>}, once this one has exited.
     */
    void restart(int round) {
        JsonObject o = event("restart");
        o.addProperty("round", round);
        send(o);
    }

    /** Every plan of the run has finished: the Java tests' and the script tests', each announced as it started. */
    void ended() {
        send(event("ended"));
    }

    private JsonObject node(TestIdentifier id) {
        JsonObject n = new JsonObject();
        n.addProperty("id", id.getUniqueId());
        id.getParentId().ifPresent(p -> n.addProperty("parent", p));
        n.addProperty("name", id.getDisplayName());
        n.addProperty("test", id.isTest());
        id.getSource().ifPresent(source -> {
            if (source instanceof MethodSource m) {
                n.addProperty("class", m.getClassName());
                n.addProperty("method", m.getMethodName());
                n.addProperty("parameters", m.getMethodParameterTypes());
            } else if (source instanceof ClassSource c) {
                n.addProperty("class", c.getClassName());
            } else if (source instanceof ClasspathResourceSource r) {
                // A script file or one of its tests; a test is told apart by its name.
                n.addProperty("script", r.getClasspathResourceName());
            }
        });
        return n;
    }

    private static JsonObject failure(Throwable t) {
        return failure(t, 0);
    }

    private static JsonObject failure(Throwable t, int depth) {
        JsonObject f = new JsonObject();
        f.addProperty("class", t.getClass().getName());
        if (t.getMessage() != null) f.addProperty("message", t.getMessage());
        JsonArray frames = new JsonArray();
        StackTraceElement[] stack = t.getStackTrace();
        for (int i = 0; i < Math.min(stack.length, MAX_FRAMES); i++) frames.add(stack[i].toString());
        f.add("trace", frames);
        if (t.getCause() != null && t.getCause() != t && depth < MAX_CAUSES) {
            f.add("cause", failure(t.getCause(), depth + 1));
        }
        return f;
    }

    private static JsonObject event(String type) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        return o;
    }

    private void send(JsonObject o) {
        agent.test(GSON.toJson(o));
    }
}
