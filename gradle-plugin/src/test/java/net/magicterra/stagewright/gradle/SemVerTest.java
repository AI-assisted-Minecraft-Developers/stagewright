package net.magicterra.stagewright.gradle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SemVerTest {

    private static final String RANGE = "[0.1.0-build.9407,0.2.0-0)";

    @Test
    void aNumberedBuildIsOrderedByItsNumberAndIgnoresTheMinecraftLine() {
        assertTrue(SemVer.inRange("0.1.0-build.9407+1.21.1", RANGE));
        assertTrue(SemVer.inRange("0.1.0-build.10000+1.21.1", RANGE));
        assertFalse(SemVer.inRange("0.1.0-build.9406+1.21.1", RANGE));
    }

    @Test
    void theReleaseAndItsPatchesAreInTheLineAndTheNextMinorIsNot() {
        assertTrue(SemVer.inRange("0.1.0", RANGE));
        assertTrue(SemVer.inRange("0.1.5", RANGE));
        assertFalse(SemVer.inRange("0.2.0-build.1", RANGE));
        assertFalse(SemVer.inRange("0.2.0", RANGE));
    }

    @Test
    void aLocalBuildIsAboveTheLowestPreReleaseOnly() {
        // Alphanumeric identifiers follow numeric ones, so `local` is past every numbered build.
        assertTrue(SemVer.inRange("0.1.0-build.local+1.21.1", "[0.1.0-0,0.2.0-0)"));
        assertTrue(SemVer.inRange("0.1.0-build.local+1.21.1", RANGE));
    }
}
