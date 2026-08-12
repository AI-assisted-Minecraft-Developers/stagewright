package net.magicterra.stagewright.scene;

import java.util.List;

/**
 * The answer {@link Recipes#closureOf} produces: everything reachable by walking a recipe graph
 * down from one item until every branch runs out of recipes.
 *
 * <p>A value object rather than a handful of methods on the facet, because the interesting
 * assertions are about the shape of the whole walk and a scene should pay for it once:
 *
 * <pre>{@code
 * var star = s.recipes().closureOf("allthetweaks:atm_star", 50000, 96);
 * s.record("closure.items", star.itemCount());
 * s.record("closure.depth", star.depth());
 * s.expect(star.unresolvable()).as("ingredients nothing in this pack can satisfy").isEmpty();
 * s.expect(star.opaque()).as("recipes the walk could not see through").isEmpty();
 * }</pre>
 *
 * <p><b>Read {@link #opaque()} before believing any of the rest.</b> A closure computed through
 * recipes the walk could not see into is not wrong, it is short — and a short closure looks exactly
 * like a healthy small one.
 *
 * <p>Every list is immutable and in discovery order, so two runs of the same pack produce output a
 * human can diff.
 */
public final class Closure {

    private final String root;
    private final List<String> items;
    private final List<String> leaves;
    private final List<String> recipes;
    private final List<String> unresolvable;
    private final List<String> opaque;
    private final List<String> inferred;
    private final int depth;

    Closure(String root, List<String> items, List<String> leaves, List<String> recipes,
            List<String> unresolvable, List<String> opaque, List<String> inferred, int depth) {
        this.root = root;
        this.items = items;
        this.leaves = leaves;
        this.recipes = recipes;
        this.unresolvable = unresolvable;
        this.opaque = opaque;
        this.inferred = inferred;
        this.depth = depth;
    }

    /** The item the walk started from. */
    public String root() {
        return root;
    }

    /** Every distinct item the walk reached, the root included. */
    public List<String> items() {
        return items;
    }

    public int itemCount() {
        return items.size();
    }

    /**
     * Items with no recipe producing them — where the graph bottoms out.
     *
     * <p>These are not failures. An ore, a mob drop and a bucket of lava are all legitimately
     * uncraftable, and a healthy closure has plenty. What makes them worth reporting is that the
     * SET of them is a pack's real dependency list: if a leaf is something no dimension in this run
     * generates, the chain is broken somewhere a recipe check would never look.
     */
    public List<String> leaves() {
        return leaves;
    }

    /** Every recipe the walk went through. */
    public List<String> recipes() {
        return recipes;
    }

    public int recipeCount() {
        return recipes.size();
    }

    /**
     * Ingredient slots nothing in this run can satisfy, as {@code "<recipe id> slot N"}.
     *
     * <p><b>The one this facet exists for.</b> A recipe referencing a tag no mod populated loads
     * without an error, appears in JEI as a perfectly good recipe, and can never be crafted. Nothing
     * in the game says a word — so a pack ships, and the report is a player stuck two hundred hours
     * in. A non-empty list here is a shipping defect every time.
     */
    public List<String> unresolvable() {
        return unresolvable;
    }

    /**
     * Recipes that produced something in this closure and declared no ingredients at all.
     *
     * <p>Almost never "a recipe with no inputs". In 1.21 {@code Recipe#getIngredients()} is a
     * DEFAULT method returning an empty list, and a mod adding its own recipe type has no reason to
     * override it — nothing in vanilla calls it for a machine recipe. So a modded processing recipe
     * typically answers "no ingredients" rather than refusing to answer, and the walk stops there
     * with nothing to report.
     *
     * <p>That is not hypothetical. All the Mods 10 builds its ATM Star in a Modern Industrialization
     * multiblock, and walking down from {@code allthetweaks:atm_star} reached 11 items through 22
     * recipes with zero leaves and zero unresolvable slots — a closure that had never left the
     * star/star-block compression loop and looked entirely healthy doing it. The Star's actual
     * ingredient tree was invisible.
     *
     * <p>So this list is the closure's own statement about its completeness. Empty means the walk
     * saw everything the recipe manager can describe. Non-empty means the tree continues past these
     * recipes and this API cannot follow — which is a fact about Minecraft's recipe interface, not a
     * defect in the pack, and has to be reported rather than absorbed.
     */
    public List<String> opaque() {
        return opaque;
    }

    public int opaqueCount() {
        return opaque.size();
    }

    /**
     * Items that are in this closure only because some recipe's serialized form mentioned them —
     * no recipe anywhere in the walk declared them as an ingredient.
     *
     * <p>These are how the walk gets past an {@link #opaque()} recipe at all: with nothing to ask,
     * it re-encodes the recipe through its own codec and follows every item id in the result. That
     * finds the real tree — the ATM Star's closure was eleven items of compression loop before this
     * existed — at the cost of a weaker claim per edge, because a JSON field naming an item is not
     * the same statement as a slot requiring one. A {@code "group"} that happens to match an item
     * name lands here, and nothing in the JSON can tell it apart from an ingredient.
     *
     * <p>So this is the closure's second completeness statement, next to {@link #opaque()}. That one
     * says how much the walk could not see; this one says how much of what it did see is inference.
     * A scene asserting on {@link #reaches} should know which kind of edge it is standing on.
     */
    public List<String> inferred() {
        return inferred;
    }

    public int inferredCount() {
        return inferred.size();
    }

    /** How many recipe steps down the deepest branch went. */
    public int depth() {
        return depth;
    }

    /** Whether this item is anywhere in the chain — the "does the star still depend on X" question. */
    public boolean reaches(String itemId) {
        return items.contains(itemId);
    }

    @Override
    public String toString() {
        return "Closure[" + root + ": " + items.size() + " items, " + recipes.size() + " recipes, depth "
                + depth + ", " + leaves.size() + " leaves, " + unresolvable.size() + " unresolvable, "
                + opaque.size() + " opaque, " + inferred.size() + " inferred]";
    }
}
