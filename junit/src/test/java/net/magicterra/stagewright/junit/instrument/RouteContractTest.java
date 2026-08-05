package net.magicterra.stagewright.junit.instrument;

import com.google.gson.JsonObject;
import net.magicterra.stagewright.junit.StageWright;
import net.magicterra.stagewright.junit.Face;
import net.magicterra.stagewright.junit.RequiresFace;
import net.magicterra.stagewright.junit.StageWrightExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;

import static net.magicterra.stagewright.junit.instrument.Contract.num;
import static net.magicterra.stagewright.junit.instrument.Contract.p;
import static net.magicterra.stagewright.junit.instrument.Contract.refuses;
import static net.magicterra.stagewright.junit.instrument.Contract.str;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The route layer's own contract: what {@code DriverApi.route} answers, and — mostly — what it
 * refuses.
 *
 * <p><b>Why these live out here and not in a scene.</b> This is the instrument face: green here is
 * what entitles the scene harness to trust the driver underneath it. A scene body runs inside that
 * harness, on the server thread, through the same assertion stack the harness uses to judge itself,
 * so a scene asserting these would be asking the thing under test to vouch for itself. Out here the
 * process is different, the transport is bare RPC, and the assertion stack is JUnit's — three
 * independent things that all have to be broken at once to produce a false green.
 */
@ExtendWith(StageWrightExtension.class)
@EnabledIfEnvironmentVariable(named = "TESTKIT_ENDPOINT", matches = ".+")
@RequiresFace(Face.SERVER)
class RouteContractTest {

    @Test
    void versionShape(StageWright tk) {
        JsonObject v = tk.call("mc.system.version");
        assertEquals("worlddriver", str(v, "modid"), "modid drifted: " + v);
        assertTrue(num(v, "uptimeMs") >= 0, "uptimeMs not a non-negative number: " + v);
    }

    @Test
    void unknownMethodIsRefused(StageWright tk) {
        refuses(tk, "mc.no.suchMethod", "unknown method");
    }

    /**
     * A required key, left out.
     *
     * <p>{@code mc.system.waitTicks} rather than an observe verb on purpose: its schema is
     * {@code req("ticks", integer(0, 200))}, a genuinely required int. Verbs whose only interesting
     * key is optional — {@code mc.observe.eventsSince}'s {@code cursor} defaults to 0 — round-trip
     * clean on empty params and prove nothing about required-key handling.
     */
    @Test
    void missingRequiredKeyIsRefused(StageWright tk) {
        refuses(tk, "mc.system.waitTicks", "ticks");
    }

    @Test
    void wrongTypedKeyIsRefused(StageWright tk) {
        // The validator renders type violations as "'ticks' must be integer, got string (...)".
        // Asserting the rendering, not merely that something failed, is what makes this a contract.
        refuses(tk, "mc.system.waitTicks", p("ticks", "not-a-number"), "must be integer");
    }

    @Test
    void unexpectedKeyIsRefused(StageWright tk) {
        JsonObject params = p("ticks", 1);
        params.addProperty("bogusKey", 1);
        refuses(tk, "mc.system.waitTicks", params, "unexpected key");
    }

    /** A client-only verb on a dedicated server must fail LOUDLY, never silently no-op. */
    @Test
    void clientOnlyVerbIsRefused(StageWright tk) {
        refuses(tk, "mc.bot.status", "client only");
    }

    /** The in-JVM script route must agree with the transport it is being called over. */
    @Test
    void scriptEvalAgreesWithTheTransport(StageWright tk) {
        JsonObject params = p("source", "Driver.invoke('mc.system.version').modid");
        params.addProperty("timeoutMs", 5000);
        JsonObject r = tk.call("mc.script.eval", params);
        assertEquals("", str(r, "error"), "script threw: " + r);
        assertEquals("worlddriver", str(r, "result"), "in-JVM route parity broken: " + r);
    }
}
