package net.magicterra.stagewright.jupiter;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import net.magicterra.stagewright.test.Await;
import net.magicterra.stagewright.test.Game;
import net.magicterra.worlddriver.api.game.WorldDriver;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.minecraft.server.MinecraftServer;

/** The {@link Game} of the server this JVM runs. */
final class GameImpl implements Game {

    /** Wall time allowed per tick of a budget before a wait gives up on the server itself, plus a fixed margin. */
    private static final long WALL_MS_PER_TICK = 1_000;
    private static final long WALL_MARGIN_MS = 60_000;

    private final MinecraftServer server;

    GameImpl(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public ApiRegistry registry() {
        return WorldDriver.registry();
    }

    @Override
    public <T> T onServer(Function<MinecraftServer, T> work) {
        if (server.isSameThread()) {
            throw new IllegalStateException("onServer was called on the server thread, which would wait for itself");
        }
        try {
            return server.submit(() -> work.apply(server)).join();
        } catch (CompletionException e) {
            throw rethrow(e.getCause() == null ? e : e.getCause());
        }
    }

    @Override
    public void ticks(int n) {
        waitFor(() -> false, n);
    }

    @Override
    public Await await() {
        return new AwaitImpl();
    }

    /** Whether {@code condition} held within {@code budget} ticks. */
    private boolean waitFor(BooleanSupplier condition, int budget) {
        if (server.isSameThread()) {
            throw new IllegalStateException("a tick wait was started on the server thread, which would wait for itself");
        }
        CompletableFuture<Boolean> done = ServerTicks.await(condition, budget);
        try {
            return done.get(budget * WALL_MS_PER_TICK + WALL_MARGIN_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            done.cancel(false);
            throw new AssertionError("the server did not run " + budget + " ticks in time: it is stalled or stopped");
        } catch (ExecutionException e) {
            throw rethrow(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for server ticks", e);
        }
    }

    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException r) return r;
        if (t instanceof Error err) throw err;
        return new IllegalStateException(t);
    }

    private final class AwaitImpl implements Await {
        private int within = 200;
        private String description = "the condition";

        @Override
        public Await within(int ticks) {
            this.within = ticks;
            return this;
        }

        @Override
        public Await as(String description) {
            this.description = description;
            return this;
        }

        @Override
        public void until(BooleanSupplier condition) {
            if (!waitFor(condition, within)) {
                throw new AssertionError(description + " did not hold within " + within + " ticks");
            }
        }
    }
}
