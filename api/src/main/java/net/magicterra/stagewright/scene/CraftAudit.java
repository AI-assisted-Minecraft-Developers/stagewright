package net.magicterra.stagewright.scene;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The answer {@link Recipes#craftAudit} produces: what happened when every recipe in the run was
 * asked to make its own output out of its own declared ingredients.
 *
 * <p><b>Read {@link #skipped()} before believing {@link #failures()}.</b> Two kinds of recipe cannot
 * be attempted at all:
 *
 * <ul>
 *   <li><b>Not a crafting grid.</b> Only {@code CraftingInput} can be built from nothing but a
 *       recipe's own declaration. A smelting recipe, and every recipe type a tech mod registers,
 *       takes an input this facet cannot construct without knowing that mod.</li>
 *   <li><b>Declares nothing.</b> A recipe that computes itself at runtime — vanilla's map cloning,
 *       armour dyeing, firework assembly — has no declared ingredients and no declared result, so
 *       "does it make what it says" is a question with no meaning for it.</li>
 * </ul>
 *
 * <p>So the interesting number is not "0 failures", it is "0 failures out of <i>how many actually
 * tried</i>" — on All the Mods 10 the attempted share is a minority of 93,834, and a report that hid
 * that would be claiming a pack-wide guarantee it never made. This is the same lesson as a skipped
 * scene resolving as PASS, in a different place.
 *
 * <pre>{@code
 * var audit = s.recipes().craftAudit();
 * s.record("craft.attempted", audit.attempted());
 * s.record("craft.skipped", audit.skipped());
 * s.record("craft.failures", audit.failures().size());     // a pack has some, legitimately
 * s.expect(audit.failuresInVanillaTypes())
 *     .as("vanilla-typed recipes that cannot make their own output").isEmpty();
 * }</pre>
 *
 * <p>Assert on {@link #failuresInVanillaTypes()} and record {@link #failures()}. The full list is
 * not empty on any real pack and that is not a defect — see that method for why.
 *
 * @param attempted how many recipes were actually built and run
 * @param skipped   how many could not be attempted, for either reason above
 * @param failures  the attempted recipes that did not produce their declared result, sorted; each
 *                  entry is {@code <recipe id>: <what went wrong>}
 */
public record CraftAudit(int attempted, int skipped, List<String> failures) {

    public CraftAudit {
        failures = List.copyOf(failures);
    }

    /** How many attempted recipes made what they said they would. */
    public int succeeded() {
        return attempted - failures.size();
    }

    /**
     * The failures counted by the serializer that declared them, commonest first.
     *
     * <p>Record this next to {@link #failuresInVanillaTypes}. It is what makes a vacuous filter
     * visible: the first version of that split keyed on {@code RecipeType}, where every crafting
     * recipe in the game shares the single type {@code minecraft:crafting}, so it matched nothing
     * and reported zero — which is exactly what a clean pack reports. A breakdown showing 50
     * failures from {@code computercraft:impostor_shapeless} beside a vanilla count of 0 says the
     * split is working; the same 0 beside a breakdown naming {@code minecraft:crafting_shaped}
     * would say it is not.
     */
    public List<String> failuresByType() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String failure : failures) {
            int open = failure.indexOf(" [");
            int close = failure.indexOf("]: ", open + 1);
            String type = (open < 0 || close < 0) ? "?" : failure.substring(open + 2, close);
            counts.merge(type, 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue() != 0
                        ? b.getValue() - a.getValue()
                        : a.getKey().compareTo(b.getKey()))
                .map(e -> e.getKey() + "=" + e.getValue())
                .toList();
    }

    /**
     * The failures declared by one of vanilla's own crafting serializers — <b>the assertable
     * subset</b>.
     *
     * <p>{@link #failures()} cannot be asserted empty on a real pack, and finding out why was worth
     * the run. A mod may register its own recipe type whose {@code matches()} deliberately never
     * succeeds: ComputerCraft ships 50-odd {@code computercraft:impostor_shapeless} recipes that
     * exist so JEI and the recipe book can DISPLAY a disk being dyed, while the actual crafting is
     * done by a dynamic recipe elsewhere. The type is named "impostor" in the mod's own datapack.
     * Those recipes really do not make their output, this audit is right that they do not, and
     * nothing is wrong.
     *
     * <p>A recipe serialized by {@code minecraft:crafting_shaped} or
     * {@code minecraft:crafting_shapeless} has no such licence: vanilla's own matcher runs it, its
     * behaviour is entirely determined by the JSON, and one that cannot make its own output is
     * broken with no second reading. So that is the line this splits on — not a threshold, not a
     * baseline file, and nothing that needs updating when a pack adds mods.
     *
     * <p><b>The serializer, not the type.</b> Every crafting-grid recipe in the game shares the one
     * {@code RecipeType} {@code minecraft:crafting}; only the serializer tells vanilla's apart from
     * a mod's. Splitting on type made this match nothing and return an empty list on a pack with 60
     * failures, which read as a clean run. {@link #failuresByType()} exists so that mistake is
     * visible in a results file rather than silent.
     */
    public List<String> failuresInVanillaTypes() {
        return failures.stream()
                .filter(f -> f.contains("[minecraft:crafting_shaped]")
                        || f.contains("[minecraft:crafting_shapeless]"))
                .toList();
    }

    @Override
    public String toString() {
        return "CraftAudit[attempted=" + attempted + ", succeeded=" + succeeded()
                + ", skipped=" + skipped + ", failures=" + failures.size() + "]";
    }
}
