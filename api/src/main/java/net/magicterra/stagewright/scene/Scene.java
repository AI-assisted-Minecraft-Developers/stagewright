package net.magicterra.stagewright.scene;

import net.magicterra.stagewright.contract.Canary;
import net.magicterra.stagewright.contract.Clock;
import net.magicterra.stagewright.contract.Terrain;

import java.util.function.Consumer;

/** One registered scene. Explicit registry (spec §10) — the suite header is dumped
 *  from this list, so registration and reconciliation share a single source. */
public record Scene(String name, int budgetTicks, boolean required, Canary canary,
                    Consumer<SceneContext> body, int originSlot, int chunkRadius,
                    Terrain terrain, Clock clock, String dimension, boolean arena) {

    /**
     * Rejects the one combination that cannot mean anything.
     *
     * <p>{@link Terrain} is itself a choice of dimension — that is the whole trick that lets one
     * server offer both a superflat and a generated world. So a scene that asks for both a terrain
     * and a named dimension has asked for its arena to be in two places. Caught here, at
     * construction, rather than at run time: the run-time answer would have to pick one, and
     * whichever it picked would be silently ignoring half of what the author wrote.
     */
    public Scene {
        if (dimension != null && terrain != Terrain.RUN_WORLD) {
            throw new IllegalArgumentException("scene '" + name + "' asks for both terrain "
                    + terrain + " and dimension '" + dimension + "' — a terrain IS a dimension"
                    + " StageWright ships, so the two are alternatives. Drop the terrain to run in"
                    + " another mod's dimension, or drop the dimension to use StageWright's ground.");
        }
    }

    public static Scene of(String name, int budgetTicks, Consumer<SceneContext> body) {
        return new Scene(name, budgetTicks, true, Canary.NONE, body, -1, 1,
                Terrain.RUN_WORLD, Clock.MIDNIGHT, null, true);
    }

    public static Scene canary(String name, int budgetTicks, Canary kind, Consumer<SceneContext> body) {
        return new Scene(name, budgetTicks, true, kind, body, -1, 1,
                Terrain.RUN_WORLD, Clock.MIDNIGHT, null, true);
    }

    /** Pin this scene to a fixed origin slot — REQUIRED for byte-determinism-
     *  sensitive scenes: auto slots are assignment-order dependent, so suite
     *  growth relocates them and double-precision physics differs by position. */
    public Scene withOriginSlot(int slot) {
        return new Scene(name, budgetTicks, required, canary, body, slot, chunkRadius, terrain, clock, dimension, arena);
    }

    /** Widen the forced-chunk window to (2r+1)² — for arenas that exceed the
     *  default 3×3 footprint (usable dx/dz beyond [-16,31] needs r>=2). */
    public Scene withChunkRadius(int r) {
        return new Scene(name, budgetTicks, required, canary, body, originSlot, r, terrain, clock, dimension, arena);
    }

    /** Mark this scene optional: FAIL/TIMEOUT is recorded per-run but does not
     *  break GREEN (contract v0 required/optional carve-out). Being swallowed
     *  still REDs regardless. Use for faithful sensors of known product bugs. */
    public Scene withRequired(boolean req) {
        return new Scene(name, budgetTicks, req, canary, body, originSlot, chunkRadius, terrain, clock, dimension, arena);
    }

    /** Build this scene's arena on ground of the given kind instead of in the run world's empty
     *  sky. See {@link Terrain} for why this is a choice of dimension and not of world type. */
    public Scene withTerrain(Terrain t) {
        return new Scene(name, budgetTicks, required, canary, body, originSlot, chunkRadius, t, clock, dimension, arena);
    }

    /** Run this scene at a time of day other than the frozen midnight every scene gets by default.
     *  See {@link Clock} for why the default is night and what the alternatives are for. */
    public Scene withClock(Clock c) {
        return new Scene(name, budgetTicks, required, canary, body, originSlot, chunkRadius, terrain, c, dimension, arena);
    }

    /**
     * Run this scene's arena in a dimension some OTHER mod registers, named as
     * {@code "twilightforest:twilight_forest"}.
     *
     * <p>Why this is not just another {@link Terrain} value: the terrains are dimensions StageWright
     * ships and can therefore guarantee. This one names something the framework knows nothing about
     * and cannot promise is there — which changes what "absent" means. A missing StageWright terrain
     * is a broken framework and reports ENV_FAIL; a missing mod dimension is a runtime that does not
     * have that mod, and reports a recorded SKIP with the reason. The two must not be the same
     * outcome, because only one of them is anybody's bug.
     *
     * <p>This is what makes a large content mod testable at all. Twilight Forest's bosses,
     * structures, progression gates and loot are all in its own dimension; a suite pinned to the
     * overworld can only ever assert about its registries.
     */
    public Scene withDimension(String id) {
        return new Scene(name, budgetTicks, required, canary, body, originSlot, chunkRadius, terrain, clock, id, arena);
    }

    /**
     * Run this scene without waiting for an arena.
     *
     * <p>For a scene that plays in the live world and never sets foot in its allocated plot. PREP
     * normally blocks until the arena is <b>entity-ticking</b>, which vanilla only grants a chunk
     * whose neighbours are loaded — so a scene that does not need the ground still inherits every
     * way that promotion can stall, a hundred thousand blocks from anything it asserts about.
     * Measured on the worlddriver journey ladder: {@code only 0 of 9 arena chunks ever loaded}
     * took one rung in one run and two rungs in another, each time failing a ladder that was
     * otherwise fine. Shrinking the arena does not help and makes it worse — {@code 0 of 1} never
     * loads either, because one chunk cannot have loaded neighbours.
     *
     * <p>{@link SceneContext} is still built and the arena is still force-loaded and audited; the
     * only thing dropped is the <i>wait</i>. So {@code ctx.level()} works as always, and a scene
     * that opts out but then reads {@code ctx.origin()} is asking about ground that may not be
     * there yet — which is exactly the trade, and why this is opt-in rather than the default.
     */
    public Scene withArena(boolean useArena) {
        return new Scene(name, budgetTicks, required, canary, body, originSlot, chunkRadius,
                terrain, clock, dimension, useArena);
    }
}
