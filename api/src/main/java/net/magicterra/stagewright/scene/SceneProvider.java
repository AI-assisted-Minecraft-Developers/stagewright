package net.magicterra.stagewright.scene;

import java.util.List;

/**
 * SPI seam for downstream mods to contribute scenes to the suite. Implementations are discovered
 * via {@link java.util.ServiceLoader}
 * (META-INF/services/net.magicterra.stagewright.scene.SceneProvider).
 * Scene names must be globally unique across all providers — the harness rejects duplicates loudly
 * before writing the suite header, because a duplicate lets a later record overwrite an earlier one
 * in the orchestrator's last-wins map and mask a real FAIL as GREEN.
 *
 * <p><b>The short form.</b> Annotate the class and its methods; the default {@link #scenes()} finds
 * them, and each scene's name comes from its method name, so it is never written twice:
 *
 * <pre>{@code
 * @SceneSet("sb")
 * public final class MagnetScenes implements SceneProvider {
 *     @SceneDef(budget = 200)
 *     static void pullsWithinRadius(SceneContext s) { ... }
 * }
 * }</pre>
 *
 * <p><b>The long form</b> — overriding {@link #scenes()} — remains first-class, and is how to
 * register canaries, aggregate several holder classes through {@link Stages#scan(Class[])}, or
 * decide at runtime which scenes the current topology supports.
 */
public interface SceneProvider {

    /** The scenes this provider contributes; by default, the {@link SceneDef} methods declared on
     *  the implementing class. */
    default List<Scene> scenes() {
        List<Scene> found = Stages.scan(getClass());
        if (found.isEmpty()) {
            // A provider that contributes nothing has no legitimate reading in THIS form: one that
            // wants to contribute conditionally overrides scenes() and returns empty on purpose.
            // Left quiet, this is a service entry that looks wired and adds no coverage — the exact
            // shape of hole that still reports GREEN.
            throw new IllegalStateException(getClass().getName()
                    + " implements SceneProvider but declares no @SceneDef methods and does not"
                    + " override scenes() — it would contribute nothing to the suite");
        }
        return found;
    }
}
