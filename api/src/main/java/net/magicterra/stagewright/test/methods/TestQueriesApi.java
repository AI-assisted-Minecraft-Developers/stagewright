package net.magicterra.stagewright.test.methods;

import java.util.concurrent.CompletionStage;
import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.api.registry.Spec;

/**
 * StageWright's own {@code mc.test.*} questions about a world, one method per subject, each telling its questions
 * apart by {@code op}. Hidden from tool lists: they serve tests, not agents. From an in-game test,
 * {@code game.api(TestQueriesBlockingApi::of)}.
 */
public interface TestQueriesApi {

    /** A caller that goes through {@code registry}, checked as a request is. */
    static TestQueriesStubApi of(ApiRegistry registry) { return TestQueriesApiBinding.calls(registry); }

    @Spec(value = ChunksContracts.class, spec = "CHUNKS")
    CompletionStage<ChunksContracts.ChunksReply> chunks(ChunksContracts.ChunksOp op, CallContext ctx);
}
