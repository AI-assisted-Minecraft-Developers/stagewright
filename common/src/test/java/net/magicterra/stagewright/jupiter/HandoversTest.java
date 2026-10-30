package net.magicterra.stagewright.jupiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HandoversTest {

    @TempDir
    Path dir;

    @AfterEach
    void emptyTheShelf() throws IOException {
        Handovers.sweep(dir, false);
    }

    @Test
    void aRoundAfterARestartFindsTheBotsTheRoundBeforeCarried() throws IOException {
        Handovers.write(dir, Map.of("keeper_bot", "AvatarTest#keepsIt"));
        Handovers.load(dir, 1);
        assertEquals("AvatarTest#keepsIt", Handovers.takeFromBeforeRestart("keeper_bot"));
        assertNull(Handovers.takeFromBeforeRestart("keeper_bot"), "a carried bot is taken once");
    }

    @Test
    void aScenesFirstRoundIgnoresALeftoverFile() throws IOException {
        Handovers.write(dir, Map.of("stale_bot", "OtherTest#long_ago"));
        Handovers.load(dir, 0);
        assertNull(Handovers.takeFromBeforeRestart("stale_bot"));
    }

    @Test
    void anUntakenCarriedBotIsCarriedAgainOnlyWhenAnotherRestartFollows() throws IOException {
        Handovers.write(dir, Map.of("a_bot", "A#keeps", "b_bot", "B#keeps"));
        Handovers.load(dir, 1);
        Handovers.takeFromBeforeRestart("a_bot");
        Handovers.sweep(dir, true);
        assertEquals(Map.of("b_bot", "B#keeps"), Handovers.read(dir));

        Handovers.load(dir, 2);
        Handovers.sweep(dir, false);
        assertEquals(Map.of(), Handovers.read(dir));
    }
}
