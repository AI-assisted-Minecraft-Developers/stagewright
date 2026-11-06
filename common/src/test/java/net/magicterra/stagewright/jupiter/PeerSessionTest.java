package net.magicterra.stagewright.jupiter;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.magicterra.stagewright.test.Locks;
import net.magicterra.worlddriver.api.schema.JsonValue;
import org.junit.jupiter.api.Test;

/** What a test's session on a peer is checked against, and how its failures and arguments cross. */
class PeerSessionTest {

    @Test
    void aSessionHoldsTheLocksOfTheGamesTestSoItsOverridesAreAllowedThere() {
        TestScope game = TestScope.ofScript("rains", Set.of(Locks.WEATHER), false);
        TestScope peer = PeerServing.scope("rains", Optional.of(JsonValue.of(PeerConnection.held(game))));
        assertDoesNotThrow(() -> peer.require(Locks.WEATHER, "Locks.WEATHER", "game.overrideWeather"));
        assertThrows(
                IllegalStateException.class,
                () -> peer.require(Locks.WORLD_RULES, "Locks.WORLD_RULES", "game.override"),
                "what the test does not hold it may not change there either");

        TestScope alone = PeerServing.scope(
                "alone", Optional.of(JsonValue.of(PeerConnection.held(TestScope.ofScript("alone", Set.of(), true)))));
        assertTrue(alone.isolated());
        assertFalse(PeerServing.scope("older game", Optional.empty()).isolated(), "a game that sends none");
    }

    @Test
    void aSessionThatEndsOnAFailedCheckIsAFailureNotAnError() {
        AssertionError check = new AssertionError("the door is open");
        AssertionError thrown = assertThrows(AssertionError.class, () -> PeerServing.throwEnding(check));
        assertInstanceOf(AssertionError.class, thrown.getCause());
        assertTrue(PeerServing.failure(thrown)
                .getAsJsonObject("error")
                .get("assertion")
                .getAsBoolean());

        assertThrows(IllegalStateException.class, () -> PeerServing.throwEnding(new IllegalStateException("x")));

        // And on this side, closing the session that brought it back.
        AssertionError given = assertThrows(
                AssertionError.class,
                () -> ScriptContext.giveBack(() -> {
                    throw new AssertionError("on peer 'b': the door is open");
                }));
        assertTrue(given.getMessage().contains("the door is open"), given.getMessage());
        assertThrows(
                IllegalStateException.class,
                () -> ScriptContext.giveBack(() -> {
                    throw new IllegalStateException("the socket closed");
                }));
    }

    @Test
    void aNumberJsonCannotHoldIsRefusedSayingSo() {
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class,
                () -> PeerConnection.tree(Map.of("ratio", List.of(Double.NaN)), "step"));
        assertTrue(e.getMessage().contains("NaN cannot be sent to a peer"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> PeerConnection.tree(Double.POSITIVE_INFINITY, "step"));
        assertDoesNotThrow(() -> PeerConnection.tree(List.of(1.5, 2), "step"));
        assertDoesNotThrow(
                () -> PeerConnection.tree(java.math.BigInteger.TWO.pow(1100), "step"), "past a double, exact in JSON");
    }
}
