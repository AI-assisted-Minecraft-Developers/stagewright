package net.magicterra.stagewright;

/**
 * SPI seam letting a runtime consumer register testkit-owned RPC verbs (the granted
 * {@code mc.test.*} namespace) through {@code ToolCatalog.registerVerb} — a dependency
 * {@code stagewright-common} must NOT take directly: worlddriver's {@code :common} already
 * depends on {@code :stagewright-common} (for the {@link net.magicterra.stagewright.scene.SceneProvider}
 * scene SPI), so a back-edge from stagewright-common to {@code ToolCatalog} would be a circular
 * module dependency. This interface inverts it: stagewright-common declares the seam, the
 * worlddriver dogfood glue ({@code net.magicterra.worlddriver.bot.testkit}) implements it and calls
 * {@code ToolCatalog.registerVerb} from the worlddriver side of the edge.
 *
 * <p>Implementations are discovered via {@link java.util.ServiceLoader}
 * (META-INF/services/net.magicterra.stagewright.StageWrightVerbHook) and invoked exactly once from
 * {@link StageWrightCommon#onServerStarted} — in BOTH autorun states — after worlddriver has wired
 * its route sink at server STARTING (so {@code registerVerb} never runs pre-boot). Mirrors the
 * {@link net.magicterra.stagewright.scene.SceneProvider} discovery precedent.
 */
public interface StageWrightVerbHook {
    /** Register this consumer's testkit RPC verbs. Called once per server, on the server thread,
     *  after the route sink is wired. Implementations must be idempotent (guard against a second
     *  discovery on a duplicate server-started). */
    void registerVerbs();
}
