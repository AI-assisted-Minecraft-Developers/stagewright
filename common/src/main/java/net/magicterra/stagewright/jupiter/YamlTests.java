package net.magicterra.stagewright.jupiter;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.Gson;
import net.magicterra.stagewright.test.Game;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Tests written in YAML: a file of {@code tests}, each a list of steps run in order. A step is a WorldDriver call
 * ({@code call: method} with {@code params}), a wait ({@code wait}, {@code waitUntil}, {@code settle}), a Java method
 * ({@code java: {class, method}}), a measurement ({@code perf}), or a named
 * step a script registered ({@code name: args}); any of them may carry {@code expect}, checked by
 * {@link Expectations#expect}, and {@code save: name}, which a later step reads as {@code ${name}} or
 * {@code ${name.field}}. There is no expression language: a step that needs one is a named step written in JavaScript.
 */
final class YamlTests {

    private static final Set<String> FILE_KEYS = Set.of("scene", "tests");
    private static final Set<String> TEST_KEYS =
            Set.of("name", "scene", "requires", "dependsOn", "knownIssue", "plot", "given", "when", "then", "steps");
    /** The keys a step may carry beside the one that says what it does. */
    private static final Set<String> MODIFIERS =
            Set.of("params", "expect", "knownIssues", "save", "within", "ticks", "as");
    private static final Gson GSON = new Gson();
    private static final Pattern REFERENCE =Pattern.compile("\\$\\{([A-Za-z_][\\w.]*)}");

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
        run(context, steps, saved, "step ");
    }

    /** {@code steps} in order; a failure names the step by {@code label} and its position. */
    private static void run(ScriptContext context, List<?> steps, Map<String, Object> saved, String label) {
        for (int i = 0; i < steps.size(); i++) {
            if (!(steps.get(i) instanceof Map<?, ?> step)) {
                throw new IllegalArgumentException(label + (i + 1) + ": a step is a map, not " + steps.get(i));
            }
            String verb = verb(step, label + (i + 1));
            try {
                Object result = step(context, verb, step, saved);
                KnownIssues known = step.containsKey("knownIssues") ? new KnownIssues(step.get("knownIssues")) : null;
                if (known != null) result = known.subtract(result);
                if (step.containsKey("expect")) Expectations.expect(result, substitute(step.get("expect"), saved));
                if (known != null) {
                    known.checkAllSeen();
                    context.record(label + (i + 1) + " known issues", known.count());
                }
                if (step.get("save") instanceof String name) saved.put(name, result);
                // A handle a built-in step named is readable as ${name} too.
                saved.putAll(context.handles());
            } catch (AssertionError e) {
                throw new AssertionError(label + (i + 1) + " (" + verb + "): " + e.getMessage(), e);
            } catch (RuntimeException e) {
                throw new IllegalStateException(label + (i + 1) + " (" + verb + "): " + e.getMessage(), e);
            }
        }
    }

    /**
     * {@code perf: {phases: [{name, steps}], warmup, sample, repeat}}: each phase's steps, then its measurement
     * ({@link PerfSampler}); the result is each phase's median window by name, kept in the report too.
     */
    private static Object perf(ScriptContext context, Object value, Map<String, Object> saved) {
        if (!(value instanceof Map<?, ?> perf) || !(perf.get("phases") instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("perf: {phases: [{name, steps}], warmup, sample, repeat}");
        }
        List<PerfSampler.Phase> phases = new ArrayList<>();
        for (Object p : list) {
            if (!(p instanceof Map<?, ?> phase) || !(phase.get("name") instanceof String name)) {
                throw new IllegalArgumentException("perf: a phase is {name, steps}");
            }
            List<?> steps = phase.get("steps") instanceof List<?> s ? s : List.of();
            phases.add(new PerfSampler.Phase(name, () -> run(context, steps, saved, "phase " + name + ", step ")));
        }
        Map<String, Object> result = PerfSampler.measure(context.game(), phases, count(perf, "warmup", 200),
                count(perf, "sample", 600), count(perf, "repeat", 3));
        context.record("perf", result);
        return result;
    }

    private static int count(Map<?, ?> map, String key, int fallback) {
        return map.containsKey(key) ? ScriptContext.ticks(map.get(key), "perf " + key) : fallback;
    }

    private static Object step(ScriptContext context, String verb, Map<?, ?> step, Map<String, Object> saved) {
        Object value = verb.equals("perf") ? null : substitute(step.get(verb), saved);
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
            // Its phases' steps are read raw: a ${name} in them means the value when the phase runs.
            case "perf" -> perf(context, step.get(verb), saved);
            case "java" -> java(context, value, substitute(step.get("params"), saved));
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
                    throw new IllegalArgumentException("'" + verb + "' is neither call, wait, waitUntil, settle, perf, java, a"
                            + " built-in step " + BuiltinSteps.NAMES + " nor one a script registered");
                }
                yield context.step(verb, value);
            }
        };
    }

    /**
     * {@code java: {class, method}} with {@code params}: a public static method of the game's class path, given what
     * its parameters ask for by type ({@link Game}, the params as a {@code Map}), on the test thread. What it returns
     * is the step's result; anything but plain values (map, list, string, number, boolean) is read as JSON would be.
     */
    private static Object java(ScriptContext context, Object value, Object params) {
        if (!(value instanceof Map<?, ?> target) || !(target.get("class") instanceof String className)
                || !(target.get("method") instanceof String methodName) || target.size() != 2) {
            throw new IllegalArgumentException("java: {class, method}");
        }
        Class<?> type;
        try {
            type = Class.forName(className, true, Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("java: no class " + className + " on the game's class path", e);
        }
        List<Method> named = Arrays.stream(type.getMethods())
                .filter(m -> m.getName().equals(methodName) && Modifier.isStatic(m.getModifiers())).toList();
        if (named.size() != 1) {
            throw new IllegalArgumentException("java: " + className + " has " + named.size() + " public static methods"
                    + " named " + methodName + "; it needs exactly one");
        }
        Method method = named.get(0);
        Object[] args = new Object[method.getParameterCount()];
        for (int i = 0; i < args.length; i++) {
            Class<?> p = method.getParameterTypes()[i];
            if (p == Game.class) args[i] = context.game();
            else if (p == Map.class) args[i] = params == null ? Map.of() : params;
            else throw new IllegalArgumentException("java: " + method + " takes a " + p.getName()
                    + "; a step's method takes only Game and Map (its params)");
        }
        Object result;
        try {
            result = method.invoke(null, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException r) throw r;
            if (e.getCause() instanceof Error r) throw r;
            throw new IllegalStateException(e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalArgumentException("java: " + method + " is not accessible", e);
        }
        return plain(result) ? result : GSON.fromJson(GSON.toJsonTree(result), Object.class);
    }

    private static boolean plain(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) return true;
        if (value instanceof List<?> list) return list.stream().allMatch(YamlTests::plain);
        if (value instanceof Map<?, ?> map) {
            return map.keySet().stream().allMatch(String.class::isInstance) && map.values().stream().allMatch(YamlTests::plain);
        }
        return false;
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
