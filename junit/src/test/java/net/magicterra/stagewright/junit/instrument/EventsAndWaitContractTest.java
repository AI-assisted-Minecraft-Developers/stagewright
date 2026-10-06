package net.magicterra.stagewright.junit.instrument;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.magicterra.stagewright.junit.StageWright;
import net.magicterra.stagewright.junit.Face;
import net.magicterra.stagewright.junit.RequiresFace;
import net.magicterra.stagewright.junit.StageWrightExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;

import static net.magicterra.stagewright.junit.instrument.Contract.cmd;
import static net.magicterra.stagewright.junit.instrument.Contract.flag;
import static net.magicterra.stagewright.junit.instrument.Contract.num;
import static net.magicterra.stagewright.junit.instrument.Contract.p;
import static net.magicterra.stagewright.junit.instrument.Contract.pos;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The observe ring's cursor semantics, and the two blocking verbs built on them. */
@ExtendWith(StageWrightExtension.class)
@EnabledIfEnvironmentVariable(named = "TESTKIT_ENDPOINT", matches = ".+")
@RequiresFace(Face.SERVER)
class EventsAndWaitContractTest {

    /**
     * A dispatched command lands in the observe ring with its success flag.
     *
     * <p>The cursor is a decimal string, never a JSON number: a seq is 64-bit, and a JavaScript
     * reader would round it. {@code mc.observe.eventsSince} answers {@code {events, cursor}}, the
     * cursor being the one to pass back.
     *
     * <p>The staged command is a {@code fill}, not a {@code setblock}: a plain setblock takes the
     * driver's fast path, which returns before the {@code command.result} emit. Only the Brigadier
     * branch produces the event this is about.
     */
    @Test
    void aDispatchedCommandEmitsItsResult(StageWright tk) {
        long cursor = cursor(tk);
        cmd(tk, "fill 40 200 40 40 200 40 minecraft:iron_block");

        JsonObject params = p("cursor", Long.toString(cursor));
        JsonArray types = new JsonArray();
        types.add("command.result");
        params.add("types", types);

        JsonObject r = tk.call("mc.observe.eventsSince", params);
        assertTrue(r.has("events") && r.get("events").isJsonArray(), "eventsSince carries no events array: " + r);
        assertTrue(Long.parseLong(r.get("cursor").getAsString()) > cursor,
                "eventsSince did not advance the cursor past the event it returned: " + r);

        JsonObject hit = null;
        for (JsonElement e : r.getAsJsonArray("events")) {
            if (e.isJsonObject() && e.toString().contains("iron_block")) {
                hit = e.getAsJsonObject();
                break;
            }
        }
        assertTrue(hit != null, "no command.result event for a dispatched command: " + r);
        assertTrue(hit.get("seq").getAsJsonPrimitive().isString(), "an event's seq must be a string: " + hit);
        assertTrue(flag(payloadOf(hit), "success"),
                "success flag wrong on a succeeding command: " + hit);
    }

    @Test
    void theCursorOnlyMovesForward(StageWright tk) {
        long before = cursor(tk);
        cmd(tk, "setblock 42 200 42 minecraft:copper_block");
        long after = cursor(tk);
        assertTrue(after > before, "cursor not monotonic: " + before + " -> " + after);
    }

    /**
     * {@code waitTicks} both answers correctly and actually blocks.
     *
     * <p>The wall-clock half is the point. A {@code waitTicks} that returned its argument
     * immediately would satisfy every shape assertion while breaking every scene built on it, and
     * the only place that is visible is out here with a clock the game does not control. Ten ticks
     * is nominally 500ms; the floor is deliberately loose (300ms) because a busy server ticks
     * slower, never faster.
     */
    @Test
    void waitTicksBlocksForTheTicksItReports(StageWright tk) {
        long t0 = System.nanoTime();
        JsonObject r = tk.call("mc.system.waitTicks", p("ticks", 10));
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        assertEquals(10L, num(r, "waited"), "waitTicks answer drifted: " + r);
        assertTrue(ms >= 300, "waitTicks returned in " + ms + "ms — it did not block on ticks");
    }

    /** {@code mc.wait.condition} resolves on a dotted field reaching a value, and reports it. */
    @Test
    void waitConditionResolvesOnTheFieldValue(StageWright tk) {
        cmd(tk, "setblock 50 200 50 minecraft:chest");
        cmd(tk, "item replace block 50 200 50 container.2 with minecraft:stone 7");

        JsonObject params = new JsonObject();
        params.addProperty("invoke", "mc.observe.container");
        params.add("params", Contract.with("pos", pos(50, 200, 50)));
        params.addProperty("field", "slots.2.count");
        params.addProperty("value", 7);
        params.addProperty("timeoutMs", 5000);
        params.addProperty("pollMs", 200);

        JsonObject r = tk.call("mc.wait.condition", params);
        assertEquals("done", r.has("outcome") ? r.get("outcome").getAsString() : "",
                "a foreground wait must answer outcome:done: " + r);
        assertTrue(flag(r, "satisfied"), "wait.condition did not resolve: " + r);
        assertEquals(7L, num(r, "value"), "wait.condition reported the wrong value: " + r);
    }

    // ------------------------------------------------------------- helpers -----

    /** {@code mc.observe.cursor}'s {@code {cursor:"N"}}, read as the number the string spells. */
    private static long cursor(StageWright tk) {
        JsonObject r = tk.call("mc.observe.cursor", new JsonObject());
        JsonElement c = r.get("cursor");
        assertTrue(c != null && c.isJsonPrimitive() && c.getAsJsonPrimitive().isString(),
                "mc.observe.cursor must answer {cursor:\"<decimal>\"}, got: " + r);
        return Long.parseLong(c.getAsString());
    }

    /** An event's {@code data} object, empty when the event carries none. */
    private static JsonObject payloadOf(JsonObject event) {
        JsonElement data = event.get("data");
        return data != null && data.isJsonObject() ? data.getAsJsonObject() : new JsonObject();
    }
}
