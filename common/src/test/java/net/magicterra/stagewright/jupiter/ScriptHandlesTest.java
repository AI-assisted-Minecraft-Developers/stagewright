package net.magicterra.stagewright.jupiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.test.TestHarnessContracts.Press;
import org.junit.jupiter.api.Test;

class ScriptHandlesTest {

    @Test
    void aPressNamingOneAxisLeavesTheOtherAsItWasHeld() {
        Press p = ScriptHandles.press(Press.of("player:a"), Map.of("forward", 1.0));
        assertEquals(Optional.of(1.0), p.forward());
        assertEquals(Optional.empty(), p.left());
    }

    @Test
    void aPressCarriesEveryKeyItNames() {
        Press p = ScriptHandles.press(
                Press.of("player:a"),
                Map.of("jump", true, "sneak", false, "aimAt", Map.of("x", 1, "y", 2, "z", 3), "hit", 7));
        assertEquals(Optional.of(true), p.jump());
        assertEquals(Optional.of(false), p.sneak());
        assertEquals(Optional.of(new BlockPosition(1, 2, 3)), p.aimAt());
        assertEquals(Optional.of(7), p.hit());
        assertEquals(Optional.empty(), p.sprint());
    }

    @Test
    void releaseLetsEveryKeyGoBeforeTheRestApply() {
        Press p = ScriptHandles.press(Press.of("player:a"), Map.of("release", true, "jump", true));
        assertEquals(Optional.of(0.0), p.forward());
        assertEquals(Optional.of(true), p.jump());
    }

    @Test
    void hasClassAnswersWhetherAClassLoadsWithoutInitialisingIt() {
        assertTrue(ScriptHandles.hasClass("java.lang.String"));
        assertTrue(ScriptHandles.hasClass(ScriptHandlesTest.class.getName()));
        assertEquals(false, ScriptHandles.hasClass("com.example.definitely.NotHere"));
    }

    @Test
    void anUnknownKeyIsRefusedWithTheKeysThereAre() {
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class,
                () -> ScriptHandles.press(Press.of("player:a"), Map.of("crouch", true)));
        assertTrue(e.getMessage().contains("sneak"), e.getMessage());
    }
}
