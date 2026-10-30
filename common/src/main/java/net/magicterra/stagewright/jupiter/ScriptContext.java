package net.magicterra.stagewright.jupiter;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import net.magicterra.stagewright.test.Game;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.api.registry.Scripting;
import net.magicterra.worlddriver.api.thread.ServerThreadGuard;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.extension.ExtensionContext.Store.CloseableResource;
import org.junit.platform.engine.TestDescriptor;

/**
 * The {@code t} a script test's body receives: the Java test context's verbs, as plain functions over plain values, and
 * the same verbs in Java for a YAML test's steps. Waits count server ticks and block the test thread.
 */
final class ScriptContext {

    private static final Gson GSON = new Gson();
    private static final int DEFAULT_WITHIN = 200;
    private static final int DEFAULT_SETTLE = 20;

    private final Game game;
    private final Map<String, Scripting.Function> steps;
    private final JavaSteps javaSteps;
    private final TestDescriptor test;
    /** What the body leased — plots, bots — in the order it did. */
    private final List<CloseableResource> leases = new ArrayList<>();
    /** Positions the test's steps named with {@code as}. */
    private final Map<String, BlockPos> handles = new LinkedHashMap<>();

    private Map<String, Object> script;
    /** What turned the refusals of {@code t.call} and the handles into script errors; null until the script asks. */
    private CatchableErrors errors;
    /** What {@code t.afterTest} registered, in the order it did. */
    private final List<Scripting.Function> afterTest = new ArrayList<>();
    /** {@code t.plot} as Java calls it for a YAML test, its refusals left as they were thrown. */
    private Scripting.Function javaPlot;

    ScriptContext(Game game, Map<String, Scripting.Function> steps, JavaSteps javaSteps, TestDescriptor test) {
        this.game = game;
        this.steps = steps;
        this.javaSteps = javaSteps;
        this.test = test;
    }

    Map<String, Object> asScript() {
        if (script != null) return script;
        script = new LinkedHashMap<>();
        script.put("call", (Scripting.Function) this::call);
        script.put("step", (Scripting.Function) this::step);
        script.put("wait", (Scripting.Function) this::await);
        script.put("waitUntil", (Scripting.Function) this::waitUntil);
        script.put("settle", (Scripting.Function) this::settle);
        script.put("expect", (Scripting.Function) this::expect);
        script.put("check", (Scripting.Function) this::check);
        script.put("record", (Scripting.Function) this::record);
        // t.afterTest(fn): cleanup run when the test ends, passed or not, last registered first.
        script.put("afterTest", (Scripting.Function) a -> {
            if (!(a.length == 1 && a[0] instanceof Scripting.Function fn)) {
                throw new IllegalArgumentException("t.afterTest(fn) takes a function");
            }
            synchronized (afterTest) {
                afterTest.add(fn);
            }
            return null;
        });
        // t.store.put(key, value) and t.store.get(key): what the tests after a restart read.
        Map<String, Object> store = new LinkedHashMap<>();
        store.put("put", (Scripting.Function) a -> {
            BuiltinSteps.run(this, "store.put", Map.of(String.valueOf(a[0]), a.length > 1 ? a[1] : "null"));
            return null;
        });
        store.put("get", (Scripting.Function) a -> BuiltinSteps.run(this, "store.get", String.valueOf(a[0])));
        script.put("store", store);
        script.put("onServer", (Scripting.Function) this::onServer);
        ScriptHandles.install(script, game, leases, test.getDisplayName());
        javaPlot = (Scripting.Function) script.get("plot");
        for (String blocking : BLOCKING) {
            Scripting.Function f = (Scripting.Function) script.get(blocking);
            script.put(blocking, (Scripting.Function) a -> {
                refuseOnServerThread("t." + blocking);
                return f.call(a);
            });
        }
        // An abort skips the test from any of t's functions, catch or not.
        script.replaceAll(
                (name, value) -> value instanceof Scripting.Function f ? CatchableErrors.carryingAborts(f) : value);
        // What t.call and the handles refuse, a script can catch; a failed t.expect or wait it still cannot.
        Scripting scripting = GameImpl.scripting(game);
        if (scripting != null) {
            errors = new CatchableErrors(scripting);
            script.put("call", errors.wrap((Scripting.Function) script.get("call"), false));
            for (String handle : List.of("plot", "bot", "player", "npc", "client")) {
                script.put(handle, errors.wrap((Scripting.Function) script.get(handle), true));
            }
        }
        return script;
    }

    /**
     * After the body, whatever it did: undo its overrides, then give back what it leased, last first. {@code failure}
     * is the body's; what ending throws is added to it, or becomes the failure when the body passed.
     */
    Throwable end(Throwable failure) {
        if (failure != null) failure = CatchableErrors.unwrapAbort(failure);
        if (errors != null && failure != null) failure = errors.explain(failure);
        // First, on this thread: a cleanup may call the game, and may still use what the test leased.
        List<Scripting.Function> cleanups;
        synchronized (afterTest) {
            cleanups = List.copyOf(afterTest);
        }
        failure = runAfterTest(cleanups, failure, errors);
        TestScope scope = TestScope.bound();
        List<Runnable> undo = new ArrayList<>();
        if (scope != null && scope.changedAnything()) undo.add(() -> game.runOnServer(scope::restore));
        AssertionError checks = scope == null ? null : scope.takeChecks();
        // A kept bot goes to a later test only when this one passed and it stands outside the test's plots.
        IllegalStateException misplaced = Handovers.endOfTest(
                leases.stream()
                        .filter(BodyImpl.BotImpl.class::isInstance)
                        .map(BodyImpl.BotImpl.class::cast)
                        .toList(),
                leases.stream()
                        .filter(PlotImpl.class::isInstance)
                        .map(PlotImpl.class::cast)
                        .toList(),
                failure != null || checks != null);
        if (misplaced != null) {
            if (failure == null) failure = misplaced;
            else failure.addSuppressed(misplaced);
        }
        for (int i = leases.size() - 1; i >= 0; i--) {
            CloseableResource lease = leases.get(i);
            undo.add(() -> {
                try {
                    lease.close();
                } catch (Throwable e) {
                    throw new IllegalStateException("could not give back " + lease, e);
                }
            });
        }
        for (Runnable step : undo) {
            try {
                step.run();
            } catch (Throwable t) {
                if (failure == null) failure = t;
                else failure.addSuppressed(t);
            }
        }
        if (checks != null) {
            if (failure == null) failure = checks;
            else failure.addSuppressed(checks);
        }
        TestScope.unbind();
        return failure;
    }

    /**
     * Run what {@code t.afterTest} registered, last first. Each runs whatever the others did: what one throws is added
     * to {@code failure}, or becomes it when the test had passed. Answers the test's failure.
     */
    static Throwable runAfterTest(List<Scripting.Function> cleanups, Throwable failure, CatchableErrors errors) {
        for (int i = cleanups.size() - 1; i >= 0; i--) {
            try {
                cleanups.get(i).call();
            } catch (Throwable t) {
                Throwable thrown = CatchableErrors.unwrapAbort(t);
                if (errors != null) thrown = errors.explain(thrown);
                if (failure == null) failure = thrown;
                else if (failure != thrown) failure.addSuppressed(thrown);
            }
        }
        return failure;
    }

    /** What waits for ticks or leases, and so cannot run inside {@code t.onServer}. */
    private static final List<String> BLOCKING = List.of("wait", "waitUntil", "settle", "plot", "bot", "perf");

    /**
     * {@code t.onServer(fn)}: {@code fn} run on the server thread, as one server task, and its answer. What it calls —
     * {@code t.call}, {@code t.step}, {@code t.check} — runs there and then, within the same tick; WorldDriver runs a
     * call made on the server thread in place rather than queueing it.
     */
    private Object onServer(Object... args) {
        if (!(args.length > 0 && args[0] instanceof Scripting.Function fn)) {
            throw new IllegalArgumentException("t.onServer(fn) takes a function");
        }
        return game.onServer(server -> fn.call());
    }

    /** Refuse {@code what} on the server thread, where its wait would be for the thread that is waiting. */
    private static void refuseOnServerThread(String what) {
        if (ServerThreadGuard.onServerThread()) {
            throw new IllegalStateException(what + " cannot be called inside t.onServer: it waits for the server, and"
                    + " the server thread would be waiting for itself. Call it before or after t.onServer");
        }
    }

    /** {@code t.call(method, params)}: any WorldDriver method, answered as plain values. */
    private Object call(Object... args) {
        return route(game.registry(), "t.call", args);
    }

    /** {@code method} of {@code registry} with {@code args[1]} as its parameters, answered as plain values. */
    static Object route(ApiRegistry registry, String usage, Object[] args) {
        String method = string(args, 0, usage + "(method, params)");
        JsonElement params = args.length > 1 && args[1] != null ? tree(args[1]) : new JsonObject();
        if (!params.isJsonObject())
            throw new IllegalArgumentException(usage + "(" + method + "): params must be an object");
        // A Game's registry already waits as long as a test may for a busy server: see GameImpl.registry.
        return GSON.fromJson(registry.route(method, params.getAsJsonObject()), Object.class);
    }

    /** {@code t.step(name, args)}: a step a script registered, given this context and the arguments. */
    private Object step(Object... args) {
        return step(string(args, 0, "t.step(name, args)"), args.length > 1 ? args[1] : null);
    }

    /** The step named {@code name} — a script's, a built-in one, or a Java {@code @Step} — given {@code args}. */
    Object step(String name, Object args) {
        Scripting.Function body;
        synchronized (steps) {
            body = steps.get(name);
        }
        if (body != null) return body.call(asScript(), args);
        if (BuiltinSteps.NAMES.contains(name)) return BuiltinSteps.run(this, name, args);
        if (javaSteps.has(name)) return javaSteps.run(name, game, args);
        throw new IllegalArgumentException("no step named '" + name + "'; built in: " + BuiltinSteps.NAMES
                + ", registered by scripts: " + steps.keySet() + ", @Step: " + javaSteps.names());
    }

    /** Whether a step is registered, built in, or a Java {@code @Step} as {@code name}. */
    boolean hasStep(String name) {
        synchronized (steps) {
            return steps.containsKey(name) || BuiltinSteps.NAMES.contains(name) || javaSteps.has(name);
        }
    }

    Game game() {
        return game;
    }

    /** Remember {@code pos} as the handle {@code name}, for later steps of this test. */
    void name(String name, BlockPos pos) {
        handles.put(name, pos);
    }

    BlockPos named(String name) {
        BlockPos pos = handles.get(name);
        if (pos == null) {
            throw new IllegalArgumentException(
                    "no handle '" + name + "': a place step names one with as; named so far: " + handles.keySet());
        }
        return pos;
    }

    /** The handles named so far, each as {@code {x, y, z}}. */
    Map<String, Object> handles() {
        Map<String, Object> out = new LinkedHashMap<>();
        handles.forEach((name, pos) -> out.put(name, ScriptHandles.position(pos)));
        return out;
    }

    /** The first plot the test leased; a position inside a plot needs one. */
    PlotImpl plotOrFail() {
        for (CloseableResource lease : leases) if (lease instanceof PlotImpl plot) return plot;
        throw new IllegalArgumentException("[x, y, z] is a position in the test's plot, and it leased none: give the"
                + " test plot: {size}, or call t.plot() first");
    }

    /** {@code t.wait(ticks)}. */
    private Object await(Object... args) {
        game.ticks(ticks(args.length > 0 ? args[0] : null, "t.wait(ticks)"));
        return null;
    }

    /** {@code t.waitUntil(condition, {within, as})}: the first tick {@code condition} answers yes. */
    private Object waitUntil(Object... args) {
        Scripting.Function condition = function(args, "t.waitUntil(condition, options)");
        Map<?, ?> options = options(args);
        poll(() -> Expectations.truthy(condition.call()), within(options), 1, as(options));
        return null;
    }

    /** {@code t.settle(condition, {ticks, within, as})}: {@code condition} held {@code ticks} ticks in a row. */
    private Object settle(Object... args) {
        Scripting.Function condition = function(args, "t.settle(condition, options)");
        Map<?, ?> options = options(args);
        int run = options.containsKey("ticks") ? ticks(options.get("ticks"), "settle's ticks") : DEFAULT_SETTLE;
        poll(() -> Expectations.truthy(condition.call()), within(options), run, as(options));
        return null;
    }

    static int within(Map<?, ?> options) {
        return options.containsKey("within") ? ticks(options.get("within"), "within") : DEFAULT_WITHIN;
    }

    static String as(Map<?, ?> options) {
        return options.get("as") instanceof String as ? as : "the condition";
    }

    /**
     * Check {@code condition} now and once a tick after, until it has held {@code run} checks in a row; fail after
     * {@code within} ticks. Checked on the test thread rather than the server's, as {@link Game#await} does: a script's
     * condition usually calls WorldDriver, and a call that waits for the server must not be made on it.
     */
    void poll(BooleanSupplier condition, int within, int run, String as) {
        int streak = 0;
        for (int tick = 0; ; tick++) {
            streak = condition.getAsBoolean() ? streak + 1 : 0;
            if (streak >= run) return;
            if (tick >= within) {
                throw new AssertionError(as + " did not hold" + (run > 1 ? " for " + run + " ticks in a row" : "")
                        + " within " + within + " ticks");
            }
            game.ticks(1);
        }
    }

    /** {@code method} with {@code params}, as {@code t.call} makes it. */
    Object call(String method, Object params) {
        return route(game.registry(), "call", new Object[] {method, params});
    }

    void ticks(int n) {
        game.ticks(n);
    }

    /** A plot leased for the test, as {@code t.plot(options)} answers it. */
    @SuppressWarnings("unchecked")
    Map<String, Object> plot(Map<String, Object> options) {
        asScript();
        return (Map<String, Object>) javaPlot.call(options);
    }

    void record(String name, Object value) {
        record(new Object[] {name, value});
    }

    /** {@code t.expect(actual)}: the operators, each failing the test when it does not hold. */
    private Object expect(Object... args) {
        Object actual = args.length > 0 ? args[0] : null;
        Map<String, Object> ops = new LinkedHashMap<>();
        ops.put("equals", (Scripting.Function) a -> {
            Expectations.equals(actual, arg(a));
            return null;
        });
        ops.put("contains", (Scripting.Function) a -> {
            Expectations.contains(actual, arg(a));
            return null;
        });
        ops.put("isEmpty", (Scripting.Function) a -> {
            Expectations.isEmpty(actual);
            return null;
        });
        ops.put("gt", (Scripting.Function) a -> {
            Expectations.gt(actual, arg(a));
            return null;
        });
        ops.put("lt", (Scripting.Function) a -> {
            Expectations.lt(actual, arg(a));
            return null;
        });
        ops.put("atLeast", (Scripting.Function) a -> {
            Expectations.atLeast(actual, arg(a));
            return null;
        });
        ops.put("atMost", (Scripting.Function) a -> {
            Expectations.atMost(actual, arg(a));
            return null;
        });
        return ops;
    }

    /**
     * {@code t.check(condition, what)}: a soft assertion, reported when the test ends. {@code condition} is a value, or
     * a function that fails the check by returning false or by a failed {@code t.expect} inside it.
     */
    private Object check(Object... args) {
        Object condition = args.length > 0 ? args[0] : null;
        String what = args.length > 1 ? String.valueOf(args[1]) : "a check";
        if (!(condition instanceof Scripting.Function f)) {
            game.check(Expectations.truthy(condition), what);
            return null;
        }
        game.check(() -> {
            Object result;
            try {
                result = f.call();
            } catch (AssertionError e) {
                throw new AssertionError(what + ": " + e.getMessage(), e);
            }
            if (Boolean.FALSE.equals(result)) throw new AssertionError(what);
        });
        return null;
    }

    /**
     * {@code t.record(name, value)}: a value kept in the report beside the test's result, and in the game's log. A
     * string is kept as it is, an empty one included; anything else as JSON.
     */
    private Object record(Object... args) {
        String name = string(args, 0, "t.record(name, value)");
        if (name.isBlank()) throw new IllegalArgumentException("t.record(name, value): the name must not be blank");
        Object value = args.length > 1 ? args[1] : null;
        Records.publish(
                test.getUniqueId().toString(),
                Records.label(test.getSource().orElse(null), test.getDisplayName()),
                name,
                value instanceof String s ? s : GSON.toJson(value));
        return null;
    }

    /** A script's value as JSON; its numbers are all doubles, so a whole one is written as an integer. */
    private static JsonElement tree(Object v) {
        return switch (v) {
            case null -> JsonNull.INSTANCE;
            case Number n
            when n.doubleValue() == Math.rint(n.doubleValue()) && Math.abs(n.doubleValue()) < 0x1p53 ->
                new JsonPrimitive(n.longValue());
            case Number n -> new JsonPrimitive(n);
            case Boolean b -> new JsonPrimitive(b);
            case String s -> new JsonPrimitive(s);
            case List<?> list -> {
                JsonArray out = new JsonArray();
                for (Object item : list) out.add(tree(item));
                yield out;
            }
            case Map<?, ?> map -> {
                JsonObject out = new JsonObject();
                map.forEach((k, value) -> out.add(String.valueOf(k), tree(value)));
                yield out;
            }
            default ->
                throw new IllegalArgumentException(
                        "t.call: a " + v.getClass().getSimpleName() + " cannot be sent as a parameter");
        };
    }

    private static Object arg(Object[] args) {
        return args.length > 0 ? args[0] : null;
    }

    static String string(Object[] args, int i, String usage) {
        if (args.length > i && args[i] instanceof String s) return s;
        throw new IllegalArgumentException(usage + ": argument " + (i + 1) + " must be a string");
    }

    private static Scripting.Function function(Object[] args, String usage) {
        if (args.length > 0 && args[0] instanceof Scripting.Function f) return f;
        throw new IllegalArgumentException(usage + ": the condition must be a function");
    }

    private static Map<?, ?> options(Object[] args) {
        return args.length > 1 && args[1] instanceof Map<?, ?> m ? m : Map.of();
    }

    static int ticks(Object v, String what) {
        if (v instanceof Number n && n.doubleValue() >= 0 && n.doubleValue() == Math.rint(n.doubleValue())) {
            return n.intValue();
        }
        throw new IllegalArgumentException(what + " is a whole number of ticks, but was " + Expectations.show(v));
    }
}
