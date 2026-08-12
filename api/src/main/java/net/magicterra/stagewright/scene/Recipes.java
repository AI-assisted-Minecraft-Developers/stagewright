package net.magicterra.stagewright.scene;

import net.magicterra.stagewright.contract.SceneFailure;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.ShapedRecipe;

/**
 * The run's recipe graph — who makes what, out of what, and whether the chain actually closes.
 *
 * <p>Reached as {@link SceneContext#recipes()}. This facet exists because of a question a modpack
 * author cannot otherwise answer: <b>"I changed a recipe — can the pack still be finished?"</b>
 *
 * <h2>Why a graph rather than a playthrough</h2>
 *
 * All the Mods 10's headline goal is crafting the ATM Star, which is a hundred hours of play across
 * 452 mods. Running that is neither possible in a gate nor repeatable if it were. But the property
 * that actually breaks when someone edits a recipe is a property of the GRAPH — is every ingredient
 * of every recipe in the chain reachable — and that is answerable in seconds from the live
 * {@link RecipeManager}. {@link #closureOf} is that answer.
 *
 * <p>The most valuable thing it reports is {@link Closure#unresolvable()}: an ingredient that
 * accepts nothing. In a big pack these appear whenever a recipe references a tag that no mod
 * populated — the recipe loads without error, shows up in JEI as a valid recipe, and can never be
 * crafted by anybody. Nothing in the game logs a word about it.
 *
 * <h2>Dynamic recipes are handled, not assumed away</h2>
 *
 * {@code Recipe.getResultItem} throws for some dynamic recipes rather than returning empty, so
 * every read of a result goes through a guard. A single mod with a badly-behaved special recipe
 * would otherwise take down every scene that so much as enumerates the recipe list — and in a
 * 452-mod pack, betting that none of them has one is not a bet worth taking.
 */
public final class Recipes {

    /**
     * Default ceiling on distinct items a closure walk may visit.
     *
     * <p>Generous for a single mod, deliberately too small for a kitchen-sink pack: a pack-scale
     * scene has to raise it explicitly, which is the moment its author decides how big an answer
     * they actually want. See {@link #closureOf(String, int, int)}.
     */
    public static final int DEFAULT_MAX_NODES = 5_000;

    /** Default ceiling on how deep a closure walk may go. */
    public static final int DEFAULT_MAX_DEPTH = 64;

    private final SceneContext ctx;

    /** Result id -> recipe ids, built once per facet. Every question this class answers is some
     *  query over this index, and rebuilding it per call would make a closure walk quadratic in the
     *  size of a pack's recipe list. */
    private Map<String, List<String>> byResult;

    /** Recipe id -> the holder, so a walk never re-scans the manager to resolve one. */
    private Map<String, RecipeHolder<?>> byId;

    Recipes(SceneContext ctx) {
        this.ctx = ctx;
    }

    // ---- single-recipe questions ----

    /** Ids of every recipe whose result is this item. Empty means the item is not craftable — which
     *  for an ore or a mob drop is the correct answer, not a problem. */
    public List<String> producing(String itemId) {
        String id = canonical(itemId);
        return List.copyOf(index().getOrDefault(id, List.of()));
    }

    /** Whether anything at all makes this item. */
    public boolean anyProduces(String itemId) {
        return !producing(itemId).isEmpty();
    }

    /** The item id this recipe outputs, or {@code ""} for a dynamic recipe that will not say. */
    public String resultOf(String recipeId) {
        RecipeHolder<?> holder = holder(recipeId);
        ItemStack result = safeResult(holder.value());
        return result == null ? "" : Ids.idOf(result);
    }

    /** How many of the result this recipe makes. Zero when the recipe will not say. */
    public int resultCountOf(String recipeId) {
        ItemStack result = safeResult(holder(recipeId).value());
        return result == null ? 0 : result.getCount();
    }

    /**
     * The item ids this recipe consumes — every alternative of every slot, flattened.
     *
     * <p>Flattened rather than nested because a tag ingredient ("any log") is a set of alternatives
     * and a scene almost always wants to ask "is X used here". {@link #ingredientSlotsOf} keeps the
     * slot structure for the rarer scene that needs it.
     */
    public List<String> ingredientsOf(String recipeId) {
        Set<String> out = new LinkedHashSet<>();
        for (List<String> slot : ingredientSlotsOf(recipeId)) out.addAll(slot);
        return List.copyOf(out);
    }

    /**
     * One list of accepted item ids per non-empty ingredient slot.
     *
     * <p>An INNER list that is empty is the interesting case: the slot exists, the recipe declares
     * it, and nothing in this run satisfies it. That is the uncraftable-recipe bug, visible here
     * and nowhere else in the game.
     */
    public List<List<String>> ingredientSlotsOf(String recipeId) {
        RecipeHolder<?> holder = holder(recipeId);
        List<List<String>> out = new ArrayList<>();
        for (Ingredient ingredient : ingredientsIn(holder.value())) {
            if (ingredient.isEmpty()) continue;           // an empty grid cell, not a missing item
            out.add(acceptedBy(ingredient));
        }
        return out;
    }

    /** Every recipe id in this run, sorted. */
    public List<String> all() {
        List<String> out = new ArrayList<>(index().values().stream().flatMap(List::stream).toList());
        out.sort(String::compareTo);
        return out;
    }

    /** How many recipes this run loaded. */
    public int count() {
        indexOnce();
        return byId.size();
    }

    /**
     * Run one recipe: fill its grid from its own declared ingredients and ask it to assemble.
     *
     * <p>Everything else on this facet reads recipe DATA. This executes the recipe's own code, and
     * that is a different question — one a graph walk cannot ask. A recipe whose {@code matches()}
     * rejects its own declared ingredients, or whose {@code assemble()} returns an empty stack or
     * something other than its stated result, is in the registry, has resolvable ingredients, and is
     * walked straight through by {@link #closureOf}. Every static check this facet has passes it.
     * The item is simply not craftable by anybody.
     *
     * <p>The grid is filled with the FIRST item each slot accepts. That is a choice with a limit
     * worth naming: a recipe that accepts a tag but rejects one of its members in {@code matches()}
     * is not found by this unless the first member is the rejected one. Trying every combination is
     * exponential and this runs over 90,000 recipes, so the cheap pass is the one that ships.
     *
     * @return the id of what the recipe assembled, or {@code ""} if it produced nothing — including
     *         when the recipe declined to match, which is the failure this exists to find
     * @throws SceneFailure if there is no such recipe, or its input is not a crafting grid (ask
     *         {@link #isGridRecipe} first; a smelting or machine recipe takes an input this facet
     *         cannot build without knowing the mod that defined it)
     */
    public String crafts(String recipeId) {
        RecipeHolder<?> holder = holder(recipeId);
        if (!(holder.value() instanceof CraftingRecipe recipe)) {
            throw new SceneFailure("'" + recipeId + "' is a " + holder.value().getType()
                    + " recipe, whose input is not a crafting grid — this facet can only run"
                    + " grid recipes generically. Ask isGridRecipe() first.");
        }
        if (isDynamic(recipe)) {
            throw new SceneFailure("'" + recipeId + "' computes itself at runtime and declares no"
                    + " ingredients or result of its own (vanilla calls these special: map cloning,"
                    + " armour dyeing, firework assembly). There is nothing to run it against."
                    + " Ask isGridRecipe() first.");
        }
        return assembleId(recipe);
    }

    /**
     * Whether {@link #crafts} can run this recipe: its input is a crafting grid AND it declares
     * ingredients and a result to be checked against.
     */
    public boolean isGridRecipe(String recipeId) {
        return holder(recipeId).value() instanceof CraftingRecipe recipe && !isDynamic(recipe);
    }

    /**
     * Ask every grid recipe in the run to make its own output out of its own declared ingredients.
     *
     * <p>The behavioural twin of {@link #uncraftable}, which is the static one. That audit finds a
     * recipe nothing can satisfy; this one finds a recipe that can be satisfied and still does not
     * work. Both are whole-pack, both are one call, and neither can find the other's bug.
     *
     * <p>Reports what it could not attempt as a first-class number rather than leaving it implied —
     * see {@link CraftAudit}.
     */
    public CraftAudit craftAudit() {
        indexOnce();
        int attempted = 0;
        int skipped = 0;
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, RecipeHolder<?>> entry : byId.entrySet()) {
            if (!(entry.getValue().value() instanceof CraftingRecipe recipe) || isDynamic(recipe)) {
                skipped++;
                continue;
            }
            attempted++;
            String declared = Ids.idOf(resultOf(recipe));
            String kind = typeOf(recipe);
            String made;
            try {
                made = assembleId(recipe);
            } catch (RuntimeException e) {
                // A recipe that throws out of its own matches/assemble is the loudest version of
                // this bug, and the one most likely to be somebody's NPE. Reported like any other
                // failure rather than ending the audit — one bad recipe must not hide the rest.
                failures.add(entry.getKey() + " [" + kind + "]: threw " + e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : " (" + e.getMessage() + ")"));
                continue;
            }
            if (made.isEmpty()) {
                failures.add(entry.getKey() + " [" + kind + "]: declares " + declared
                        + " but makes nothing from its own ingredients");
            } else if (!made.equals(declared)) {
                failures.add(entry.getKey() + " [" + kind + "]: declares " + declared
                        + " but makes " + made);
            }
        }
        failures.sort(String::compareTo);
        return new CraftAudit(attempted, skipped, failures);
    }

    /**
     * A recipe's registered SERIALIZER id, or {@code "?"} if the registry does not know it.
     *
     * <p>Not its {@code RecipeType}, which was the first version of this and reported nothing
     * useful: every crafting-grid recipe in the game shares the single type {@code minecraft:crafting},
     * vanilla's and ComputerCraft's impostors alike. The serializer is what actually distinguishes
     * them ({@code minecraft:crafting_shaped} from {@code computercraft:impostor_shapeless}), and it
     * is what {@link CraftAudit#failuresInVanillaTypes} splits on. The type-keyed version made that
     * split match nothing at all — a filter that returns an empty list because it can never match is
     * indistinguishable from a clean run, and it reported one.
     */
    private static String typeOf(Recipe<?> recipe) {
        ResourceLocation key = BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer());
        return key == null ? "?" : key.toString();
    }

    /**
     * Whether a recipe computes itself at runtime and so declares nothing to audit against.
     *
     * <p>Vanilla's map cloning, banner duplication, armour dyeing, firework assembly and shield
     * decoration are all of this kind: they extend {@code CustomRecipe}, declare no ingredients and
     * an empty result, and decide everything from whatever the player actually put in the grid.
     * Asking one to "make its own output out of its own ingredients" is a question with no meaning,
     * and the first version of this audit asked it anyway — reporting five vanilla recipes as broken
     * on a completely clean install, which is the most expensive kind of false positive a whole-pack
     * audit can produce.
     *
     * <p>Detected by what the recipe DECLARES rather than by {@code isSpecial()} alone. The flag is
     * the vanilla name for this category and is checked first, but it is a mod's to set and a mod
     * that forgets it still has nothing here to test; going on the declaration covers both.
     */
    private boolean isDynamic(CraftingRecipe recipe) {
        if (recipe.isSpecial()) return true;
        return ingredientsIn(recipe).isEmpty() || resultOf(recipe).isEmpty();
    }

    /** Build the recipe's declared grid, match it, assemble it; {@code ""} if it makes nothing. */
    private String assembleId(CraftingRecipe recipe) {
        List<ItemStack> grid = new ArrayList<>();
        for (Ingredient ingredient : ingredientsIn(recipe)) {
            ItemStack[] accepted = ingredient.getItems();
            // An empty ingredient is a blank cell in a shaped recipe and must stay blank — filling
            // it would change the shape and the recipe would rightly refuse to match.
            grid.add(accepted.length == 0 ? ItemStack.EMPTY : accepted[0].copy());
        }
        if (grid.isEmpty()) return "";
        // A shaped recipe's ingredient list is its declared rectangle read row by row, so its own
        // width is the only one that reconstructs it. A shapeless recipe has no shape; vanilla's
        // 3x3 is what a player has, and anything up to nine fits one row of it as far as matching
        // is concerned — but not more than three per row, or a 4-ingredient shapeless recipe would
        // be handed a 4-wide grid no crafting table has.
        int width = recipe instanceof ShapedRecipe shaped ? shaped.getWidth() : Math.min(3, grid.size());
        int height = recipe instanceof ShapedRecipe shaped ? shaped.getHeight()
                : (grid.size() + 2) / 3;
        while (grid.size() < width * height) grid.add(ItemStack.EMPTY);
        if (grid.size() > width * height) return "";      // cannot be laid out; not a craft failure
        CraftingInput input = CraftingInput.of(width, height, grid);
        if (!recipe.matches(input, ctx.level())) return "";
        ItemStack made = recipe.assemble(input, ctx.level().registryAccess());
        return made.isEmpty() ? "" : Ids.idOf(made);
    }

    /** A recipe's declared result, through the registry-aware accessor 1.21 requires. */
    private ItemStack resultOf(Recipe<?> recipe) {
        try {
            return recipe.getResultItem(ctx.level().registryAccess());
        } catch (RuntimeException e) {
            return ItemStack.EMPTY;
        }
    }

    /**
     * Every recipe that declares an ingredient slot nothing can satisfy.
     *
     * <p>A whole-pack audit in one call, and the cheapest real bug this facet finds. Sorted, so two
     * runs of the same pack produce comparable output.
     */
    public List<String> uncraftable() {
        indexOnce();
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, RecipeHolder<?>> entry : byId.entrySet()) {
            for (Ingredient ingredient : ingredientsIn(entry.getValue().value())) {
                if (ingredient.isEmpty()) continue;
                if (acceptedBy(ingredient).isEmpty()) {
                    out.add(entry.getKey());
                    break;
                }
            }
        }
        out.sort(String::compareTo);
        return out;
    }

    // ---- what a recipe mentions, when it will not say what it consumes ----

    /**
     * Item ids this recipe's own serialized form names, whatever kind of recipe it is.
     *
     * <p><b>Read {@link #ingredientsOf} first.</b> That is the recipe telling you what it consumes.
     * This is the recipe being re-encoded through its own codec and the resulting JSON scanned for
     * anything that resolves to an item — a different and weaker claim, and the two must not be
     * confused. It exists for the recipes that answer {@link #ingredientsOf} with silence.
     *
     * <p>Which, in a kitchen-sink pack, is most of them. {@code Recipe#getIngredients()} is a default
     * method returning an empty list, so a mod's own recipe type answers "no ingredients" instead of
     * refusing to answer, and every walk down through it stops with nothing to report. But every
     * recipe that loaded from a datapack has a codec that read it, and
     * {@link Recipe#CODEC} is a dispatch codec keyed on the recipe type — so the same codec will
     * write it back out, custom type and all, with no knowledge of the mod required.
     *
     * <p><b>What this cannot do, and why it is a separate method rather than a better
     * {@code ingredientsOf}:</b>
     * <ul>
     *   <li>It has no slot structure. "This recipe mentions iron" is not "slot 3 takes iron", so it
     *       can never report an <em>unsatisfiable</em> slot — the single most valuable thing
     *       {@link #uncraftable()} finds.</li>
     *   <li>It over-collects by construction. A {@code "group": "bucket"} field names an item that is
     *       not an ingredient, and nothing in the JSON distinguishes the two. The recipe's own result
     *       is filtered out because a self-edge is both certain and useless; the rest is not
     *       filterable without knowing the mod.</li>
     * </ul>
     *
     * <p>Empty means the codec refused to encode this recipe at all — some special recipes have
     * codecs that only decode — which is reported as emptiness rather than thrown, exactly like
     * {@link #safeResult}.
     */
    public List<String> mentionedBy(String recipeId) {
        return mentionsOf(holder(recipeId));
    }

    private List<String> mentionsOf(RecipeHolder<?> holder) {
        JsonElement json;
        try {
            RegistryOps<JsonElement> ops =
                    ctx.level().registryAccess().createSerializationContext(JsonOps.INSTANCE);
            json = Recipe.CODEC.encodeStart(ops, holder.value()).result().orElse(null);
        } catch (RuntimeException e) {
            return List.of();
        }
        if (json == null) return List.of();

        Set<String> strings = new LinkedHashSet<>();
        harvest(null, json, strings);

        ItemStack made = safeResult(holder.value());
        String result = made == null || made.isEmpty() ? "" : Ids.idOf(made);

        Set<String> out = new LinkedHashSet<>();
        for (String text : strings) {
            if (text.startsWith("#")) {
                for (String id : itemsInTag(text.substring(1))) {
                    if (!id.equals(result)) out.add(id);
                }
                continue;
            }
            ResourceLocation location = ResourceLocation.tryParse(text);
            if (location == null || !BuiltInRegistries.ITEM.containsKey(location)) continue;
            String id = location.toString();
            if (!id.equals(result)) out.add(id);
        }
        return List.copyOf(out);
    }

    /**
     * Every string anywhere in the encoded recipe, with tag references normalised to a {@code #}
     * prefix.
     *
     * <p>Structure-blind on purpose: the point is to work for a recipe type nobody here has heard of,
     * and any attempt to understand the shape would be an attempt to understand every mod's schema.
     * The key is carried down for one reason — vanilla's {@code HolderSet} codec writes a tag as
     * {@code "#c:ingots/iron"}, but a mod rolling its own ingredient field writes
     * {@code {"tag": "c:ingots/iron"}}, and without the key the second form reads as an item id that
     * happens not to exist and is silently dropped.
     */
    private static void harvest(String key, JsonElement json, Set<String> out) {
        if (json.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject().entrySet()) {
                harvest(entry.getKey(), entry.getValue(), out);
            }
        } else if (json.isJsonArray()) {
            for (JsonElement element : json.getAsJsonArray()) harvest(key, element, out);
        } else if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isString()) {
            String text = json.getAsString();
            out.add("tag".equals(key) && !text.startsWith("#") ? "#" + text : text);
        }
    }

    /** The item ids in an item tag, or none if no such tag is bound in this run. */
    private static List<String> itemsInTag(String tagId) {
        ResourceLocation location = ResourceLocation.tryParse(tagId);
        if (location == null) return List.of();
        List<String> out = new ArrayList<>();
        for (Holder<Item> holder : BuiltInRegistries.ITEM
                .getTagOrEmpty(TagKey.create(Registries.ITEM, location))) {
            out.add(Ids.idOf(holder.value()));
        }
        return out;
    }

    // ---- the closure ----

    /** {@link #closureOf(String, int, int)} with the default bounds. */
    public Closure closureOf(String itemId) {
        return closureOf(itemId, DEFAULT_MAX_NODES, DEFAULT_MAX_DEPTH);
    }

    /**
     * Walk the recipe graph down from an item until every branch reaches something uncraftable.
     *
     * <p>Breadth-first over distinct item ids, so a diamond-shaped graph (and every real pack's is)
     * costs its item count rather than its path count. An item already seen is not re-expanded,
     * which is also what makes cycles terminate instead of hanging the tick.
     *
     * <p><b>Exceeding a bound throws.</b> Returning a partial closure would be the worst possible
     * outcome: a truncated walk has fewer unresolvable ingredients and fewer dangling leaves than
     * the real graph, so it looks EXACTLY like a healthier pack. The message names which bound blew
     * and what it had reached, so raising it is a deliberate act.
     *
     * <p><b>The walk only sees as far as {@code Recipe#getIngredients()} does, which for a modded
     * machine recipe is usually nowhere.</b> That method is a default returning an empty list, so a
     * custom recipe type answers "no ingredients" rather than refusing to answer, and the branch
     * ends silently. This cannot be worked around from here — there is no other interface to ask —
     * so it is reported instead: every such recipe lands in {@link Closure#opaque()}, and a scene
     * that does not check it is reading a number whose meaning it does not know.
     */
    public Closure closureOf(String itemId, int maxNodes, int maxDepth) {
        String root = canonical(itemId);
        if (maxNodes < 1 || maxDepth < 1) {
            throw new SceneFailure("closure bounds must both be at least 1, got maxNodes=" + maxNodes
                    + " maxDepth=" + maxDepth);
        }

        Set<String> seen = new LinkedHashSet<>();
        Set<String> leaves = new LinkedHashSet<>();
        Set<String> recipes = new LinkedHashSet<>();
        List<String> unresolvable = new ArrayList<>();
        Set<String> opaque = new LinkedHashSet<>();
        Set<String> declared = new LinkedHashSet<>();       // reached via a real ingredient slot
        Deque<String[]> frontier = new ArrayDeque<>();      // {itemId, depth}
        int deepest = 0;

        frontier.add(new String[] { root, "0" });
        seen.add(root);

        while (!frontier.isEmpty()) {
            String[] node = frontier.removeFirst();
            String item = node[0];
            int depth = Integer.parseInt(node[1]);
            deepest = Math.max(deepest, depth);

            List<String> makers = producing(item);
            if (makers.isEmpty()) {
                leaves.add(item);
                continue;
            }
            if (depth >= maxDepth) {
                throw new SceneFailure("the recipe closure of '" + root + "' is deeper than maxDepth="
                        + maxDepth + " (reached '" + item + "' at depth " + depth + " with "
                        + seen.size() + " items visited) — raise the bound deliberately rather than"
                        + " reading a truncated closure, which looks exactly like a healthy one");
            }

            for (String recipeId : makers) {
                recipes.add(recipeId);
                List<List<String>> slots = ingredientSlotsOf(recipeId);
                // No slots AT ALL is a different claim from a slot nothing satisfies, and the
                // difference decides whether this closure means anything. See Closure#opaque.
                if (slots.isEmpty()) {
                    opaque.add(recipeId);
                    // ...and then keep walking anyway, on what the recipe's own codec says it
                    // mentions. Stopping here is what made the ATM Star's closure eleven items of
                    // compression loop that looked perfectly healthy. These edges are weaker than a
                    // declared ingredient and Closure#inferred says exactly which items came in on
                    // one, so a scene can tell the two apart instead of being told they are the same.
                    for (String next : mentionedBy(recipeId)) {
                        if (!seen.add(next)) continue;
                        requireRoom(seen.size(), maxNodes, root, item, recipeId);
                        frontier.addLast(new String[] { next, String.valueOf(depth + 1) });
                    }
                    continue;
                }
                for (int slot = 0; slot < slots.size(); slot++) {
                    List<String> accepted = slots.get(slot);
                    if (accepted.isEmpty()) {
                        unresolvable.add(recipeId + " slot " + slot);
                        continue;
                    }
                    for (String next : accepted) {
                        declared.add(next);
                        if (!seen.add(next)) continue;
                        requireRoom(seen.size(), maxNodes, root, item, recipeId);
                        frontier.addLast(new String[] { next, String.valueOf(depth + 1) });
                    }
                }
            }
        }
        // Set difference rather than "which channel got there first": an item can arrive by a
        // mention and later turn up in a real slot, and calling that inferred because of walk order
        // would make the number depend on breadth-first ordering rather than on the pack.
        Set<String> inferred = new LinkedHashSet<>(seen);
        inferred.removeAll(declared);
        inferred.remove(root);
        return new Closure(root, List.copyOf(seen), List.copyOf(leaves), List.copyOf(recipes),
                List.copyOf(unresolvable), List.copyOf(opaque), List.copyOf(inferred), deepest);
    }

    /** Blow the node bound loudly. A truncated closure has fewer unresolvable slots and fewer
     *  dangling leaves than the real graph, so it looks EXACTLY like a healthier pack — which is why
     *  raising the bound has to be somebody's decision rather than this method's. */
    private static void requireRoom(int reached, int maxNodes, String root, String item, String via) {
        if (reached <= maxNodes) return;
        throw new SceneFailure("the recipe closure of '" + root + "' touches more than maxNodes="
                + maxNodes + " distinct items (blew the bound expanding '" + item + "' via " + via
                + ") — raise the bound deliberately rather than reading a truncated closure, which"
                + " looks exactly like a healthy one");
    }

    // ---- internals ----

    /** Normalise an id through the item registry, so a typo fails here rather than as a silently
     *  empty result list three assertions later. */
    private String canonical(String itemId) {
        return Ids.idOf(Ids.item(itemId));
    }

    private Map<String, List<String>> index() {
        indexOnce();
        return byResult;
    }

    private void indexOnce() {
        if (byResult != null) return;
        Map<String, List<String>> results = new LinkedHashMap<>();
        Map<String, RecipeHolder<?>> holders = new LinkedHashMap<>();
        RecipeManager manager = ctx.level().getRecipeManager();
        for (RecipeHolder<?> holder : manager.getRecipes()) {
            holders.put(holder.id().toString(), holder);
            ItemStack result = safeResult(holder.value());
            if (result == null || result.isEmpty()) continue;   // dynamic / special recipe
            results.computeIfAbsent(Ids.idOf(result), k -> new ArrayList<>())
                   .add(holder.id().toString());
        }
        byResult = results;
        byId = holders;
    }

    private RecipeHolder<?> holder(String recipeId) {
        indexOnce();
        RecipeHolder<?> holder = byId.get(Ids.location(recipeId, "recipe").toString());
        if (holder == null) {
            throw new SceneFailure("no recipe is registered as '" + recipeId + "' — this run loaded "
                    + byId.size() + " recipes");
        }
        return holder;
    }

    /** The item ids an ingredient accepts. Empty means nothing in this run satisfies it. */
    private static List<String> acceptedBy(Ingredient ingredient) {
        List<String> out = new ArrayList<>();
        for (ItemStack stack : ingredient.getItems()) {
            String id = Ids.idOf(stack);
            if (!id.isEmpty() && !out.contains(id)) out.add(id);
        }
        return out;
    }

    /** A recipe's declared ingredients, or none when it will not say — same defensive reasoning as
     *  {@link #safeResult}: a dynamic recipe may throw rather than answer. */
    private static NonNullList<Ingredient> ingredientsIn(Recipe<?> recipe) {
        try {
            return recipe.getIngredients();
        } catch (RuntimeException e) {
            return NonNullList.create();
        }
    }

    /** A recipe's result, or null when reading it throws — which some dynamic recipes do. */
    private ItemStack safeResult(Recipe<?> recipe) {
        HolderLookup.Provider registries = ctx.level().registryAccess();
        try {
            return recipe.getResultItem(registries);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
