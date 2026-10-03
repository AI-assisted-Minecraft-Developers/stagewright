package net.magicterra.stagewright.script;

import java.util.ArrayList;
import java.util.List;
import net.magicterra.stagewright.contract.DriverBinding;

import net.magicterra.stagewright.StageWrightCommon;
import net.magicterra.stagewright.contract.SceneSpec;
import net.magicterra.stagewright.contract.Scripts;
import net.magicterra.stagewright.scene.PackFiles;
import net.magicterra.stagewright.scene.Scene;

/**
 * Scenes written in JavaScript, loaded from the run directory rather than compiled into a mod.
 *
 * <p>This exists for people who ship a modpack. They have a {@code mods/} folder and a
 * {@code config/} folder and no build at all — asking them to stand up a Gradle project with a
 * {@code testmod} source set to assert that their pack still works is asking them to become mod
 * developers first. A file of scenes beside their configs is not a lesser version of the Java
 * surface: it registers into the SAME registry, runs on the SAME harness, is caught by the SAME
 * canaries, and is reconciled against the SAME expected-scenes manifest. In the results file a JS
 * scene and a Java scene are indistinguishable, which is the point.
 *
 * <p><b>What is left here is only the in-process half.</b> Parsing, the prelude, the registration
 * protocol, the instruction cap, the exception unwrapping and the error phrasing all moved to
 * {@link Scripts} in {@code :stagewright-attached}, where the out-of-process home calls the same
 * code. This class does the one thing that needs a game: turn a {@link SceneSpec} into a
 * {@link Scene} whose body takes a {@code SceneContext}. If that split ever starts to look like
 * indirection, the thing it buys is that a {@code .js} file cannot mean two different things — which
 * is not a property that survives two hand-maintained interpreters.
 *
 * <p>Rhino is provided by the required WorldDriver runtime.
 */
public final class JsScenes {

    private JsScenes() {}

    /** Load every {@code .js} file under the scenes directory, as in-process scenes. */
    public static List<Scene> load(DriverBinding binding) {
        List<SceneSpec> specs = Scripts.load(
                PackFiles.scenes(),
                // The globals only this home has: the file-scoped bridge, and driver access that
                // uses the injected in-process binding.
                (cx, scope, fileName) -> {
                    dev.latvian.mods.rhino.ScriptableObject.putProperty(scope, "__bridge",
                            cx.javaToJS(new JsBridge(fileName), scope), cx);
                    DriverAccess.install(cx, scope, binding);
                },
                line -> StageWrightCommon.LOG.info("[{}] {}", StageWrightCommon.MOD_ID, line));

        List<Scene> out = new ArrayList<>(specs.size());
        for (SceneSpec spec : specs) {
            Scene scene = Scene.of(spec.name(), spec.budgetTicks(), ctx -> Scripts.run(spec, ctx))
                    .withTerrain(spec.terrain())
                    .withClock(spec.clock())
                    .withDimension(spec.dimension());
            out.add(spec.optional() ? scene.withRequired(false) : scene);
        }
        return out;
    }
}
