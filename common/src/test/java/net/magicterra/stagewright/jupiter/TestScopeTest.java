package net.magicterra.stagewright.jupiter;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import net.magicterra.stagewright.test.Locks;
import org.junit.jupiter.api.Test;
import org.opentest4j.MultipleFailuresError;

class TestScopeTest {

    @Test
    void aScriptOverrideWithoutItsLockIsRefusedInTheScriptsOwnTerms() {
        TestScope scope = TestScope.ofScript("rains", Set.of(), false);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> scope.require(Locks.WEATHER, "Locks.WEATHER", "game.overrideWeather"));
        assertTrue(e.getMessage().contains("locks: ['weather']"), e.getMessage());
    }

    @Test
    void noFailedCheckReportsNothingAndOneReportsItselfOnce() {
        TestScope scope = TestScope.ofScript("checks", Set.of(), false);
        assertNull(scope.takeChecks());
        AssertionError miss = new AssertionError("the door is open");
        scope.failed(miss);
        assertSame(miss, scope.takeChecks());
        assertNull(scope.takeChecks());
    }

    @Test
    void severalFailedChecksAreReportedTogetherAndCounted() {
        TestScope scope = TestScope.ofScript("checks", Set.of(), false);
        scope.failed(new AssertionError("the door is open"));
        scope.failed(new AssertionError("the chest is empty"));
        MultipleFailuresError e = assertInstanceOf(MultipleFailuresError.class, scope.takeChecks());
        assertEquals(2, e.getFailures().size());
        assertTrue(e.getMessage().startsWith("2 checks failed"), e.getMessage());
        assertTrue(e.getMessage().contains("the chest is empty"), e.getMessage());
    }

    @Test
    void aScriptHoldingTheLockOrIsolatedMayOverride() {
        assertDoesNotThrow(() -> TestScope.ofScript("rains", Set.of(Locks.WEATHER), false)
                .require(Locks.WEATHER, "Locks.WEATHER", "game.overrideWeather"));
        assertDoesNotThrow(() -> TestScope.ofScript("rains", Set.of(), true)
                .require(Locks.WEATHER, "Locks.WEATHER", "game.overrideWeather"));
    }
}
