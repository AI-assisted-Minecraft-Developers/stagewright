package net.magicterra.stagewright.test.methods;

import java.util.concurrent.CompletionStage;
import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.api.registry.Spec;

/**
 * StageWright's own {@code mc.test.*} questions about a world, one method per subject, each telling its questions apart
 * by {@code op}. Hidden from tool lists: they serve tests, not agents. From an in-game test,
 * {@code game.api(TestQueriesBlockingApi::of)}.
 */
public interface TestQueriesApi {

    /** A caller that goes through {@code registry}, checked as a request is. */
    static TestQueriesStubApi of(ApiRegistry registry) {
        return TestQueriesApiBinding.calls(registry);
    }

    /** Takes chunks out of memory and brings them back, as a player walking away and back would. */
    @Spec(value = ChunksContracts.class, spec = "CHUNKS")
    CompletionStage<ChunksContracts.ChunksReply> chunks(ChunksContracts.ChunksOp op, CallContext ctx);

    /** The loaded recipe graph: who makes what out of what. */
    @Spec(value = RecipesContracts.class, spec = "RECIPES")
    CompletionStage<RecipesContracts.RecipesReply> recipes(RecipesContracts.RecipesOp op, CallContext ctx);

    /** Whether loot tables are registered, and what they give over many rolls. */
    @Spec(value = LootContracts.class, spec = "LOOT")
    CompletionStage<LootContracts.LootReply> loot(LootContracts.LootOp op, CallContext ctx);

    /** A server player's advancement progress, read, granted and revoked. */
    @Spec(value = AdvancementsContracts.class, spec = "ADVANCEMENTS")
    CompletionStage<AdvancementsContracts.AdvancementsReply> advancements(
            AdvancementsContracts.AdvancementsOp op, CallContext ctx);

    /** Worldgen structures: whether one is registered, covers a block, or lies nearby. */
    @Spec(value = StructuresContracts.class, spec = "STRUCTURES")
    CompletionStage<StructuresContracts.StructuresReply> structures(
            StructuresContracts.StructuresOp op, CallContext ctx);

    /** The mods this game loaded, as its loader lists them. */
    @Spec(value = ModsContracts.class, spec = "MODS")
    CompletionStage<ModsContracts.ModsReply> mods(ModsContracts.ModsOp op, CallContext ctx);
}
