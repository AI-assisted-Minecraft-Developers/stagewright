package net.magicterra.stagewright.testengine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which tests some game started, and which a game disabled for an unmet {@code @Requires}, across every scene of the
 * run. A test disabled that way everywhere it was asked for never ran at all, and the run fails saying so.
 */
final class Coverage {

    /** How the game's requirement check begins its skip reason. */
    static final String REQUIRES_PREFIX = "@Requires: ";

    private final Set<String> ran = new HashSet<>();
    private final Map<String, List<String>> unmet = new LinkedHashMap<>();

    synchronized void ran(Nodes.TestMethod method) {
        ran.add(method.key());
    }

    /** A skip the game reported; only an unmet requirement counts against coverage. */
    synchronized void skipped(Nodes.TestMethod method, String scene, String reason) {
        if (reason == null || !reason.startsWith(REQUIRES_PREFIX)) return;
        unmet.computeIfAbsent(method.key(), k -> new ArrayList<>())
                .add(scene + " (" + reason.substring(REQUIRES_PREFIX.length()) + ")");
    }

    /** One line per test that no scene ran because none met its requirements; empty when there is none. */
    synchronized List<String> neverRan() {
        List<String> lines = new ArrayList<>();
        unmet.forEach((key, where) -> {
            if (!ran.contains(key)) lines.add(key + ": " + String.join("; ", where));
        });
        return lines;
    }
}
