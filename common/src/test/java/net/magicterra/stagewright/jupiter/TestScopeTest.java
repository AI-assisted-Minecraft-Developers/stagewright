package net.magicterra.stagewright.jupiter;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import net.magicterra.stagewright.test.Locks;
import org.junit.jupiter.api.Test;

class TestScopeTest {

    @Test
    void aScriptOverrideWithoutItsLockIsRefusedInTheScriptsOwnTerms() {
        TestScope scope = TestScope.ofScript("rains", Set.of(), false);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> scope.require(Locks.WEATHER, "Locks.WEATHER", "game.overrideWeather"));
        assertTrue(e.getMessage().contains("locks: ['weather']"), e.getMessage());
    }

    @Test
    void aScriptHoldingTheLockOrIsolatedMayOverride() {
        assertDoesNotThrow(() -> TestScope.ofScript("rains", Set.of(Locks.WEATHER), false)
                .require(Locks.WEATHER, "Locks.WEATHER", "game.overrideWeather"));
        assertDoesNotThrow(() -> TestScope.ofScript("rains", Set.of(), true)
                .require(Locks.WEATHER, "Locks.WEATHER", "game.overrideWeather"));
    }
}
