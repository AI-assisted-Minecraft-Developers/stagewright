package net.magicterra.stagewright.capability;

import java.util.ArrayList;
import java.util.List;

/**
 * The facet a {@link CapabilityDescriptor} hands a test: what was detected, and a way in.
 *
 * <p>A descriptor cannot know what its mod's API looks like, so it cannot offer verbs the way {@link Equip} or
 * {@link Quests} do. What it can do is state precisely what it found and hand over a {@link Probe} rooted at the class
 * the descriptor named — a string in one file rather than in every test.
 *
 * <pre>{@code
 * var mek = t.capability('mekanism');
 * t.record('mekanism', mek.version());
 * mek.probe().callStatic('getModVersion');
 * }</pre>
 *
 * <p>Everything here is script-safe — strings, lists of strings, and {@link Probe}.
 */
public final class Detected {

    private final CapabilityDescriptor descriptor;

    Detected(CapabilityDescriptor descriptor) {
        this.descriptor = descriptor;
    }

    /** The capability name, as tests ask for it. */
    public String name() {
        return descriptor.name();
    }

    /** The mods this descriptor named that are actually loaded, sorted. */
    public List<String> mods() {
        List<String> out = new ArrayList<>();
        for (String mod : descriptor.declaredMods()) {
            if (Mods.loaded(mod)) {
                out.add(mod);
            }
        }
        out.sort(String::compareTo);
        return out;
    }

    /** The version of one of the mods this descriptor named, or {@code ""}. */
    public String version(String modId) {
        return Mods.version(modId);
    }

    /** The version of the single mod this descriptor names, or {@code ""} when it names none or several. */
    public String version() {
        List<String> loaded = mods();
        return loaded.size() == 1 ? Mods.version(loaded.get(0)) : "";
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
     * <p>Both failures here fail the test, never abort it. A descriptor with no {@code probe} asked for one is a bug in
     * the test. And a probe class missing while every condition held is the mod having moved its API, not the mod being
     * absent — which is what {@link Probe#of}'s abort would claim.
     */
    public Probe probe() {
        String className = descriptor.probeClass();
        if (className == null || className.isBlank()) {
            throw new IllegalStateException("the '" + name() + "' capability, declared in " + source()
                    + ", has no \"probe\" class, so there is nothing to reflect into. Add one to that"
                    + " descriptor, or use Probe.of('<class>') directly.");
        }
        if (!Probe.hasClass(className)) {
            throw new IllegalStateException("the '" + name() + "' capability is available — every condition"
                    + " in " + source() + " holds" + (mods().isEmpty() ? "" : ", and " + mods() + " is loaded")
                    + " — but the class it names for probing, '" + className
                    + "', is not in this runtime. The mod is here and has moved its API; fix the"
                    + " \"probe\" line in that descriptor rather than the mods folder.");
        }
        return Probe.of(className);
    }

    /** Whether this descriptor named a class to probe. */
    public boolean hasProbe() {
        String className = descriptor.probeClass();
        return className != null && !className.isBlank();
    }

    @Override
    public String toString() {
        return "Detected[" + name() + " mods=" + mods() + " classes=" + classes() + " from " + source() + "]";
    }
}
