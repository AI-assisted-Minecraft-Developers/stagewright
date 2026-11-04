package net.magicterra.stagewright.jupiter;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class ParkedConditionTest {

    /** Runs {@code condition} watched on a thread of its own, as the server thread would, and samples it for 4 s. */
    private static StackTraceElement[] sample(BooleanSupplier condition) throws InterruptedException {
        ParkedCondition watch = new ParkedCondition();
        BooleanSupplier watched = watch.watching(condition);
        Thread server = new Thread(() -> {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < end && !Thread.currentThread().isInterrupted()) {
                watched.getAsBoolean();
            }
        });
        server.setDaemon(true);
        server.start();
        try {
            for (int i = 0; i < 40; i++) {
                Thread.sleep(100);
                java.util.Optional<StackTraceElement[]> parked = watch.parked();
                if (parked.isPresent()) {
                    return parked.get();
                }
            }
            return null;
        } finally {
            server.interrupt();
        }
    }

    @Test
    void aConditionWaitingOnAFutureIsCalledParkedWithItsStack() throws Exception {
        CompletableFuture<Boolean> never = new CompletableFuture<>();
        StackTraceElement[] stack = sample(() -> {
            try {
                return never.get(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (java.util.concurrent.ExecutionException | TimeoutException e) {
                return false;
            }
        });
        assertNotNull(stack, "the parked condition was not told");
        assertTrue(
                Arrays.stream(stack).anyMatch(f -> f.getClassName().equals(CompletableFuture.class.getName())),
                Arrays.toString(stack));
    }

    @Test
    void aSlowConditionThatKeepsWorkingIsNotParked() throws Exception {
        assertNull(sample(() -> {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            long spins = 0;
            while (System.nanoTime() < end) {
                spins++;
            }
            return spins < 0;
        }));
    }

    @Test
    void aConditionThatReturnsEachTickIsNotParked() throws Exception {
        assertNull(sample(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return false;
        }));
    }
}
