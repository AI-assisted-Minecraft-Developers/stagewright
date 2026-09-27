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
                "--mod", "driver.jar", "-Dstagewright.scenes=wd.a", "--world", "keep",
                "--client", "neoforge:1.21.1:21.1.248"});
        assertEquals("pack", p.opts().get("game-dir"));
        assertEquals("expected.txt", p.opts().get("expect"));
        assertEquals("true", p.opts().get("no-install"));
        assertEquals("keep", p.opts().get("world"));
        assertEquals("neoforge:1.21.1:21.1.248", p.opts().get("client"));
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
    void theRetiredOptionsAreRefusedRatherThanIgnored() {
        // A script written for the HeadlessMC-era CLI must fail loudly, not run a different topology.
        for (String retired : List.of("--clean-world", "--headlessmc", "--display-client", "--loader",
                "--mc-version", "--launcher-jvm", "--account")) {
            assertThrows(IllegalArgumentException.class,
                    () -> Args.parse(new String[] {"--game-dir", "pack", retired, "x"}), retired);
        }
        assertThrows(IllegalArgumentException.class,
                () -> Args.parse(new String[] {"--game-dir", "pack", "--online"}));
    }

    @Test
    void aServerWorldIsResetOrKeptAndNothingElse() {
        assertEquals(Worlds.ServerWorld.RESET, Worlds.parse("reset"));
        assertEquals(Worlds.ServerWorld.KEEP, Worlds.parse("keep"));
        assertEquals(null, Worlds.parse(null));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Worlds.parse("false"));
        assertTrue(e.getMessage().contains("reset or keep"), e.getMessage());
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
