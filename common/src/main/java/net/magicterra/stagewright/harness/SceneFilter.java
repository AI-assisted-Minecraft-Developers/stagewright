package net.magicterra.stagewright.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import net.magicterra.stagewright.scene.Scene;

/**
 * Narrows a run to the scenes whose names match a pattern, so iterating on one scene does not cost a
 * whole suite.
 *
 * <p>The tax this removes is real and it falls hardest on the people this framework is for: a
 * modpack author debugging one failing scene was paying for every other scene in the pack on every
 * edit — minutes per attempt, on a loop that is supposed to be tight. The same is true of anyone
 * writing a scene in the first place.
 *
 * <p><b>A filtered run is not a gate result, and the results file says so.</b> The suite header
 * carries the pattern, and {@code Verdict} reads it back: expected-scenes reconciliation is skipped
 * (it would report every unmatched scene as missing, which is true and useless), the verdict label
 * is suffixed FILTERED, and a pattern that matched nothing is a RED rather than a green run of an
 * empty suite. Without that last rule the most dangerous typo in the system — a pattern matching no
 * scene — would be the one that looks most like success.
 *
 * <p>Canaries are filtered like anything else. A narrow run therefore usually has no framework
 * self-check left in it, which is the other half of why it must never be read as a gate.
 *
 * <p><b>A filtered run does not reproduce an unfiltered run's arena.</b> Arena slots are assigned
 * from the scene list AFTER filtering, so slot == index in whatever survived: a scene that sits at
 * slot 6 in the full suite runs at slot 0 alone, 512 blocks × 6 away, on different ground in a
 * different chunk. That makes this the wrong tool for "does scene X fail on its own?" — it passing
 * alone is not evidence about the run it failed in, and reading it that way sends the investigation
 * after cross-scene interference that was never there. It cost exactly that once. Reproduce with the
 * full suite; use the filter for iterating on a scene you are writing.
 *
 * <h2>Patterns</h2>
 * A comma-separated list; a scene runs if it matches ANY entry. {@code *} is the only metacharacter
 * and matches any run of characters; everything else is literal, so a name containing {@code .}
 * needs no escaping — {@code wd.gearScope} means that scene and not "wd?gearScope".
 *
 * <pre>
 *   wd.gearScope            one scene
 *   wd.client*              a family
 *   wd.gearScope,pack.*     several patterns
 * </pre>
 */
public final class SceneFilter {

    /** JVM system property the game reads; the Gradle plugin sets it from {@code -Pstagewright.scenes}. */
    public static final String PROPERTY = "stagewright.scenes";

    private SceneFilter() {}

    /** The configured pattern list, or null when this run is unfiltered. */
    public static String pattern() {
        String raw = System.getProperty(PROPERTY);
        return raw == null || raw.isBlank() ? null : raw.trim();
    }

    /** {@code scenes} narrowed to those matching {@code patterns}; the list unchanged when null. */
    public static List<Scene> apply(List<Scene> scenes, String patterns) {
        if (patterns == null || patterns.isBlank()) return scenes;
        List<Pattern> compiled = compile(patterns);
        List<Scene> kept = new ArrayList<>();
        for (Scene s : scenes) {
            for (Pattern p : compiled) {
                if (p.matcher(s.name()).matches()) { kept.add(s); break; }
            }
        }
        return kept;
    }

    /** One regex per comma-separated entry, with {@code *} as the only metacharacter. */
    private static List<Pattern> compile(String patterns) {
        List<Pattern> out = new ArrayList<>();
        for (String entry : patterns.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) continue;
            StringBuilder regex = new StringBuilder();
            int from = 0;
            for (int star = trimmed.indexOf('*'); star >= 0; star = trimmed.indexOf('*', from)) {
                regex.append(Pattern.quote(trimmed.substring(from, star))).append(".*");
                from = star + 1;
            }
            regex.append(Pattern.quote(trimmed.substring(from)));
            out.add(Pattern.compile(regex.toString()));
        }
        return out;
    }
}
