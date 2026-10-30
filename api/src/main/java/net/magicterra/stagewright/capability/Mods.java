package net.magicterra.stagewright.capability;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.opentest4j.TestAbortedException;

/**
 * What is actually installed in this run: mod ids and versions.
 *
 * <pre>{@code
 * Mods.loaded("mekanism")            // branch
 * Mods.require("ae2")                // or abort the test, with a reason that names the mod
 * Mods.version("create")             // "6.0.10"
 * }</pre>
 *
 * <p>The mod list lives in the loader, and this module compiles against neither, so each loader's entrypoint
 * {@link #install}s the list at startup. Not installed at all is a failure ({@link IllegalStateException}), never an
 * empty list: an empty list would make every mod look uninstalled, StageWright's own wiring failing while wearing an
 * absent mod's clothes.
 */
public final class Mods {

    /**
     * id -> version, installed once by the loader entrypoint. Null until then, which is a bug and is reported as one.
     */
    private static volatile Map<String, String> installed;

    private Mods() {}

    /**
     * Hand StageWright this runtime's mod list. Called by the loader entrypoint at startup. Last write wins: a client
     * that loads the harness twice under two classloaders is harmless otherwise.
     */
    public static void install(Map<String, String> idToVersion) {
        installed = Map.copyOf(idToVersion);
    }

    /** Whether the loader ever installed a list — for the harness's own self-check. */
    public static boolean isInstalled() {
        return installed != null;
    }

    /** The installed ids; empty when the loader never installed a list. */
    public static Set<String> installedIds() {
        return raw().keySet();
    }

    /** Whether this mod id is loaded. */
    public static boolean loaded(String modId) {
        return list().containsKey(modId);
    }

    /** Whether every one of these mod ids is loaded. */
    public static boolean all(String... modIds) {
        for (String id : modIds) if (!loaded(id)) return false;
        return true;
    }

    /** Whether any one of these mod ids is loaded — a fork, a rename, a shim, all satisfy the same test. */
    public static boolean any(String... modIds) {
        for (String id : modIds) if (loaded(id)) return true;
        return false;
    }

    /** This mod's version string, or {@code ""} when it is not loaded. */
    public static String version(String modId) {
        return list().getOrDefault(modId, "");
    }

    /** Every loaded mod id, sorted. */
    public static List<String> ids() {
        List<String> out = new ArrayList<>(list().keySet());
        out.sort(String::compareTo);
        return out;
    }

    /** How many mods are loaded: the one number that says which pack the results are about. */
    public static int count() {
        return list().size();
    }

    /**
     * This mod's version.
     *
     * @throws TestAbortedException when it is not installed: not the pack's defect, but not a pass either
     */
    public static String require(String modId) {
        String version = list().get(modId);
        if (version == null) {
            throw new TestAbortedException("this test needs the mod '" + modId + "', which is not installed in this"
                    + " run (" + count() + " mods are)");
        }
        return version;
    }

    private static Map<String, String> list() {
        Map<String, String> snapshot = installed;
        if (snapshot == null) {
            throw new IllegalStateException("no mod list was ever installed — StageWright's loader entrypoint did not"
                    + " run, or this is running outside the harness. This is a framework failure, deliberately NOT"
                    + " reported as 'that mod is absent': an empty list would make every mod look uninstalled.");
        }
        return snapshot;
    }

    private static Map<String, String> raw() {
        Map<String, String> snapshot = installed;
        return snapshot == null ? new LinkedHashMap<>() : snapshot;
    }
}
