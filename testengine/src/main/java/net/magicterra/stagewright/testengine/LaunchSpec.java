package net.magicterra.stagewright.testengine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.magicterra.stagewright.engine.Json;

/**
 * How to start the games of one topology, written by whatever configured the test run (the Gradle plugin, the CLI)
 * as {@code <topology>.json} in the directory {@value #PROPERTY} names. The format is in
 * {@code docs/reference/launch-spec.md}.
 */
record LaunchSpec(String topology, Path gameDirectory, Path worldDirectory, boolean cleanWorld, int timeoutMinutes,
                  int stallMinutes, boolean server, Leaks leaks, Process primary, Process companion) {

    static final String PROPERTY = "stagewright.launches";

    /**
     * {@code skip} when a topology without a spec is to be reported as one this run cannot launch rather than as a
     * mistake, as the CLI does, whose user chooses what to install; {@code fail}, the default, as the Gradle plugin
     * does, where a missing spec means the build is not configured for that topology.
     */
    static final String MISSING = "stagewright.launches.missing";

    /** A topology this run cannot launch, by its writer's word or, under {@value #MISSING}, by having no spec. */
    static final class Unlaunchable extends IllegalStateException {
        Unlaunchable(String message) {
            super(message);
        }
    }

    /** What a game the agent had to force down after its run does to its scene; reported either way. */
    enum Leaks {
        /** The scene fails. */
        FAIL,
        /** The scene's verdict is its tests'; for a launcher whose own threads never end, such as Architectury Loom's in dev. */
        REPORT;

        static Leaks parse(Object value) {
            if (value == null) return FAIL;
            return switch (String.valueOf(value)) {
                case "fail" -> FAIL;
                case "report" -> REPORT;
                default -> throw new IllegalArgumentException("'leaks' is '" + value + "'; it is 'fail' or 'report'");
            };
        }
    }

    /**
     * One JVM: {@code java jvmArgs… mainClass args…}, in {@code workingDirectory}; {@code mainClass} is null when an
     * argument file in {@code jvmArgs} names it, as a NeoForge server's does.
     */
    record Process(String java, List<String> jvmArgs, String mainClass, List<String> args, Path workingDirectory,
                   Map<String, String> environment) {

        /**
         * The command line, with {@code extra} JVM arguments placed after the run's own system properties and before
         * any argument file, so an extra {@code -D} overrides the run's and the JVM, not the launcher, reads it.
         */
        List<String> command(List<String> extra) {
            List<String> command = new ArrayList<>();
            command.add(java);
            int at = jvmArgs.size();
            for (int i = 0; i < jvmArgs.size(); i++) {
                String a = jvmArgs.get(i);
                if (a.startsWith("@") || a.equals("-cp") || a.equals("-classpath") || a.equals("--class-path")) {
                    at = i;
                    break;
                }
            }
            command.addAll(jvmArgs.subList(0, at));
            command.addAll(extra);
            command.addAll(jvmArgs.subList(at, jvmArgs.size()));
            if (mainClass != null) command.add(mainClass);
            command.addAll(args);
            return command;
        }
    }

    /** The spec for {@code topology}; refuses when none was written for it, saying where it looked. */
    static LaunchSpec load(String topology) {
        String dir = System.getProperty(PROPERTY);
        if (dir == null || dir.isBlank()) {
            throw new IllegalStateException("no launch specs: -D" + PROPERTY + " is not set. The Gradle plugin and the"
                    + " CLI set it; a test task configured by hand must too");
        }
        Path file = Path.of(dir).resolve(topology + ".json");
        if (!Files.isRegularFile(file)) {
            String message = "topology '" + topology + "' has no launch spec: " + file + " is missing";
            if ("skip".equals(System.getProperty(MISSING))) throw new Unlaunchable(message);
            throw new IllegalStateException(message);
        }
        Map<?, ?> json;
        try {
            json = (Map<?, ?>) Json.parse(Files.readString(file));
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("topology '" + topology + "': " + file + " could not be read", e);
        }
        if (json.get("unavailable") instanceof String why) {
            throw new Unlaunchable("topology '" + topology + "' cannot be launched in this run: " + why);
        }
        try {
            return parse(topology, json);
        } catch (RuntimeException e) {
            throw new IllegalStateException("topology '" + topology + "': " + file + " could not be read", e);
        }
    }

    static LaunchSpec parse(String topology, Map<?, ?> o) {
        Object companion = o.get("companion");
        Path gameDirectory = Path.of(string(o, "gameDirectory"));
        return new LaunchSpec(topology, gameDirectory,
                gameDirectory.resolve(o.get("worldDirectory") instanceof String w ? w : "world"),
                !Boolean.FALSE.equals(o.get("cleanWorld")),
                number(o, "timeoutMinutes", 20), number(o, "stallMinutes", 5), Boolean.TRUE.equals(o.get("server")),
                Leaks.parse(o.get("leaks")), process((Map<?, ?>) o.get("primary")),
                companion instanceof Map<?, ?> c ? process(c) : null);
    }

    private static Process process(Map<?, ?> p) {
        if (p == null) throw new IllegalArgumentException("no primary process");
        Map<String, String> env = new LinkedHashMap<>();
        if (p.get("environment") instanceof Map<?, ?> e) e.forEach((k, v) -> env.put(String.valueOf(k), String.valueOf(v)));
        return new Process(string(p, "java"), strings(p.get("jvmArgs")),
                p.get("mainClass") instanceof String main ? main : null,
                strings(p.get("args")), Path.of(string(p, "workingDirectory")), env);
    }

    private static String string(Map<?, ?> o, String key) {
        if (!(o.get(key) instanceof String s)) throw new IllegalArgumentException("'" + key + "' is missing");
        return s;
    }

    private static int number(Map<?, ?> o, String key, int fallback) {
        return o.get(key) instanceof Number n ? n.intValue() : fallback;
    }

    private static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) list.forEach(v -> out.add(String.valueOf(v)));
        return out;
    }
}
