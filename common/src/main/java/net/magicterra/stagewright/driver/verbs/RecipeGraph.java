package net.magicterra.stagewright.driver.verbs;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.magicterra.stagewright.test.methods.RecipesContracts.AuditReport;
import net.magicterra.stagewright.test.methods.RecipesContracts.Closure;
import net.magicterra.stagewright.test.methods.RecipesContracts.Counted;
import net.magicterra.stagewright.test.methods.RecipesContracts.IngredientsOf;
import net.magicterra.stagewright.test.methods.RecipesContracts.KindCount;
import net.magicterra.stagewright.test.methods.RecipesContracts.ProducedBy;
import net.magicterra.stagewright.test.methods.RecipesContracts.Slot;
import net.magicterra.stagewright.test.methods.RecipesContracts.UncraftableRecipes;
import net.magicterra.worlddriver.api.schema.ApiException;
import net.magicterra.worlddriver.api.schema.ApiIssue;
import net.minecraft.core.Holder;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;

/**
 * The loaded recipe graph for one question: who makes what, out of what, and whether the chain closes. Built from the
 * recipe manager each time, so it never goes stale across a datapack reload.
 *
 * <p>Several recipes cannot be asked what they make or consume: the "special" ones (map cloning, armour dyeing,
 * firework assembly) compute themselves at runtime, and {@code getResultItem} or {@code getIngredients} on a modded one
 * can throw. Every read here goes through a guard that turns that into "declares nothing", because one bad recipe must
 * not hide the rest of the graph.
 */
final class RecipeGraph {

    /** Result id to the recipes that make it. */
    private final Map<String, List<String>> byResult = new LinkedHashMap<>();

    private final Map<String, RecipeHolder<?>> byId = new LinkedHashMap<>();
    private final ServerLevel level;

    RecipeGraph(ServerLevel level) {
        this.level = level;
        for (RecipeHolder<?> holder : level.getRecipeManager().getRecipes()) {
            byId.put(holder.id().toString(), holder);
            ItemStack result = resultOf(holder.value());
            if (result.isEmpty()) {
                continue; // dynamic recipe
            }
            byResult.computeIfAbsent(idOf(result), k -> new ArrayList<>())
                    .add(holder.id().toString());
        }
    }

    ProducedBy producing(String itemId) {
        return new ProducedBy(List.copyOf(byResult.getOrDefault(item(itemId), List.of())));
    }

    IngredientsOf ingredients(String recipeId) {
        RecipeHolder<?> holder = holder(recipeId);
        ItemStack result = resultOf(holder.value());
        List<Slot> slots = new ArrayList<>();
        for (List<String> accepts : slotsOf(holder)) {
            slots.add(new Slot(accepts));
        }
        return new IngredientsOf(idOf(result), result.isEmpty() ? 0 : result.getCount(), slots);
    }

    UncraftableRecipes uncraftable() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, RecipeHolder<?>> entry : byId.entrySet()) {
            for (Ingredient ingredient : ingredientsIn(entry.getValue().value())) {
                if (ingredient.isEmpty()) {
                    continue;
                }
                if (acceptedBy(ingredient).isEmpty()) {
                    out.add(entry.getKey());
                    break;
                }
            }
        }
        out.sort(String::compareTo);
        return new UncraftableRecipes(out.size(), out.subList(0, Math.min(out.size(), 10_000)));
    }

    /**
     * Lay every grid recipe out from its own first-accepted ingredients and see what it assembles. A recipe that
     * throws, makes nothing, or makes something else than it declares is a failure; the rest are the audit.
     */
    AuditReport audit() {
        int attempted = 0;
        int skipped = 0;
        List<String> failures = new ArrayList<>();
        Map<String, Integer> bySerializer = new LinkedHashMap<>();
        for (Map.Entry<String, RecipeHolder<?>> entry : byId.entrySet()) {
            if (!(entry.getValue().value() instanceof CraftingRecipe recipe) || isDynamic(recipe)) {
                skipped++;
                continue;
            }
            attempted++;
            String declared = idOf(resultOf(recipe));
            String kind = typeOf(recipe);
            String made;
            String failure = null;
            try {
                made = assembleId(recipe);
                if (made.isEmpty()) {
                    failure = "declares " + declared + " but makes nothing from its own ingredients";
                } else if (!made.equals(declared)) {
                    failure = "declares " + declared + " but makes " + made;
                }
            } catch (RuntimeException e) {
                failure = "threw " + e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : " (" + e.getMessage() + ")");
            }
            if (failure != null) {
                failures.add(entry.getKey() + " [" + kind + "]: " + failure);
                bySerializer.merge(kind, 1, Integer::sum);
            }
        }
        failures.sort(String::compareTo);
        List<String> cut = failures.subList(0, Math.min(failures.size(), 1000)).stream()
                .map(f -> f.length() > 1024 ? f.substring(0, 1024) : f)
                .toList();
        return new AuditReport(
                byId.size(),
                attempted,
                attempted - failures.size(),
                skipped,
                failures.size(),
                mostFirst(bySerializer),
                cut);
    }

    /** How many recipes are loaded, in all and per recipe type. */
    Counted count() {
        Map<String, Integer> byType = new LinkedHashMap<>();
        for (RecipeHolder<?> holder : byId.values()) {
            ResourceLocation type =
                    BuiltInRegistries.RECIPE_TYPE.getKey(holder.value().getType());
            byType.merge(type == null ? "?" : type.toString(), 1, Integer::sum);
        }
        return new Counted(byId.size(), mostFirst(byType));
    }

    /** Counts by kind, most first, then by name; cut at the reply's limit. */
    private static List<KindCount> mostFirst(Map<String, Integer> counts) {
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue()
                        .reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(4096)
                .map(e -> new KindCount(
                        e.getKey().length() > 256 ? e.getKey().substring(0, 256) : e.getKey(), e.getValue()))
                .toList();
    }

    /**
     * Walk every ingredient of an item down to raw materials. A walk that hits a bound stops and says so
     * ({@code truncated}), because a cut-off closure looks exactly like a healthy one otherwise.
     */
    // One breadth-first walk sharing its frontier and result sets; splitting it would pass them all around.
    @SuppressWarnings("checkstyle:javancss")
    Closure closure(String itemId, int maxNodes, int maxDepth) {
        String root = item(itemId);
        Set<String> seen = new LinkedHashSet<>();
        Set<String> leaves = new LinkedHashSet<>();
        Set<String> recipes = new LinkedHashSet<>();
        List<String> unresolvable = new ArrayList<>();
        Set<String> opaque = new LinkedHashSet<>();
        Set<String> declared = new LinkedHashSet<>(); // reached through a real ingredient slot
        Deque<String[]> frontier = new ArrayDeque<>(); // {itemId, depth}
        boolean truncated = false;
        int deepest = 0;

        frontier.add(new String[] {root, "0"});
        seen.add(root);

        while (!frontier.isEmpty()) {
            String[] node = frontier.removeFirst();
            String item = node[0];
            int depth = Integer.parseInt(node[1]);
            deepest = Math.max(deepest, depth);

            List<String> makers = byResult.getOrDefault(item, List.of());
            if (makers.isEmpty()) {
                leaves.add(item);
                continue;
            }
            if (depth >= maxDepth) {
                truncated = true;
                continue;
            }
            for (String recipeId : makers) {
                recipes.add(recipeId);
                List<List<String>> slots = slotsOf(byId.get(recipeId));
                // No slots at all is a different claim from a slot nothing satisfies. Keep walking on what the
                // recipe's own serialization mentions: stopping here made a modded closure look healthy at
                // eleven items. These edges are weaker, and `inferred` says which items came in on one.
                if (slots.isEmpty()) {
                    opaque.add(recipeId);
                    for (String next : mentionedBy(byId.get(recipeId))) {
                        if (seen.contains(next)) {
                            continue;
                        }
                        if (seen.size() >= maxNodes) {
                            truncated = true;
                            continue;
                        }
                        seen.add(next);
                        frontier.addLast(new String[] {next, String.valueOf(depth + 1)});
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
                        if (seen.contains(next)) {
                            continue;
                        }
                        if (seen.size() >= maxNodes) {
                            truncated = true;
                            continue;
                        }
                        seen.add(next);
                        frontier.addLast(new String[] {next, String.valueOf(depth + 1)});
                    }
                }
            }
        }
        // Set difference, not "which channel got there first": an item can arrive by a mention and later turn up
        // in a real slot, and breadth-first order must not decide what counts as inferred.
        Set<String> inferred = new LinkedHashSet<>(seen);
        inferred.removeAll(declared);
        inferred.remove(root);
        return new Closure(
                root,
                List.copyOf(seen),
                List.copyOf(leaves),
                List.copyOf(recipes),
                unresolvable.stream()
                        .map(s -> s.length() > 512 ? s.substring(0, 512) : s)
                        .toList(),
                List.copyOf(opaque),
                List.copyOf(inferred),
                deepest,
                truncated);
    }

    // ---- ids ----

    /** A registered item's canonical id; the registry is defaulted, so an unknown id must not read as air. */
    private static String item(String itemId) {
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            throw ApiException.invalidParam("/item", ApiIssue.Code.INVALID_VALUE, "no item is registered as " + itemId);
        }
        return id.toString();
    }

    private RecipeHolder<?> holder(String recipeId) {
        RecipeHolder<?> holder = byId.get(recipeId);
        if (holder == null) {
            throw ApiException.invalidParam(
                    "/recipe",
                    ApiIssue.Code.INVALID_VALUE,
                    "no recipe is registered as " + recipeId + "; " + byId.size() + " are loaded");
        }
        return holder;
    }

    private static String idOf(ItemStack stack) {
        return stack.isEmpty() ? "" : idOf(stack.getItem());
    }

    private static String idOf(Item item) {
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
        return key == null ? "<unregistered>" : key.toString();
    }

    // ---- guarded reads ----

    private ItemStack resultOf(Recipe<?> recipe) {
        try {
            return recipe.getResultItem(level.registryAccess());
        } catch (RuntimeException e) {
            return ItemStack.EMPTY;
        }
    }

    private static List<Ingredient> ingredientsIn(Recipe<?> recipe) {
        try {
            return recipe.getIngredients();
        } catch (RuntimeException e) {
            return NonNullList.create();
        }
    }

    /** The accepted item ids of each non-empty ingredient; an empty grid cell is not a missing item. */
    private static List<List<String>> slotsOf(RecipeHolder<?> holder) {
        List<List<String>> out = new ArrayList<>();
        for (Ingredient ingredient : ingredientsIn(holder.value())) {
            if (ingredient.isEmpty()) {
                continue;
            }
            out.add(acceptedBy(ingredient));
        }
        return out;
    }

    private static List<String> acceptedBy(Ingredient ingredient) {
        List<String> out = new ArrayList<>();
        for (ItemStack stack : ingredient.getItems()) {
            String id = idOf(stack);
            if (!id.isEmpty() && !out.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    private static String typeOf(Recipe<?> recipe) {
        ResourceLocation key = BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer());
        return key == null ? "?" : key.toString();
    }

    private boolean isDynamic(CraftingRecipe recipe) {
        if (recipe.isSpecial()) {
            return true;
        }
        return ingredientsIn(recipe).isEmpty() || resultOf(recipe).isEmpty();
    }

    private String assembleId(CraftingRecipe recipe) {
        List<ItemStack> grid = new ArrayList<>();
        for (Ingredient ingredient : ingredientsIn(recipe)) {
            ItemStack[] accepted = ingredient.getItems();
            // An empty ingredient is a blank cell in a shaped recipe and must stay blank, or the shape changes.
            grid.add(accepted.length == 0 ? ItemStack.EMPTY : accepted[0].copy());
        }
        if (grid.isEmpty()) {
            return "";
        }
        // A shaped recipe's ingredients are its rectangle read row by row, so its own width is the only one that
        // reconstructs it. A shapeless one has no shape: at most three per row, as in a crafting table.
        int width = recipe instanceof ShapedRecipe shaped ? shaped.getWidth() : Math.min(3, grid.size());
        int height = recipe instanceof ShapedRecipe shaped ? shaped.getHeight() : (grid.size() + 2) / 3;
        while (grid.size() < width * height) {
            grid.add(ItemStack.EMPTY);
        }
        if (grid.size() > width * height) {
            return ""; // cannot be laid out; not a craft failure
        }
        CraftingInput input = CraftingInput.of(width, height, grid);
        if (!recipe.matches(input, level)) {
            return "";
        }
        ItemStack made = recipe.assemble(input, level.registryAccess());
        return made.isEmpty() ? "" : idOf(made);
    }

    // ---- what a recipe mentions, when it will not say what it consumes ----

    private List<String> mentionedBy(RecipeHolder<?> holder) {
        JsonElement json;
        try {
            RegistryOps<JsonElement> ops = level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
            json = Recipe.CODEC.encodeStart(ops, holder.value()).result().orElse(null);
        } catch (RuntimeException e) {
            return List.of();
        }
        if (json == null) {
            return List.of();
        }

        Set<String> strings = new LinkedHashSet<>();
        harvest(null, json, strings);
        String result = idOf(resultOf(holder.value()));

        Set<String> out = new LinkedHashSet<>();
        for (String text : strings) {
            if (text.startsWith("#")) {
                for (String id : itemsInTag(text.substring(1))) {
                    if (!id.equals(result)) {
                        out.add(id);
                    }
                }
                continue;
            }
            ResourceLocation location = ResourceLocation.tryParse(text);
            if (location == null || !BuiltInRegistries.ITEM.containsKey(location)) {
                continue;
            }
            if (!location.toString().equals(result)) {
                out.add(location.toString());
            }
        }
        return List.copyOf(out);
    }

    private static void harvest(String key, JsonElement json, Set<String> out) {
        if (json.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject().entrySet()) {
                harvest(entry.getKey(), entry.getValue(), out);
            }
        } else if (json.isJsonArray()) {
            for (JsonElement element : json.getAsJsonArray()) {
                harvest(key, element, out);
            }
        } else if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isString()) {
            String text = json.getAsString();
            out.add("tag".equals(key) && !text.startsWith("#") ? "#" + text : text);
        }
    }

    private static List<String> itemsInTag(String tagId) {
        ResourceLocation location = ResourceLocation.tryParse(tagId);
        if (location == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Holder<Item> holder : BuiltInRegistries.ITEM.getTagOrEmpty(TagKey.create(Registries.ITEM, location))) {
            out.add(idOf(holder.value()));
        }
        return out;
    }
}
