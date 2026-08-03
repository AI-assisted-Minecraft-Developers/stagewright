package net.magicterra.stagewright.engine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The expected-scenes manifest: which scenes a run must contain, by name.
 *
 * <p>Shared for the same reason as {@link Verdict}. The manifest is half of the reconciliation that
 * catches a scene silently dropping out of a suite, and a front-end that parsed it even slightly
 * differently — one that missed a comment form, say — would reconcile against a different set of
 * names than the other and call the same run by a different verdict.
 */
public final class Manifest {

    private Manifest() {}

    /** Names from a manifest file, or an empty list if it holds none. */
    public static List<String> read(Path file) {
        List<String> names = new ArrayList<>();
        try {
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = raw.trim();
                // '#' comments and blank lines, and commas so a manifest can be written either one
                // name per line or comma-separated without the two forms disagreeing.
                int hash = line.indexOf('#');
                if (hash >= 0) line = line.substring(0, hash).trim();
                if (line.isEmpty()) continue;
                for (String part : line.split(",")) {
                    String name = part.trim();
                    if (!name.isEmpty()) names.add(name);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the expected-scenes manifest " + file, e);
        }
        return names;
    }
}
