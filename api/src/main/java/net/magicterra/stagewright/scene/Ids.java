package net.magicterra.stagewright.scene;

import net.magicterra.stagewright.contract.SceneFailure;

import java.util.List;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * String ids in, registry entries out — loudly.
 *
 * <p>Every facet added for the conformance work takes ids as strings rather than as registry
 * objects, and that is not a convenience: a scene file written in JavaScript resolves method names
 * at runtime against whatever the jar was remapped to, so it may hold a Minecraft object but must
 * never call a method ON one (see {@link SceneContext#originX()} for the full argument). A facet
 * that took an {@code Item} would be a Java-only facet.
 *
 * <p>The cost of that choice is that every id is a chance to typo, and this class exists so the
 * answer to a typo is the same everywhere: <b>throw, naming the id and the registry</b>. The
 * alternative — resolving an unknown id to air, or to an empty stack, or to nothing at all — makes
 * a scene assert against a world it never set up, and the failure then points at the assertion
 * rather than at the misspelling three lines above it. {@code JsScenes}' {@code block()} bridge
 * settled this question the same way for the same reason.
 *
 * <p>Package-private on purpose. This is how facets talk to the registries; it is not part of the
 * vocabulary a scene author learns.
 */
final class Ids {

    /** How many near-misses to offer when an id is not found. Enough to catch a typo, few enough
     *  that the message stays readable in a results file's {@code reason} field. */
    private static final int SUGGESTIONS = 5;

    private Ids() {}

    /**
     * Parse an id, failing on syntax before ever reaching a registry.
     *
     * <p>Separate from the lookup because the two mistakes need different sentences: {@code "Diamond"}
     * is not a malformed id, it is a well-formed id for something that does not exist, and telling
     * someone "no such item" when the real problem is a capital letter sends them looking in the
     * wrong place.
     */
    static ResourceLocation location(String id, String what) {
        if (id == null || id.isBlank()) {
            throw new SceneFailure("a " + what + " id is required, but the scene passed "
                    + (id == null ? "null" : "an empty string"));
        }
        ResourceLocation parsed = ResourceLocation.tryParse(id);
        if (parsed == null) {
            throw new SceneFailure("'" + id + "' is not a valid " + what + " id — ids are"
                    + " namespace:path, lowercase, e.g. 'minecraft:diamond'."
                    + " A bare path is read as 'minecraft:<path>'.");
        }
        return parsed;
    }

    /**
     * Look an id up in a registry, or fail naming the closest things that ARE registered.
     *
     * <p>The suggestions are the point. In a 452-mod pack the difference between
     * {@code allthemodium:unobtainium_ingot} and {@code allthemodium:unobtanium_ingot} is invisible
     * in a diff and obvious in a candidate list.
     */
    static <T> T require(Registry<T> registry, String id, String what) {
        ResourceLocation key = location(id, what);
        // containsKey, NOT a null check on get(). BuiltInRegistries.ITEM and BLOCK are DEFAULTED
        // registries: get() answers minecraft:air for anything unregistered, so a null check never
        // fires and a typo sails through as an empty ItemStack. Downstream that reads as a give that
        // failed, a slot that is empty, a recipe with no ingredients — every symptom EXCEPT the
        // misspelling. This class exists to stop exactly that, and the first version of it had the
        // bug it was written to prevent; the capability suite caught it on its first real run.
        if (!registry.containsKey(key)) {
            throw new SceneFailure("no " + what + " is registered as '" + key + "'"
                    + suggest(registry, key));
        }
        return registry.get(key);
    }

    /** True when the id resolves, without throwing — for the {@code exists}-shaped questions a
     *  scene legitimately asks about a pack it is auditing rather than driving. */
    static <T> boolean registered(Registry<T> registry, String id, String what) {
        return registry.containsKey(location(id, what));
    }

    static Item item(String id) {
        return require(BuiltInRegistries.ITEM, id, "item");
    }

    /** A stack of {@code count} of this item. Count is validated here rather than at the call site
     *  because a zero or negative count silently produces an empty stack, and an empty stack is
     *  indistinguishable from "the give failed". */
    static ItemStack stack(String id, int count) {
        if (count <= 0) {
            throw new SceneFailure("count must be at least 1, but the scene asked for " + count
                    + " of '" + id + "' — an empty stack reads downstream exactly like a failed give");
        }
        return new ItemStack(item(id), count);
    }

    /** The registry id of an item, as a string, for handing back to a scene. */
    static String idOf(Item item) {
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
        return key == null ? "<unregistered>" : key.toString();
    }

    /** The registry id of what is in a stack, or the empty string for an empty stack — so a scene
     *  reading an empty slot gets a falsy value rather than {@code "minecraft:air"}, which reads as
     *  "there is air here" and is a different claim. */
    static String idOf(ItemStack stack) {
        return stack.isEmpty() ? "" : idOf(stack.getItem());
    }

    /**
     * Registered ids that look like the one asked for, rendered as a message suffix.
     *
     * <p>Matched on the path rather than the whole id, and case-insensitively: the namespace is the
     * half an author is most likely to get right and the path is where the typo lives. Empty when
     * nothing is close, so an id that is simply from an absent mod produces a short message instead
     * of an irrelevant list.
     */
    private static <T> String suggest(Registry<T> registry, ResourceLocation wanted) {
        String path = wanted.getPath().toLowerCase(java.util.Locale.ROOT);
        List<String> near = registry.keySet().stream()
                .filter(k -> {
                    String other = k.getPath().toLowerCase(java.util.Locale.ROOT);
                    return other.contains(path) || path.contains(other);
                })
                .map(ResourceLocation::toString)
                .sorted()
                .limit(SUGGESTIONS)
                .toList();
        if (near.isEmpty()) {
            return " — and nothing registered looks like it, so the mod that owns '"
                    + wanted.getNamespace() + "' is probably not in this run";
        }
        return " — did you mean " + String.join(", ", near) + "?";
    }
}
