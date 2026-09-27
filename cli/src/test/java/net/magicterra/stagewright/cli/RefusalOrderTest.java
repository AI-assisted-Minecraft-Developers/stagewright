package net.magicterra.stagewright.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** A server run refused before it starts leaves {@code --world reset}'s world where it was. */
class RefusalOrderTest {

    private static Path serverWithWorld(Path tmp) throws IOException {
        Path server = Files.createDirectories(tmp.resolve("server"));
        Files.writeString(Files.createDirectories(server.resolve("world")).resolve("level.dat"), "");
        return server;
    }

    @Test
    void aServerWhoseLoaderIsUnknownIsRefusedBeforeTheWorldIsReset(@TempDir Path tmp) throws IOException {
        Path server = serverWithWorld(tmp);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Main.run(new String[] {
                "--game-dir", server.toString(), "--world", "reset", "--launch", "java -jar server.jar"}));
        assertTrue(Files.exists(server.resolve("world/level.dat")), "a refused run reset the world anyway");
        assertTrue(e.getMessage().contains("cannot tell which loader"), e.getMessage());
    }

    @Test
    void halvesOnDifferentLoadersAreRefusedBeforeTheWorldIsReset(@TempDir Path tmp) throws IOException {
        Path server = serverWithWorld(tmp);
        Files.createDirectories(server.resolve("libraries/net/neoforged/neoforge"));
        Path client = tmp.resolve("client");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Main.run(new String[] {
                "--game-dir", server.toString(), "--world", "reset", "--client", "fabric:1.21.1:0.16.5",
                "--with-client", client.toString(), "--install-dir", tmp.resolve("install").toString(),
                "--launch", "java -jar server.jar"}));
        assertTrue(Files.exists(server.resolve("world/level.dat")), "a refused run reset the world anyway");
        assertFalse(Files.exists(client), "a refused run created the client directory anyway");
        assertTrue(e.getMessage().contains("same loader"), e.getMessage());
    }

    @Test
    void aServerWithNoWayToStartItIsRefusedBeforeTheWorldIsReset(@TempDir Path tmp) throws IOException {
        Path server = serverWithWorld(tmp);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Main.run(new String[] {
                "--game-dir", server.toString(), "--world", "reset", "--no-install"}));
        assertTrue(Files.exists(server.resolve("world/level.dat")), "a refused run reset the world anyway");
        assertTrue(e.getMessage().contains("cannot tell how to start the server"), e.getMessage());
    }

    @Test
    void aTimeoutOfNoMinutesIsRefusedBeforeTheWorldIsReset(@TempDir Path tmp) throws IOException {
        Path server = serverWithWorld(tmp);
        Files.createDirectories(server.resolve("libraries/net/neoforged/neoforge"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Main.run(new String[] {
                "--game-dir", server.toString(), "--world", "reset", "--stall-timeout", "0"}));
        assertTrue(Files.exists(server.resolve("world/level.dat")), "a refused run reset the world anyway");
        assertTrue(e.getMessage().contains("--stall-timeout must be at least 1 minute"), e.getMessage());
    }

    @Test
    void bothTimeoutsTakeAWholeNumberOfMinutesAboveZero() {
        assertEquals(45, Main.minutes(Map.of(), "timeout", 45));
        assertEquals(7, Main.minutes(Map.of("timeout", "7"), "timeout", 45));
        for (String bad : new String[] {"0", "-1", "1.5", "five"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> Main.minutes(Map.of("timeout", bad), "timeout", 45), bad);
            assertThrows(IllegalArgumentException.class,
                    () -> Main.minutes(Map.of("stall-timeout", bad), "stall-timeout", 5), bad);
        }
    }

    @Test
    void aModThatIsNotAFileIsRefusedBeforeTheWorldIsReset(@TempDir Path tmp) throws IOException {
        Path server = serverWithWorld(tmp);
        Files.createDirectories(server.resolve("libraries/net/neoforged/neoforge"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Main.run(new String[] {
                "--game-dir", server.toString(), "--world", "reset", "--launch", "java -jar server.jar",
                "--mod", tmp.resolve("typo.jar").toString()}));
        assertTrue(Files.exists(server.resolve("world/level.dat")), "a refused run reset the world anyway");
        assertTrue(e.getMessage().contains("is not a file"), e.getMessage());
    }

    @Test
    void aForgeServerIsRefusedBeforeTheWorldIsReset(@TempDir Path tmp) throws IOException {
        Path server = serverWithWorld(tmp);
        Files.createDirectories(server.resolve("libraries/net/minecraftforge/forge"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Main.run(new String[] {
                "--game-dir", server.toString(), "--world", "reset", "--launch", "java -jar server.jar"}));
        assertTrue(Files.exists(server.resolve("world/level.dat")), "a refused run reset the world anyway");
        assertTrue(e.getMessage().contains("not forge"), e.getMessage());
    }

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aVanillaClientIsRefusedBeforeAnythingIsInstalled(@TempDir Path tmp) {
        Path install = tmp.resolve("install");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Main.run(new String[] {
                "--game-dir", tmp.resolve("game").toString(), "--client", "vanilla:1.21.1",
                "--install-dir", install.toString()}));
        assertFalse(Files.exists(install), "a refused run installed the client anyway");
        assertTrue(e.getMessage().contains("vanilla client cannot run scenes"), e.getMessage());
    }

    @Test
    void aModWithNoInstallIsRefusedRatherThanDropped(@TempDir Path tmp) throws IOException {
        Path server = serverWithWorld(tmp);
        Path mod = Files.writeString(tmp.resolve("driver.jar"), "");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Main.run(new String[] {
                "--game-dir", server.toString(), "--world", "reset", "--no-install",
                "--launch", "java -jar s.jar", "--mod", mod.toString()}));
        assertTrue(Files.exists(server.resolve("world/level.dat")), "a refused run reset the world anyway");
        assertTrue(e.getMessage().contains("--mod and --no-install"), e.getMessage());
    }

    @Test
    void aForgeServerWithAClientIsRefusedForItsLoaderNotForAMismatch(@TempDir Path tmp) throws IOException {
        Path server = serverWithWorld(tmp);
        Files.createDirectories(server.resolve("libraries/net/minecraftforge/forge"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Main.run(new String[] {
                "--game-dir", server.toString(), "--world", "reset", "--client", "neoforge:1.21.1:21.1.248",
                "--with-client", tmp.resolve("client").toString(), "--launch", "java -jar s.jar",
                "--install-dir", tmp.resolve("install").toString()}));
        assertTrue(Files.exists(server.resolve("world/level.dat")), "a refused run reset the world anyway");
        assertFalse(Files.exists(tmp.resolve("client")), "a refused run created the client directory anyway");
        assertTrue(e.getMessage().contains("not forge"), e.getMessage());
    }
}
