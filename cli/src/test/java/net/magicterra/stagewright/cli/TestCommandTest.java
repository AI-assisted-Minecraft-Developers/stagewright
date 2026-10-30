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
        Map<String, Object> server = TestCommand.serverProcess(
                PACK,
                List.of("java", "-Xmx4G", "@libraries/net/neoforged/neoforge/21.1.230/unix_args.txt", "nogui"),
                List.of("-Dx=1"),
                NO_MODS,
                List.of("-Dstagewright.awaitPlayer=true"));
        assertEquals(
                List.of(
                        "-Dx=1",
                        "-Dstagewright.awaitPlayer=true",
                        "-Xmx4G",
                        "@libraries/net/neoforged/neoforge/21.1.230/unix_args.txt"),
                server.get("jvmArgs"));
        assertFalse(server.containsKey("mainClass"), "the argument file names it");
        @SuppressWarnings("unchecked")
        List<String> args = (List<String>) server.get("args");
        assertEquals(List.of("nogui", "--universe", PACK.resolve("stagewright").toString(), "--world", "world"), args);
    }

    @Test
    void aJoiningClientIsTheCompanionOfTheServer() {
        Map<String, Object> client = TestCommand.clientProcess(
                "java",
                new ClientLaunch.Parts(List.of("-cp", "x"), "net.minecraft.client.main.Main", List.of("--demo")),
                PACK.resolve("stagewright/client"));
        Map<String, Object> spec = TestCommand.topology(
                "dedicatedServerWithClient",
                PACK,
                "stagewright/world",
                true,
                Map.of("java", "java"),
                client,
                Map.of("timeout", "7"));
        assertEquals("dedicatedServerWithClient", spec.get("topology"));
        assertEquals(true, spec.get("server"));
        assertEquals(7, spec.get("timeoutMinutes"));
        assertEquals(client, spec.get("companion"));
        assertEquals("net.minecraft.client.main.Main", client.get("mainClass"));
    }

    @Test
    void leaksFailTheSceneUnlessReportIsAskedFor() {
        Map<String, Object> plain = TestCommand.topology(
                "dedicatedServer", PACK, "stagewright/world", true, Map.of("java", "java"), null, Map.of());
        assertEquals("fail", plain.get("leaks"));
        Map<String, Object> report = TestCommand.topology(
                "dedicatedServer",
                PACK,
                "stagewright/world",
                true,
                Map.of("java", "java"),
                null,
                Map.of("leaks", "report"));
        assertEquals("report", report.get("leaks"));
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> TestCommand.leaks(Map.of("leaks", "ignore")));
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
        Map<String, Object> spec = TestCommand.topology(
                "integratedServer",
                clientDir,
                "saves/" + TestCommand.CLIENT_WORLD,
                false,
                Map.of("java", "java"),
                null,
                Map.of());
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
        TestCommand.linkMods(from, to, line -> {});
        try (var list = Files.list(to)) {
            assertEquals(
                    List.of("a.jar"), list.map(p -> p.getFileName().toString()).toList());
        }
        assertEquals("a", Files.readString(to.resolve("a.jar")));
    }

    @Test
    void aSymlinkedTestsDirectoryAndTheLinksInsideItAreCopiedAsFiles(@TempDir Path dir) throws IOException {
        Path elsewhere = Files.createDirectories(dir.resolve("repo/tests/sub"));
        Files.writeString(elsewhere.resolve("a.js"), "test('a', t => {});");
        Path single = Files.writeString(dir.resolve("repo/b.yaml"), "tests: []");
        Files.createSymbolicLink(elsewhere.getParent().resolve("b.yaml"), single);
        Path linked = Files.createSymbolicLink(
                Files.createDirectories(dir.resolve("pack/stagewright")).resolve("tests"), elsewhere.getParent());
        Path to = dir.resolve("root/stagewright/tests");
        assertEquals(2, TestCommand.copyTree(linked, to));
        assertEquals("test('a', t => {});", Files.readString(to.resolve("sub/a.js")));
        assertFalse(Files.isSymbolicLink(to.resolve("b.yaml")), "the file the link names, not the link");
        assertEquals("tests: []", Files.readString(to.resolve("b.yaml")));
    }

    @Test
    void theTestsComeFromTheServerUnlessTestsNamesADirectory(@TempDir Path dir) throws IOException {
        Path server = Files.createDirectories(dir.resolve("server/stagewright/tests"))
                .getParent()
                .getParent();
        assertEquals(server.resolve("stagewright"), TestCommand.testsDir(server, Map.of()));
        Path repo = Files.createDirectories(dir.resolve("repo/suite/tests")).getParent();
        assertEquals(repo, TestCommand.testsDir(server, Map.of("tests", repo.toString())));
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class,
                () -> TestCommand.testsDir(
                        server, Map.of("tests", dir.resolve("repo").toString())));
        assertTrue(e.getMessage().contains("repo/tests is not a directory"), e.getMessage());
    }

    @Test
    void theClientsModsComeFromClientModsThenTheClientDirThenTheServer() {
        Path client = Path.of("/packs/client");
        assertEquals(PACK.resolve("mods"), TestCommand.clientMods(PACK, null, Map.of()));
        assertEquals(client.resolve("mods"), TestCommand.clientMods(PACK, client, Map.of()));
        assertEquals(
                Path.of("/elsewhere/mods"),
                TestCommand.clientMods(PACK, client, Map.of("client-mods", "/elsewhere/mods")));
    }

    @Test
    void aClientDirThatIsNotADirectoryIsRefused(@TempDir Path dir) {
        assertEquals(null, TestCommand.clientDir(Map.of()));
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class,
                () -> TestCommand.clientDir(
                        Map.of("client-dir", dir.resolve("missing").toString())));
        assertTrue(e.getMessage().startsWith("--client-dir "), e.getMessage());
    }

    @Test
    void theClientDirsEntriesButItsOwnAreLinkedNotCopied(@TempDir Path dir) throws IOException {
        Path pack = Files.createDirectories(dir.resolve("pack-client"));
        Files.writeString(Files.createDirectories(pack.resolve("config")).resolve("a.toml"), "a = 1");
        Files.writeString(
                Files.createDirectories(pack.resolve("kubejs/server_scripts")).resolve("x.js"), "//");
        Files.writeString(pack.resolve("options.txt"), "lang:zh_cn");
        for (String own : List.of("mods", "saves", "logs", "crash-reports", "stagewright")) {
            Files.createDirectories(pack.resolve(own));
        }
        Path client = Files.createDirectories(dir.resolve("server/stagewright/client"));
        // Left by a run whose pack-client had an entry it has no longer, and one it still has.
        Files.createSymbolicLink(client.resolve("gone"), Files.createDirectories(dir.resolve("elsewhere")));
        Files.createSymbolicLink(client.resolve("config"), Files.createDirectories(dir.resolve("old-config")));
        Files.createDirectories(client.resolve("saves/StageWright"));

        assertEquals(3, TestCommand.linkClientDir(pack, client, line -> {}));
        for (String linked : List.of("config", "kubejs", "options.txt")) {
            assertTrue(Files.isSymbolicLink(client.resolve(linked)), linked);
            assertEquals(pack.resolve(linked), Files.readSymbolicLink(client.resolve(linked)));
        }
        assertEquals("a = 1", Files.readString(client.resolve("config/a.toml")));
        assertFalse(Files.exists(client.resolve("gone"), java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.isDirectory(dir.resolve("elsewhere")), "a link is taken out, not what it names");
        assertTrue(Files.isDirectory(client.resolve("saves/StageWright")), "the client keeps its own saves");
        assertFalse(Files.isSymbolicLink(client.resolve("saves")));
        for (String own : List.of("mods", "logs", "crash-reports", "stagewright")) {
            assertFalse(Files.isSymbolicLink(client.resolve(own)), own);
        }

        assertEquals(0, TestCommand.linkClientDir(null, client, line -> {}));
        try (var list = Files.list(client)) {
            assertEquals(
                    List.of("saves"),
                    list.map(p -> p.getFileName().toString()).toList(),
                    "a run with no --client-dir keeps no link of the one before");
        }
    }

    @Test
    void aRealDirectoryWhereALinkWouldGoIsRefusedAndLeftAlone(@TempDir Path dir) throws IOException {
        Path pack = Files.createDirectories(dir.resolve("pack-client"));
        Files.createDirectories(pack.resolve("config"));
        Files.createDirectories(pack.resolve("kubejs"));
        Files.writeString(pack.resolve("options.txt"), "lang:zh_cn");
        Path client = Files.createDirectories(dir.resolve("server/stagewright/client"));
        // Copies an earlier layout made, and a link of the run before.
        Files.writeString(Files.createDirectories(client.resolve("config")).resolve("mine.toml"), "b = 2");
        Files.writeString(client.resolve("options.txt"), "lang:en_us");
        Files.createSymbolicLink(client.resolve("kubejs"), Files.createDirectories(dir.resolve("old-kubejs")));

        EnvFailure e = assertThrows(EnvFailure.class, () -> TestCommand.linkClientDir(pack, client, line -> {}));
        assertTrue(e.getMessage().contains(client.resolve("config").toString()), e.getMessage());
        assertTrue(e.getMessage().contains(client.resolve("options.txt").toString()), e.getMessage());
        assertFalse(e.getMessage().contains(client.resolve("kubejs").toString()), "a link is the runner's own");
        assertEquals("b = 2", Files.readString(client.resolve("config/mine.toml")), "the directory is left as it was");
        assertEquals("lang:en_us", Files.readString(client.resolve("options.txt")));
        assertTrue(Files.isSymbolicLink(client.resolve("kubejs")), "nothing changes when the run is refused");
    }

    @Test
    void aClientsPropertiesComeBeforeTheLoadersOwn() {
        ModInjection.Arguments mods = new ModInjection.Arguments(List.of("-Dfabric.addMods=a.jar"), List.of());
        List<String> props = TestCommand.clientProps(List.of("-Xmx2G"), mods, "stagewright.client.world=W");
        assertEquals(List.of("-Xmx2G", "-Dstagewright.client.world=W", "-Dfabric.addMods=a.jar"), props);
        assertTrue(props.indexOf("-Dfabric.addMods=a.jar") > props.indexOf("-Xmx2G"));
    }
}
