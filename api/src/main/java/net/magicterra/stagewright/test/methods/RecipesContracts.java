package net.magicterra.stagewright.test.methods;

import java.util.List;
import java.util.Optional;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.ResourceId;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.MethodSpec;
import net.magicterra.worlddriver.api.schema.UnionType;
import net.magicterra.worlddriver.api.schema.annotation.Doc;
import net.magicterra.worlddriver.api.schema.annotation.Length;
import net.magicterra.worlddriver.api.schema.annotation.Named;
import net.magicterra.worlddriver.api.schema.annotation.Range;
import net.magicterra.worlddriver.api.schema.annotation.Tag;
import net.magicterra.worlddriver.api.schema.annotation.Variant;

/** {@code mc.test.recipes}: the loaded recipe graph, who makes what out of what and whether the chain closes. */
public final class RecipesContracts {

    /** Ceiling on how many distinct items a closure walk may touch when {@code maxNodes} is omitted. */
    public static final int DEFAULT_MAX_NODES = 5_000;

    /** Ceiling on how deep a closure walk may go when {@code maxDepth} is omitted. */
    public static final int DEFAULT_MAX_DEPTH = 64;

    public static final UnionType<RecipesOp> RECIPES_PARAMS = ApiTypes.union(RecipesOp.class);

    public static final UnionType<RecipesReply> RECIPES_REPLY = ApiTypes.union(RecipesReply.class);

    public static final MethodSpec<RecipesOp, RecipesReply> RECIPES = MethodSpec.of(
                    "mc.test.recipes",
                    RECIPES_PARAMS,
                    RECIPES_REPLY,
                    "Questions about the loaded recipe graph. producing lists the recipes making an item; ingredients gives a "
                            + "recipe's slots and result; closure walks every ingredient of an item down to raw materials "
                            + "and says what could not be resolved or was cut short; uncraftable lists recipes with an "
                            + "unfillable slot; audit lays each grid recipe out from its own ingredients and reports those "
                            + "that make something other than they declare, with its totals; count says how many recipes "
                            + "are loaded, in all and per recipe type.")
            .hidden();

    private RecipesContracts() {}

    /** The requests {@code mc.test.recipes} takes, told apart by {@code op}. */
    @Tag("op")
    @Named("TestRecipesParams")
    public sealed interface RecipesOp permits Producing, Ingredients, ClosureOf, Uncraftable, Audit, Count {}

    /** Asks which recipes make an item. */
    @Variant("producing")
    public record Producing(
            @Doc("Item whose makers are wanted.") @ResourceId
            String item) implements RecipesOp {}

    /** Asks for one recipe's slots and result. */
    @Variant("ingredients")
    public record Ingredients(@Doc("Recipe id.") @ResourceId String recipe) implements RecipesOp {}

    /** Asks for every ingredient of an item, down to the raw materials. */
    @Variant("closure")
    public record ClosureOf(
            @Doc("Item whose whole ingredient tree is walked.") @ResourceId
            String item,

            @Doc("Distinct items the walk may touch before it stops and reports truncated. Omitted: 5000.")
            Optional<@Range(min = 1, max = 20_000) Integer> maxNodes,

            @Doc("Recipe generations the walk may descend before it stops and reports truncated. Omitted: 64.")
            Optional<@Range(min = 1, max = 256) Integer> maxDepth)
            implements RecipesOp {}

    /** Asks for every recipe with a slot nothing registered can fill. */
    @Variant("uncraftable")
    public record Uncraftable() implements RecipesOp {}

    /** Asks for each grid recipe to be laid out and assembled from its own ingredients. */
    @Variant("audit")
    public record Audit() implements RecipesOp {}

    /** Asks how many recipes are loaded, in all and per type. */
    @Variant("count")
    public record Count() implements RecipesOp {}

    /** How many recipes of one kind: a recipe type, or for an audit's failures a serializer. */
    @Named("TestRecipeKindCount")
    public record KindCount(
            @Doc("A recipe type id (minecraft:crafting), or for audit failures a serializer id; ? when unregistered.")
            @Length(max = 256)
            String kind,

            @Range(min = 0) int count) {}

    /** One ingredient slot: every item that fits it. Empty means nothing registered can fill it. */
    @Named("TestRecipeSlot")
    public record Slot(@Length(max = 4096) List<@ResourceId String> accepts) {}

    /** The answers {@code mc.test.recipes} gives, one per {@link RecipesOp} variant. */
    @Tag("op")
    @Named("TestRecipesReply")
    public sealed interface RecipesReply
            permits ProducedBy, IngredientsOf, Closure, UncraftableRecipes, AuditReport, Counted {}

    /** The recipe ids that make an item. */
    @Variant("producing")
    public record ProducedBy(@Length(max = 4096) List<@ResourceId String> recipes) implements RecipesReply {}

    /** A recipe's result and its non-empty ingredient slots. */
    @Variant("ingredients")
    public record IngredientsOf(
            @Doc("Result item id; empty for a dynamic recipe that declares none.") @Length(max = 256)
            String result,

            @Doc("Result stack size; 0 for a dynamic recipe.") @Range(min = 0)
            int count,

            @Doc("Non-empty slots in recipe order; empty grid cells are left out.") @Length(max = 4096)
            List<Slot> slots)
            implements RecipesReply {}

    /** The result of a closure walk; the lists are a prefix of the full closure when {@code truncated} is set. */
    @Variant("closure")
    public record Closure(
            @Doc("The item asked about.") @ResourceId String root,

            @Doc("Every item reached, root first.") @Length(max = 20_000)
            List<@ResourceId String> items,

            @Doc("Reached items nothing makes: the raw materials.") @Length(max = 20_000)
            List<@ResourceId String> leaves,

            @Doc("Every recipe expanded.") @Length(max = 50_000)
            List<@ResourceId String> recipes,

            @Doc("'<recipe> slot <n>' for each slot nothing registered can fill.") @Length(max = 20_000)
            List<@Length(max = 512) String> unresolvable,

            @Doc("Recipes that declare no ingredient slots at all; their edges are inferred, see inferred.")
            @Length(max = 20_000)
            List<@ResourceId String> opaque,

            @Doc("Items reached only through what an opaque recipe's own serialization mentions.") @Length(max = 20_000)
            List<@ResourceId String> inferred,

            @Doc("Deepest generation reached.") @Range(min = 0)
            int depth,

            @Doc("True when maxNodes or maxDepth stopped the walk early: the lists are a prefix, not the closure.")
            boolean truncated)
            implements RecipesReply {}

    /** The recipes with a slot nothing registered can fill. */
    @Variant("uncraftable")
    public record UncraftableRecipes(
            @Doc("Total, even when the list below is cut.") @Range(min = 0)
            int count,

            @Doc("Recipes with a slot nothing registered can fill, sorted.") @Length(max = 10_000)
            List<@ResourceId String> recipes)
            implements RecipesReply {}

    /** The totals of an audit, and the recipes that did not make what they declare. */
    @Variant("audit")
    public record AuditReport(
            @Doc("Every recipe loaded: attempted plus skipped.") @Range(min = 0)
            int total,

            @Doc("Grid recipes that were laid out from their own ingredients and assembled.") @Range(min = 0)
            int attempted,

            @Doc("Attempted recipes that made what they declare: attempted minus failureCount.") @Range(min = 0)
            int succeeded,

            @Doc("Recipes that are not grid recipes or compute themselves at runtime, so cannot be run generically.")
            @Range(min = 0)
            int skipped,

            @Doc("Total failures, even when the list below is cut.") @Range(min = 0)
            int failureCount,

            @Doc("Failures per serializer, most first; every failure is counted, the cut ones included.")
            @Length(max = 4096)
            List<KindCount> failuresBySerializer,

            @Doc("'<recipe> [<serializer>]: <what went wrong>', sorted.") @Length(max = 1000)
            List<@Length(max = 1024) String> failures)
            implements RecipesReply {}

    /** The number of recipes loaded, in all and per recipe type. */
    @Variant("count")
    public record Counted(
            @Doc("Every recipe loaded.") @Range(min = 0) int total,

            @Doc("Recipes per recipe type, most first.") @Length(max = 4096)
            List<KindCount> byType)
            implements RecipesReply {}
}
