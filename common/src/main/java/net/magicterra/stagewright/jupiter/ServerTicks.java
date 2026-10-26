package net.magicterra.stagewright.jupiter;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

/**
 * Conditions a test thread waits on, checked on the server thread at the end of every tick. A waiter is answered
 * {@code true} the first tick its condition holds and {@code false} once its budget of ticks has passed; a
 * condition that throws fails the wait with that throwable. Also what the {@code mc.test.*} methods that span
 * ticks wait on.
 */
public final class ServerTicks {

    private static final List<Waiter> WAITERS = new CopyOnWriteArrayList<>();

    private ServerTicks() {}

    private static final class Waiter {
        final BooleanSupplier condition;
        final int budget;
        final CompletableFuture<Boolean> done = new CompletableFuture<>();
        int elapsed;

        Waiter(BooleanSupplier condition, int budget) {
            this.condition = condition;
            this.budget = budget;
        }
    }

    /** A wait of at most {@code budget} ticks for {@code condition}. */
    public static CompletableFuture<Boolean> await(BooleanSupplier condition, int budget) {
        Waiter w = new Waiter(condition, Math.max(0, budget));
        WAITERS.add(w);
        return w.done;
    }

    /** Server thread, once per tick after the tick's work. */
    static void tick() {
        for (Waiter w : WAITERS) {
            w.elapsed++;
            try {
                if (w.condition.getAsBoolean()) w.done.complete(true);
                else if (w.elapsed >= w.budget) w.done.complete(false);
            } catch (Throwable t) {
                w.done.completeExceptionally(t);
            }
            if (w.done.isDone()) WAITERS.remove(w);
        }
    }

    /** Fail every pending wait: the server is going away and no tick will answer them. */
    static void stop() {
        for (Waiter w : WAITERS) w.done.completeExceptionally(new IllegalStateException("the server stopped"));
        WAITERS.clear();
    }
}
