package net.magicterra.stagewright.jupiter;

import java.util.ArrayList;
import java.util.List;
import net.magicterra.stagewright.test.Bot;
import net.magicterra.stagewright.test.Client;
import net.magicterra.stagewright.test.DependsOn;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Player;
import net.magicterra.stagewright.test.Plot;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * Injects the {@link Game}, leased {@link Plot}s and {@link Bot}s, the client's {@link Player} and the {@link Client}
 * itself, and binds each test method's {@link TestScope} so its
 * overrides are checked against its locks and undone when it ends. A bot the test keeps goes to {@link Handovers}
 * when the test's context closes, for the dependent that takes it. Registered for every test through Jupiter's
 * extension auto-detection (META-INF/services), which the in-game launcher turns on.
 */
public final class GameExtension implements ParameterResolver, BeforeEachCallback, AfterEachCallback {

    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(GameExtension.class);

    private static final String PARALLEL = "junit.jupiter.execution.parallel.enabled";

    private static volatile Game game;

    /** The game the next run's tests receive. */
    static void bind(Game g) {
        game = g;
    }

    @Override
    public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
        Class<?> type = parameter.getParameter().getType();
        return type == Game.class || type == Plot.class || type == Bot.class || type == Player.class
                || type == Client.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
        Game g = game();
        Class<?> type = parameter.getParameter().getType();
        if (type == Game.class) return g;
        if (type == Player.class) return BodyImpl.player(g);
        if (type == Client.class) return ClientConnection.client(g);
        if (context.getTestMethod().isEmpty()) {
            throw new ParameterResolutionException("a " + type.getSimpleName() + " is leased to one test: take it as a"
                    + " test method parameter");
        }
        if (type == Bot.class) {
            BodyImpl.BotImpl bot = bot(parameter, context, g);
            bots(context).add(bot);
            // Leaves, or is kept for a later test, once the test method's context ends, after every @AfterEach.
            context.getStore(NAMESPACE).put("bot." + parameter.getIndex(), bot);
            return bot;
        }
        Vec3i size = parameter.findAnnotation(Plot.Size.class)
                .map(s -> new Vec3i(s.x() > 0 ? s.x() : s.value(), s.y() > 0 ? s.y() : s.value(),
                        s.z() > 0 ? s.z() : s.value()))
                .orElse(new Vec3i(PlotImpl.DEFAULT_SIZE, PlotImpl.DEFAULT_SIZE, PlotImpl.DEFAULT_SIZE));
        ResourceKey<Level> dimension = parameter.findAnnotation(Plot.In.class)
                .map(in -> ScriptHandles.dimension(in.value())).orElse(Level.OVERWORLD);
        PlotImpl plot;
        try {
            plot = PlotImpl.lease(g, size, parameter.isAnnotated(Plot.Centered.class), dimension);
        } catch (IllegalArgumentException e) {
            throw new ParameterResolutionException(e.getMessage(), e);
        }
        plots(context).add(plot);
        // Closed — the plot put back — once the test method's context ends, after every @AfterEach.
        context.getStore(NAMESPACE).put("plot." + parameter.getIndex(), plot);
        return plot;
    }

    /** A joined bot, or with {@code @Bot.Kept} the one a test this one depends on kept under the same name. */
    private static BodyImpl.BotImpl bot(ParameterContext parameter, ExtensionContext context, Game g) {
        String name = parameter.findAnnotation(Bot.Name.class).map(Bot.Name::value).orElse(null);
        boolean parked = parameter.isAnnotated(Bot.Parked.class);
        boolean keep = parameter.isAnnotated(Bot.Keep.class);
        boolean kept = parameter.isAnnotated(Bot.Kept.class);
        if ((keep || kept) && name == null) {
            throw new ParameterResolutionException("a bot kept for a later test, or taken from one, is named: give it"
                    + " @Bot.Name");
        }
        String test = context.getRequiredTestClass().getSimpleName() + "#" + context.getRequiredTestMethod().getName();
        BodyImpl.BotImpl bot;
        if (kept) {
            if (!context.getRequiredTestMethod().isAnnotationPresent(DependsOn.class)) {
                throw new ParameterResolutionException("a @Bot.Kept bot comes from a test this one @DependsOn");
            }
            try {
                bot = BodyImpl.BotImpl.take(g, name, parked, test);
            } catch (IllegalStateException e) {
                throw new ParameterResolutionException(e.getMessage(), e);
            }
        } else {
            bot = BodyImpl.BotImpl.join(g, name, parked);
        }
        if (keep) bot.keep(test);
        return bot;
    }

    @SuppressWarnings("unchecked")
    private static List<BodyImpl.BotImpl> bots(ExtensionContext context) {
        return context.getStore(NAMESPACE).getOrComputeIfAbsent("bots", k -> new ArrayList<BodyImpl.BotImpl>(),
                List.class);
    }

    @SuppressWarnings("unchecked")
    private static List<PlotImpl> plots(ExtensionContext context) {
        return context.getStore(NAMESPACE).getOrComputeIfAbsent("plots", k -> new ArrayList<PlotImpl>(), List.class);
    }

    /** Waits for the test's dependencies first, so it holds none of its locks while it waits. */
    @Override
    public void beforeEach(ExtensionContext context) throws InterruptedException {
        Dependencies.awaitUpstream(context);
        TestScope scope = TestScope.of(context);
        scope.hold(!context.getConfigurationParameter(PARALLEL).map(Boolean::parseBoolean).orElse(false));
        TestScope.bind(scope);
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        // Unbound when the test was aborted before beforeEach got here; nothing to undo then, and throwing would
        // replace that abort with a failure.
        TestScope scope = TestScope.bound();
        try {
            if (scope != null && scope.changedAnything()) game().runOnServer(scope::restore);
        } finally {
            TestScope.unbind();
            if (scope != null) scope.release();
        }
        AssertionError checks = scope == null ? null : scope.takeChecks();
        // Decided here, before the store closes the bots: the outcome is only recorded after that.
        IllegalStateException misplaced = Handovers.endOfTest(bots(context), plots(context),
                checks != null || context.getExecutionException().isPresent());
        if (checks != null) {
            if (misplaced != null) checks.addSuppressed(misplaced);
            throw checks;
        }
        if (misplaced != null) throw misplaced;
    }

    static Game game() {
        Game g = game;
        if (g == null) {
            throw new ParameterResolutionException("no Game: these tests run inside a game StageWright started");
        }
        return g;
    }
}
