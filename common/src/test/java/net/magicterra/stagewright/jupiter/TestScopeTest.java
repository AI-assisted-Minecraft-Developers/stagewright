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
        IllegalStateException e = assertThrows(
                IllegalStateException.class,
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
    void aServerTaskSeesTheTestThatStartedItButCannotChangeWhatItMustUndo() throws Exception {
        TestScope scope = TestScope.ofScript("checks on the server", Set.of(), false);
        Object[] seen = new Object[2];
        Thread server = new Thread(() -> TestScope.lending(scope, () -> {
            seen[0] = TestScope.bound();
            try {
                TestScope.current("game.override");
            } catch (IllegalStateException refused) {
                seen[1] = refused;
            }
            return null;
        }));
        server.start();
        server.join();
        assertSame(scope, seen[0]);
        IllegalStateException refused = assertInstanceOf(IllegalStateException.class, seen[1]);
        assertTrue(refused.getMessage().contains("test method's own thread"), refused.getMessage());
    }

    @Test
    void aLentScopeEndsWithItsTaskAndNestsBackToTheOneBefore() {
        TestScope outer = TestScope.ofScript("outer", Set.of(), false);
        TestScope inner = TestScope.ofScript("inner", Set.of(), false);
        assertNull(TestScope.bound());
        TestScope.lending(outer, () -> {
            assertSame(inner, TestScope.lending(inner, TestScope::bound));
            assertSame(outer, TestScope.bound());
            return null;
        });
        assertNull(TestScope.bound());
        assertEquals("ran", TestScope.lending(null, () -> "ran"));
    }

    @Test
    void aScriptHoldingTheLockOrIsolatedMayOverride() {
        assertDoesNotThrow(() -> TestScope.ofScript("rains", Set.of(Locks.WEATHER), false)
                .require(Locks.WEATHER, "Locks.WEATHER", "game.overrideWeather"));
        assertDoesNotThrow(() -> TestScope.ofScript("rains", Set.of(), true)
                .require(Locks.WEATHER, "Locks.WEATHER", "game.overrideWeather"));
    }
}
