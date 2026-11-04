package net.magicterra.stagewright.jupiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.engine.TestExecutionResult.Status;

/** The tests a quit kept from starting run in the next game, and every later round one game later. */
class RoundsLeftOverTest {

    private static final String RAN = "t.js#ran";

    private static final String QUITS = "t.js#quits";

    private static final String LEFT = "t.js#left";

    private static final String AFTER = "t.js#after";

    private static final Map<String, Rounds.Upstream> SCENE = Map.of(
            RAN, new Rounds.Upstream(List.of(), false),
            QUITS, new Rounds.Upstream(List.of(), false),
            LEFT, new Rounds.Upstream(List.of(), false),
            AFTER, new Rounds.Upstream(List.of(QUITS), true, new Rounds.World("second", "")));

    @TempDir
    Path dir;

    @AfterEach
    void forget() {
        System.clearProperty(Rounds.PROPERTY);
        Rounds.scripts(Map.of());
    }

    @Test
    void aTestLeftOverRunsInTheNextGameOnTheSameSaveAndTheRoundAfterMovesOn() throws IOException {
        game(0);
        assertTrue(Rounds.runsNow(LEFT));
        assertEquals(1, Rounds.of(AFTER));
        Rounds.save(dir, Map.of(RAN, Status.SUCCESSFUL, QUITS, Status.SUCCESSFUL, LEFT, Status.ABORTED), Set.of(LEFT));
        assertEquals(2, Rounds.latest(), "one game more than the scene declares");
        assertNull(Rounds.world(1), "the next game runs on the save the quit left");

        game(1);
        assertTrue(Rounds.runsNow(LEFT), "left over, so run now");
        assertFalse(Rounds.runsNow(RAN), "it ran in the game before");
        assertFalse(Rounds.runsNow(QUITS), "it ran in the game before");
        assertNull(Rounds.endedBefore(LEFT), "not run, so no outcome");
        assertEquals(2, Rounds.of(AFTER), "after the game that runs what was left");
        Rounds.save(dir, Map.of(LEFT, Status.SUCCESSFUL), Set.of());
        assertEquals(new Rounds.World("second", ""), Rounds.world(2), "the world it names, one game later");

        game(2);
        assertTrue(Rounds.runsNow(AFTER));
        assertFalse(Rounds.runsNow(LEFT));
        assertEquals("SUCCESSFUL", Rounds.endedBefore(QUITS));
    }

    @Test
    void aRoundWithNothingLeftOverMovesNothing() throws IOException {
        game(0);
        Rounds.of(AFTER);
        Rounds.save(dir, Map.of(RAN, Status.SUCCESSFUL, QUITS, Status.SUCCESSFUL, LEFT, Status.SUCCESSFUL), Set.of());
        assertEquals(1, Rounds.latest());
        game(1);
        assertTrue(Rounds.runsNow(AFTER));
    }

    /** A new game of the scene, running {@code round}, as it starts: the file read and the tests asked about. */
    private void game(int round) {
        System.setProperty(Rounds.PROPERTY, String.valueOf(round));
        Rounds.scripts(SCENE);
        Rounds.load(dir);
    }
}
