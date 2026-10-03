package net.magicterra.stagewright.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DriverRequirementTest {
    @Test
    void missingDriverIsRefusedBeforeWorldReset(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("libraries/net/neoforged/neoforge"));
        Files.writeString(Files.createDirectories(tmp.resolve("world")).resolve("level.dat"), "save");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> Main.run(new String[] {
                "--game-dir", tmp.toString(), "--world", "reset", "--launch", "java -jar server.jar"}));
        assertTrue(error.getMessage().contains("requires one compatible WorldDriver"), error.getMessage());
        assertEquals("save", Files.readString(tmp.resolve("world/level.dat")));
    }

    @Test
    void arbitraryFilenameIsRecognizedFromMetadata(@TempDir Path tmp) throws IOException {
        Path jar = TestModJar.driver(tmp.resolve("mods/unrelated-name.jar"), "driver");
        for (String loader : List.of("fabric", "neoforge")) {
            assertDoesNotThrow(() -> DriverRequirement.check(tmp, loader, List.of(), true));
        }
    }

    @Test
    void effectivePackCopyMustSatisfyTheRange(@TempDir Path tmp) throws IOException {
        TestModJar.create(tmp.resolve("mods/driver.jar"), "worlddriver", "0.2.0-build.1+1.21.1", "old");
        Path extra = TestModJar.driver(tmp.resolve("candidate/driver.jar"), "candidate");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> DriverRequirement.check(tmp, "fabric", List.of(extra), true));
        assertTrue(error.getMessage().contains("incompatible"), error.getMessage());
    }

    @Test
    void aJarReplacedBetweenTwoChecksIsReadAgain(@TempDir Path tmp) throws IOException {
        Path jar = TestModJar.driver(tmp.resolve("mods/driver.jar"), "driver");
        assertDoesNotThrow(() -> DriverRequirement.check(tmp, "fabric", List.of(), true));
        FileTime before = Files.getLastModifiedTime(jar);
        TestModJar.create(jar, "worlddriver", "0.2.0-build.1+1.21.1", "newer");
        Files.setLastModifiedTime(jar, FileTime.fromMillis(before.toMillis() + 1000));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> DriverRequirement.check(tmp, "fabric", List.of(), true));
        assertTrue(error.getMessage().contains("incompatible"), error.getMessage());
    }

    @Test
    void duplicateDriversAreRefused(@TempDir Path tmp) throws IOException {
        TestModJar.driver(tmp.resolve("mods/one.jar"), "one");
        Path extra = TestModJar.driver(tmp.resolve("two.jar"), "two");
        assertThrows(IllegalArgumentException.class, () -> DriverRequirement.check(tmp, "neoforge", List.of(extra), true));
    }

    @Test
    void anUnrelatedModsMetadataIsLeftToItsLoader(@TempDir Path tmp) throws IOException {
        TestModJar.driver(tmp.resolve("mods/driver.jar"), "driver");
        List<String> shapes = List.of("{\"schemaVersion\":1}", "[]", "{\"id\":null}", "{\"id\":7}", "{not json",
                "{\"id\":\"other\",\"depends\":[\"worlddriver\"]}", "﻿{\"id\":\"other\"}");
        for (int i = 0; i < shapes.size(); i++) {
            TestModJar.withMetadata(tmp.resolve("mods/odd" + i + ".jar"), shapes.get(i), false);
        }
        assertDoesNotThrow(() -> DriverRequirement.check(tmp, "fabric", List.of(), true));
    }

    @Test
    void ourOwnMetadataIsReadPastAByteOrderMark(@TempDir Path tmp) throws IOException {
        TestModJar.withMetadata(tmp.resolve("mods/driver.jar"), "﻿{\"schemaVersion\":1,\"id\":\"worlddriver\","
                + "\"version\":\"0.1.0-build.1+1.21.1\"}", false);
        assertDoesNotThrow(() -> DriverRequirement.check(tmp, "fabric", List.of(), true));
    }

    @Test
    void aJarThatOnlyReadsThroughItsDirectoryIsStillRead(@TempDir Path tmp) throws IOException {
        // A stored entry flagged with a data descriptor: ZipInputStream refuses it, ZipFile does not.
        TestModJar.withMetadata(tmp.resolve("mods/driver.jar"), "{\"schemaVersion\":1,\"id\":\"worlddriver\","
                + "\"version\":\"0.1.0-build.1+1.21.1\"}", true);
        assertDoesNotThrow(() -> DriverRequirement.check(tmp, "fabric", List.of(), true));
    }

    @Test
    void aPackWhoseLoaderCannotBeToldIsReadByItsOwnMetadata(@TempDir Path tmp) throws IOException {
        TestModJar.create(tmp.resolve("mods/stagewright.jar"), "mc_testkit", "0.1.0-build.1+1.21.1", "framework");
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> DriverRequirement.check(tmp, null, List.of(), false));
        assertTrue(missing.getMessage().contains("requires one compatible WorldDriver"), missing.getMessage());
        assertFalse(missing.getMessage().contains("null"), missing.getMessage());
        TestModJar.driver(tmp.resolve("mods/driver.jar"), "driver");
        assertDoesNotThrow(() -> DriverRequirement.check(tmp, null, List.of(), false));
    }

    @Test
    void aLoaderWithNoStageWrightBuildIsRefusedByNameEvenWithoutInstalling(@TempDir Path tmp) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> DriverRequirement.check(tmp, "forge", List.of(), false));
        assertTrue(error.getMessage().contains("not forge"), error.getMessage());
    }

    @Test
    void generatedRangesAcceptPublishedAndLocalCandidates() {
        for (String range : List.of(">=0.1.0-0 <0.2.0-0", "[0.1.0-0,0.2.0-0)")) {
            for (String version : List.of("0.1.0", "0.1.0-build.12+1.21.1", "0.1.0-build.local+1.21.1", "0.1.12")) {
                assertTrue(DriverRequirement.accepts(range, version), version);
            }
            for (String version : List.of("0.0.9", "0.2.0-build.1+1.21.1", "bad", "0.1.0-alpha")) {
                assertFalse(DriverRequirement.accepts(range, version), version);
            }
        }
    }
}
