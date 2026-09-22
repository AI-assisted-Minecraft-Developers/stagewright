package net.magicterra.stagewright.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The expected-scenes manifest, read the way both front ends read it.
 *
 * <p>A name this parser loses is a scene reconciliation stops asking about, and nothing downstream
 * can notice a name it was never given — so the spellings a manifest is allowed to use are pinned
 * here rather than left to whichever form the checked-in files happen to use today.
 */
class ManifestTest {

    private static Path write(Path dir, String... lines) throws IOException {
        Path file = dir.resolve("expected-scenes.txt");
        Files.writeString(file, String.join("\n", lines), StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void oneNamePerLine(@TempDir Path dir) throws IOException {
        assertEquals(List.of("wd.a", "wd.b"), Manifest.read(write(dir, "wd.a", "wd.b")));
    }

    @Test
    void commentsAndBlankLinesAreNotNames(@TempDir Path dir) throws IOException {
        Path file = write(dir,
                "# the whole line is a comment",
                "",
                "   ",
                "wd.a   # and so is the rest of this one",
                "  wd.b  ");
        assertEquals(List.of("wd.a", "wd.b"), Manifest.read(file));
    }

    @Test
    void commasAndLinesAreTheSameSpelling(@TempDir Path dir) throws IOException {
        Path file = write(dir, "wd.a, wd.b,,wd.c", "wd.d,");
        assertEquals(List.of("wd.a", "wd.b", "wd.c", "wd.d"), Manifest.read(file));
    }

    @Test
    void aMissingManifestIsAnErrorNotAnEmptyList(@TempDir Path dir) {
        assertThrows(UncheckedIOException.class, () -> Manifest.read(dir.resolve("absent.txt")));
    }
}
