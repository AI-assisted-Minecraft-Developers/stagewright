package net.magicterra.stagewright.jupiter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.magicterra.worlddriver.api.registry.Scripting;
import net.magicterra.worlddriver.api.schema.ApiException;

/**
 * Makes what a Java function handed to a script test throws an error the script can catch.
 *
 * <p>WorldDriver's {@link Scripting} lets a Java function's exception leave the script engine as itself, and Rhino lets
 * a script's {@code catch} see only errors of the script's own: a Java exception runs the script's {@code finally}
 * blocks and leaves the script altogether. So each function goes to the script through a small script function that
 * calls it, and when the Java side answers with a refusal instead of a value, throws a script {@code Error} carrying its
 * message, the Java class as {@code type} and WorldDriver's error code as {@code code}. StageWright links no Rhino of
 * its own, so this is the one way to raise a script error from here.
 *
 * <p>Only exceptions are turned into script errors. An {@link Error}, which is what a failed {@code t.expect} and a
 * {@code t.waitUntil} out of budget throw, still cannot be caught, nor can an abort ({@code TestAbortedException}),
 * nor anything thrown once the test thread has been interrupted. A script error nobody caught fails the test with
 * the Java exception it came from as its cause, so the report still has the Java side's stack.
 */
final class CatchableErrors {

    /** Marks an answer that is a refusal rather than a value; no plain value a function answers carries it. */
    private static final String MARK = "__stagewrightError";

    private static final String FACTORY = "(function (java) { return function () {"
            + " var answer = Function.prototype.apply.call(java, null, arguments);"
            + " if (answer !== null && typeof answer === 'object' && answer." + MARK + " === true) {"
            + " var e = new Error(answer.message); e.type = answer.type;"
            + " if (answer.code !== null) e.code = answer.code; throw e; }"
            + " return answer; }; })";

    private record Raised(String message, RuntimeException exception) {}

    private final Scripting scripting;
    private final List<Raised> raised = new ArrayList<>();
    private Scripting.Function factory;

    CatchableErrors(Scripting scripting) {
        this.scripting = scripting;
    }

    /**
     * {@code java} as a script calls it: what it throws is an error the script can catch. With {@code handles}, the
     * functions inside a map or list it answers are made so too, as a handle's methods are.
     */
    Scripting.Function wrap(Scripting.Function java, boolean handles) {
        Scripting.Function refusing = args -> {
            Object answer;
            try {
                answer = java.call(args);
            } catch (RuntimeException e) {
                if (isAbort(e)) throw new Abort(e);
                if (!catchable(e)) throw e;
                return refusal(e);
            }
            return handles ? handlesIn(answer) : answer;
        };
        return (Scripting.Function) factory().call(refusing);
    }

    /**
     * {@code failure}, with the Java exception behind it as its cause when it is a script error made from one that the
     * script did not catch (or caught and threw again).
     */
    Throwable explain(Throwable failure) {
        if (!(failure instanceof Scripting.ScriptFailure) || failure.getCause() != null || failure.getMessage() == null) {
            return failure;
        }
        synchronized (raised) {
            for (int i = raised.size() - 1; i >= 0; i--) {
                Raised r = raised.get(i);
                if (failure.getMessage().contains(r.message())) {
                    failure.initCause(r.exception());
                    break;
                }
            }
        }
        return failure;
    }

    private Map<String, Object> refusal(RuntimeException e) {
        String message = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
        synchronized (raised) {
            raised.add(new Raised(message, e));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(MARK, true);
        out.put("message", message);
        out.put("type", e.getClass().getName());
        ApiException api = ApiException.find(e);
        out.put("code", api == null || api.error().code() == null ? null : api.error().code().name());
        return out;
    }

    private Object handlesIn(Object value) {
        if (value instanceof Scripting.Function f) return wrap(f, true);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), handlesIn(v)));
            return out;
        }
        if (value instanceof List<?> list) return list.stream().map(this::handlesIn).toList();
        return value;
    }

    /** Nothing thrown on an interrupted thread, which is the test being stopped. */
    private static boolean catchable(RuntimeException e) {
        return !Thread.currentThread().isInterrupted();
    }

    /** Whether {@code e} is an abort ({@code TestAbortedException}), which skips the rest of the test as in Java. */
    static boolean isAbort(Throwable e) {
        for (Class<?> c = e.getClass(); c != null; c = c.getSuperclass()) {
            // By name: in a Fabric development game the class may come from another loader.
            if (c.getName().equals("org.opentest4j.TestAbortedException")) return true;
        }
        return false;
    }

    /**
     * An abort on its way through the script. WorldDriver's engine hands a script's {@code catch} every exception a
     * Java function throws, and only an {@link Error} passes through untouched, as a failed {@code t.expect} does; so
     * an abort travels as this and {@link #unwrapAbort} gives the test its abort back.
     */
    static final class Abort extends Error {
        Abort(RuntimeException abort) {
            super(abort.getMessage(), abort, false, false);
        }
    }

    /** {@code f}, with an abort it throws carried past the script's {@code catch}. */
    static Scripting.Function carryingAborts(Scripting.Function f) {
        return args -> {
            try {
                return f.call(args);
            } catch (RuntimeException e) {
                if (isAbort(e)) throw new Abort(e);
                throw e;
            }
        };
    }

    /** The abort {@code failure} carries, wherever in its causes, or {@code failure} as it is. */
    static Throwable unwrapAbort(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof Abort) return t.getCause();
            if (t.getCause() == t) break;
        }
        return failure;
    }

    private synchronized Scripting.Function factory() {
        if (factory == null) factory = (Scripting.Function) scripting.evaluate("stagewright-catchable", FACTORY, Map.of());
        return factory;
    }
}
