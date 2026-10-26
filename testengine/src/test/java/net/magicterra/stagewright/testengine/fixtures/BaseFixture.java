package net.magicterra.stagewright.testengine.fixtures;

import org.junit.jupiter.api.Test;

/** Two tests, inherited by every scene fixture; never run directly. */
public abstract class BaseFixture {
    @Test
    void a() {}

    @Test
    void b() {}
}
