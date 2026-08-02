package net.magicterra.stagewright.verbs;

import java.util.Map;

import net.magicterra.worlddriver.mcp.ToolCatalog;
import net.magicterra.worlddriver.mcp.schema.ToolSchema;
import net.magicterra.stagewright.StageWrightCommon;

import static net.magicterra.worlddriver.mcp.schema.Schemas.object;
import static net.magicterra.worlddriver.mcp.schema.Schemas.tool;

/**
 * The {@code mc.test.run} on-demand suite trigger — a hidden verb StageWright registers into
 * the driver's {@link ToolCatalog} on its own behalf.
 *
 * <p>This class used to live on the worlddriver side of the module edge and reach StageWright
 * through a {@code StageWrightVerbHook} ServiceLoader SPI, because stagewright-common could not
 * import {@code ToolCatalog} without creating a circular module dependency. That inversion is
 * gone: StageWright now depends on the driver it drives, so it calls {@code registerVerb}
 * directly and the SPI it needed has been deleted.
 *
 * <h2>Boot ordering</h2>
 * {@code registerVerb} refuses a pre-boot (sink-less) call. Registration runs from
 * {@link StageWrightCommon#onServerStarted} (SERVER_STARTED), strictly after worlddriver wired its
 * route sink at SERVER_STARTING ({@code WorldDriverCommon.ensureRpcUp}) — so the sink is always
 * present by the time this fires. {@link #register()} is idempotent-guarded so a duplicate
 * server-started cannot append the schema supplier twice.
 */
public final class TestRunVerb {

    private TestRunVerb() {}

    /** Hidden ToolSchema — declared (satisfies the boot schema invariant + drives route-layer
     *  validation) but kept out of MCP {@code tools/list}, exactly like {@code mc.test.reset} and the
     *  harness-verb convention. No params. */
    public static final ToolSchema SCHEMA = tool(
            "mc.test.run",
            "Trigger the stagewright scene suite ON DEMAND (dev/test harness verb; reachable over RPC "
            + "only). Runs the same suite path -Dstagewright.autorun uses (Scenes.all -> execute on server "
            + "ticks -> JSONL results -> done footer), scheduled onto the server thread. Returns "
            + "{accepted:true, scenes:N} once the run is ACCEPTED; the JSONL done footer remains the "
            + "sole completion signal. Idempotent: if the suite already ran or is running it fails "
            + "loudly rather than re-running. No params.",
            object().additionalProperties(false)).asHidden();

    private static volatile boolean registered;

    /**
     * Register {@code mc.test.run} through the paired SPI. Idempotent (guarded) so a duplicate
     * discovery/boot call cannot append the schema supplier twice. Must run after the route sink is
     * wired (see class javadoc) — a pre-boot call throws from {@code registerVerb}.
     */
    public static synchronized void register() {
        if (registered) return;
        registered = true;
        ToolCatalog.registerVerb(SCHEMA, TestRunVerb::handle);
    }

    /** Route handler (runs on the RPC socket thread): hop the trigger into the testkit runtime,
     *  which idempotency-checks + schedules the harness onto the server thread, or throws loudly. */
    static Object handle(Map<String, Object> params) {
        return StageWrightCommon.triggerOnDemandRun();
    }
}
