package net.magicterra.stagewright.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class GameTest {

    /** A game whose only server task runs here, with no server, and counts itself. */
    @SuppressWarnings("unchecked")
    private static Game game(AtomicInteger tasks) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getName().equals("onServer")) {
                tasks.incrementAndGet();
                return ((Function<Object, ?>) args[0]).apply(null);
            }
            if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
            throw new UnsupportedOperationException(method.getName());
        };
        return (Game) Proxy.newProxyInstance(Game.class.getClassLoader(), new Class<?>[] {Game.class}, handler);
    }

    @Test
    void onServerHoldsIsOneServerTaskAnsweringThePredicate() {
        AtomicInteger tasks = new AtomicInteger();
        Game game = game(tasks);
        // The point of it: this compiles, where assertTrue(game.onServer(s -> true)) does not.
        assertTrue(game.onServerHolds(server -> server == null));
        assertFalse(game.onServerHolds(server -> server != null));
        assertEquals(2, tasks.get());
    }
}
