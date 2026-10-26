package net.magicterra.stagewright.jupiter;

import net.magicterra.stagewright.test.Bot;
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
 * Injects the {@link Game}, leased {@link Plot}s and {@link Bot}s, and the client's {@link Player}, and binds each test method's {@link TestScope} so its
 * overrides are checked against its locks and undone when it ends. Registered for every test through Jupiter's
 * extension auto-detection (META-INF/services), which the in-game launcher turns on.
 */
public final class GameExtension implements ParameterResolver, BeforeEachCallback, AfterEachCallback {

    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(GameExtension.class);

    private static volatile Game game;

    /** The game the next run's tests receive. */
    static void bind(Game g) {
        game = g;
    }

    @Override
    public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
        Class<?> type = parameter.getParameter().getType();
        return type == Game.class || type == Plot.class || type == Bot.class || type == Player.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
        Game g = game();
        Class<?> type = parameter.getParameter().getType();
        if (type == Game.class) return g;
        if (type == Player.class) return BodyImpl.player(g);
        if (context.getTestMethod().isEmpty()) {
            throw new ParameterResolutionException("a " + type.getSimpleName() + " is leased to one test: take it as a"
                    + " test method parameter");
        }
        if (type == Bot.class) {
            BodyImpl.BotImpl bot = BodyImpl.BotImpl.join(g,
                    parameter.findAnnotation(Bot.Name.class).map(Bot.Name::value).orElse(null));
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

    @Override
    public void beforeEach(ExtensionContext context) {
        TestScope.bind(TestScope.of(context));
    }

    @Override
    public void afterEach(ExtensionContext context) {
        try {
            // Unbound when an earlier extension aborted the test before beforeEach got here; nothing to undo then,
            // and throwing would replace that abort with a failure.
            TestScope scope = TestScope.bound();
            if (scope != null && scope.changedAnything()) game().runOnServer(scope::restore);
        } finally {
            TestScope.unbind();
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
