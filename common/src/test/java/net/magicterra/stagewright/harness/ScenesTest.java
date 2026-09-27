package net.magicterra.stagewright.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import net.magicterra.stagewright.scene.Scene;
import org.junit.jupiter.api.Test;

/** The registry as {@link Scenes#all()} builds it, before anything narrows it. */
class ScenesTest {

    private static List<String> names(List<Scene> scenes) {
        return scenes.stream().map(Scene::name).toList();
    }

    @Test
    void theRetiredFilterPropertyDoesNotNarrowTheRegistry() {
        // Narrowing is only honest when the suite header says so, and only SceneFilter's pattern
        // reaches the header. stagewright.filter does not, so if it shrank the list the verdict
        // would judge part of the suite as the whole of it.
        List<String> whole = names(Scenes.all());
        String before = System.getProperty("stagewright.filter");
        System.setProperty("stagewright.filter", "floorAssert");
        try {
            assertEquals(whole, names(Scenes.all()));
        } finally {
            if (before == null) System.clearProperty("stagewright.filter");
            else System.setProperty("stagewright.filter", before);
        }
        assertTrue(whole.contains("awaitTicks"), whole.toString());
    }
}
