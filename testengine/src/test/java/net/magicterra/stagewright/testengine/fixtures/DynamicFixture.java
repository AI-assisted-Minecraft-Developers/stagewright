package net.magicterra.stagewright.testengine.fixtures;

import net.magicterra.stagewright.test.Scene;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Scene("dynamic")
public class DynamicFixture {
    @ParameterizedTest
    @ValueSource(strings = {"x", "y"})
    void rows(String row) {}
}
