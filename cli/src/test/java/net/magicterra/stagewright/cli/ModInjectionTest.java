package net.magicterra.stagewright.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Mods reach the loader without the pack's {@code mods/} changing — which may be a player's own.
 */
class ModInjectionTest {

    @Test
    void neoforgeGetsAMavenRootAndCoordinates(@TempDir Path tmp) throws IOException {
        Path gameDir = tmp.resolve("pack");
        Path mods = Files.createDirectories(gameDir.resolve("mods"));
        Files.writeString(mods.resolve("jei.jar"), "the pack's", StandardCharsets.UTF_8);
        Path driver = Files.writeString(tmp.resolve("WorldDriver 0.1+1.21.1.jar"), "driver");

        ModInjection.Arguments args = ModInjection.prepare(gameDir, "neoforge", List.of(driver), l -> {});

        assertEquals(List.of(), args.jvm());
        assertEquals("--fml.mavenRoots", args.game().get(0));
        Path root = Path.of(args.game().get(1));
        assertEquals("--fml.mods", args.game().get(2));
        assertEquals("stagewright.mods:mc_stagewright-neoforge:0,stagewright.mods:worlddriver_0.1_1.21.1:0",
                args.game().get(3));
        assertTrue(Files.isRegularFile(root.resolve(
                "stagewright/mods/worlddriver_0.1_1.21.1/0/worlddriver_0.1_1.21.1-0.jar")));
        assertTrue(root.startsWith(gameDir.resolve("stagewright")), root.toString());
        try (Stream<Path> left = Files.list(mods)) {
            assertEquals(List.of(mods.resolve("jei.jar")), left.toList(), "mods/ is the pack's alone");
        }
    }

    @Test
    void fabricGetsAddMods(@TempDir Path tmp) throws IOException {
        Path gameDir = Files.createDirectories(tmp.resolve("pack"));
        Path driver = Files.writeString(tmp.resolve("worlddriver.jar"), "driver");

        ModInjection.Arguments args = ModInjection.prepare(gameDir, "fabric", List.of(driver), l -> {});

        assertEquals(List.of(), args.game());
        String addMods = args.jvm().get(0);
        assertTrue(addMods.startsWith("-Dfabric.addMods="), addMods);
        String[] paths = addMods.substring("-Dfabric.addMods=".length()).split(File.pathSeparator);
        assertTrue(paths[0].endsWith("mc_stagewright-fabric.jar"), paths[0]);
        assertEquals(driver.toAbsolutePath().toString(), paths[1]);
    }

    @Test
    void whatAnOlderCliCopiedIntoModsIsNamedAndLeftAlone(@TempDir Path tmp) throws IOException {
        // The ledger kept only a name, and the pack has since put its own jar under it.
        Path gameDir = tmp.resolve("pack");
        Path mods = Files.createDirectories(gameDir.resolve("mods"));
        Files.writeString(mods.resolve("worlddriver-neoforge.jar"), "the pack's, same name");
        Files.writeString(mods.resolve(".stagewright-installed"), "worlddriver-neoforge.jar\n");

        List<String> log = new java.util.ArrayList<>();
        ModInjection.prepare(gameDir, "neoforge", List.of(), log::add);

        assertEquals("the pack's, same name", Files.readString(mods.resolve("worlddriver-neoforge.jar")));
        assertTrue(Files.exists(mods.resolve(".stagewright-installed")));
        assertTrue(log.stream().anyMatch(l -> l.startsWith("NOTE:") && l.contains("worlddriver-neoforge.jar")),
                log.toString());
    }

    @Test
    void aPackCopyWithOtherBytesIsUsedAndWarnedAbout(@TempDir Path tmp) throws IOException {
        Path gameDir = tmp.resolve("pack");
        Path mods = Files.createDirectories(gameDir.resolve("mods"));
        Files.writeString(mods.resolve("worlddriver.jar"), "last week's build");
        Path mine = Files.writeString(Files.createDirectories(tmp.resolve("build")).resolve("worlddriver.jar"),
                "today's build");

        List<String> log = new java.util.ArrayList<>();
        ModInjection.prepare(gameDir, "fabric", List.of(mine), log::add);

        assertTrue(log.stream().anyMatch(l -> l.startsWith("WARNING: using the pack's own")
                && l.contains(mods.resolve("worlddriver.jar").toString())), log.toString());
    }

    @Test
    void aStageWrightThePackShipsIsKeptAndNotLoadedTwice(@TempDir Path tmp) throws IOException {
        Path gameDir = tmp.resolve("pack");
        Path mods = Files.createDirectories(gameDir.resolve("mods"));
        Files.writeString(mods.resolve("mc_stagewright-neoforge-0.1.0+1.21.1.jar"), "shipped");

        ModInjection.Arguments args = ModInjection.prepare(gameDir, "neoforge", List.of(), l -> {});

        assertEquals(ModInjection.Arguments.NONE, args);
        assertEquals("shipped", Files.readString(mods.resolve("mc_stagewright-neoforge-0.1.0+1.21.1.jar")));
    }

    @Test
    void aModThePackAlreadyShipsIsNotLoadedTwice(@TempDir Path tmp) throws IOException {
        Path gameDir = tmp.resolve("pack");
        Path mods = Files.createDirectories(gameDir.resolve("mods"));
        Files.writeString(mods.resolve("worlddriver.jar"), "shipped");
        Path mine = Files.writeString(Files.createDirectories(tmp.resolve("build")).resolve("worlddriver.jar"),
                "mine");

        ModInjection.Arguments args = ModInjection.prepare(gameDir, "fabric", List.of(mine), l -> {});

        assertFalse(args.jvm().get(0).contains(mine.toString()), args.jvm().toString());
        assertEquals("shipped", Files.readString(mods.resolve("worlddriver.jar")));
    }
}
