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

    @Spec(value = ChunksContracts.class, spec = "CHUNKS")
    CompletionStage<ChunksContracts.ChunksReply> chunks(ChunksContracts.ChunksOp op, CallContext ctx);

    @Spec(value = RecipesContracts.class, spec = "RECIPES")
    CompletionStage<RecipesContracts.RecipesReply> recipes(RecipesContracts.RecipesOp op, CallContext ctx);

    @Spec(value = LootContracts.class, spec = "LOOT")
    CompletionStage<LootContracts.LootReply> loot(LootContracts.LootOp op, CallContext ctx);

    @Spec(value = AdvancementsContracts.class, spec = "ADVANCEMENTS")
    CompletionStage<AdvancementsContracts.AdvancementsReply> advancements(
            AdvancementsContracts.AdvancementsOp op, CallContext ctx);

    @Spec(value = StructuresContracts.class, spec = "STRUCTURES")
    CompletionStage<StructuresContracts.StructuresReply> structures(
            StructuresContracts.StructuresOp op, CallContext ctx);

    @Spec(value = ModsContracts.class, spec = "MODS")
    CompletionStage<ModsContracts.ModsReply> mods(ModsContracts.ModsOp op, CallContext ctx);
}
