package net.magicterra.stagewright.jupiter;

import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Plot;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * Injects the {@link Game} and leased {@link Plot}s, and binds each test method's {@link TestScope} so its
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
        return type == Game.class || type == Plot.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
        Game g = game();
        if (parameter.getParameter().getType() == Game.class) return g;
        if (context.getTestMethod().isEmpty()) {
            throw new ParameterResolutionException("a Plot is leased to one test: take it as a test method parameter");
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

    private static Game game() {
        Game g = game;
        if (g == null) {
            throw new ParameterResolutionException("no Game: these tests run inside a game StageWright started");
        }
        return g;
    }
}
