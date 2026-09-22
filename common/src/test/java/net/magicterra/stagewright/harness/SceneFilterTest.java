package net.magicterra.stagewright.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import net.magicterra.stagewright.contract.Canary;
import net.magicterra.stagewright.scene.Scene;
import org.junit.jupiter.api.Test;

/** Narrowing a registry by name pattern — and what narrowing is never allowed to remove. */
class SceneFilterTest {

    private static Scene scene(String name) {
        return Scene.of(name, 20, ctx -> { });
    }

    private static Scene canary(String name, Canary kind) {
        return Scene.canary(name, 20, kind, ctx -> { });
    }

    private static List<String> names(List<Scene> scenes) {
        return scenes.stream().map(Scene::name).toList();
    }

    @Test
    void aPatternKeepsTheScenesItMatches() {
        List<Scene> all = List.of(scene("wd.a"), scene("wd.b"), scene("pack.c"));
        assertEquals(List.of("wd.a", "pack.c"), names(SceneFilter.apply(all, "wd.a, pack.*")));
    }

    @Test
    void theDotIsLiteral() {
        List<Scene> all = List.of(scene("wd.a"), scene("wdxa"));
        assertEquals(List.of("wd.a"), names(SceneFilter.apply(all, "wd.a")));
    }

    @Test
    void noPatternIsNoFilter() {
        List<Scene> all = List.of(scene("wd.a"));
        assertEquals(all, SceneFilter.apply(all, null));
        assertEquals(all, SceneFilter.apply(all, "  "));
    }

    @Test
    void theFrameworkCanariesSurviveEveryFilter() {
        // A filtered run that dropped them would report the same GREEN whether or not the harness
        // could still catch a failure.
        List<Scene> all = List.of(scene("wd.a"), scene("wd.b"),
                canary("canaryMustFail", Canary.MUST_FAIL),
                canary("canaryMustTimeout", Canary.MUST_TIMEOUT),
                canary("canaryMustSwallow", Canary.MUST_SWALLOW));
        assertEquals(List.of("wd.a", "canaryMustFail", "canaryMustTimeout", "canaryMustSwallow"),
                names(SceneFilter.apply(all, "wd.a")));
    }

    @Test
    void aMustSkipSceneIsFilteredLikeTheSceneItIs() {
        // Its subject is a pack's own absence, not the harness, so it is selected by name.
        List<Scene> all = List.of(scene("wd.a"), canary("cap.absent", Canary.MUST_SKIP));
        assertEquals(List.of("wd.a"), names(SceneFilter.apply(all, "wd.*")));
        assertEquals(List.of("cap.absent"), names(SceneFilter.apply(all, "cap.*")));
    }
}
