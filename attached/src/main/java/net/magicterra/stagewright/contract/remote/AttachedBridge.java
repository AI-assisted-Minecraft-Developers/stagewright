package net.magicterra.stagewright.contract.remote;

import java.util.function.Consumer;
import java.util.regex.Pattern;

import dev.latvian.mods.rhino.ScriptableObject;
import net.magicterra.stagewright.contract.AttachedContext;
import net.magicterra.stagewright.contract.DriverAccess;
import net.magicterra.stagewright.contract.DriverBinding;
import net.magicterra.stagewright.contract.Scripts;

/**
 * The handful of things a scene file reaches through the prelude's {@code __bridge}, for the home
 * that has no game to ask: {@code block(id)} and {@code console.log}.
 *
 * <p>The in-process bridge resolves a block against the registry and is loud on a miss. Out here
 * there is no registry, so the id is checked for shape only and the game checks the rest when the id
 * arrives in a call — an unknown block fails at the first line that uses it, not at the line that
 * spelled it.
 */
public final class AttachedBridge {
    /** namespace:path, with the characters a resource location allows. */
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    private final String sceneFile;
    private final Consumer<String> log;

    AttachedBridge(String sceneFile, Consumer<String> log) {
        this.sceneFile = sceneFile;
        this.log = log;
    }

    /**
     * The scene whose body is running, for the global {@code driver(...)}. A run is one scene at a time
     * on one thread, so a single reference is the whole of it; null outside a body.
     */
    private static volatile AttachedContext current;

    /** Called by the runner around each scene body. */
    public static void enter(AttachedContext scene) {
        current = scene;
    }

    public static void leave() {
        current = null;
    }

    /**
     * What to hand {@link Scripts#load}: installs this bridge and the global {@code driver} in each
     * file's scope. {@code driver} is the one in-process scenes have, with the same conversions
     * ({@link DriverAccess}); here it goes by way of the running scene, so a {@code mc.test.*} verb
     * reaches the game's server thread as it does through {@code s.driver}.
     */
    public static Scripts.ScopeSetup setup(Consumer<String> log) {
        DriverBinding viaTheScene = (method, params) -> {
            AttachedContext scene = current;
            if (scene == null) {
                throw new IllegalStateException("driver('" + method + "') was called outside a scene body, where"
                        + " there is no arena to go through");
            }
            return scene.driver(method, params);
        };
        return (cx, scope, fileName) -> {
            ScriptableObject.putProperty(scope, "__bridge",
                    cx.javaToJS(new AttachedBridge(fileName, log), scope), cx);
            DriverAccess.install(cx, scope, viaTheScene);
        };
    }

    /** A block by registry id. A bare path is a vanilla block, as everywhere else a resource location is parsed. */
    public RemoteId block(String id) {
        String full = id.indexOf(':') < 0 ? "minecraft:" + id : id;
        if (!ID.matcher(full).matches()) {
            throw new IllegalArgumentException("'" + id + "' is not a block id (expected namespace:path)");
        }
        return new RemoteId("block", full);
    }

    /** Write to the runner's log, tagged with the file the scene came from. */
    public void log(String message) {
        log.accept(sceneFile + ": " + message);
    }
}
