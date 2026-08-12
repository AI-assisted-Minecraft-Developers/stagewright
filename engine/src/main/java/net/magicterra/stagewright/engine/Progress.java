package net.magicterra.stagewright.engine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turns the game's heartbeat file into the sentence "no done footer" cannot say.
 *
 * <p>A results file that ends mid-stream is already judged correctly — no footer is RED, and the
 * harness cannot have died quietly. What that verdict omits is everything an operator needs next:
 * which scene was running, whether the world was still ticking when it stopped, and how long before
 * the kill it last said so. The in-game watchdog writes those four numbers once a second; this reads
 * them back.
 *
 * <h2>Why only when the footer is missing</h2>
 *
 * <p>The heartbeat outlives a healthy run — it is truncated in place, never deleted, so after a
 * complete suite it names whichever scene happened to be last. Printed on an ordinary RED, where the
 * footer is present and some scene simply failed its assertions, that line points at a scene with no
 * connection to the failure and invites exactly the wrong investigation. It earns its place in one
 * case only: nothing wrote a conclusion, so the last thing written is the only thing there is.
 */
public final class Progress {

    private Progress() {}

    /**
     * @param results the results file this run was judged on; the heartbeat is its sibling
     * @param records the parsed records, consulted only for whether a {@code done} footer landed
     * @return the position to report, or empty when the run finished, when no heartbeat was written,
     *         or when the one on disk does not decode. All three are "nothing useful to add" rather
     *         than errors: this is detail on a failure that has already been decided.
     */
    public static Optional<String> lastKnownPosition(Path results, List<Map<String, Object>> records) {
        for (Map<String, Object> rec : records) {
            if ("done".equals(Verdict.str(rec.get("type")))) return Optional.empty();
        }
        Path file = results.toAbsolutePath().resolveSibling(RunDirectory.PROGRESS_FILE);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8).trim();
            if (text.isEmpty()) return Optional.empty();
            if (!(Json.parse(text) instanceof Map<?, ?> map)) return Optional.empty();
            return Optional.of(describe(map));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static String describe(Map<?, ?> beat) {
        String scene = Verdict.str(beat.get("scene"));
        long ticks = asLong(beat.get("ticks"));
        long inWindow = asLong(beat.get("ticksInWindow"));
        long windowMs = asLong(beat.get("windowMs"));
        long atMs = asLong(beat.get("atMs"));

        StringBuilder sb = new StringBuilder("last heartbeat: ");
        sb.append(scene == null || scene.isEmpty() ? "between scenes" : "scene '" + scene + "'");
        sb.append(" at tick ").append(ticks);

        // The rate is reported as the raw pair the game wrote rather than a quotient, because the
        // two failures look identical once divided: a world delivering nothing and a world delivering
        // a trickle both round to roughly zero, and they are not the same bug.
        if (windowMs > 0) {
            sb.append(" — ").append(inWindow).append(" tick(s) in the preceding ")
              .append(windowMs / 1000).append("s");
            sb.append(inWindow == 0 ? " (the world had stopped)"
                    : inWindow * 1000L < windowMs ? " (the world was crawling)"
                    : " (the world was healthy — whatever killed this run, it was not the tick)");
        }

        // Same machine, same clock: the game process and this verdict are two processes on one host,
        // which is what makes the subtraction meaningful. It is offered as "before this verdict"
        // rather than an absolute time for that reason — a clock skew would show up as an absurd
        // number here instead of a plausible-looking timestamp.
        if (atMs > 0) {
            long agoMs = System.currentTimeMillis() - atMs;
            if (agoMs >= 0) sb.append(", written ").append(agoMs / 1000).append("s before this verdict");
        }
        return sb.toString();
    }

    private static long asLong(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }
}
