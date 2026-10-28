package net.magicterra.stagewright.capability;

import net.magicterra.stagewright.test.Game;

/**
 * SPI seam for wiring in a capability Minecraft does not have.
 *
 * <p>The test API covers the vanilla server. Everything a modpack is actually about lives outside it — Curios'
 * slots, FTB Quests' graph, Mekanism's chemical tanks, Create's stress network, AE2's ME grid — and without a seam,
 * every one of those costs a StageWright release and a version bump for everybody. This is the seam.
 *
 * <p>Discovered via {@link java.util.ServiceLoader}
 * (META-INF/services/net.magicterra.stagewright.capability.CapabilityProvider), so a mod ships its own adapter in its
 * own jar and anybody's tests can use it:
 *
 * <pre>{@code
 * public final class RitualsCapability implements CapabilityProvider {
 *     public String name() { return "mymod:rituals"; }
 *     public boolean availableIn(Game game) { return true; }
 *     public Object facet(Game game) { return new Rituals(game); }
 * }
 * }</pre>
 *
 * <pre>{@code
 * game.capability("mymod:rituals", Rituals.class).consecrate(x, y, z);   // Java
 * }</pre>
 * <pre>{@code
 * t.capability('mymod:rituals').consecrate(x, y, z);                     // a script test
 * }</pre>
 *
 * <h2>Write it typed, not reflectively</h2>
 *
 * <p>An adapter for Curios imports Curios' classes, and in a runtime without Curios it cannot even load. That is
 * correct — it could not work there either — and discovery is built for it: each provider is instantiated inside its
 * own try/catch, so one that fails to load is recorded as absent with the reason, and the rest are unaffected. The
 * reflective style {@link Quests} uses is what you do when the adapter must live in a jar that ships whether the mod
 * does or not, which is StageWright's problem and not yours.
 *
 * <h2>The facet's own surface must be script-safe</h2>
 *
 * <p>Tests call facets from a test thread, and script tests call them by name: Minecraft's names differ between a
 * mojmap runtime and a production Fabric one. So a facet's parameters and return values must be StageWright types
 * (such as {@link net.magicterra.stagewright.test.Body}), string ids, or primitives — never an {@code ItemStack}. And
 * every operation touching game state must hop to the server thread itself, through {@link Game#onServer} or
 * {@link net.magicterra.stagewright.test.Body#onServer}.
 *
 * @see Game#capability(String)
 * @see Probe for the escape hatch when there is no adapter and you cannot write one
 */
public interface CapabilityProvider {

    /**
     * This capability's name, as tests ask for it.
     *
     * <p>Bare names ({@code "curios"}, {@code "ftbquests"}) are reserved for the adapters StageWright ships. Anything
     * else uses {@code "<modid>:<what>"}. Duplicates are rejected loudly naming both classes: the later one would
     * silently win, and a test would then test something it did not mean to.
     */
    String name();

    /**
     * Whether this capability works in this game. Called more than once, possibly from several test threads at
     * once, so it must be cheap, thread-safe and free of side effects. A provider whose class loaded at all can
     * usually return {@code true} — the class loading IS the probe.
     */
    boolean availableIn(Game game);

    /**
     * The object tests call. Resolved once per test and reused, so an adapter registering
     * {@link Game#afterTest} does not register it several times over.
     */
    Object facet(Game game);

    /** What an aborted test should say when this is absent. Optional. */
    default String absentReason() {
        return "no provider in this run offers '" + name() + "'";
    }
}
