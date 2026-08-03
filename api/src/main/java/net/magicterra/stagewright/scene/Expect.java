package net.magicterra.stagewright.scene;

import java.util.Collection;
import java.util.Map;
import java.util.function.Predicate;

/**
 * One assertion, in hard or soft mode.
 *
 * <p>Hard ({@link SceneContext#expect}) throws {@link SceneFailure} on the first violation, ending
 * the scene. Soft ({@link SceneContext#check}) records the violation and returns, so a body that
 * probes twenty blocks reports all twenty offenders instead of the first — and the scene still
 * fails, at the end, with every violation in one message.
 *
 * <p>Every failure message states the actual value. That is not politeness: in this framework the
 * failure {@code reason} is the only diagnostic channel that reliably survives, because the async
 * logger drops bursts exactly when a long suite is finishing. An assertion that says "expected true"
 * has thrown away the evidence.
 */
public final class Expect {

    private final SceneContext ctx;
    private final boolean soft;
    private final Object actual;
    private String label;

    Expect(SceneContext ctx, boolean soft, Object actual) {
        this.ctx = ctx;
        this.soft = soft;
        this.actual = actual;
    }

    /** Name what is being asserted, so the message reads about the subject rather than the value. */
    public Expect as(String label) {
        this.label = label;
        return this;
    }

    // ---- equality / identity ----

    public Expect isEqualTo(Object expected) {
        return verify(java.util.Objects.equals(actual, expected), "to equal " + show(expected));
    }

    public Expect isNotEqualTo(Object unexpected) {
        return verify(!java.util.Objects.equals(actual, unexpected), "not to equal " + show(unexpected));
    }

    public Expect isSameAs(Object expected) {
        return verify(actual == expected, "to be the same instance as " + show(expected));
    }

    public Expect isNotSameAs(Object unexpected) {
        return verify(actual != unexpected, "not to be the same instance as " + show(unexpected));
    }

    public Expect isNull() {
        return verify(actual == null, "to be null");
    }

    public Expect isNotNull() {
        return verify(actual != null, "to be non-null");
    }

    // ---- booleans ----

    public Expect isTrue() {
        return verify(Boolean.TRUE.equals(actual), "to be true");
    }

    public Expect isFalse() {
        return verify(Boolean.FALSE.equals(actual), "to be false");
    }

    // ---- numbers ----

    public Expect isGreaterThan(double bound) {
        Double v = asNumber();
        return v == null ? this : verify(v > bound, "to be greater than " + bound);
    }

    public Expect isAtLeast(double bound) {
        Double v = asNumber();
        return v == null ? this : verify(v >= bound, "to be at least " + bound);
    }

    public Expect isLessThan(double bound) {
        Double v = asNumber();
        return v == null ? this : verify(v < bound, "to be less than " + bound);
    }

    public Expect isAtMost(double bound) {
        Double v = asNumber();
        return v == null ? this : verify(v <= bound, "to be at most " + bound);
    }

    public Expect isBetween(double lo, double hi) {
        Double v = asNumber();
        return v == null ? this : verify(v >= lo && v <= hi, "to be within [" + lo + ", " + hi + "]");
    }

    /** Tolerance-based equality — the right shape for anything a mod computes in floating point, and
     *  the only honest shape for a value that depends on RNG or wall-clock. */
    public Expect isCloseTo(double expected, double tolerance) {
        Double v = asNumber();
        return v == null ? this
                : verify(Math.abs(v - expected) <= tolerance,
                        "to be within " + tolerance + " of " + expected
                                + " (off by " + fmt(Math.abs(v - expected)) + ")");
    }

    // ---- membership / size ----

    public Expect isIn(Object... options) {
        for (Object o : options) {
            if (java.util.Objects.equals(actual, o)) return this;
        }
        return verify(false, "to be one of " + show(java.util.Arrays.asList(options)));
    }

    public Expect contains(Object element) {
        if (actual instanceof Collection<?> c) {
            return verify(c.contains(element), "to contain " + show(element));
        }
        if (actual instanceof String str) {
            return verify(str.contains(String.valueOf(element)), "to contain " + show(element));
        }
        return verify(false, "to be a collection or string so that 'contains' means something");
    }

    public Expect isEmpty() {
        Integer n = sizeOf();
        return n == null ? this : verify(n == 0, "to be empty");
    }

    public Expect isNotEmpty() {
        Integer n = sizeOf();
        return n == null ? this : verify(n > 0, "to be non-empty");
    }

    public Expect hasSize(int expected) {
        Integer n = sizeOf();
        return n == null ? this : verify(n == expected, "to have size " + expected);
    }

    /** Escape hatch for anything the vocabulary above does not cover. The description is what the
     *  failure message reads, so write it as a property: {@code "be a powered ME node"}. */
    public Expect satisfies(String description, Predicate<Object> predicate) {
        boolean ok;
        try {
            ok = predicate.test(actual);
        } catch (RuntimeException e) {
            return verify(false, "to " + description + " (the predicate itself threw " + e + ")");
        }
        return verify(ok, "to " + description);
    }

    // ---- plumbing ----

    private Double asNumber() {
        if (actual instanceof Number n) return n.doubleValue();
        verify(false, "to be a number so that a numeric comparison means something");
        return null;
    }

    private Integer sizeOf() {
        if (actual instanceof Collection<?> c) return c.size();
        if (actual instanceof Map<?, ?> m) return m.size();
        if (actual instanceof CharSequence s) return s.length();
        if (actual != null && actual.getClass().isArray()) return java.lang.reflect.Array.getLength(actual);
        verify(false, "to be a collection, map, string or array so that a size means something");
        return null;
    }

    private Expect verify(boolean ok, String expectation) {
        if (!ok) {
            ctx.violation((label == null ? show(actual) : label + " (" + show(actual) + ")")
                    + " expected " + expectation, soft);
        }
        return this;
    }

    private static String show(Object o) {
        if (o == null) return "null";
        if (o instanceof String s) return '"' + s + '"';
        if (o instanceof Double d) return fmt(d);
        if (o instanceof Float f) return fmt(f);
        return String.valueOf(o);
    }

    /** Trim the noise off doubles so a message reads 3.5 rather than 3.4999999999999996. */
    private static String fmt(double d) {
        if (d == Math.rint(d) && !Double.isInfinite(d)) return String.valueOf((long) d);
        return String.format(java.util.Locale.ROOT, "%.6g", d);
    }
}
