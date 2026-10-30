package net.magicterra.stagewright.testengine;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * A scene's declaration, {@code stagewright/scenes/<name>.yaml} on the test class path: the topology it runs in and the
 * world it runs on. The format is in {@code docs/reference/scene-file.md}. A key this version does not apply is refused
 * rather than run without.
 *
 * @param from {@code world.from}: a save to start from instead of a new world, a class path resource under
 *     {@code stagewright/}; null for a new world
 * @param files {@code files}: text written into the game's directory before it starts, by path relative to it
 * @param excludedMods {@code mods.exclude}: mods the game starts without, each a mod id or a jar file name pattern
 */
// One validator for the whole file: keys are checked in one fixed order and the first bad one is reported, so the
// checks stay together. The messages share the "scene '…': " shape by design.
@SuppressWarnings({"checkstyle:javancss", "checkstyle:multiplestringliterals"})
record SceneFile(
        String name, String topology, World world, String from, Map<String, String> files, List<String> excludedMods) {

    static final String DIRECTORY = "stagewright/scenes/";
    /** What {@code world.from} is relative to on the test class path. */
    static final String ROOT = "stagewright/";

    private static final Set<String> TOP = Set.of("topology", "world", "files", "mods");
    private static final Set<String> WORLD = Set.of(
            "preset",
            "seed",
            "structures",
            "gamemode",
            "difficulty",
            "gamerules",
            "datapacks",
            "time",
            "weather",
            "tickRate",
            "mobSpawning",
            "from");
    private static final Set<String> GAMEMODES = Set.of("survival", "creative", "adventure", "spectator");
    private static final Set<String> DIFFICULTIES = Set.of("peaceful", "easy", "normal", "hard");
    private static final Map<String, Long> NAMED_TIMES =
            Map.of("day", 1000L, "noon", 6000L, "night", 13_000L, "midnight", 18_000L);

    /**
     * The world a scene runs on. The creation settings are null when not declared, and the game's own default then
     * holds; the rest always has a value, the quiet world unless declared: midnight with the clock stopped, clear and
     * staying clear, nothing spawning.
     */
    record World(
            String preset,
            String seed,
            Boolean structures,
            String gamemode,
            String difficulty,
            List<String> enablePacks,
            List<String> disablePacks,
            Map<String, Object> gamerules,
            long time,
            boolean timeFrozen,
            boolean raining,
            boolean thundering,
            boolean weatherFrozen,
            Float tickRate,
            boolean mobSpawning) {

        static final World QUIET = new World(
                null,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                Map.of(),
                NAMED_TIMES.get("midnight"),
                true,
                false,
                false,
                true,
                null,
                false);

        /** Whether anything here takes effect only when the world is created. */
        boolean createsWorld() {
            return preset != null
                    || seed != null
                    || structures != null
                    || gamemode != null
                    || difficulty != null
                    || !enablePacks.isEmpty()
                    || !disablePacks.isEmpty();
        }

        /** {@code server.properties} entries for what applies at creation. */
        Map<String, String> serverProperties() {
            Map<String, String> p = new LinkedHashMap<>();
            if (preset != null) {
                p.put("level-type", preset);
            }
            if (seed != null) {
                p.put("level-seed", seed);
            }
            if (structures != null) {
                p.put("generate-structures", structures.toString());
            }
            if (gamemode != null) {
                p.put("gamemode", gamemode);
            }
            if (difficulty != null) {
                p.put("difficulty", difficulty);
            }
            return p;
        }

        /** What the game applies once its world is loaded, as JSON for {@code -Dstagewright.scene.world}. */
        String runtimeJson() {
            StringBuilder out = new StringBuilder(256);
            out.append("{\"gamerules\":{");
            boolean first = true;
            for (Map.Entry<String, Object> rule : gamerules.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append(quote(rule.getKey()))
                        .append(':')
                        .append(
                                rule.getValue() instanceof Boolean b
                                        ? b.toString()
                                        : String.valueOf(((Number) rule.getValue()).longValue()));
            }
            out.append("},\"time\":")
                    .append(time)
                    .append(",\"timeFrozen\":")
                    .append(timeFrozen)
                    .append(",\"raining\":")
                    .append(raining)
                    .append(",\"thundering\":")
                    .append(thundering)
                    .append(",\"weatherFrozen\":")
                    .append(weatherFrozen)
                    .append(",\"mobSpawning\":")
                    .append(mobSpawning);
            if (tickRate != null) {
                out.append(",\"tickRate\":").append(tickRate);
            }
            out.append(",\"enablePacks\":")
                    .append(strings(enablePacks))
                    .append(",\"disablePacks\":")
                    .append(strings(disablePacks))
                    .append('}');
            return out.toString();
        }

        /** One line for the report: every setting the scene runs with, declared or not. */
        String describe() {
            return "preset=" + orDefault(preset) + ", seed=" + orDefault(seed) + ", structures=" + orDefault(structures)
                    + ", gamemode=" + orDefault(gamemode) + ", difficulty=" + orDefault(difficulty)
                    + ", datapacks=+" + enablePacks + " -" + disablePacks + ", gamerules=" + gamerules
                    + ", time=" + time + (timeFrozen ? " (frozen)" : "") + ", weather="
                    + (thundering ? "thunder" : raining ? "rain" : "clear") + (weatherFrozen ? " (frozen)" : "")
                    + ", tickRate=" + orDefault(tickRate) + ", mobSpawning=" + mobSpawning;
        }

        private static String orDefault(Object value) {
            return value == null ? "(the topology's)" : value.toString();
        }
    }

    /** The declaration of scene {@code name}; refuses a missing or malformed one, saying what is wrong. */
    static SceneFile load(String name, ClassLoader loader) {
        String path = DIRECTORY + name + ".yaml";
        Object root;
        try (InputStream in = loader.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException(
                        "scene '" + name + "' has no declaration: expected " + path + " on the test class path");
            }
            root = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
        } catch (IOException e) {
            throw new IllegalStateException("scene '" + name + "': " + path + " could not be read", e);
        }
        Map<?, ?> map = mapping(name, path, root);
        only(name, path, map, TOP);
        if (!(map.get("topology") instanceof String topology) || topology.isBlank()) {
            throw new IllegalStateException("scene '" + name + "': " + path + " names no topology");
        }
        Object world = map.get("world");
        World w = world == null ? World.QUIET : world(name, path + " world", world);
        String from = world == null ? null : from(name, path + " world.from", ((Map<?, ?>) world).get("from"));
        if (from != null && w.createsWorld()) {
            throw new IllegalStateException("scene '" + name + "': " + path + " starts from a save (world.from) and"
                    + " also says how to create its world, which only applies to a new one");
        }
        return new SceneFile(
                name,
                topology,
                w,
                from,
                files(name, path + " files", map.get("files")),
                excludedMods(name, path + " mods", map.get("mods")));
    }

    private static List<String> excludedMods(String name, String at, Object value) {
        if (value == null) {
            return List.of();
        }
        Map<?, ?> mods = mapping(name, at, value);
        if (mods.containsKey("only")) {
            // Every mod not listed would go, its dependencies with it, and a pack's library mods are many.
            throw new IllegalStateException("scene '" + name + "': " + at + ".only is not applied: name what the scene"
                    + " runs without in mods.exclude");
        }
        only(name, at, mods, Set.of("exclude"));
        List<String> exclude = strings(name, at + ".exclude", mods.get("exclude"));
        if (exclude.isEmpty()) {
            throw new IllegalStateException("scene '" + name + "': " + at + ".exclude names no mod");
        }
        return exclude;
    }

    private static String from(String name, String at, Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String s) || s.isBlank()) {
            throw new IllegalStateException("scene '" + name + "': " + at + " is a save's path under " + ROOT);
        }
        relative(name, at, s);
        return s;
    }

    private static Map<String, String> files(String name, String at, Object value) {
        if (value == null) {
            return Map.of();
        }
        Map<String, String> files = new LinkedHashMap<>();
        mapping(name, at, value).forEach((k, v) -> {
            String file = String.valueOf(k);
            relative(name, at + "." + file, file);
            if (!(v instanceof String text)) {
                throw new IllegalStateException("scene '" + name + "': " + at + "." + file + " is the file's text");
            }
            files.put(file, text);
        });
        return files;
    }

    /** Refuses a path that is absolute or climbs out of where it is resolved. */
    private static void relative(String name, String at, String path) {
        Path p = Path.of(path).normalize();
        if (p.isAbsolute() || path.startsWith("/") || p.startsWith("..")) {
            throw new IllegalStateException(
                    "scene '" + name + "': " + at + " is " + path + "; it is a relative path that stays inside");
        }
    }

    // gamemode and difficulty are checked here, ahead of the game rules, so a file with two bad keys reports them
    // in document order; moving them later would change which error is shown.
    @SuppressWarnings({"checkstyle:variabledeclarationusagedistance", "checkstyle:javancss"})
    private static World world(String name, String at, Object raw) {
        Map<?, ?> w = mapping(name, at, raw);
        only(name, at, w, WORLD);
        // from belongs to the scene rather than to the world's settings; load() reads it.
        World q = World.QUIET;
        String gamemode = choice(name, at, w, "gamemode", GAMEMODES);
        String difficulty = choice(name, at, w, "difficulty", DIFFICULTIES);

        Map<String, Object> gamerules = new LinkedHashMap<>();
        if (w.get("gamerules") != null) {
            mapping(name, at + ".gamerules", w.get("gamerules")).forEach((k, v) -> {
                if (!(v instanceof Boolean) && !(v instanceof Integer) && !(v instanceof Long)) {
                    throw new IllegalStateException("scene '" + name + "': " + at + ".gamerules." + k + " is " + v
                            + "; a game rule is true, false or a whole number");
                }
                gamerules.put(String.valueOf(k), v);
            });
        }

        List<String> enable = List.of();
        List<String> disable = List.of();
        if (w.get("datapacks") != null) {
            Map<?, ?> packs = mapping(name, at + ".datapacks", w.get("datapacks"));
            only(name, at + ".datapacks", packs, Set.of("enable", "disable"));
            enable = strings(name, at + ".datapacks.enable", packs.get("enable"));
            disable = strings(name, at + ".datapacks.disable", packs.get("disable"));
        }

        long time = q.time();
        boolean timeFrozen = q.timeFrozen();
        if (w.get("time") != null) {
            Map<?, ?> t = mapping(name, at + ".time", w.get("time"));
            only(name, at + ".time", t, Set.of("at", "frozen"));
            Object atValue = t.get("at");
            if (atValue instanceof String s && NAMED_TIMES.containsKey(s)) {
                time = NAMED_TIMES.get(s);
            } else if (atValue instanceof Number n) {
                time = n.longValue();
            } else if (atValue != null) {
                throw new IllegalStateException("scene '" + name + "': " + at + ".time.at is " + atValue
                        + "; it is one of " + new TreeSet<>(NAMED_TIMES.keySet()) + " or a tick of the day");
            }
            timeFrozen = bool(name, at + ".time.frozen", t.get("frozen"), timeFrozen);
        }

        boolean raining = q.raining();
        boolean thundering = q.thundering();
        boolean weatherFrozen = q.weatherFrozen();
        if (w.get("weather") != null) {
            Map<?, ?> wt = mapping(name, at + ".weather", w.get("weather"));
            only(name, at + ".weather", wt, Set.of("clear", "thunder", "frozen"));
            thundering = bool(name, at + ".weather.thunder", wt.get("thunder"), false);
            raining = thundering || !bool(name, at + ".weather.clear", wt.get("clear"), true);
            weatherFrozen = bool(name, at + ".weather.frozen", wt.get("frozen"), weatherFrozen);
        }

        Float tickRate = null;
        if (w.get("tickRate") instanceof Number n) {
            tickRate = n.floatValue();
        } else if (w.get("tickRate") != null) {
            throw new IllegalStateException("scene '" + name + "': " + at + ".tickRate is not a number");
        }

        Object structures = w.get("structures");
        if (structures != null && !(structures instanceof Boolean)) {
            throw new IllegalStateException("scene '" + name + "': " + at + ".structures is true or false");
        }
        Object preset = w.get("preset");
        if (preset != null && !(preset instanceof String)) {
            throw new IllegalStateException("scene '" + name + "': " + at + ".preset is a world preset id");
        }
        Object seed = w.get("seed");
        return new World(
                (String) preset,
                seed == null ? null : String.valueOf(seed),
                (Boolean) structures,
                gamemode,
                difficulty,
                enable,
                disable,
                gamerules,
                time,
                timeFrozen,
                raining,
                thundering,
                weatherFrozen,
                tickRate,
                bool(name, at + ".mobSpawning", w.get("mobSpawning"), q.mobSpawning()));
    }

    private static Map<?, ?> mapping(String name, String at, Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalStateException("scene '" + name + "': " + at + " is not a mapping");
        }
        return map;
    }

    private static void only(String name, String at, Map<?, ?> map, Set<String> applied) {
        Set<String> unapplied = new TreeSet<>();
        for (Object key : map.keySet()) {
            if (!applied.contains(String.valueOf(key))) {
                unapplied.add(String.valueOf(key));
            }
        }
        if (!unapplied.isEmpty()) {
            throw new IllegalStateException("scene '" + name + "': " + at + " declares " + unapplied
                    + ", which this StageWright does not apply; it applies " + new TreeSet<>(applied));
        }
    }

    private static String choice(String name, String at, Map<?, ?> map, String key, Set<String> allowed) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        if (!allowed.contains(String.valueOf(value))) {
            throw new IllegalStateException("scene '" + name + "': " + at + "." + key + " is " + value
                    + "; it is one of " + new TreeSet<>(allowed));
        }
        return String.valueOf(value);
    }

    private static boolean bool(String name, String at, Object value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        throw new IllegalStateException("scene '" + name + "': " + at + " is true or false");
    }

    private static List<String> strings(String name, String at, Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalStateException("scene '" + name + "': " + at + " is a list");
        }
        List<String> out = new ArrayList<>();
        list.forEach(v -> out.add(String.valueOf(v)));
        return List.copyOf(out);
    }

    private static String strings(List<String> values) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(quote(values.get(i)));
        }
        return out.append(']').toString();
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
