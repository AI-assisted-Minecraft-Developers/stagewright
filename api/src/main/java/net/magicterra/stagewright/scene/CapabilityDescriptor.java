package net.magicterra.stagewright.scene;

import java.util.ArrayList;
import java.util.List;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * A capability declared in JSON instead of written in Java — the layer between
 * {@link SceneContext#probe} and {@link CapabilityProvider}.
 *
 * <p>{@link CapabilityProvider} asks for a jar. A modpack author has a {@code config/} folder, and
 * telling them to stand up a Gradle project to name a class is the same mistake as telling them to
 * do it to write a scene. So a descriptor is a file:
 *
 * <pre>{@code
 * // config/stagewright/capabilities/mekanism.json
 * {
 *   "name": "mekanism",
 *   "classes": ["mekanism.api.MekanismAPI"],
 *   "probe":    "mekanism.api.MekanismAPI",
 *   "absent":   "Mekanism is not in this pack"
 * }
 * }</pre>
 *
 * <pre>{@code
 * s.capability('mekanism').probe().callStatic('...')     // from a .js scene, no build anywhere
 * }</pre>
 *
 * <h2>What it can test for</h2>
 *
 * <p>Any combination of {@code mods} (all must be loaded), {@code anyMods} (at least one),
 * {@code classes} (all must be in this JVM), {@code items} and {@code blocks} (all must be
 * registered). <b>At least one condition is required.</b> A descriptor with none would claim to be
 * available in every runtime, which is worse than useless: scenes gated on it would run against a
 * pack that does not have the thing, and fail for the wrong reason.
 *
 * <p>Registry conditions are the ones worth reaching for when a mod is present but its content is
 * not — a mod loaded with its feature switched off in config registers no items, and no amount of
 * mod-list checking notices.
 *
 * <h2>Where they come from</h2>
 *
 * <ul>
 *   <li><b>Built in</b> — {@code data/stagewright/capabilities.json} in the harness jar. These are
 *       the auto-detected ones: a pack that has Mekanism gets {@code s.capability("mekanism")}
 *       having configured nothing, and a pack that does not gets a skip that says so.</li>
 *   <li><b>The pack's own</b> — {@code config/stagewright/capabilities/*.json}, beside its scenes,
 *       installed by the CLI the same way. A pack file with a built-in's name REPLACES it, so a pack
 *       whose fork of a mod moved a class can correct us without waiting for a release.</li>
 * </ul>
 *
 * @see CapabilityProvider for the typed version, when you do have a jar
 */
public final class CapabilityDescriptor implements CapabilityProvider {

    private final String name;
    private final List<String> mods;
    private final List<String> anyMods;
    private final List<String> classes;
    private final List<String> items;
    private final List<String> blocks;
    private final String probeClass;
    private final String absent;
    private final String source;

    private CapabilityDescriptor(String name, List<String> mods, List<String> anyMods,
            List<String> classes, List<String> items, List<String> blocks, String probeClass,
            String absent, String source) {
        this.name = name;
        this.mods = mods;
        this.anyMods = anyMods;
        this.classes = classes;
        this.items = items;
        this.blocks = blocks;
        this.probeClass = probeClass;
        this.absent = absent;
        this.source = source;
    }

    /**
     * Parse one descriptor.
     *
     * @param source where it came from, quoted in every error — a pack author editing a file needs
     *               to be told which file, and there can be dozens.
     * @throws IllegalStateException on anything malformed. Loudly, at load: a descriptor that
     *               silently degrades to "unavailable" is indistinguishable from an absent mod, and
     *               a typo would then read as a normal skip forever.
     */
    public static CapabilityDescriptor parse(JsonObject json, String source) {
        String name = string(json, "name", source);
        if (name == null || name.isBlank()) {
            throw new IllegalStateException("capability descriptor in " + source + " has no 'name'");
        }
        List<String> mods = strings(json, "mods", source);
        List<String> anyMods = strings(json, "anyMods", source);
        List<String> classes = strings(json, "classes", source);
        List<String> items = strings(json, "items", source);
        List<String> blocks = strings(json, "blocks", source);
        if (mods.isEmpty() && anyMods.isEmpty() && classes.isEmpty() && items.isEmpty()
                && blocks.isEmpty()) {
            throw new IllegalStateException("capability descriptor '" + name + "' in " + source
                    + " states no condition (mods / anyMods / classes / items / blocks), so it would"
                    + " report itself available in every runtime — including ones without the thing"
                    + " it names, where scenes gated on it would run and fail for the wrong reason");
        }
        return new CapabilityDescriptor(name, mods, anyMods, classes, items, blocks,
                string(json, "probe", source), string(json, "absent", source), source);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean availableIn(SceneContext ctx) {
        return missing(ctx) == null;
    }

    @Override
    public Object facet(SceneContext ctx) {
        return new Detected(this, ctx);
    }

    @Override
    public String absentReason() {
        String stated = absent == null || absent.isBlank() ? "" : " — " + absent;
        return "this scene needs the '" + name + "' capability, declared in " + source
                + ", and this runtime does not satisfy it: " + missingSummary() + stated;
    }

    /** The first unsatisfied condition, or null when everything holds. Kept as one method so
     *  {@link #availableIn} and the skip message can never disagree about why. */
    private String missing(SceneContext ctx) {
        for (String mod : mods) {
            if (!ctx.mods().loaded(mod)) return "the mod '" + mod + "' is not loaded";
        }
        if (!anyMods.isEmpty() && !ctx.mods().any(anyMods.toArray(new String[0]))) {
            return "none of the mods " + anyMods + " is loaded";
        }
        for (String className : classes) {
            if (!ctx.hasClass(className)) return "the class '" + className + "' is not in this JVM";
        }
        for (String item : items) {
            if (!Ids.registered(BuiltInRegistries.ITEM, item, "item")) {
                return "the item '" + item + "' is not registered";
            }
        }
        for (String block : blocks) {
            if (!Ids.registered(BuiltInRegistries.BLOCK, block, "block")) {
                return "the block '" + block + "' is not registered";
            }
        }
        return null;
    }

    /** {@link #missing} without a context, for {@link #absentReason} — which the registry calls
     *  holding only the provider. States the conditions rather than which one failed. */
    private String missingSummary() {
        List<String> parts = new ArrayList<>();
        if (!mods.isEmpty()) parts.add("mods " + mods);
        if (!anyMods.isEmpty()) parts.add("any of " + anyMods);
        if (!classes.isEmpty()) parts.add("classes " + classes);
        if (!items.isEmpty()) parts.add("items " + items);
        if (!blocks.isEmpty()) parts.add("blocks " + blocks);
        return "it wants " + String.join(", ", parts);
    }

    String probeClass() {
        return probeClass;
    }

    String source() {
        return source;
    }

    List<String> declaredMods() {
        List<String> out = new ArrayList<>(mods);
        out.addAll(anyMods);
        return out;
    }

    List<String> declaredClasses() {
        return classes;
    }

    private static String string(JsonObject json, String key, String source) {
        JsonElement e = json.get(key);
        if (e == null || e.isJsonNull()) return null;
        if (!e.isJsonPrimitive()) {
            throw new IllegalStateException("'" + key + "' in " + source + " must be a string");
        }
        return e.getAsString();
    }

    private static List<String> strings(JsonObject json, String key, String source) {
        JsonElement e = json.get(key);
        if (e == null || e.isJsonNull()) return List.of();
        List<String> out = new ArrayList<>();
        if (e.isJsonPrimitive()) {                    // one entry may be written bare
            out.add(e.getAsString());
            return List.copyOf(out);
        }
        if (!e.isJsonArray()) {
            throw new IllegalStateException("'" + key + "' in " + source
                    + " must be a string or an array of strings");
        }
        for (JsonElement element : (JsonArray) e) {
            if (!element.isJsonPrimitive()) {
                throw new IllegalStateException("'" + key + "' in " + source
                        + " must contain only strings");
            }
            out.add(element.getAsString());
        }
        return List.copyOf(out);
    }
}
