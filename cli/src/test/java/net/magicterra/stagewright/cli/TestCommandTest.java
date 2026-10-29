package net.magicterra.stagewright.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The launch specs {@code stagewright test} writes, one per topology. */
class TestCommandTest {

    private static final Path PACK = Path.of("/packs/server");
    private static final ModInjection.Arguments NO_MODS = ModInjection.Arguments.NONE;

    @Test
    void theServerPlaysInAWorldOfItsOwnWithTheTopologysPropertiesFirst() {
        Map<String, Object> server = TestCommand.serverProcess(PACK,
                List.of("java", "-Xmx4G", "@libraries/net/neoforged/neoforge/21.1.230/unix_args.txt", "nogui"),
                List.of("-Dx=1"), NO_MODS, List.of("-Dstagewright.awaitPlayer=true"));
        assertEquals(List.of("-Dx=1", "-Dstagewright.awaitPlayer=true", "-Xmx4G",
                "@libraries/net/neoforged/neoforge/21.1.230/unix_args.txt"), server.get("jvmArgs"));
        assertFalse(server.containsKey("mainClass"), "the argument file names it");
        @SuppressWarnings("unchecked")
        List<String> args = (List<String>) server.get("args");
        assertEquals(List.of("nogui", "--universe", PACK.resolve("stagewright").toString(), "--world", "world"), args);
    }

    @Test
    void aJoiningClientIsTheCompanionOfTheServer() {
        Map<String, Object> client = TestCommand.clientProcess("java",
                new ClientLaunch.Parts(List.of("-cp", "x"), "net.minecraft.client.main.Main", List.of("--demo")),
                PACK.resolve("stagewright/client"));
        Map<String, Object> spec = TestCommand.topology("dedicatedServerWithClient", PACK, "stagewright/world", true,
                Map.of("java", "java"), client, Map.of("timeout", "7"));
        assertEquals("dedicatedServerWithClient", spec.get("topology"));
        assertEquals(true, spec.get("server"));
        assertEquals(7, spec.get("timeoutMinutes"));
        assertEquals(client, spec.get("companion"));
        assertEquals("net.minecraft.client.main.Main", client.get("mainClass"));
    }

    @Test
    void leaksFailTheSceneUnlessReportIsAskedFor() {
        Map<String, Object> plain = TestCommand.topology("dedicatedServer", PACK, "stagewright/world", true,
                Map.of("java", "java"), null, Map.of());
        assertEquals("fail", plain.get("leaks"));
        Map<String, Object> report = TestCommand.topology("dedicatedServer", PACK, "stagewright/world", true,
                Map.of("java", "java"), null, Map.of("leaks", "report"));
        assertEquals("report", report.get("leaks"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> TestCommand.leaks(Map.of("leaks", "ignore")));
        assertTrue(e.getMessage().contains("'fail' or 'report'"), e.getMessage());
    }

    @Test
    void aTopologyWithNoClientToStartSaysWhy() {
        Map<String, Object> spec = TestCommand.unavailable("integratedServer", "no --client");
        assertEquals("integratedServer", spec.get("topology"));
        assertEquals("no --client", spec.get("unavailable"));
        assertFalse(spec.containsKey("primary"));
    }

    @Test
    void anIntegratedServerIsTheClientAloneInASaveOfItsOwn() {
        Path clientDir = PACK.resolve("stagewright/client");
        Map<String, Object> spec = TestCommand.topology("integratedServer", clientDir,
                "saves/" + TestCommand.CLIENT_WORLD, false, Map.of("java", "java"), null, Map.of());
        assertEquals(clientDir.toString(), spec.get("gameDirectory"));
        assertEquals("saves/StageWright", spec.get("worldDirectory"));
        assertEquals(false, spec.get("server"));
        assertFalse(spec.containsKey("companion"));
    }

    @Test
    void theClientsModsAreMadeAnewFromTheJarsNamed(@TempDir Path dir) throws IOException {
        Path from = Files.createDirectories(dir.resolve("server/mods"));
        Files.writeString(from.resolve("a.jar"), "a");
        Files.writeString(from.resolve("notes.txt"), "not a mod");
        Path to = Files.createDirectories(dir.resolve("client/mods"));
        Files.writeString(to.resolve("stale.jar"), "from the run before");
        TestCommand.linkMods(from, to, line -> { });
        try (var list = Files.list(to)) {
            assertEquals(List.of("a.jar"), list.map(p -> p.getFileName().toString()).toList());
        }
        assertEquals("a", Files.readString(to.resolve("a.jar")));
    }

    @Test
    void aSymlinkedTestsDirectoryAndTheLinksInsideItAreCopiedAsFiles(@TempDir Path dir) throws IOException {
        Path elsewhere = Files.createDirectories(dir.resolve("repo/tests/sub"));
        Files.writeString(elsewhere.resolve("a.js"), "test('a', t => {});");
        Path single = Files.writeString(dir.resolve("repo/b.yaml"), "tests: []");
        Files.createSymbolicLink(elsewhere.getParent().resolve("b.yaml"), single);
        Path linked = Files.createSymbolicLink(Files.createDirectories(dir.resolve("pack/stagewright")).resolve("tests"),
                elsewhere.getParent());
        Path to = dir.resolve("root/stagewright/tests");
        assertEquals(2, TestCommand.copyTree(linked, to));
        assertEquals("test('a', t => {});", Files.readString(to.resolve("sub/a.js")));
        assertFalse(Files.isSymbolicLink(to.resolve("b.yaml")), "the file the link names, not the link");
        assertEquals("tests: []", Files.readString(to.resolve("b.yaml")));
    }

    @Test
    void aClientsPropertiesComeBeforeTheLoadersOwn() {
        ModInjection.Arguments mods = new ModInjection.Arguments(List.of("-Dfabric.addMods=a.jar"), List.of());
        List<String> props = TestCommand.clientProps(List.of("-Xmx2G"), mods, "stagewright.client.world=W");
        assertEquals(List.of("-Xmx2G", "-Dstagewright.client.world=W", "-Dfabric.addMods=a.jar"), props);
        assertTrue(props.indexOf("-Dfabric.addMods=a.jar") > props.indexOf("-Xmx2G"));
    }
}
