package net.magicterra.stagewright.test;

import java.util.function.BooleanSupplier;

/**
 * Waits on the test thread for something that happens on the server. The budget is counted in server ticks, not wall
 * time, so a slow machine runs the same test as a fast one; the condition is evaluated on the server thread once per
 * tick, after the tick's work, so it reads the world as that tick left it.
 */
public interface Await {

    /** The budget in server ticks; the default is 200. */
    Await within(int ticks);

    /** What the condition means, for the failure message. */
    Await as(String description);

    /** Return once {@code condition} holds; fail the test with an {@code AssertionError} when the budget runs out. */
    void until(BooleanSupplier condition);
}
