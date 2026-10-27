package net.magicterra.stagewright.testengine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import dev.latvian.mods.rhino.BaseFunction;
import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.ContextFactory;
import dev.latvian.mods.rhino.EvaluatorException;
import dev.latvian.mods.rhino.RhinoException;
import dev.latvian.mods.rhino.Scriptable;
import dev.latvian.mods.rhino.ScriptableObject;
import dev.latvian.mods.rhino.WrappedException;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Reads a script test file without the game: evaluates it once with a {@code test()} that only records what it is
 * given and a {@code step()} that does nothing, never running a test's body. What a file registers therefore cannot
 * depend on the game, which is the rule script tests are written under; a file whose top level reaches for the game
 * fails here, saying so. The game evaluates the same file again and runs the bodies. A YAML test file is read for
 * its test names and scenes only.
 */
final class ScriptScan {

    /** Where script tests live under a test root. */
    static final String DIRECTORY = "stagewright/tests";

    /** One registered test: its name and the scenes it runs in. */
    record Test(String name, List<String> scenes) {}

    /** A file and what it registered, or why it could not be read. */
    record Scanned(String resource, List<Test> tests, String error) {}

    private ScriptScan() {}

    /** Every {@code .js} file under {@value #DIRECTORY} of {@code root}, as class-path resource names, sorted. */
    static List<String> scriptsUnder(Path root) {
        Path dir = root.resolve(DIRECTORY);
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(p -> isScript(p.toString()) && Files.isRegularFile(p))
                    .map(p -> root.relativize(p).toString().replace(root.getFileSystem().getSeparator(), "/"))
                    .sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Scanned scan(String resource, ClassLoader loader) {
        String source;
        try (var in = loader.getResourceAsStream(resource)) {
            if (in == null) return new Scanned(resource, List.of(), "no " + resource + " on the test class path");
            source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return new Scanned(resource, List.of(), "could not read " + resource + ": " + e.getMessage());
        }
        Map<String, Test> tests = new LinkedHashMap<>();
        if (isYaml(resource)) return scanYaml(resource, source, tests);
        try {
            Context cx = new ContextFactory().enter();
            ScriptableObject scope = cx.initStandardObjects();
            ScriptableObject.putProperty(scope, "test", new Recorder(resource, tests), cx);
            ScriptableObject.putProperty(scope, "step", new Ignored(), cx);
            cx.evaluateString(scope, source, resource, 1, null);
        } catch (WrappedException e) {
            // A registration the recorder refused, at the line that made it.
            String where = e.lineNumber() > 0 ? resource + ":" + e.lineNumber() : resource;
            return new Scanned(resource, List.of(), where + ": " + e.getWrappedException().getMessage());
        } catch (EvaluatorException e) {
            // A syntax error: the game's engine is this same Rhino, so the file would not load there either.
            String where = e.lineNumber() > 0 ? resource + ":" + e.lineNumber() : resource;
            return new Scanned(resource, List.of(), where + ": " + (e.details() != null ? e.details() : e.getMessage()));
        } catch (RhinoException e) {
            String where = e.lineNumber() > 0 ? resource + ":" + e.lineNumber() : resource;
            return new Scanned(resource, List.of(), where + ": " + (e.details() != null ? e.details() : e.getMessage())
                    + " (outside the game a file only registers its tests: its top level cannot use the game)");
        } catch (RuntimeException e) {
            return new Scanned(resource, List.of(), resource + ": " + e.getMessage());
        }
        return new Scanned(resource, List.copyOf(tests.values()), null);
    }

    static boolean isScript(String name) {
        return name.endsWith(".js") || isYaml(name);
    }

    static boolean isYaml(String name) {
        return name.endsWith(".yaml") || name.endsWith(".yml");
    }

    /**
     * A YAML file's test names and scenes, a test's own {@code scene} or else the file's. Only what the outer tree
     * needs is read here; the game reads the steps, and refuses what is wrong with them.
     */
    private static Scanned scanYaml(String resource, String source, Map<String, Test> tests) {
        try {
            Object document = new Yaml(new SafeConstructor(new LoaderOptions())).load(source);
            if (!(document instanceof Map<?, ?> file) || !(file.get("tests") instanceof List<?> list)) {
                return new Scanned(resource, List.of(), resource + ": a YAML test file is a map with a list of tests");
            }
            for (Object t : list) {
                if (!(t instanceof Map<?, ?> test) || !(test.get("name") instanceof String name)) {
                    return new Scanned(resource, List.of(), resource + ": each test is a map with a name");
                }
                List<String> scenes = scenes(test.containsKey("scene") ? test.get("scene") : file.get("scene"));
                if (scenes.isEmpty()) {
                    return new Scanned(resource, List.of(), resource + ": test '" + name + "' names no scene: give it"
                            + " or the file a scene");
                }
                if (tests.putIfAbsent(name, new Test(name, scenes)) != null) {
                    return new Scanned(resource, List.of(), resource + ": test '" + name + "' is there twice");
                }
            }
        } catch (YAMLException e) {
            return new Scanned(resource, List.of(), resource + ": " + e.getMessage());
        }
        return new Scanned(resource, List.copyOf(tests.values()), null);
    }

    private static List<String> scenes(Object scene) {
        List<String> scenes = new ArrayList<>();
        if (scene instanceof CharSequence s) scenes.add(s.toString());
        else if (scene instanceof List<?> list) for (Object s : list) scenes.add(String.valueOf(s));
        return scenes;
    }

    /** {@code test(name, options, body)}: the name and {@code options.scene}, one name or a list of them. */
    private static final class Recorder extends BaseFunction {
        private final String resource;
        private final Map<String, Test> tests;

        Recorder(String resource, Map<String, Test> tests) {
            this.resource = resource;
            this.tests = tests;
        }

        @Override
        public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
            if (args.length < 2 || !(args[0] instanceof CharSequence n)) {
                throw new IllegalArgumentException("test(name, options, body) takes a name, options and a function");
            }
            String name = n.toString();
            List<String> scenes = scenes(args.length > 2 && args[1] instanceof Map<?, ?> options ? options.get("scene") : null);
            if (scenes.isEmpty()) {
                throw new IllegalArgumentException("test '" + name + "' names no scene: give it options with scene,"
                        + " a scene's name or a list of them");
            }
            if (tests.putIfAbsent(name, new Test(name, List.copyOf(scenes))) != null) {
                throw new IllegalArgumentException("test '" + name + "' is registered twice in " + resource);
            }
            return null;
        }
    }

    private static final class Ignored extends BaseFunction {
        @Override
        public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
            return null;
        }
    }
}
