package net.magicterra.testkit;

/**
 * SPI seam letting a runtime consumer register testkit-owned RPC verbs (the granted
 * {@code mc.test.*} namespace) through {@code ToolCatalog.registerVerb} — a dependency
 * {@code testkit-common} must NOT take directly: agent-driver's {@code :common} already
 * depends on {@code :testkit-common} (for the {@link net.magicterra.testkit.scene.SceneProvider}
 * scene SPI), so a back-edge from testkit-common to {@code ToolCatalog} would be a circular
 * module dependency. This interface inverts it: testkit-common declares the seam, the
 * agent-driver dogfood glue ({@code net.magicterra.agent.bot.testkit}) implements it and calls
 * {@code ToolCatalog.registerVerb} from the agent-driver side of the edge.
 *
 * <p>Implementations are discovered via {@link java.util.ServiceLoader}
 * (META-INF/services/net.magicterra.testkit.TestkitVerbHook) and invoked exactly once from
 * {@link TestkitCommon#onServerStarted} — in BOTH autorun states — after agent-driver has wired
 * its route sink at server STARTING (so {@code registerVerb} never runs pre-boot). Mirrors the
 * {@link net.magicterra.testkit.scene.SceneProvider} discovery precedent.
 */
public interface TestkitVerbHook {
    /** Register this consumer's testkit RPC verbs. Called once per server, on the server thread,
     *  after the route sink is wired. Implementations must be idempotent (guard against a second
     *  discovery on a duplicate server-started). */
    void registerVerbs();
}
