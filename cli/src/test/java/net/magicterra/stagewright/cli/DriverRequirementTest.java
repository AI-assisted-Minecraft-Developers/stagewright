package net.magicterra.stagewright.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DriverRequirementTest {

    /** The jars this CLI carries, unpacked beside the pack rather than into its mods/. */
    private static DriverRequirement.Carried carried(Path gameDir, String loader) {
        return ModInjection.unpackCarried(loader, gameDir.resolve("carried-" + loader));
    }

    @Test
    void aWorldDriverGivenUnderTheNameOfAnotherJarInModsDoesNotStopTheCarriedOneLoading(@TempDir Path tmp)
            throws IOException {
        Files.createDirectories(tmp.resolve("mods"));
        Files.writeString(tmp.resolve("mods/worlddriver.jar"), "the pack's, no mod at all");
        Path extra = TestModJar.driver(tmp.resolve("build/worlddriver.jar"), "skipped: mods/ has that name");
        DriverRequirement.Verdict verdict =
                DriverRequirement.check(tmp, "neoforge", List.of(extra), carried(tmp, "neoforge"));
        assertTrue(verdict.carriedDriver(), "the extra is not loaded, so the carried WorldDriver is");
    }

    @Test
    void aFabricPackWithoutFabricApiIsWarnedNotRefused(@TempDir Path tmp) throws IOException {
        Files.createDirectories(tmp.resolve("mods"));
        DriverRequirement.Verdict verdict = DriverRequirement.check(tmp, "fabric", List.of(), carried(tmp, "fabric"));
        assertTrue(verdict.carriedDriver());
        assertTrue(
                verdict.warnings().stream().anyMatch(w -> w.contains("needs Fabric API")),
                verdict.warnings().toString());
        TestModJar.create(tmp.resolve("mods/fabric-api.jar"), "fabric-api", "0.116.4+1.21.1", "api");
        assertEquals(
                List.of(),
                DriverRequirement.check(tmp, "fabric", List.of(), carried(tmp, "fabric"))
                        .warnings());
    }

    @Test
    void arbitraryFilenameIsRecognizedFromMetadata(@TempDir Path tmp) throws IOException {
        Path jar = TestModJar.driver(tmp.resolve("mods/unrelated-name.jar"), "driver");
        for (String loader : List.of("fabric", "neoforge")) {
            assertDoesNotThrow(() -> DriverRequirement.check(tmp, loader, List.of(), carried(tmp, loader)));
        }
    }

    @Test
    void effectivePackCopyMustSatisfyTheRange(@TempDir Path tmp) throws IOException {
        TestModJar.create(tmp.resolve("mods/driver.jar"), "worlddriver", "0.2.0-alpha.1+1.21.1", "old");
        Path extra = TestModJar.driver(tmp.resolve("candidate/driver.jar"), "candidate");
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> DriverRequirement.check(tmp, "fabric", List.of(extra), carried(tmp, "fabric")));
        assertTrue(error.getMessage().contains("incompatible"), error.getMessage());
    }

    @Test
    void aJarReplacedBetweenTwoChecksIsReadAgain(@TempDir Path tmp) throws IOException {
        Path jar = TestModJar.driver(tmp.resolve("mods/driver.jar"), "driver");
        assertDoesNotThrow(() -> DriverRequirement.check(tmp, "fabric", List.of(), carried(tmp, "fabric")));
        FileTime before = Files.getLastModifiedTime(jar);
        TestModJar.create(jar, "worlddriver", "0.2.0-alpha.1+1.21.1", "newer");
        Files.setLastModifiedTime(jar, FileTime.fromMillis(before.toMillis() + 1000));
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> DriverRequirement.check(tmp, "fabric", List.of(), carried(tmp, "fabric")));
        assertTrue(error.getMessage().contains("incompatible"), error.getMessage());
    }

    @Test
    void duplicateDriversAreRefused(@TempDir Path tmp) throws IOException {
        TestModJar.driver(tmp.resolve("mods/one.jar"), "one");
        Path extra = TestModJar.driver(tmp.resolve("two.jar"), "two");
        assertThrows(
                IllegalArgumentException.class,
                () -> DriverRequirement.check(tmp, "neoforge", List.of(extra), carried(tmp, "neoforge")));
    }

    @Test
    void anUnrelatedModsMetadataIsLeftToItsLoader(@TempDir Path tmp) throws IOException {
        TestModJar.driver(tmp.resolve("mods/driver.jar"), "driver");
        List<String> shapes = List.of(
                "{\"schemaVersion\":1}",
                "[]",
                "{\"id\":null}",
                "{\"id\":7}",
                "{not json",
                "{\"id\":\"other\",\"depends\":[\"worlddriver\"]}",
                "﻿{\"id\":\"other\"}");
        for (int i = 0; i < shapes.size(); i++) {
            TestModJar.withMetadata(tmp.resolve("mods/odd" + i + ".jar"), shapes.get(i), false);
        }
        assertDoesNotThrow(() -> DriverRequirement.check(tmp, "fabric", List.of(), carried(tmp, "fabric")));
    }

    @Test
    void ourOwnMetadataIsReadPastAByteOrderMark(@TempDir Path tmp) throws IOException {
        TestModJar.withMetadata(
                tmp.resolve("mods/driver.jar"),
                "﻿{\"schemaVersion\":1,\"id\":\"worlddriver\"," + "\"version\":\"0.1.0-snapshot+1.21.1\"}",
                false);
        assertDoesNotThrow(() -> DriverRequirement.check(tmp, "fabric", List.of(), carried(tmp, "fabric")));
    }

    @Test
    void aJarThatOnlyReadsThroughItsDirectoryIsStillRead(@TempDir Path tmp) throws IOException {
        // A stored entry flagged with a data descriptor: ZipInputStream refuses it, ZipFile does not.
        TestModJar.withMetadata(
                tmp.resolve("mods/driver.jar"),
                "{\"schemaVersion\":1,\"id\":\"worlddriver\"," + "\"version\":\"0.1.0-snapshot+1.21.1\"}",
                true);
        assertDoesNotThrow(() -> DriverRequirement.check(tmp, "fabric", List.of(), carried(tmp, "fabric")));
    }

    @Test
    void aPackWhoseLoaderCannotBeToldIsReadByItsOwnMetadata(@TempDir Path tmp) throws IOException {
        TestModJar.create(tmp.resolve("mods/stagewright.jar"), "mc_testkit", "0.1.0-alpha.1+1.21.1", "framework");
        IllegalArgumentException missing =
                assertThrows(IllegalArgumentException.class, () -> DriverRequirement.check(tmp, null, List.of(), null));
        assertTrue(missing.getMessage().contains("requires one compatible WorldDriver"), missing.getMessage());
        assertFalse(missing.getMessage().contains("null"), missing.getMessage());
        TestModJar.driver(tmp.resolve("mods/driver.jar"), "driver");
        assertDoesNotThrow(() -> DriverRequirement.check(tmp, null, List.of(), null));
    }

    @Test
    void aLoaderWithNoStageWrightBuildIsRefusedByNameEvenWithoutInstalling(@TempDir Path tmp) {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> DriverRequirement.check(tmp, "forge", List.of(), null));
        assertTrue(error.getMessage().contains("not forge"), error.getMessage());
    }

    @Test
    void generatedRangesAcceptPublishedAndLocalCandidates() {
        for (String range : List.of(">=0.1.0-alpha.0 <0.2.0-alpha.0", "[0.1.0-alpha.0,0.2.0-alpha.0)")) {
            for (String version : List.of("0.1.0", "0.1.0-alpha.12+1.21.1", "0.1.0-snapshot+1.21.1", "0.1.12")) {
                assertTrue(DriverRequirement.accepts(range, version), version);
            }
            for (String version : List.of(
                    "0.0.9", "0.2.0-alpha.1+1.21.1", "0.2.0-snapshot+1.21.1", "bad", "0.1.0-beta", "0.1.0-SNAPSHOT")) {
                assertFalse(DriverRequirement.accepts(range, version), version);
            }
        }
    }

    @Test
    void aNumberedFloorRefusesTheBuildsBeforeIt() {
        for (String range : List.of(">=0.1.0-alpha.900 <0.2.0-alpha.0", "[0.1.0-alpha.900,0.2.0-alpha.0)")) {
            for (String version : List.of(
                    "0.1.0-alpha.900+1.21.1",
                    "0.1.0-alpha.1000+1.21.1",
                    "0.1.0-snapshot+1.21.1",
                    "0.1.0",
                    "0.1.1-alpha.1+1.21.1")) {
                assertTrue(DriverRequirement.accepts(range, version), version);
            }
            for (String version : List.of(
                    "0.1.0-alpha.899+1.21.1", "0.1.0-alpha.1+1.21.1", "0.1.0-alpha.0", "0.2.0-alpha.1+1.21.1")) {
                assertFalse(DriverRequirement.accepts(range, version), version);
            }
        }
    }
}
