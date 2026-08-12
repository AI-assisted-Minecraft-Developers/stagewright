package net.magicterra.stagewright.scene;

/**
 * SPI seam for wiring in a capability Minecraft does not have.
 *
 * <p>The eight facets on {@link SceneContext} cover the vanilla server API. Everything a modpack is
 * actually about lives outside it — Curios' slots, FTB Quests' graph, Mekanism's chemical tanks,
 * Create's stress network, AE2's ME grid — and without a seam, every one of those costs a StageWright
 * release and a version bump for everybody. This is the seam.
 *
 * <p>Discovered via {@link java.util.ServiceLoader}, the same mechanism scenes themselves arrive
 * through (META-INF/services/net.magicterra.stagewright.scene.CapabilityProvider), so a mod ships its
 * own adapter in its own jar and anybody's scenes can use it:
 *
 * <pre>{@code
 * public final class RitualsCapability implements CapabilityProvider {
 *     public String name() { return "mymod:rituals"; }
 *     public boolean availableIn(SceneContext ctx) { return true; }
 *     public Object facet(SceneContext ctx) { return new Rituals(ctx); }
 * }
 * }</pre>
 *
 * <pre>{@code
 * s.capability("mymod:rituals", Rituals.class).consecrate(0, 1, 0);   // Java
 * }</pre>
 * <pre>{@code
 * s.capability('mymod:rituals').consecrate(0, 1, 0);                  // JS — same object, via Rhino
 * }</pre>
 *
 * <h2>Write it typed, not reflectively</h2>
 *
 * <p>An adapter for Curios imports Curios' classes, and in a runtime without Curios it cannot even
 * load. That is correct — it could not work there either — and discovery is built for it: each
 * provider is instantiated inside its own try/catch, so one that fails to load is recorded as absent
 * with the reason, and the rest are unaffected. So write the natural typed adapter. The reflective
 * style {@link Quests} uses is what you do when the adapter must live in a jar that ships whether the
 * mod does or not, which is StageWright's problem and not yours.
 *
 * <h2>The facet's own surface must be JS-safe</h2>
 *
 * <p>This is on the provider author and nothing can check it at compile time. The rule is the one in
 * {@link SceneContext#originX()}: a scene may hold a Minecraft object but must never call a method on
 * one, because names on {@code net.minecraft.*} differ between a mojmap runtime and a production
 * Fabric one. So a facet's parameters and return values must be StageWright types, string ids, or
 * primitives — never an {@code ItemStack} the scene is then expected to call {@code getCount()} on.
 * Get this wrong and the facet works on NeoForge and throws on Fabric, which is the single most
 * expensive shape of bug this framework has.
 *
 * @see SceneContext#capability(String)
 * @see Probe for the escape hatch when there is no adapter and you cannot write one
 */
public interface CapabilityProvider {

    /**
     * This capability's name, as scenes ask for it.
     *
     * <p>Bare names ({@code "curios"}, {@code "ftbquests"}) are reserved for the adapters StageWright
     * ships. Anything else uses {@code "<modid>:<what>"}. Duplicates are rejected loudly naming both
     * classes, for the same reason duplicate scene names are: the later one silently wins, and a
     * scene then tests something it did not mean to.
     */
    String name();

    /**
     * Whether this capability works in the runtime the scene is in.
     *
     * <p>Called more than once and cached for the run, so it must be cheap and free of side effects.
     * A provider whose class loaded at all can usually return {@code true} — the class loading IS the
     * probe. Return false for the finer conditions: the mod is present but its data has not loaded,
     * the feature is switched off in config, the server has no world yet.
     */
    boolean availableIn(SceneContext ctx);

    /**
     * The object scenes call.
     *
     * <p>Resolved once per scene and reused, so an adapter registering {@link SceneContext#cleanup}
     * does not register it several times over. Returning a fresh instance per call is the bug that
     * produces.
     */
    Object facet(SceneContext ctx);

    /**
     * What a scene's skip message should say when this is absent. Optional.
     *
     * <p>Defaulted to something serviceable, but a provider that names the mod and where to get it
     * turns "this scene skipped" into "install X" for whoever reads the results next week.
     */
    default String absentReason() {
        return "no provider in this run offers '" + name() + "'";
    }
}
