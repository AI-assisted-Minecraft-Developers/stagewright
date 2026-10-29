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
    private final Set<String> unlaunched = new HashSet<>();

    /** {@code key}: a test method's or script test's own key, the same in every scene. */
    synchronized void ran(String key) {
        ran.add(key);
    }

    /** A skip the game reported; only an unmet requirement counts against coverage. */
    synchronized void skipped(String key, String scene, String reason) {
        if (reason == null || !reason.startsWith(REQUIRES_PREFIX)) return;
        unmet.computeIfAbsent(key, k -> new ArrayList<>())
                .add(scene + " (" + reason.substring(REQUIRES_PREFIX.length()) + ")");
    }

    /** A test in a scene this run could not launch: whether it would have run there is unknown, so it is not judged. */
    synchronized void unlaunched(String key) {
        unlaunched.add(key);
    }

    /** One line per test that no scene ran because none met its requirements; empty when there is none. */
    synchronized List<String> neverRan() {
        List<String> lines = new ArrayList<>();
        unmet.forEach((key, where) -> {
            if (!ran.contains(key) && !unlaunched.contains(key)) lines.add(key + ": " + String.join("; ", where));
        });
        return lines;
    }
}
