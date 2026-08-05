package net.magicterra.stagewright.junit.instrument;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.magicterra.stagewright.junit.StageWright;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared vocabulary for the instrument-contract tests: build a params object, dig a field out of a
 * reply, assert an error says what the contract says it says.
 *
 * <p>No state, no transport of its own — every method works on a snapshot the caller already
 * fetched over the {@link StageWright} facade, the same discipline as the {@code ui} package's
 * {@code UiSupport}.
 */
final class Contract {

    private Contract() {}

    // ------------------------------------------------------------- params ------

    /** {@code p("cmd", "time set day")} — a one-key params object. */
    static JsonObject p(String k, String v) {
        JsonObject o = new JsonObject();
        o.addProperty(k, v);
        return o;
    }

    /** {@code p("ticks", 10)} — a one-key numeric params object. */
    static JsonObject p(String k, Number v) {
        JsonObject o = new JsonObject();
        o.addProperty(k, v);
        return o;
    }

    /** {@code p("nope", true)} — a one-key boolean params object. */
    static JsonObject p(String k, boolean v) {
        JsonObject o = new JsonObject();
        o.addProperty(k, v);
        return o;
    }

    /** A {@code {x,y,z}} block position, the shape every world verb takes. */
    static JsonObject pos(int x, int y, int z) {
        JsonObject o = new JsonObject();
        o.addProperty("x", x);
        o.addProperty("y", y);
        o.addProperty("z", z);
        return o;
    }

    /** Params carrying a nested object under {@code key}. */
    static JsonObject with(String key, JsonElement value) {
        JsonObject o = new JsonObject();
        o.add(key, value);
        return o;
    }

    /** {@code mc.query} params for a radius-filtered block or entity search. */
    static JsonObject query(String q, JsonObject center, int radius, String type) {
        JsonObject filter = new JsonObject();
        filter.addProperty("in_radius", radius);
        filter.addProperty("type", type);
        JsonObject o = new JsonObject();
        o.addProperty("q", q);
        o.add("center", center);
        o.add("filter", filter);
        return o;
    }

    // -------------------------------------------------------------- reads ------

    /** A string field, or "" when absent — never null, so an assertion message always prints. */
    static String str(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    /** A numeric field as a long, or {@code Long.MIN_VALUE} when absent or not a number. */
    static long num(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()
                ? e.getAsLong() : Long.MIN_VALUE;
    }

    /** True only when the key is present AND boolean AND true. */
    static boolean flag(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean()
                && e.getAsBoolean();
    }

    /** A bare-array result (mc.query rows, mc.observe.eventsSince events). */
    static JsonArray rows(StageWright tk, String method, JsonObject params) {
        JsonElement bare = tk.callBare(method, params);
        assertTrue(bare.isJsonArray(), method + " must answer a bare array, got: " + bare);
        return bare.getAsJsonArray();
    }

    /** The i-th element of an array, as an object. */
    static JsonObject at(JsonArray arr, int i) {
        assertTrue(arr.size() > i, "expected at least " + (i + 1) + " rows, got " + arr.size());
        JsonElement e = arr.get(i);
        assertTrue(e.isJsonObject(), "row " + i + " is not an object: " + e);
        return e.getAsJsonObject();
    }

    // ------------------------------------------------------------ asserts ------

    /**
     * Assert a call failed AND that its error says the documented thing.
     *
     * <p>Both halves matter and neither is enough. "It threw" would pass on a connection reset; a
     * substring match alone would NPE on the call that wrongly succeeded. So this reports the two
     * failures differently: one says the driver accepted what it must reject, the other says the
     * rejection drifted.
     */
    static void refuses(StageWright tk, String method, JsonObject params, String... mustContain) {
        String err = tk.errorOf(method, params);
        assertNotNull(err, method + " accepted params it must reject: " + params);
        for (String needle : mustContain) {
            assertTrue(err.contains(needle),
                    method + " error shape drifted — expected it to mention '" + needle
                            + "', got: " + err);
        }
    }

    /** {@link #refuses} with no params. */
    static void refuses(StageWright tk, String method, String... mustContain) {
        refuses(tk, method, new JsonObject(), mustContain);
    }

    /**
     * Run a vanilla command and assert it dispatched. Staging must never fail silently: a check
     * whose setup did not land reads as a driver bug in whatever it asserts next.
     */
    static void cmd(StageWright tk, String command) {
        tk.exec(command);
    }
}
