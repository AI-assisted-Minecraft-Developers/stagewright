package net.magicterra.testkit.scene;

import java.util.function.Consumer;

/** One registered scene. Explicit registry (spec §10) — the suite header is dumped
 *  from this list, so registration and reconciliation share a single source. */
public record Scene(String name, int budgetTicks, boolean required, Canary canary,
                    Consumer<SceneContext> body) {
    public static Scene of(String name, int budgetTicks, Consumer<SceneContext> body) {
        return new Scene(name, budgetTicks, true, Canary.NONE, body);
    }

    public static Scene canary(String name, int budgetTicks, Canary kind, Consumer<SceneContext> body) {
        return new Scene(name, budgetTicks, true, kind, body);
    }
}
