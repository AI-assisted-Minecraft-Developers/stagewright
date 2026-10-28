package net.magicterra.stagewright.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What provisioning writes, and what it leaves alone. */
class RunDirectoryTest {

    @Test
    void aServerDirectoryGetsItsEulaAndForcedKeysAndKeepsTheRest(@TempDir Path tmp) throws IOException {
        Path gameDir = Files.createDirectories(tmp.resolve("server"));
        Files.writeString(gameDir.resolve("server.properties"), "motd=hello\nonline-mode=true\n");

        RunDirectory.provision(gameDir, true);

        assertEquals("eula=true\n", Files.readString(gameDir.resolve("eula.txt")));
        String properties = Files.readString(gameDir.resolve("server.properties"));
        assertTrue(properties.contains("motd=hello\n"), properties);
        assertTrue(properties.contains("online-mode=false\n"), properties);
        assertFalse(properties.contains("online-mode=true"), properties);
        assertTrue(properties.contains("level-seed=5471\n"), properties);
        assertTrue(properties.contains("sync-chunk-writes=false\n"), properties);
    }

    @Test
    void aClientDirectoryGetsNoServerFilesAndKeepsItsOptions(@TempDir Path tmp) throws IOException {
        Path gameDir = Files.createDirectories(tmp.resolve(".minecraft"));
        Path options = Files.writeString(gameDir.resolve("options.txt"), "key_key.jump:key.keyboard.space\n");

        RunDirectory.provision(gameDir, false);

        assertEquals("key_key.jump:key.keyboard.space\n", Files.readString(options));
        assertFalse(Files.exists(gameDir.resolve("eula.txt")));
        assertFalse(Files.exists(gameDir.resolve("server.properties")));
    }
}
