package net.magicterra.stagewright.scene;

import java.util.function.Consumer;

/** One registered scene. Explicit registry (spec §10) — the suite header is dumped
 *  from this list, so registration and reconciliation share a single source. */
public record Scene(String name, int budgetTicks, boolean required, Canary canary,
                    Consumer<SceneContext> body, int originSlot, int chunkRadius,
                    Terrain terrain) {
    public static Scene of(String name, int budgetTicks, Consumer<SceneContext> body) {
        return new Scene(name, budgetTicks, true, Canary.NONE, body, -1, 1, Terrain.RUN_WORLD);
    }

    public static Scene canary(String name, int budgetTicks, Canary kind, Consumer<SceneContext> body) {
        return new Scene(name, budgetTicks, true, kind, body, -1, 1, Terrain.RUN_WORLD);
    }

    /** Pin this scene to a fixed origin slot — REQUIRED for byte-determinism-
     *  sensitive scenes: auto slots are assignment-order dependent, so suite
     *  growth relocates them and double-precision physics differs by position. */
    public Scene withOriginSlot(int slot) {
        return new Scene(name, budgetTicks, required, canary, body, slot, chunkRadius, terrain);
    }

    /** Widen the forced-chunk window to (2r+1)² — for arenas that exceed the
     *  default 3×3 footprint (usable dx/dz beyond [-16,31] needs r>=2). */
    public Scene withChunkRadius(int r) {
        return new Scene(name, budgetTicks, required, canary, body, originSlot, r, terrain);
    }

    /** Mark this scene optional: FAIL/TIMEOUT is recorded per-run but does not
     *  break GREEN (contract v0 required/optional carve-out). Being swallowed
     *  still REDs regardless. Use for faithful sensors of known product bugs. */
    public Scene withRequired(boolean req) {
        return new Scene(name, budgetTicks, req, canary, body, originSlot, chunkRadius, terrain);
    }

    /** Build this scene's arena on ground of the given kind instead of in the run world's empty
     *  sky. See {@link Terrain} for why this is a choice of dimension and not of world type. */
    public Scene withTerrain(Terrain t) {
        return new Scene(name, budgetTicks, required, canary, body, originSlot, chunkRadius, t);
    }
}
