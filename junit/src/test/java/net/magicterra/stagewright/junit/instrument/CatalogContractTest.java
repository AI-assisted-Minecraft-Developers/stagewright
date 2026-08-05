package net.magicterra.stagewright.junit.instrument;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.magicterra.stagewright.junit.Endpoint;
import net.magicterra.stagewright.junit.McpCatalog;
import net.magicterra.stagewright.junit.StageWright;
import net.magicterra.stagewright.junit.Face;
import net.magicterra.stagewright.junit.RequiresFace;
import net.magicterra.stagewright.junit.StageWrightExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Map;

import static net.magicterra.stagewright.junit.instrument.Contract.p;
import static net.magicterra.stagewright.junit.instrument.Contract.refuses;
import static net.magicterra.stagewright.junit.instrument.Contract.str;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The verb pipeline: a tool's schema is registered atomically with its route, advertised in the
 * catalog, and enforced by the validator BEFORE the handler runs.
 *
 * <p><b>Each pairing is asserted from both ends, over two different transports.</b> What a tool
 * <i>declares</i> comes from MCP {@code tools/list}; what the validator <i>does</i> with that
 * declaration comes from bare RPC. Reading only one side leaves the two failures that matter
 * invisible: a schema advertised but not enforced, and a route that dispatches with no schema at
 * all — the latter being a real regression class, because a schema-less route skips validation
 * entirely and every bad param falls straight through to the handler.
 */
@ExtendWith(StageWrightExtension.class)
@EnabledIfEnvironmentVariable(named = "TESTKIT_ENDPOINT", matches = ".+")
@RequiresFace(Face.SERVER)
class CatalogContractTest {

    /**
     * {@code mc.bot.setting}'s advertised schema is CLOSED and carries the whole registry.
     *
     * <p><b>Closed renders as an ABSENT key, never a literal false.</b> {@code additionalProperties}
     * is stored as null when disallowed and emitted through an optional field, so a closed object
     * simply omits it — matching the validator's own reading, where only an explicit {@code true}
     * means open. So the assertion is "not true", not "equals false"; asserting the latter would
     * fail against a correctly closed schema.
     *
     * <p>The key-count floor catches the other half: a registry that silently lost its reflective
     * completion pass still renders as a valid closed object, just a much smaller one.
     */
    @Test
    void settingSchemaIsClosedAndComplete(StageWright tk) {
        JsonObject tool = requireTool(tk, "mc.bot.setting");
        JsonObject schema = tool.getAsJsonObject("inputSchema");
        assertNotNull(schema, "mc.bot.setting advertises no inputSchema: " + tool);
        assertEquals("object", str(schema, "type"), "inputSchema is not an object: " + schema);

        JsonElement open = schema.get("additionalProperties");
        assertFalse(open != null && open.isJsonPrimitive() && open.getAsJsonPrimitive().isBoolean()
                        && open.getAsBoolean(),
                "mc.bot.setting schema is OPEN (additionalProperties:true) — it must be closed");

        JsonObject props = schema.getAsJsonObject("properties");
        assertNotNull(props, "mc.bot.setting advertises no properties: " + schema);
        assertTrue(props.size() >= 200,
                "mc.bot.setting advertises " + props.size() + " keys (< 200) — the single-source"
                        + " settings registry is undersized");
    }

    /**
     * The validator runs before the handler, and that ordering is side-uniform.
     *
     * <p>{@code mc.bot.setting} is client-only, so on a dedicated server it has two ways to fail.
     * Which one it picks is the assertion: a bogus key must produce the VALIDATOR's unexpected-key
     * error, not the handler's client-only error. If it produced client-only, validation would be
     * happening after the side gate — meaning params reaching a client are validated and the same
     * params reaching a server are not.
     */
    @Test
    void unknownSettingKeyHitsTheValidatorNotTheSideGate(StageWright tk) {
        refuses(tk, "mc.bot.setting", p("definitelyNotAKnob", true),
                "definitelyNotAKnob", "unexpected key");
    }

    /** A hidden verb with valid params still fails loudly on the wrong side — never a silent no-op. */
    @Test
    void testResetIsClientOnly(StageWright tk) {
        refuses(tk, "mc.test.reset", "client only", "mc.test.reset");
    }

    /**
     * {@code mc.test.reset} was registered through the paired schema+route entry point, so its
     * schema exists, is closed, and is enforced ahead of the client-only gate. A regression to
     * schema-less dispatch would show up here as the client-only error instead.
     */
    @Test
    void testResetSchemaIsPairedAndEnforcedFirst(StageWright tk) {
        refuses(tk, "mc.test.reset", p("nope", true), "nope", "unexpected key");
    }

    /**
     * {@code mc.test.run} is HIDDEN: out of the catalog, still declared, routable and validated.
     *
     * <p>These two assertions are the only remaining gate on what "hidden" means, so they are worth
     * stating together — a verb that vanished from tools/list because it stopped being registered
     * at all would satisfy the first half alone.
     */
    @Test
    void testRunIsHiddenButStillSchemaChecked(StageWright tk) {
        Map<String, JsonObject> tools = McpCatalog.tools(endpoint(tk));
        assertFalse(tools.containsKey("mc.test.run"),
                "mc.test.run leaked into tools/list — the on-demand trigger must stay hidden");
        refuses(tk, "mc.test.run", p("nope", true), "nope", "unexpected key");
    }

    // ------------------------------------------------------------- helpers -----

    private static JsonObject requireTool(StageWright tk, String name) {
        Map<String, JsonObject> tools = McpCatalog.tools(endpoint(tk));
        JsonObject tool = tools.get(name);
        assertNotNull(tool, name + " is absent from tools/list (" + tools.size() + " tools advertised)");
        return tool;
    }

    private static Endpoint endpoint(StageWright tk) {
        return tk.endpoint();
    }
}
