package net.magicterra.stagewright.jupiter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Tests written in YAML: a file of {@code tests}, each a list of steps run in order. A step is a WorldDriver call
 * ({@code call: method} with {@code params}), a wait ({@code wait}, {@code waitUntil}, {@code settle}), or a named
 * step a script registered ({@code name: args}); any of them may carry {@code expect}, checked by
 * {@link Expectations#expect}, and {@code save: name}, which a later step reads as {@code ${name}} or
 * {@code ${name.field}}. There is no expression language: a step that needs one is a named step written in JavaScript.
 */
final class YamlTests {

    private static final Set<String> FILE_KEYS = Set.of("scene", "tests");
    private static final Set<String> TEST_KEYS =
            Set.of("name", "scene", "requires", "dependsOn", "knownIssue", "plot", "given", "when", "then", "steps");
    /** The keys a step may carry beside the one that says what it does. */
    private static final Set<String> MODIFIERS = Set.of("params", "expect", "save", "within", "ticks", "as");
    private static final Pattern REFERENCE = Pattern.compile("\\$\\{([A-Za-z_][\\w.]*)}");

    private YamlTests() {}

    /** One test: its name, the options {@code test()} takes, and what running it does. */
    record Test(String name, Map<String, Object> options, ScriptTestEngine.Body body) {}

    static List<Test> read(String resource, String source) {
        Object document = new Yaml(new SafeConstructor(new LoaderOptions())).load(source);
        if (!(document instanceof Map<?, ?> file)) {
            throw new IllegalArgumentException(resource + ": a YAML test file is a map with tests");
        }
        unknownKeys(file, FILE_KEYS, resource);
        if (!(file.get("tests") instanceof List<?> tests)) {
            throw new IllegalArgumentException(resource + ": tests is a list of tests");
        }
        List<Test> out = new ArrayList<>();
        for (Object t : tests) {
            if (!(t instanceof Map<?, ?> test) || !(test.get("name") instanceof String name)) {
                throw new IllegalArgumentException(resource + ": each test is a map with a name");
            }
            String where = resource + ", test '" + name + "'";
            unknownKeys(test, TEST_KEYS, where);
            Map<String, Object> options = new LinkedHashMap<>();
            Object scene = test.containsKey("scene") ? test.get("scene") : file.get("scene");
            if (scene != null) options.put("scene", scene);
            for (String key : List.of("requires", "dependsOn", "knownIssue")) {
                if (test.containsKey(key)) options.put(key, test.get(key));
            }
            Integer plot = plotSize(test.get("plot"), where);
            List<Map<?, ?>> steps = steps(test, where);
            out.add(new Test(name, options, context -> run(context, plot, steps)));
        }
        return out;
    }

    private static Integer plotSize(Object plot, String where) {
        if (plot == null) return null;
        if (plot instanceof Map<?, ?> m && m.keySet().stream().allMatch("size"::equals)) {
            return m.containsKey("size") ? ScriptContext.ticks(m.get("size"), where + ": plot size") : PlotImpl.DEFAULT_SIZE;
        }
        throw new IllegalArgumentException(where + ": plot is {size: n}");
    }

    /** {@code given}, {@code when} and {@code then} in that order, or {@code steps}; never both. */
    private static List<Map<?, ?>> steps(Map<?, ?> test, String where) {
        boolean phased = test.containsKey("given") || test.containsKey("when") || test.containsKey("then");
        if (phased && test.containsKey("steps")) {
            throw new IllegalArgumentException(where + ": given/when/then or steps, not both");
        }
        List<Map<?, ?>> out = new ArrayList<>();
        for (String phase : phased ? List.of("given", "when", "then") : List.of("steps")) {
            Object list = test.get(phase);
            if (list == null) continue;
            if (!(list instanceof List<?> l)) throw new IllegalArgumentException(where + ": " + phase + " is a list of steps");
            for (Object step : l) {
                if (!(step instanceof Map<?, ?> m)) throw new IllegalArgumentException(where + ": a step is a map, not " + step);
                verb(m, where);
                out.add(m);
            }
        }
        if (out.isEmpty()) throw new IllegalArgumentException(where + ": no steps");
        return out;
    }

    /** The one key of a step that says what it does. */
    private static String verb(Map<?, ?> step, String where) {
        List<String> verbs = step.keySet().stream().map(String::valueOf).filter(k -> !MODIFIERS.contains(k)).toList();
        if (verbs.size() != 1) {
            throw new IllegalArgumentException(where + ": a step does one thing, but " + step.keySet() + " names "
                    + (verbs.isEmpty() ? "nothing to do" : verbs));
        }
        return verbs.get(0);
    }

    private static void run(ScriptContext context, Integer plot, List<Map<?, ?>> steps) {
        Map<String, Object> saved = new HashMap<>();
        if (plot != null) saved.put("plot", context.plot(plot));
        for (int i = 0; i < steps.size(); i++) {
            Map<?, ?> step = steps.get(i);
            String verb = verb(step, "");
            try {
                Object result = step(context, verb, step, saved);
                if (step.containsKey("expect")) Expectations.expect(result, substitute(step.get("expect"), saved));
                if (step.get("save") instanceof String name) saved.put(name, result);
                // A handle a built-in step named is readable as ${name} too.
                saved.putAll(context.handles());
            } catch (AssertionError e) {
                throw new AssertionError("step " + (i + 1) + " (" + verb + "): " + e.getMessage(), e);
            } catch (RuntimeException e) {
                throw new IllegalStateException("step " + (i + 1) + " (" + verb + "): " + e.getMessage(), e);
            }
        }
    }

    private static Object step(ScriptContext context, String verb, Map<?, ?> step, Map<String, Object> saved) {
        Object value = substitute(step.get(verb), saved);
        Map<String, Object> options = new HashMap<>();
        for (String key : List.of("within", "ticks", "as")) if (step.containsKey(key)) options.put(key, step.get(key));
        return switch (verb) {
            case "call" -> {
                if (!(value instanceof String method)) throw new IllegalArgumentException("call names a method");
                yield context.call(method, substitute(step.get("params"), saved));
            }
            case "wait" -> {
                context.ticks(ScriptContext.ticks(value, "wait"));
                yield null;
            }
            case "waitUntil", "settle" -> {
                int run = verb.equals("settle")
                        ? (options.containsKey("ticks") ? ScriptContext.ticks(options.get("ticks"), "ticks") : 20) : 1;
                Object[] last = {null};
                context.poll(() -> holds(context, value, saved, last), ScriptContext.within(options), run,
                        ScriptContext.as(options));
                yield last[0];
            }
            default -> {
                if (!context.hasStep(verb)) {
                    throw new IllegalArgumentException("'" + verb + "' is neither call, wait, waitUntil, settle, a"
                            + " built-in step " + BuiltinSteps.NAMES + " nor one a script registered");
                }
                yield context.step(verb, value);
            }
        };
    }

    /** A {@code waitUntil}/{@code settle} condition: {@code {call, params, expect}}, its call made once per check. */
    private static boolean holds(ScriptContext context, Object condition, Map<String, Object> saved, Object[] last) {
        if (!(condition instanceof Map<?, ?> c) || !(c.get("call") instanceof String method) || !c.containsKey("expect")) {
            throw new IllegalArgumentException("a condition is {call: method, params: …, expect: …}");
        }
        last[0] = context.call(method, c.get("params"));
        try {
            Expectations.expect(last[0], c.get("expect"));
            return true;
        } catch (AssertionError notYet) {
            return false;
        }
    }

    /** {@code ${name}} alone is the saved value itself; inside a longer string, its text. */
    static Object substitute(Object value, Map<String, Object> saved) {
        if (value instanceof String s) {
            Matcher whole = REFERENCE.matcher(s);
            if (whole.matches()) return lookup(whole.group(1), saved);
            Matcher m = REFERENCE.matcher(s);
            StringBuilder out = new StringBuilder();
            while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement(String.valueOf(lookup(m.group(1), saved))));
            m.appendTail(out);
            return out.toString();
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object item : list) out.add(substitute(item, saved));
            return out;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), substitute(v, saved)));
            return out;
        }
        return value;
    }

    private static Object lookup(String path, Map<String, Object> saved) {
        String[] keys = path.split("\\.");
        if (!saved.containsKey(keys[0])) {
            throw new IllegalArgumentException("${" + path + "}: nothing was saved as '" + keys[0] + "'; saved: "
                    + saved.keySet());
        }
        Object at = saved.get(keys[0]);
        for (int i = 1; i < keys.length; i++) {
            if (!(at instanceof Map<?, ?> m) || !m.containsKey(keys[i])) {
                throw new IllegalArgumentException("${" + path + "}: no '" + keys[i] + "' in " + Expectations.show(at));
            }
            at = m.get(keys[i]);
        }
        return at;
    }

    private static void unknownKeys(Map<?, ?> map, Set<String> known, String where) {
        for (Object key : map.keySet()) {
            if (!known.contains(String.valueOf(key))) {
                throw new IllegalArgumentException(where + ": unknown key '" + key + "'; known: " + known);
            }
        }
    }
}
