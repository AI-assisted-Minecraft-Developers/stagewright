package net.magicterra.stagewright.jupiter;

import net.magicterra.stagewright.test.Game;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * Injects the {@link Game} into test methods and constructors. Registered for every test through Jupiter's
 * extension auto-detection (META-INF/services), which the in-game launcher turns on.
 */
public final class GameExtension implements ParameterResolver {

    private static volatile Game game;

    /** The game the next run's tests receive. */
    static void bind(Game g) {
        game = g;
    }

    @Override
    public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
        return parameter.getParameter().getType() == Game.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
        Game g = game;
        if (g == null) {
            throw new ParameterResolutionException("no Game: these tests run inside a game StageWright started");
        }
        return g;
    }
}
