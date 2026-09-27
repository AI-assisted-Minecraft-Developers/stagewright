package net.magicterra.stagewright.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Which screen a stalled client is reported as sitting on. */
class StallTest {

    private static final String WAITING = "[Render thread/INFO] [net.magicterra.stagewright.StageWrightCommon/]:"
            + " [mc_testkit] waiting for the title screen, currently on ";

    @Test
    void theLastScreenBeforeTheTitleIsNamed(@TempDir Path tmp) throws IOException {
        Path log = Files.writeString(tmp.resolve("client.log"), WAITING + "no screen\n"
                + WAITING + "xaero.lib.client.gui.GuiUpdateAll\n[Render thread/INFO] something else\n");

        assertEquals("xaero.lib.client.gui.GuiUpdateAll", Stall.screen(log));
    }

    @Test
    void aClientThatReachedItsWorldIsNotOnAScreen(@TempDir Path tmp) throws IOException {
        Path log = Files.writeString(tmp.resolve("client.log"), WAITING + "net.minecraft.client.gui.screens.TitleScreen\n"
                + "[Render thread/INFO] [mc_testkit] client is in world after 40 ticks\n");

        assertNull(Stall.screen(log));
        assertNull(Stall.screen(tmp.resolve("absent.log")));
    }

    @Test
    void aClientPastItsTitleScreenIsNotOnAScreen(@TempDir Path tmp) throws IOException {
        // Stuck generating or joining a world: the last waiting line names the title screen itself.
        for (String left : new String[] {"[mc_testkit] creating world 'stagewright'",
                "[mc_testkit] connecting to 127.0.0.1:25565", "[mc_testkit] opening existing world 'w'"}) {
            Path log = Files.writeString(tmp.resolve("client.log"), WAITING + "net.minecraft.class_442\n"
                    + "[Render thread/INFO] [net.magicterra.stagewright.StageWrightCommon/]: " + left + "\n");
            assertNull(Stall.screen(log), left);
        }
    }

    @Test
    void noScreenIsNotAScreenNobodyClicksPast(@TempDir Path tmp) throws IOException {
        Path log = Files.writeString(tmp.resolve("client.log"), WAITING + "no screen\n");

        assertNull(Stall.screen(log));
    }
}
