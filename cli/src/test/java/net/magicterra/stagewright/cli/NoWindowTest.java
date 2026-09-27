package net.magicterra.stagewright.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A client's output is read as it grows, a whole line at a time. */
class NoWindowTest {

    private static void append(Path log, String text) throws IOException {
        Files.writeString(log, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    @Test
    void theLineIsFoundOnceItIsWhole(@TempDir Path tmp) throws IOException {
        Path log = tmp.resolve("client.log");
        NoWindow noWindow = new NoWindow(log);
        assertNull(noWindow.said(), "no log yet");

        append(log, "[main/INFO] Loading ImmediateWindowProvider fmlearlywindow\n[main/ERROR] glfwIn");
        assertNull(noWindow.said(), "half a line is not a line");

        append(log, "it failed\n[main/INFO] after\n");
        assertEquals("[main/ERROR] glfwInit failed", noWindow.said());
        assertEquals("[main/ERROR] glfwInit failed", noWindow.said(), "and it stays said");
    }

    @Test
    void aClientThatOpenedItsWindowSaysNothing(@TempDir Path tmp) throws IOException {
        Path log = tmp.resolve("client.log");
        append(log, "[Render thread/INFO] Backend library: LWJGL version 3.3.3\n");

        assertNull(new NoWindow(log).said());
        assertNull(NoWindow.NEVER.said());
    }
}
