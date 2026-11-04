package net.magicterra.stagewright.jupiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class QuitTest {

    @AfterEach
    void reset() {
        Quit.reset();
    }

    @Test
    void theFirstTestToQuitIsTheOneNamed() {
        Quit.request("a");
        Quit.request("b");
        assertEquals("the game quit after a called game.quitNow()", Quit.reason());
        assertFalse(Quit.crashed());
    }

    @Test
    void aCrashAfterAQuitIsWhatTheSkippedTestsAreTold() {
        Quit.request("a");
        Quit.crash("b");
        Quit.request("c");
        assertTrue(Quit.crashed(), "nothing may ask the server for anything now");
        assertEquals("the game crashed when b called game.crashNow()", Quit.reason());
    }

    @Test
    void aNewRunHasNeitherQuitNorCrashed() {
        Quit.crash("a");
        Quit.reset();
        assertNull(Quit.reason());
        assertFalse(Quit.crashed());
    }
}
