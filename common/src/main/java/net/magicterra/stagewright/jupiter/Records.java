package net.magicterra.stagewright.jupiter;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.function.Consumer;
import net.magicterra.stagewright.StageWrightCommon;
import net.magicterra.stagewright.harness.AgentBridge;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.support.descriptor.ClasspathResourceSource;
import org.junit.platform.engine.support.descriptor.MethodSource;

/**
 * A value a test recorded beside its result — {@code t.record(name, value)} in a script, a Java test's
 * {@code TestReporter} entry — on its way out of the game: one line in the game's log, and a {@code record} test event
 * to whoever started the game, which puts it in its own report.
 *
 * <p>Carried here rather than as a JUnit {@code ReportEntry}, which refuses a blank value: a script recording an empty
 * result is ordinary, and such a refusal could not even be caught. A blank value is kept as the empty string.
 */
final class Records {

    private static final Gson GSON = new Gson();

    private Records() {}

    /** {@code value} under {@code name} for the test with {@code uniqueId}; {@code label} names it in the log. */
    static void publish(String uniqueId, String label, String name, String value) {
        publish(AgentBridge.system()::test, uniqueId, label, name, value);
    }

    /** As {@link #publish(String, String, String, String)}, the event handed to {@code send}. */
    static void publish(Consumer<String> send, String uniqueId, String label, String name, String value) {
        String text = value == null || value.isBlank() ? "" : value;
        StageWrightCommon.LOG.info("[{}] record {}: {} = {}", StageWrightCommon.MOD_ID, label, name, oneLine(text));
        JsonObject o = new JsonObject();
        o.addProperty("type", "record");
        o.addProperty("id", uniqueId);
        o.addProperty("name", name);
        o.addProperty("value", text);
        send.accept(GSON.toJson(o));
    }

    /** How a test is named in the log: {@code Class#method}, or a script's {@code file#test}. */
    static String label(TestSource source, String displayName) {
        if (source instanceof MethodSource m) return m.getClassName() + "#" + m.getMethodName();
        if (source instanceof ClasspathResourceSource r) return r.getClasspathResourceName() + "#" + displayName;
        return displayName;
    }

    /** One line per record, whatever the value holds. */
    static String oneLine(String value) {
        return value.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n");
    }
}
