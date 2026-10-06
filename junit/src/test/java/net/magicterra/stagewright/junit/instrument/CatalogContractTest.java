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
     * <p>The params are tagged on {@code op} ({@code read|patch|apply}), so the schema is a
     * {@code oneOf} of three closed variants; the registry lives in {@code patch}'s {@code values},
     * a closed object with one typed property per key.
     * Only an explicit {@code additionalProperties:true} means open, so the assertion is "not true".
     *
     * <p>The key-count floor catches the other half: a registry that silently lost its reflective
     * completion pass still renders as a valid closed schema, just a much smaller one.
     */
    @Test
    void settingSchemaIsClosedAndComplete(StageWright tk) {
        JsonObject tool = requireTool(tk, "mc.bot.setting");
        JsonObject schema = tool.getAsJsonObject("inputSchema");
        assertNotNull(schema, "mc.bot.setting advertises no inputSchema: " + tool);
        assertEquals("object", str(schema, "type"), "inputSchema is not an object: " + schema);
        assertFalse(isOpen(schema), "mc.bot.setting schema is OPEN (additionalProperties:true) — it must be closed");

        JsonObject patch = null;
        for (String op : new String[] {"read", "patch", "apply"}) {
            JsonObject variant = variantTagged(schema, "op", op);
            assertNotNull(variant, "mc.bot.setting schema has no op:'" + op + "' variant: " + schema);
            assertFalse(isOpen(variant), "mc.bot.setting op:'" + op + "' variant is OPEN: " + variant);
            if (op.equals("patch")) patch = variant;
        }

        JsonObject values = patch.getAsJsonObject("properties").getAsJsonObject("values");
        assertNotNull(values, "op:'patch' advertises no values: " + patch);
        assertFalse(isOpen(values), "op:'patch' values is OPEN: " + values);
        JsonObject perKey = values.getAsJsonObject("properties");
        assertNotNull(perKey, "values has no per-key properties: " + values);
        int keys = perKey.size();
        assertTrue(keys >= 200, "mc.bot.setting advertises " + keys + " keys (< 200) — the single-source"
                + " settings registry is undersized");
    }

    /**
     * The validator runs before the handler, and that ordering is side-uniform.
     *
     * <p>{@code op:"apply"} acts on this client's player, so on a dedicated server it has two ways
     * to fail. Which one it picks is the assertion: a bogus control must produce the VALIDATOR's
     * {@code invalid_params} at {@code /control}, not the handler's {@code capability_unavailable}.
     * If it produced the latter, validation would be happening after the side gate — meaning params
     * reaching a client are validated and the same params reaching a server are not.
     */
    @Test
    void unknownSettingKeyHitsTheValidatorNotTheSideGate(StageWright tk) {
        JsonObject params = p("op", "apply");
        params.addProperty("control", "definitelyNotAKnob");
        params.addProperty("value", true);
        refuses(tk, "mc.bot.setting", params, "[invalid_params]", "/control invalid_value", "definitelyNotAKnob");
    }

    /** A hidden verb with valid params still fails loudly on the wrong side — never a silent no-op. */
    @Test
    void testResetIsClientOnly(StageWright tk) {
        refuses(tk, "mc.test.reset", "[capability_unavailable]", "client only", "mc.test.reset");
    }

    /**
     * {@code mc.test.reset} is a typed method, so its params are decoded, strictly, before the
     * client-only gate runs. A handler reached with unchecked params would answer the client-only
     * error here instead.
     */
    @Test
    void testResetParamsAreDecodedFirst(StageWright tk) {
        refuses(tk, "mc.test.reset", p("nope", true), "[invalid_params]", "/nope unknown_field");
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
        refuses(tk, "mc.test.run", p("nope", true), "[invalid_params]", "/nope unknown_field");
    }

    // ------------------------------------------------------------- helpers -----

    private static boolean isOpen(JsonObject schema) {
        JsonElement open = schema.get("additionalProperties");
        return open != null && open.isJsonPrimitive() && open.getAsJsonPrimitive().isBoolean() && open.getAsBoolean();
    }

    /** The {@code oneOf} variant whose {@code tag} property is {@code const: value}, or null. */
    private static JsonObject variantTagged(JsonObject schema, String tag, String value) {
        JsonElement oneOf = schema.get("oneOf");
        if (oneOf == null || !oneOf.isJsonArray()) return null;
        for (JsonElement e : oneOf.getAsJsonArray()) {
            if (!e.isJsonObject()) continue;
            JsonObject props = e.getAsJsonObject().getAsJsonObject("properties");
            JsonObject t = props == null ? null : props.getAsJsonObject(tag);
            if (t != null && value.equals(str(t, "const"))) return e.getAsJsonObject();
        }
        return null;
    }

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
