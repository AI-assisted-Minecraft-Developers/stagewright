package net.magicterra.stagewright.scene;

import net.magicterra.stagewright.contract.SceneFailure;

import java.util.ArrayList;
import java.util.List;

/**
 * The facet a {@link CapabilityDescriptor} hands a scene: what was detected, and a way in.
 *
 * <p>A descriptor cannot know what its mod's API looks like, so it cannot offer verbs the way
 * {@link Equip} or {@link Quests} do. What it can do is state precisely what it found and hand over a
 * {@link Probe} rooted at the class the descriptor named — which is the difference between a scene
 * writing {@code s.probe('mekanism.api.MekanismAPI')} (a string in every scene, wrong in every scene
 * the day it moves) and {@code s.capability('mekanism').probe()} (a string in one file).
 *
 * <pre>{@code
 * var mek = s.capability('mekanism');
 * s.record('mekanism', mek.version());
 * mek.probe().callStatic('getModVersion');
 * }</pre>
 *
 * <p>Everything here is JS-safe by construction — strings, ints, lists of strings, and {@link Probe},
 * which is itself the wrapper that keeps a scene from ever holding a bare Minecraft object.
 */
public final class Detected {

    private final CapabilityDescriptor descriptor;
    private final SceneContext ctx;

    Detected(CapabilityDescriptor descriptor, SceneContext ctx) {
        this.descriptor = descriptor;
        this.ctx = ctx;
    }

    /** The capability name, as scenes ask for it. */
    public String name() {
        return descriptor.name();
    }

    /** The mods this descriptor named that are actually loaded, sorted. */
    public List<String> mods() {
        List<String> out = new ArrayList<>();
        for (String mod : descriptor.declaredMods()) {
            if (ctx.mods().loaded(mod)) out.add(mod);
        }
        out.sort(String::compareTo);
        return out;
    }

    /** The version of one of the mods this descriptor named, or {@code ""}. */
    public String version(String modId) {
        return ctx.mods().version(modId);
    }

    /**
     * The version of the single mod this descriptor names, or {@code ""} when it names none or
     * several — the common case being one, and a scene recording it should not have to repeat the id.
     */
    public String version() {
        List<String> loaded = mods();
        return loaded.size() == 1 ? ctx.mods().version(loaded.get(0)) : "";
    }

    /** The classes this descriptor required, all of which are present or this facet would not exist. */
    public List<String> classes() {
        return descriptor.declaredClasses();
    }

    /** Where the descriptor was read from — the harness jar, or the pack file that declared it. */
    public String source() {
        return descriptor.source();
    }

    /**
     * Reflection rooted at the class the descriptor named.
     *
     * <p>Both failures here are {@link SceneFailure}, never a skip, and that is the whole point of
     * not just calling {@link SceneContext#probe} directly.
     *
     * <p>A descriptor with no {@code probe} is a perfectly good capability — plenty of scenes only
     * need "is this here?" — so asking one for a class it never named is a bug in the scene, and the
     * message names the file to add it to.
     *
     * <p>The second is subtler and is the reason for the explicit check. {@code s.probe()} skips on
     * an absent class, and its wording is right for its own case: nobody asked about a mod, so the
     * class not being there means the mod is not there. Here somebody DID ask — the descriptor's
     * conditions all held, so the mod is installed — and the class is still missing. That is the mod
     * having moved its API, and reporting it as "the mod is not installed" would send whoever reads
     * the results to check a mods folder that is perfectly correct.
     */
    public Probe probe() {
        String className = descriptor.probeClass();
        if (className == null || className.isBlank()) {
            throw new SceneFailure("the '" + name() + "' capability, declared in " + source()
                    + ", has no \"probe\" class, so there is nothing to reflect into. Add one to that"
                    + " descriptor, or use s.probe('<class>') directly.");
        }
        if (!ctx.hasClass(className)) {
            throw new SceneFailure("the '" + name() + "' capability is available — every condition"
                    + " in " + source() + " holds" + (mods().isEmpty() ? "" : ", and " + mods()
                    + " is loaded") + " — but the class it names for probing, '" + className
                    + "', is not in this runtime. The mod is here and has moved its API; fix the"
                    + " \"probe\" line in that descriptor rather than the mods folder.");
        }
        return ctx.probe(className);
    }

    /** Whether this descriptor named a class to probe. */
    public boolean hasProbe() {
        String className = descriptor.probeClass();
        return className != null && !className.isBlank();
    }

    @Override
    public String toString() {
        return "Detected[" + name() + " mods=" + mods() + " classes=" + classes()
                + " from " + source() + "]";
    }
}
