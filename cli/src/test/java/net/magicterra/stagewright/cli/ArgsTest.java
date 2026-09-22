package net.magicterra.stagewright.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The command line. Every rejection here goes through the usage-and-exit-3 path, because an option
 * that is quietly accepted and never read turns a check off without saying so.
 */
class ArgsTest {

    @Test
    void optionsFlagsModsAndPropertiesLandWhereTheyBelong() {
        Args.Parsed p = Args.parse(new String[] {
                "--game-dir", "pack", "--expect", "expected.txt", "--no-install",
                "--mod", "driver.jar", "-Dstagewright.scenes=wd.a", "--clean-world", "false"});
        assertEquals("pack", p.opts().get("game-dir"));
        assertEquals("expected.txt", p.opts().get("expect"));
        assertEquals("true", p.opts().get("no-install"));
        assertEquals("false", p.opts().get("clean-world"));
        assertEquals(List.of("-Dstagewright.scenes=wd.a"), p.systemProps());
        assertEquals(List.of(Path.of("driver.jar").toAbsolutePath().normalize()), p.extraMods());
    }

    @Test
    void aMistypedExpectIsRefusedRatherThanSwitchingReconciliationOff() {
        // The manifest's own file name invites exactly this spelling.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Args.parse(new String[] {"--game-dir", "pack", "--expected", "expected.txt"}));
        assertTrue(e.getMessage().contains("--expected"), e.getMessage());
    }

    @Test
    void aMistypedCleanWorldIsRefusedRatherThanDeletingTheWorld() {
        assertThrows(IllegalArgumentException.class,
                () -> Args.parse(new String[] {"--game-dir", "pack", "--clean-wrold", "false"}));
    }

    @Test
    void aBooleanOptionTakesOnlyTrueOrFalse() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Args.parse(new String[] {"--game-dir", "pack", "--clean-world", "no"}));
        assertTrue(e.getMessage().contains("true or false"), e.getMessage());
        assertEquals("true",
                Args.parse(new String[] {"--clean-world", "true"}).opts().get("clean-world"));
    }

    @Test
    void aValueOptionAtTheEndNeedsItsValue() {
        assertThrows(IllegalArgumentException.class, () -> Args.parse(new String[] {"--expect"}));
    }

    @Test
    void aBareWordIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> Args.parse(new String[] {"pack"}));
    }
}
