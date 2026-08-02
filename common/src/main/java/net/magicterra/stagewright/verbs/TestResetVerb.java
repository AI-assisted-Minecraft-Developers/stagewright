package net.magicterra.stagewright.verbs;

import java.util.Map;

import net.magicterra.worlddriver.bot.BotApi;
import net.magicterra.worlddriver.bot.BotHooks;
import net.magicterra.worlddriver.mcp.ToolCatalog;
import net.magicterra.worlddriver.mcp.schema.ToolSchema;

import static net.magicterra.worlddriver.mcp.schema.Schemas.object;
import static net.magicterra.worlddriver.mcp.schema.Schemas.tool;

/**
 * The first runtime consumer of the paired verb-registration SPI ({@link ToolCatalog#registerVerb}):
 * the hidden {@code mc.test.reset} client-pool entry reset (testkit P2b). It is the driver
 * <b>dogfooding the third-party path on purpose</b> — {@code mc.test.reset} is a NEW name under the
 * granted {@code mc.test.*} namespace (not a driver-owned baseline verb), so it registers through the
 * same public paired entry a third-party mod would use, exercising the whole namespace-policy +
 * atomic schema/route contract end to end.
 *
 * <h2>Boot placement</h2>
 * {@link #register()} is invoked from {@code WorldDriverCommon.ensureRpcUp}, once, immediately after
 * {@code ToolCatalog.wireRouteSink(api::addRoute)} wires the route sink — the pattern of
 * {@code PathDebugBootstrap.init} (a one-time subsystem bootstrap that runs only after the
 * {@code DriverApi} exists), but deliberately on the COMMON boot path rather than the client-only
 * {@code ClientHooks.register}: the verb must exist on a dedicated server too (the dogfood harness is
 * a headless server, and {@code wd.settingRegistryClosed} asserts the route + schema are present
 * there). Registering pre-boot would throw ({@code registerVerb} refuses a sink-less call), which is
 * exactly why this hook sits after the sink is wired.
 *
 * <h2>Client-only, without loading client classes on a server</h2>
 * The handler delegates through {@link BotHooks#impl()} — a common broker whose impl is null on a
 * dedicated server (the bot impl, {@code BotApiImpl}, references client classes and is only
 * registered on the client). On a server the null check throws an {@link IllegalStateException}
 * carrying the established {@code mc.bot.*} "client only" phrasing BEFORE any client type is touched,
 * so registering this verb on a server never drags {@code BotApiImpl} onto the server class path
 * (this class imports no {@code net.minecraft.client.*} type). The actual reset work lives in
 * {@code BotApiImpl.resetClientEntry()}, where the client + scheduler both already exist.
 */
public final class TestResetVerb {

    private TestResetVerb() {}

    /** Hidden ToolSchema — declared (satisfies the boot schema invariant) but kept out of MCP
     *  {@code tools/list} — a harness verb is not an agent action. No params. */
    public static final ToolSchema SCHEMA = tool(
            "mc.test.reset",
            "Reset the client entry between testkit client-pool reuse runs (dev/test harness verb; "
            + "reachable over RPC only): releases held movement keys, closes any open screen, clears "
            + "the chat readback log, and cancels a residual smooth-look process. No params. "
            + "Client-only — a dedicated server rejects it loudly.",
            object().additionalProperties(false)).asHidden();

    private static volatile boolean registered;

    /**
     * Register {@code mc.test.reset} through the paired SPI. Idempotent (guarded) so a duplicate
     * boot call cannot append the schema supplier twice. Must run after the route sink is wired
     * (see class javadoc) — a pre-boot call throws from {@code registerVerb}.
     */
    public static synchronized void register() {
        if (registered) return;
        registered = true;
        ToolCatalog.registerVerb(SCHEMA, TestResetVerb::handle);
    }

    /** Route handler: hop to the bot impl (client) or throw the established client-only error. */
    static Object handle(Map<String, Object> params) {
        BotApi bot = BotHooks.impl();
        if (bot == null) {
            throw new IllegalStateException(
                    "mc.test.reset not available (client only; bot impl not registered)");
        }
        return bot.resetClientEntry();
    }
}
