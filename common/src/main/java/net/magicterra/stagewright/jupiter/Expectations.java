package net.magicterra.stagewright.jupiter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The assertion operators script tests share, over plain values (null, booleans, numbers, strings, lists, string-keyed
 * maps). Numbers compare as numbers whatever their Java type, so a script's {@code 7} equals a method's {@code 7L}.
 * Each operator throws an {@link AssertionError} saying what it saw; a collection is shown by its first
 * {@value #SHOWN} items.
 */
final class Expectations {

    static final int SHOWN = 10;

    private Expectations() {}

    static void equals(Object actual, Object expected) {
        if (!same(actual, expected)) fail("expected " + show(expected) + " but was " + show(actual));
    }

    /** A list holding an equal item, a string holding the substring, a map holding every entry of a map given. */
    static void contains(Object actual, Object expected) {
        boolean holds = switch (actual) {
            case List<?> list -> list.stream().anyMatch(item -> same(item, expected));
            case String s when expected instanceof String part -> s.contains(part);
            case Map<?, ?> map when expected instanceof Map<?, ?> part ->
                    part.entrySet().stream().allMatch(e -> map.containsKey(e.getKey())
                            && same(map.get(e.getKey()), e.getValue()));
            case Map<?, ?> map -> map.containsKey(String.valueOf(expected));
            case null, default -> throw new AssertionError("contains needs a list, string or map, but was "
                    + show(actual));
        };
        if (!holds) fail("expected " + show(actual) + " to contain " + show(expected));
    }

    static void isEmpty(Object actual) {
        boolean empty = switch (actual) {
            case null -> true;
            case Collection<?> c -> c.isEmpty();
            case Map<?, ?> m -> m.isEmpty();
            case String s -> s.isEmpty();
            default -> throw new AssertionError("isEmpty needs a list, string or map, but was " + show(actual));
        };
        if (!empty) fail("expected nothing but was " + show(actual) + sizeOf(actual));
    }

    static void gt(Object actual, Object bound) {
        if (!(number(actual, "gt") > number(bound, "gt"))) fail("expected more than " + show(bound) + " but was " + show(actual));
    }

    static void lt(Object actual, Object bound) {
        if (!(number(actual, "lt") < number(bound, "lt"))) fail("expected less than " + show(bound) + " but was " + show(actual));
    }

    static void atLeast(Object actual, Object bound) {
        if (!(number(actual, "atLeast") >= number(bound, "atLeast"))) fail("expected at least " + show(bound) + " but was " + show(actual));
    }

    static void atMost(Object actual, Object bound) {
        if (!(number(actual, "atMost") <= number(bound, "atMost"))) fail("expected at most " + show(bound) + " but was " + show(actual));
    }

    static final Set<String> OPERATORS = Set.of("equals", "contains", "empty", "gt", "lt", "atLeast", "atMost", "anyOf", "allOf");

    /**
     * A YAML test's {@code expect}: an operator's name alone ({@code empty}), or a map whose keys are operators, applied
     * to {@code actual}, or field paths ({@code result.count}), each holding an expectation of that field. A failure
     * names the field it was about.
     */
    static void expect(Object actual, Object spec) {
        expect(actual, spec, "");
    }

    private static void expect(Object actual, Object spec, String where) {
        if (spec instanceof String op) {
            apply(op, actual, true, where);
            return;
        }
        if (!(spec instanceof Map<?, ?> map) || map.isEmpty()) {
            throw new IllegalArgumentException(at(where) + "an expectation is an operator's name or a map of them, not "
                    + show(spec));
        }
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String key = String.valueOf(e.getKey());
            if (OPERATORS.contains(key)) apply(key, actual, e.getValue(), where);
            else expect(field(actual, key, where), e.getValue(), where.isEmpty() ? key : where + "." + key);
        }
    }

    private static void apply(String op, Object actual, Object arg, String where) {
        try {
            switch (op) {
                case "equals" -> equals(actual, arg);
                case "contains" -> contains(actual, arg);
                case "empty" -> {
                    if (Boolean.FALSE.equals(arg)) {
                        if (actual == null || (actual instanceof Collection<?> c && c.isEmpty())
                                || (actual instanceof Map<?, ?> m && m.isEmpty()) || "".equals(actual)) {
                            fail("expected something but was " + show(actual));
                        }
                    } else {
                        isEmpty(actual);
                    }
                }
                case "gt" -> gt(actual, arg);
                case "lt" -> lt(actual, arg);
                case "atLeast" -> atLeast(actual, arg);
                case "atMost" -> atMost(actual, arg);
                case "anyOf" -> {
                    List<String> misses = new ArrayList<>();
                    for (Object each : alternatives(arg, op)) {
                        try {
                            expect(actual, each, "");
                            return;
                        } catch (AssertionError miss) {
                            misses.add(miss.getMessage());
                        }
                    }
                    fail("none of anyOf held: " + String.join("; ", misses));
                }
                case "allOf" -> {
                    for (Object each : alternatives(arg, op)) expect(actual, each, "");
                }
                default -> throw new IllegalArgumentException("no operator '" + op + "'; there are " + OPERATORS);
            }
        } catch (AssertionError e) {
            if (where.isEmpty()) throw e;
            throw new AssertionError(at(where) + e.getMessage(), e.getCause());
        }
    }

    private static List<?> alternatives(Object arg, String op) {
        if (arg instanceof List<?> list && !list.isEmpty()) return list;
        throw new IllegalArgumentException(op + " takes a list of expectations, not " + show(arg));
    }

    /** The field {@code path} (dot-separated) of a map; a missing one fails, naming what was there. */
    private static Object field(Object actual, String path, String where) {
        Object at = actual;
        String walked = where;
        for (String key : path.split("\\.")) {
            walked = walked.isEmpty() ? key : walked + "." + key;
            if (!(at instanceof Map<?, ?> map) || !map.containsKey(key)) {
                fail(walked + ": absent from " + show(at));
            }
            at = ((Map<?, ?>) at).get(key);
        }
        return at;
    }

    private static String at(String where) {
        return where.isEmpty() ? "" : where + ": ";
    }

    /** Equal as plain values: numbers by value, lists in order, maps by their entries. */
    static boolean same(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) return x.doubleValue() == y.doubleValue();
        if (a instanceof List<?> x && b instanceof List<?> y) {
            if (x.size() != y.size()) return false;
            Iterator<?> i = x.iterator();
            Iterator<?> j = y.iterator();
            while (i.hasNext()) if (!same(i.next(), j.next())) return false;
            return true;
        }
        if (a instanceof Map<?, ?> x && b instanceof Map<?, ?> y) {
            if (x.size() != y.size()) return false;
            for (Map.Entry<?, ?> e : x.entrySet()) {
                if (!y.containsKey(e.getKey()) || !same(e.getValue(), y.get(e.getKey()))) return false;
            }
            return true;
        }
        return a == null ? b == null : a.equals(b);
    }

    /** Whether a script's answer counts as yes, as JavaScript would judge it. */
    static boolean truthy(Object v) {
        return switch (v) {
            case null -> false;
            case Boolean b -> b;
            case Number n -> n.doubleValue() != 0 && !Double.isNaN(n.doubleValue());
            case String s -> !s.isEmpty();
            default -> true;
        };
    }

    private static double number(Object v, String operator) {
        if (v instanceof Number n) return n.doubleValue();
        throw new AssertionError(operator + " compares numbers, but was given " + show(v));
    }

    static String show(Object v) {
        return switch (v) {
            case null -> "null";
            case String s -> '"' + s + '"';
            case Number n when n.doubleValue() == Math.rint(n.doubleValue()) && !Double.isInfinite(n.doubleValue()) ->
                    String.valueOf(n.longValue());
            case List<?> list -> list.stream().limit(SHOWN).map(Expectations::show)
                    .collect(Collectors.joining(", ", "[", list.size() > SHOWN ? ", …]" : "]"));
            case Map<?, ?> map -> map.entrySet().stream().limit(SHOWN).map(e -> e.getKey() + ": " + show(e.getValue()))
                    .collect(Collectors.joining(", ", "{", map.size() > SHOWN ? ", …}" : "}"));
            default -> String.valueOf(v);
        };
    }

    private static String sizeOf(Object v) {
        int size = v instanceof Collection<?> c ? c.size() : v instanceof Map<?, ?> m ? m.size() : -1;
        return size > SHOWN ? " (" + size + " items)" : "";
    }

    private static void fail(String message) {
        throw new AssertionError(message);
    }
}
