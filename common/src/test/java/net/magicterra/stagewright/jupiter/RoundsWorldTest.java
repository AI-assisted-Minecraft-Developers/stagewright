package net.magicterra.stagewright.jupiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Which save each round of a scene's restarts runs on, as its tests name it. */
class RoundsWorldTest {

    private static final String A = "t.js#a";

    private static final String B = "t.js#b";

    private static final String C = "t.js#c";

    @AfterEach
    void forget() {
        Rounds.scripts(Map.of());
    }

    @Test
    void aRoundRunsOnTheWorldItsTestNames() {
        Rounds.scripts(Map.of(
                A, new Rounds.Upstream(List.of(), false),
                B, new Rounds.Upstream(List.of(A), true, new Rounds.World("second", "")),
                C, new Rounds.Upstream(List.of(B), true)));
        assertEquals(2, Rounds.of(C));
        assertEquals(new Rounds.World("second", ""), Rounds.world(1));
        assertNull(Rounds.world(2), "unnamed: the world of the round before");
    }

    @Test
    void twoTestsOfARoundNamingDifferentWorldsAreRefused() {
        Rounds.scripts(Map.of(
                A, new Rounds.Upstream(List.of(), false),
                B, new Rounds.Upstream(List.of(A), true, new Rounds.World("second", "")),
                C, new Rounds.Upstream(List.of(A), true, new Rounds.World("third", ""))));
        Rounds.of(B);
        assertThrows(IllegalStateException.class, () -> Rounds.of(C));
    }

    @Test
    void aWorldIsNamedOnlyAfterARestart() {
        assertThrows(IllegalStateException.class, () -> Rounds.World.of("x", false, "second", ""));
        assertThrows(IllegalStateException.class, () -> Rounds.World.of("x", true, "", "worlds/a.zip"));
        assertThrows(IllegalStateException.class, () -> Rounds.World.of("x", true, "../up", ""));
        assertNull(Rounds.World.of("x", true, "", ""));
        assertEquals(new Rounds.World("second", "w.zip"), Rounds.World.of("x", true, "second", "w.zip"));
    }
}
