package net.magicterra.testkit.scene;

import java.util.function.Consumer;

/** One registered scene. Explicit registry (spec §10) — the suite header is dumped
 *  from this list, so registration and reconciliation share a single source. */
public record Scene(String name, int budgetTicks, boolean required, Canary canary,
                    Consumer<SceneContext> body, int originSlot, int chunkRadius) {
    public static Scene of(String name, int budgetTicks, Consumer<SceneContext> body) {
        return new Scene(name, budgetTicks, true, Canary.NONE, body, -1, 1);
    }

    public static Scene canary(String name, int budgetTicks, Canary kind, Consumer<SceneContext> body) {
        return new Scene(name, budgetTicks, true, kind, body, -1, 1);
    }

    /** Pin this scene to a fixed origin slot — REQUIRED for byte-determinism-
     *  sensitive scenes: auto slots are assignment-order dependent, so suite
     *  growth relocates them and double-precision physics differs by position. */
    public Scene withOriginSlot(int slot) {
        return new Scene(name, budgetTicks, required, canary, body, slot, chunkRadius);
    }

    /** Widen the forced-chunk window to (2r+1)² — for arenas that exceed the
     *  default 3×3 footprint (usable dx/dz beyond [-16,31] needs r>=2). */
    public Scene withChunkRadius(int r) {
        return new Scene(name, budgetTicks, required, canary, body, originSlot, r);
    }
}
