package net.magicterra.stagewright.harness;

import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.function.Supplier;

import net.magicterra.stagewright.scene.Scene;

/**
 * The suite this run will execute, or the reason it cannot have one.
 *
 * <p>Everything that can reject a registry happens here, before a harness exists: discovery (a
 * provider with no scenes, a {@code .js} file that does not parse, scene files with no Rhino), the
 * filter, and the harness's own structural checks (duplicate names, origin-slot pins). A rejection
 * is returned rather than thrown, because a throw in SERVER_STARTED writes no results at all and the
 * supervisor can then only say the game never armed — sending an author with a typo in a scene file
 * to look for a missing mod jar.
 */
public final class SuiteRegistry {

    /**
     * @param scenes     what the run executes; empty when {@code failure} is set
     * @param discovered how many scenes discovery produced before the filter, for the FILTERED log
     * @param failure    why the registry could not be built, or null
     */
    public record Resolved(List<Scene> scenes, int discovered, Throwable failure) {
        /** One line for the results header, naming the exception type and its message. */
        public String error() {
            if (failure == null) return null;
            return failure.getClass().getSimpleName() + ": " + failure.getMessage();
        }
    }

    private SuiteRegistry() {}

    /** @param filter the {@link SceneFilter} pattern, or null for the whole registry */
    public static Resolved resolve(Supplier<List<Scene>> discovery, String filter) {
        try {
            List<Scene> all = discovery.get();
            List<Scene> scenes = SceneFilter.apply(all, filter);
            StageWrightHarness.validate(scenes);
            return new Resolved(scenes, all.size(), null);
        } catch (RuntimeException | ServiceConfigurationError e) {
            return new Resolved(List.of(), 0, e);
        }
    }
}
