package net.magicterra.stagewright.testengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A game started without some of its mods, its own directories left alone. */
class SceneModsTest {

    @TempDir
    Path game;

    private Path jar(Path at, String entry, String text) throws IOException {
        Files.createDirectories(at.getParent());
        try (OutputStream out = Files.newOutputStream(at);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(entry));
            zip.write(text.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return at;
    }

    private Path neoforgeMod(Path at, String id) throws IOException {
        return jar(at, "META-INF/neoforge.mods.toml", "modLoader=\"javafml\"\n[[mods]]\nmodId=\"" + id + "\"\n");
    }

    private LaunchSpec.Process process(String mainClass, List<String> jvm, List<String> args) {
        return new LaunchSpec.Process("java", jvm, mainClass, args, game, Map.of());
    }

    @Test
    void fabricIsToldWhichIdsToDisable() throws IOException {
        jar(game.resolve("mods/farmers.jar"), "fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"farmersdelight\"}");
        jar(game.resolve("mods/other.jar"), "fabric.mod.json", "{\"id\":\"other\"}");
        SceneMods.Applied applied = SceneMods.apply(
                process("net.fabricmc.loader.impl.launch.server.FabricServerLauncher", List.of("-Xmx2G"), List.of()),
                List.of("farmersdelight"),
                game.resolve("stagewright/scene-mods/primary"));
        assertEquals(
                "-Dfabric.debug.disableModIds=farmersdelight",
                applied.process().jvmArgs().get(0));
        assertTrue(Files.exists(game.resolve("mods/farmers.jar")), "the pack's mods/ is left alone");
    }

    @Test
    void neoforgeGetsAGameDirectoryWhoseModsLeaveOutTheJar() throws IOException {
        neoforgeMod(game.resolve("mods/farmersdelight-1.2.jar"), "farmersdelight");
        Path kept = neoforgeMod(game.resolve("mods/maid.jar"), "touhou_little_maid");
        Files.createDirectories(game.resolve("config"));
        SceneMods.Applied applied = SceneMods.apply(
                process(null, List.of("@libraries/net/neoforged/neoforge/21.1.230/unix_args.txt"), List.of("nogui")),
                List.of("farmersdelight-*.jar"),
                game.resolve("stagewright/scene-mods/primary"));
        List<String> args = applied.process().args();
        int at = args.indexOf("--gameDir");
        Path view = Path.of(args.get(at + 1));
        assertTrue(Files.isSymbolicLink(view.resolve("config")));
        assertTrue(Files.exists(view.resolve("mods/maid.jar")));
        assertEquals(kept.toAbsolutePath(), Files.readSymbolicLink(view.resolve("mods/maid.jar")));
        assertFalse(Files.exists(view.resolve("mods/farmersdelight-1.2.jar")));
        assertTrue(Files.exists(game.resolve("mods/farmersdelight-1.2.jar")), "the pack's mods/ is left alone");
    }

    @Test
    void neoforgeLosesTheJarFromEveryPathListInItsArgumentFiles() throws IOException {
        Path farmers = neoforgeMod(game.resolve("libs/farmersdelight.jar"), "farmersdelight");
        Path keep = neoforgeMod(game.resolve("libs/keep.jar"), "keep");
        Path legacy = Files.writeString(game.resolve("legacy.txt"), farmers + "\n" + keep + "\n");
        String sep = File.pathSeparator;
        Path vmArgs = Files.writeString(
                game.resolve("vm.txt"),
                "-DlegacyClassPath.file=" + legacy + "\n-cp \"" + keep + sep + farmers + "\"\n");
        Path scratch = game.resolve("stagewright/scene-mods/primary");
        SceneMods.Applied applied = SceneMods.apply(
                process("net.neoforged.devlaunch.Main", List.of("@" + vmArgs), List.of()),
                List.of("farmersdelight"),
                scratch);
        String rewritten = applied.process().jvmArgs().get(0);
        assertTrue(rewritten.startsWith("@" + scratch), rewritten);
        String text = Files.readString(Path.of(rewritten.substring(1)));
        assertFalse(text.contains(farmers.toString()), text);
        assertTrue(text.contains(keep.toString()), text);
        String legacyCopy = text.substring(text.indexOf("legacyClassPath.file=") + 21, text.indexOf('\n'));
        assertFalse(Files.readString(Path.of(legacyCopy)).contains(farmers.toString()));
        assertTrue(Files.readString(vmArgs).contains(farmers.toString()), "the run's own file is left alone");
    }

    @Test
    void aNameThatIsNoModOfTheGameIsReportedAndTheGameStartsAsItIs() throws IOException {
        neoforgeMod(game.resolve("mods/maid.jar"), "touhou_little_maid");
        LaunchSpec.Process process = process(null, List.of("-Xmx2G"), List.of("nogui"));
        SceneMods.Applied applied = SceneMods.apply(process, List.of("farmersdelight"), game.resolve("s"));
        assertEquals(List.of("farmersdelight"), applied.unmatched());
        assertEquals(List.of(), applied.excluded());
        assertEquals(process, applied.process());
    }

    @Test
    void theNamesThatMatchAreExcludedBesideOnesThatDoNot() throws IOException {
        jar(game.resolve("mods/farmers.jar"), "fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"farmersdelight\"}");
        SceneMods.Applied applied = SceneMods.apply(
                process("net.fabricmc.loader.impl.launch.server.FabricServerLauncher", List.of(), List.of()),
                List.of("luckperms", "farmersdelight"),
                game.resolve("s"));
        assertEquals(List.of("luckperms"), applied.unmatched());
        assertEquals(
                "-Dfabric.debug.disableModIds=farmersdelight",
                applied.process().jvmArgs().get(0));
    }

    @Test
    void scratchIsDeletedWithoutFollowingItsLinksIntoThePack() throws IOException {
        neoforgeMod(game.resolve("mods/a.jar"), "a");
        neoforgeMod(game.resolve("mods/b.jar"), "b");
        Files.createDirectories(game.resolve("config"));
        Files.writeString(game.resolve("config/keep.toml"), "x");
        Path scratch = game.resolve("stagewright/scene-mods/primary");
        SceneMods.apply(process(null, List.of(), List.of()), List.of("a"), scratch);
        SceneMods.deleteTree(game.resolve("stagewright/scene-mods"));
        assertTrue(Files.exists(game.resolve("config/keep.toml")));
        assertTrue(Files.exists(game.resolve("mods/a.jar")) && Files.exists(game.resolve("mods/b.jar")));
    }
}
