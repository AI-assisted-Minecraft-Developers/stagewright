package net.magicterra.stagewright.jupiter;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import net.magicterra.stagewright.test.DependsOn;
import org.junit.platform.engine.TestExecutionResult;

/**
 * A scene's games one after another on the same world, for {@code afterRestart}. A test runs in round 0 unless it
 * depends on a test after a restart, which puts it one round after the latest of those; a test depending on one without
 * a restart runs in that one's round or later. Each game runs one round, {@value #PROPERTY}, records how its tests
 * ended in {@value #FILE} in the game's {@code stagewright/}, and asks for another game when a later round has tests.
 * The test engine outside deletes the file before a scene's first game.
 *
 * <p>A round a test ended early ({@link Quit}) leaves the tests it had not started to the game after it, on the same
 * save, and every later round moves one game later with them: a test runs in its declared round plus the {@code shift}
 * of such rounds so far, once, the file holding both.
 */
final class Rounds {

    /** Which round this game runs. */
    static final String PROPERTY = "stagewright.junit.round";

    /** How the tests of the rounds before ended, by key. */
    static final String FILE = "rounds.json";

    private static final String OUTCOMES = "outcomes";

    private static final String SHIFT = "shift";

    /** What a test depends on, and whether only after a restart. */
    record Upstream(List<String> keys, boolean afterRestart, World world) {
        Upstream(List<String> keys, boolean afterRestart) {
            this(keys, afterRestart, null);
        }
    }

    /**
     * The save a round runs on, by the name its tests give it, and the save resource a world new to the scene starts
     * from, or empty for a new world.
     */
    record World(String name, String from) {
        private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]+");

        /** What {@code test} declared; null when it names no world. Refused when it is not a world it could run on. */
        static World of(String test, boolean afterRestart, String name, String from) {
            if (name.isEmpty()) {
                if (!from.isEmpty()) {
                    throw new IllegalStateException(test + ": worldFrom needs world, the name of the save it starts");
                }
                return null;
            }
            if (!afterRestart) {
                throw new IllegalStateException(test + ": world needs afterRestart, since a game runs on one world");
            }
            checkName(test, name);
            return new World(name, from);
        }

        /** Refuses a world name that is not a save folder's. */
        static void checkName(String test, String name) {
            if (!NAME.matcher(name).matches() || ".".equals(name) || "..".equals(name)) {
                throw new IllegalStateException(
                        test + ": world '" + name + "' is a save's name, of letters, digits, '_', '-' and '.'");
            }
        }
    }

    /** The world each later round's tests named for it. */
    private static final Map<Integer, World> WORLDS = new ConcurrentHashMap<>();

    /** For each later round, a test that restarts into it naming no world, so expecting the round before's. */
    private static final Map<Integer, String> UNNAMED = new ConcurrentHashMap<>();

    private static final Map<String, Upstream> SCRIPTS = new ConcurrentHashMap<>();

    private static final Map<String, Integer> ROUND_OF = new ConcurrentHashMap<>();

    private static volatile Map<String, String> before = Map.of();

    /** How many games so far ran a round that left tests to the next; every test not run yet moves on by as many. */
    private static volatile int shift;

    private static volatile int latest;

    private Rounds() {}

    /** The round this game runs. */
    static int current() {
        return Integer.getInteger(PROPERTY, 0);
    }

    /** Before any round is asked: the script tests of the scene and what each depends on. */
    static void scripts(Map<String, Upstream> scripts) {
        SCRIPTS.clear();
        SCRIPTS.putAll(scripts);
        ROUND_OF.clear();
        WORLDS.clear();
        UNNAMED.clear();
    }

    /**
     * Once every test's round is known: refuses a round where one test names a world and another, naming none, expects
     * the world of the round before, unless that is the world named.
     */
    static void checkWorlds() {
        int last = 0;
        for (int r : WORLDS.keySet()) {
            last = Math.max(last, r);
        }
        String before = DependsOn.FIRST_WORLD;
        for (int r = 1; r <= last; r++) {
            World named = WORLDS.get(r);
            String unnamed = UNNAMED.get(r);
            if (named != null && unnamed != null && !named.name().equals(before)) {
                throw new IllegalStateException("round " + r + " of the scene's restarts runs on one world, but "
                        + unnamed + " names none, so expects the round before's (" + before + "), and another test"
                        + " of it names " + named.name());
            }
            if (named != null) {
                before = named.name();
            }
        }
    }

    /**
     * The world the tests of {@code round} named, or null when none did: that round runs on the one before's. A round
     * of tests left over is a declared round once more, so it names that round's world, the one open when it was left.
     */
    static World world(int round) {
        return WORLDS.get(round - shift);
    }

    /**
     * Read how the earlier rounds' tests ended, from the file in {@code dir}; none before the first round. A run reads
     * it once, before its tests start.
     */
    static void load(Path dir) {
        latest = 0;
        Path file = dir.resolve(FILE);
        if (current() == 0 || !Files.isRegularFile(file)) {
            before = Map.of();
            shift = 0;
            return;
        }
        try {
            JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            Map<String, String> outcomes = new HashMap<>();
            o.getAsJsonObject(OUTCOMES)
                    .entrySet()
                    .forEach(e -> outcomes.put(e.getKey(), e.getValue().getAsString()));
            before = Map.copyOf(outcomes);
            shift = o.get(SHIFT).getAsInt();
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("could not read how the rounds before ended from " + file, e);
        }
    }

    /** How {@code key} ended in an earlier round, as a status name; null when it did not run in one. */
    static String endedBefore(String key) {
        return before.get(key);
    }

    /**
     * Add this round's outcomes to the file in {@code dir}, but for the tests in {@code left}, which it did not start:
     * when there are any, they and every later round move to the next game.
     */
    static void save(Path dir, Map<String, TestExecutionResult.Status> outcomes, Set<String> left) throws IOException {
        JsonObject ended = new JsonObject();
        before.forEach(ended::addProperty);
        outcomes.forEach((k, v) -> {
            if (!left.contains(k)) {
                ended.addProperty(k, v.name());
            }
        });
        if (!left.isEmpty()) {
            shift++;
            latest++;
        }
        JsonObject all = new JsonObject();
        all.add(OUTCOMES, ended);
        all.addProperty(SHIFT, shift);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(FILE), new Gson().toJson(all), StandardCharsets.UTF_8);
    }

    /**
     * The latest round a test not run yet needs; another game is wanted while it is past {@link #current()}. After
     * {@link #save}, the latest with any tests left over.
     */
    static int latest() {
        return latest;
    }

    /** Whether this game runs {@code key}: a test of its round that no earlier game ran. */
    static boolean runsNow(String key) {
        return of(key) == current();
    }

    /**
     * The round {@code key} runs in, its declared one moved on by the rounds left over so far; for a test an earlier
     * game ran, the round it ran in or a later one. Remembers the latest round any test not run yet needs.
     */
    static int of(String key) {
        if (before.containsKey(key)) {
            return -1;
        }
        int round = of(key, new HashSet<>()) + shift;
        latest = Math.max(latest, round);
        return round;
    }

    private static int of(String key, Set<String> visiting) {
        Integer known = ROUND_OF.get(key);
        if (known != null) {
            return known;
        }
        // A cycle is refused before anything runs; here it only must not recurse forever.
        if (!visiting.add(key)) {
            return 0;
        }
        Upstream up = upstream(key);
        int round = 0;
        for (String k : up.keys()) {
            round = Math.max(round, of(k, visiting));
        }
        if (up.afterRestart()) {
            round++;
        }
        if (up.afterRestart() && up.world() == null) {
            UNNAMED.putIfAbsent(round, key);
        }
        if (up.world() != null) {
            World named = WORLDS.putIfAbsent(round, up.world());
            if (named != null && !named.equals(up.world())) {
                throw new IllegalStateException("round " + round + " of the scene's restarts runs on one world, but "
                        + key + " names " + up.world() + " and another test of it " + named);
            }
        }
        visiting.remove(key);
        ROUND_OF.put(key, round);
        return round;
    }

    /** A script test's from what its file registered; a Java test's from its {@code @DependsOn}, read now. */
    private static Upstream upstream(String key) {
        if (Dependencies.isScript(key)) {
            return SCRIPTS.getOrDefault(key, new Upstream(List.of(), false));
        }
        Dependencies.Ref ref = Dependencies.Ref.of(key);
        List<String> keys = new ArrayList<>();
        boolean afterRestart = false;
        World world = null;
        try {
            Class<?> type =
                    Class.forName(ref.className(), false, Thread.currentThread().getContextClassLoader());
            for (Method m : type.getDeclaredMethods()) {
                if (!m.getName().equals(ref.method())) {
                    continue;
                }
                DependsOn d = m.getAnnotation(DependsOn.class);
                if (d == null) {
                    continue;
                }
                afterRestart |= d.afterRestart();
                World named = World.of(key, d.afterRestart(), d.world(), d.worldFrom());
                world = named != null ? named : world;
                for (Dependencies.Ref r : Dependencies.upstream(type, m)) {
                    keys.add(r.key());
                }
                keys.addAll(Dependencies.scriptUpstream(m));
            }
        } catch (ClassNotFoundException | LinkageError e) {
            // Not this game's: it runs in no round, and whatever depends on it is told it is not in the run.
        }
        return new Upstream(keys, afterRestart, world);
    }
}
