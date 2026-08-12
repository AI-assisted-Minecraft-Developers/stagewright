package net.magicterra.stagewright.scene;

import net.magicterra.stagewright.contract.SceneFailure;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What is actually installed in this run: mod ids and versions.
 *
 * <p>The ground floor of everything conditional. A pack's scenes are written against a pack, and a
 * pack changes — a mod is dropped, replaced by a fork, renamed at a major version. Without this, a
 * scene's only way to ask "is X here?" is to look for one of X's side effects (an item id, a class)
 * and hope that side effect means what it used to.
 *
 * <pre>{@code
 * s.mods().loaded("mekanism")            // branch
 * s.mods().require("ae2")                // or skip, with a reason that names the mod
 * s.mods().version("create")             // "6.0.10"
 * }</pre>
 *
 * <h2>Why this is installed rather than read</h2>
 *
 * <p>The mod list lives in the loader — {@code FabricLoader} on one side, {@code ModList} on the
 * other — and this module compiles against neither, by the rule at the top of its build file. So
 * each loader's entrypoint pushes the list in at startup, before any scene runs. The alternative,
 * reflecting into the loader by name, would work (loader classes are not remapped) but it would be
 * two more strings that can silently be wrong, to reach data somebody already has typed.
 *
 * <p>Not installed at all is a {@link SceneFailure}, never an empty list. An empty list would make
 * every {@code loaded()} answer false, so a scene would skip saying "mekanism is not installed" in a
 * run where it plainly is — StageWright's own wiring failing while wearing an absent mod's clothes,
 * which is the one shape of bug that costs a whole afternoon.
 */
public final class Mods {

    /** id -> version, installed once by the loader entrypoint. Null until then, which is a bug and
     *  is reported as one. */
    private static volatile Map<String, String> installed;

    private final SceneContext ctx;

    Mods(SceneContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Hand StageWright this runtime's mod list. Called by the loader entrypoint at startup.
     *
     * <p>Idempotent by last-write, not by rejection: a client that loads the harness twice under two
     * classloaders is a thing that happens, and refusing the second install would be a crash in a
     * situation that is otherwise harmless.
     */
    public static void install(Map<String, String> idToVersion) {
        installed = Map.copyOf(idToVersion);
    }

    /** Whether the loader ever installed a list — for the harness's own self-check, not for scenes. */
    public static boolean isInstalled() {
        return installed != null;
    }

    /** Whether this mod id is loaded. */
    public boolean loaded(String modId) {
        return list().containsKey(modId);
    }

    /** Whether every one of these mod ids is loaded. */
    public boolean all(String... modIds) {
        for (String id : modIds) if (!loaded(id)) return false;
        return true;
    }

    /** Whether any one of these mod ids is loaded — a fork, a rename, a shim, all satisfy the same
     *  scene. */
    public boolean any(String... modIds) {
        for (String id : modIds) if (loaded(id)) return true;
        return false;
    }

    /** This mod's version string, or {@code ""} when it is not loaded. */
    public String version(String modId) {
        return list().getOrDefault(modId, "");
    }

    /** Every loaded mod id, sorted. */
    public List<String> ids() {
        List<String> out = new ArrayList<>(list().keySet());
        out.sort(String::compareTo);
        return out;
    }

    /** How many mods are loaded. Worth recording in a pack suite: it is the one number that says
     *  which pack these results are about. */
    public int count() {
        return list().size();
    }

    /**
     * This mod's version, or a recorded skip naming it.
     *
     * <p>Skip rather than fail, matching {@link SceneContext#player()} and
     * {@link SceneContext#capability(String)}: a mod that is not installed is not the pack's defect,
     * and must not read as a pass either.
     */
    public String require(String modId) {
        String version = list().get(modId);
        if (version == null) {
            ctx.skip("this scene needs the mod '" + modId + "', which is not installed in this run"
                    + " (" + count() + " mods are)");
        }
        return version;
    }

    private Map<String, String> list() {
        Map<String, String> snapshot = installed;
        if (snapshot == null) {
            throw new SceneFailure("no mod list was ever installed — StageWright's loader entrypoint"
                    + " did not run, or this scene is running outside the harness. This is a"
                    + " framework failure, deliberately NOT reported as 'that mod is absent': an"
                    + " empty list would make every mod look uninstalled and send you looking at the"
                    + " pack.");
        }
        return snapshot;
    }

    /** The installed list as the descriptor evaluator needs it, without a context. */
    static Map<String, String> raw() {
        Map<String, String> snapshot = installed;
        return snapshot == null ? new LinkedHashMap<>() : snapshot;
    }
}
