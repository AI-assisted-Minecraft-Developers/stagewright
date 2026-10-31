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
 */
final class Rounds {

    /** Which round this game runs. */
    static final String PROPERTY = "stagewright.junit.round";

    /** How the tests of the rounds before ended, by key. */
    static final String FILE = "rounds.json";

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

    private static final Map<String, Upstream> SCRIPTS = new ConcurrentHashMap<>();

    private static final Map<String, Integer> ROUND_OF = new ConcurrentHashMap<>();

    private static volatile Map<String, String> before = Map.of();

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
    }

    /** The world the tests of {@code round} named, or null when none did: that round runs on the one before's. */
    static World world(int round) {
        return WORLDS.get(round);
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
            return;
        }
        try {
            JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            Map<String, String> outcomes = new HashMap<>();
            o.entrySet().forEach(e -> outcomes.put(e.getKey(), e.getValue().getAsString()));
            before = Map.copyOf(outcomes);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("could not read how the rounds before ended from " + file, e);
        }
    }

    /** How {@code key} ended in an earlier round, as a status name; null when it did not run in one. */
    static String endedBefore(String key) {
        return before.get(key);
    }

    /** Add this round's outcomes to the file in {@code dir}. */
    static void save(Path dir, Map<String, TestExecutionResult.Status> outcomes) throws IOException {
        JsonObject all = new JsonObject();
        before.forEach(all::addProperty);
        outcomes.forEach((k, v) -> all.addProperty(k, v.name()));
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(FILE), new Gson().toJson(all), StandardCharsets.UTF_8);
    }

    /** The latest round a test asked about needs; another game is wanted while it is past {@link #current()}. */
    static int latest() {
        return latest;
    }

    /** The round {@code key} runs in, remembering the latest round any test of the scene needs. */
    static int of(String key) {
        int round = of(key, new HashSet<>());
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
