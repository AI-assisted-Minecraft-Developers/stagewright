package net.magicterra.stagewright.jupiter;

import net.magicterra.stagewright.test.Bot;
import net.magicterra.stagewright.test.Client;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Player;
import net.magicterra.stagewright.test.Plot;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * Injects the {@link Game}, leased {@link Plot}s and {@link Bot}s, the client's {@link Player} and the {@link Client}
 * itself, and binds each test method's {@link TestScope} so its
 * overrides are checked against its locks and undone when it ends. Registered for every test through Jupiter's
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
            BodyImpl.BotImpl bot = BodyImpl.BotImpl.join(g,
                    parameter.findAnnotation(Bot.Name.class).map(Bot.Name::value).orElse(null),
                    parameter.isAnnotated(Bot.Parked.class));
            // Leaves once the test method's context ends, after every @AfterEach.
            context.getStore(NAMESPACE).put("bot." + parameter.getIndex(), bot);
            return bot;
        }
        int size = parameter.findAnnotation(Plot.Size.class).map(Plot.Size::value).orElse(PlotImpl.DEFAULT_SIZE);
        if (size < 1 || size > PlotImpl.MAX_SIZE) {
            throw new ParameterResolutionException("@Plot.Size(" + size + "): a plot's side runs from 1 to "
                    + PlotImpl.MAX_SIZE);
        }
        PlotImpl plot = PlotImpl.lease(g, size);
        // Closed — the plot put back — once the test method's context ends, after every @AfterEach.
        context.getStore(NAMESPACE).put("plot." + parameter.getIndex(), plot);
        return plot;
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
    }

    static Game game() {
        Game g = game;
        if (g == null) {
            throw new ParameterResolutionException("no Game: these tests run inside a game StageWright started");
        }
        return g;
    }
}
