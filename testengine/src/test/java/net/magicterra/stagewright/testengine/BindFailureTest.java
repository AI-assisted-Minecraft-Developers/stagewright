package net.magicterra.stagewright.testengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.magicterra.stagewright.engine.supervise.Supervisor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A server that could not bind its port fails its scene saying so, with the cause its log gives. */
class BindFailureTest {

    @TempDir
    Path temp;

    @Test
    void aBindFailureIsReadWithItsCause() throws IOException {
        Path log = temp.resolve("game.log");
        Files.writeString(
                log,
                "[Server thread/WARN]: **** FAILED TO BIND TO PORT!\n"
                        + "[Server thread/WARN]: The exception was: io.netty.channel.unix.Errors$NativeIoException:"
                        + " bind(..) failed: 地址已在使用\n"
                        + "[Server thread/WARN]: Perhaps a server is already running on that port?\n",
                StandardCharsets.UTF_8);
        String bind = SceneRun.bindFailure(log.toFile());
        assertEquals("io.netty.channel.unix.Errors$NativeIoException: bind(..) failed: 地址已在使用", bind);
        assertTrue(SceneRun.describe(Supervisor.Ended.PROCESS_DIED, "armed", 0, null, List.of(), log.toFile())
                .contains("could not listen on its port"));
    }

    @Test
    void aLogWithoutOneSaysNothing() throws IOException {
        Path log = temp.resolve("game.log");
        Files.write(log, new byte[] {'o', 'k', (byte) 0xff, '\n'});
        assertNull(SceneRun.bindFailure(log.toFile()));
        assertNull(SceneRun.bindFailure(temp.resolve("absent.log").toFile()));
    }
}
