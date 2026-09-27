package net.magicterra.stagewright.cli;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * A client that has said it cannot open a window, noticed while it is still saying it.
 *
 * <p>That client is never going to run anything, and it does not exit either: NeoForge answers a
 * failed {@code glfwInit} with a dialog offering to open a help page, then sleeps until somebody
 * clicks it. The stall watchdog does catch that, but only after its full limit of silence, so the
 * failure is read from the client's own output as it is written instead.
 */
final class NoWindow {

    static final NoWindow NEVER = new NoWindow(null);

    private static final List<String> MARKERS =
            List.of("glfwInit failed", "We are unable to initialize the graphics system");
    private static final int CHUNK = 1 << 20;

    private final Path log;
    private long offset;
    private String line;

    /** @param log a client's output, written from empty by this run */
    NoWindow(Path log) {
        this.log = log;
    }

    /** The line that said so, once one has been written; null until then. Called about once a second. */
    String said() {
        if (line != null || log == null) return line;
        try (SeekableByteChannel in = Files.newByteChannel(log)) {
            long size = in.size();
            if (size < offset) offset = 0;
            if (size == offset) return null;
            ByteBuffer chunk = ByteBuffer.allocate((int) Math.min(size - offset, CHUNK));
            in.position(offset);
            while (chunk.hasRemaining() && in.read(chunk) > 0) {
                // fill
            }
            byte[] bytes = chunk.array();
            int end = chunk.position() - 1;
            while (end >= 0 && bytes[end] != '\n') end--;
            if (end < 0) {
                // A line longer than a chunk is not one of ours; step over it rather than stall here.
                if (chunk.position() == CHUNK) offset += CHUNK;
                return null;
            }
            offset += end + 1;
            for (String l : new String(bytes, 0, end + 1, StandardCharsets.UTF_8).split("\n")) {
                if (MARKERS.stream().anyMatch(l::contains)) return line = l.strip();
            }
        } catch (IOException e) {
            // Not there yet, or being written as we look; the next poll sees it.
        }
        return null;
    }

    Path log() {
        return log;
    }
}
