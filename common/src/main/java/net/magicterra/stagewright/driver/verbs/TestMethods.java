package net.magicterra.stagewright.driver.verbs;

import java.util.ArrayList;
import net.magicterra.stagewright.test.methods.TestQueriesApiBinding;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiFunction;

import net.magicterra.worlddriver.api.schema.ApiError;
import net.magicterra.worlddriver.api.schema.ApiException;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.MethodSpec;
import net.magicterra.worlddriver.api.schema.MethodSpec.Effect;
import net.magicterra.worlddriver.api.schema.RecordType;
import net.magicterra.worlddriver.api.schema.annotation.Doc;
import net.magicterra.worlddriver.api.schema.annotation.Length;
import net.magicterra.worlddriver.api.schema.annotation.Named;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.NoParams;
import net.magicterra.worlddriver.api.methods.test.TestHarnessApiBinding;
import net.magicterra.worlddriver.api.methods.test.TestInputContracts;
import net.magicterra.worlddriver.api.registry.ApiBatch;
import net.magicterra.worlddriver.api.registry.ApiHandler;
import net.magicterra.worlddriver.api.registry.ApiMethod;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.bot.BotApi;
import net.magicterra.worlddriver.bot.BotHooks;

/**
 * The {@code mc.test.*} harness methods, published as one hidden batch under the {@code stagewright}
 * owner, the only owner the registry lets name {@code mc.test.*}. Hidden: callable on every transport,
 * left out of the MCP tool list, since a harness verb is not an agent action.
 *
 * <p>The client-side three delegate through {@link BotHooks#impl()}, null on a dedicated server, where
 * they answer {@code capability_unavailable} before any client type is touched; this class imports no
 * {@code net.minecraft.client.*} type, so publishing it on a server loads none.
 */
public final class TestMethods {
    private TestMethods() {}

    @Named("TestReset")
    public record Reset(@Doc("One token per effect the reset undid.") @Length(max = 64) List<String> reset) {}

    @Named("TestHeldKeys")
    public record Keys(TestInputContracts.HeldKeys keys) {}

    private static final RecordType<Reset> RESET = ApiTypes.record(Reset.class);

    private static final RecordType<Keys> KEYS = ApiTypes.record(Keys.class);

    public static final MethodSpec<NoParams, Reset> RESET_ENTRY = MethodSpec.of("mc.test.reset", CommonTypes.NO_PARAMS,
            RESET, "Reset this client between reused test runs: end the bot's task and fight, release held keys, close "
                    + "the screen, clear the chat readback. Client only.")
            .hidden().effects(Effect.BODY_TASK, Effect.CLIENT_INPUT).mayWait();

    public static final MethodSpec<NoParams, Keys> HELD_KEYS = MethodSpec.of("mc.test.input.heldKeys",
            CommonTypes.NO_PARAMS, KEYS, "Read on the client thread whether each of the eight inputs a key "
                    + "release clears is down, from outside the release itself. Client only.")
            .hidden().effects(Effect.SNAPSHOT_READ).mayWait();

    public static final MethodSpec<TestInputContracts.UseOnBlock, TestInputContracts.UsedOnBlock> USE_ON_BLOCK =
            MethodSpec.of("mc.test.input.useOnBlock", TestInputContracts.USE_ON_BLOCK, TestInputContracts.USED_ON_BLOCK,
                    "Right-click the block at pos with no moving, aiming or sneaking: the face nearest the eye, hit at "
                            + "its centre. The one instrument route that opens a block entity's screen. Client only.")
            .hidden().effects(Effect.CLIENT_INPUT, Effect.WORLD_WRITE).mayWait();

    /**
     * The client hooks hop to the client thread and wait for it. They wait here, never on the caller's
     * thread: a scene asks from the server thread, which must keep ticking while the client answers.
     */
    private static final ExecutorService CLIENT_WAITS = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "stagewright-test-methods");
        t.setDaemon(true);
        return t;
    });

    private static <P, R> ApiHandler<P, R> onClient(MethodSpec<P, R> spec, BiFunction<BotApi, P, R> body) {
        return (p, ctx) -> {
            BotApi bot;
            try {
                bot = bot(spec);
            } catch (ApiException unavailable) {
                return CompletableFuture.failedFuture(unavailable);
            }
            return CompletableFuture.supplyAsync(() -> body.apply(bot, p), CLIENT_WAITS);
        };
    }

    private static final List<ApiMethod<?, ?>> METHODS = methods();

    private static List<ApiMethod<?, ?>> methods() {
        List<ApiMethod<?, ?>> all = new ArrayList<>(List.of(
                new ApiMethod<>(RESET_ENTRY, onClient(RESET_ENTRY, (bot, p) -> new Reset(bot.resetClientEntry()))),
                new ApiMethod<>(HELD_KEYS, onClient(HELD_KEYS, (bot, p) -> new Keys(bot.heldKeys()))),
                new ApiMethod<>(USE_ON_BLOCK, onClient(USE_ON_BLOCK, BotApi::useOnBlock))));
        all.addAll(TestHarnessApiBinding.methods(new HarnessService()));
        all.addAll(TestQueriesApiBinding.methods(new TestQueriesService()));
        return all;
    }

    /** One batch object, so publishing it again on the same API is the no-op the registry makes of a retry. */
    private static final ApiBatch BATCH = new ApiBatch(ApiRegistry.TEST_OWNER, "test", METHODS);

    public static void register(ApiRegistry api) {
        api.registerBatch(BATCH);
    }

    private static BotApi bot(MethodSpec<?, ?> spec) {
        BotApi bot = BotHooks.impl();
        if (bot == null) {
            throw ApiException.of(ApiError.Code.CAPABILITY_UNAVAILABLE,
                    spec.name() + " not available (client only; bot impl not registered)");
        }
        return bot;
    }
}
