package net.magicterra.stagewright.scene;

import java.util.List;

/**
 * SPI seam for downstream mods to contribute scenes to the T0 suite.
 * Implementations are discovered via {@link java.util.ServiceLoader}
 * (META-INF/services/net.magicterra.stagewright.scene.SceneProvider).
 * Scene names must be globally unique across all providers — the harness
 * rejects duplicates loudly before writing the suite header.
 */
public interface SceneProvider {
    List<Scene> scenes();
}
