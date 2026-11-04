package net.magicterra.stagewright.testengine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.platform.engine.EngineExecutionListener;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestExecutionResult;
import org.opentest4j.TestAbortedException;

/**
 * A throwable from the game, rebuilt here from its relayed {@code failure}: the class it had is in the message, its
 * stack is the game's. An assertion stays an {@link AssertionError} and an abort a {@link TestAbortedException}, so a
 * report sorts them as the game did; anything else is reported as an error.
 */
final class Relayed {

    private static final Pattern FRAME = Pattern.compile("^(?:.*/)?(.+)\\.([^.(]+)\\((?:(.+?)(?::(\\d+))?)?\\)$");

    private Relayed() {}

    static Throwable failure(Map<?, ?> f, boolean aborted) {
        return failure(f, aborted, null);
    }

    /** As {@link #failure(Map, boolean)}, its message followed by {@code note} on a line of its own, when not null. */
    static Throwable failure(Map<?, ?> f, boolean aborted, String note) {
        String className = String.valueOf(f.get("class"));
        String own = f.get("message") instanceof String s ? s : null;
        String message = note == null ? own : (own == null ? "" : own + "\n") + note;
        Throwable cause = f.get("cause") instanceof Map<?, ?> c ? failure(c, false) : null;
        Throwable t;
        if (aborted) {
            t = new TestAbortedException(message, cause);
        } else if (isAssertion(className)) {
            t = new AssertionError(message, cause);
        } else {
            t = new GameException(className + (message == null ? "" : ": " + message), cause);
        }
        t.setStackTrace(frames(f.get("trace")));
        return t;
    }

    /**
     * Report {@code d} as not run for {@code reason}: each test under it as aborted with that reason, rather than
     * skipped. Gradle's JUnit XML keeps the message of an aborted test in its {@code <skipped>} element and drops a
     * skip's reason, so a skipped test read there, as CI reads it, said nothing of why; both count as skipped. A
     * container is started and finished around its children; one with none yet, a parameterized test whose invocations
     * the game registers, is skipped as before, there being no test to carry the reason.
     */
    static void skip(EngineExecutionListener listener, TestDescriptor d, String reason) {
        if (!d.isTest() && d.getChildren().isEmpty()) {
            listener.executionSkipped(d, reason);
            return;
        }
        listener.executionStarted(d);
        if (d.isTest()) {
            TestAbortedException why = new TestAbortedException(reason);
            // The reason is the whole of it; this engine's frames would only bury it in the report.
            why.setStackTrace(new StackTraceElement[0]);
            listener.executionFinished(d, TestExecutionResult.aborted(why));
            return;
        }
        for (TestDescriptor child : d.getChildren()) {
            skip(listener, child, reason);
        }
        listener.executionFinished(d, TestExecutionResult.successful());
    }

    /** A non-assertion throwable from the game, under its own class's name. */
    static final class GameException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        GameException(String message, Throwable cause) {
            super(message, cause);
        }

        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }

    private static boolean isAssertion(String className) {
        return "java.lang.AssertionError".equals(className)
                || className.startsWith("org.opentest4j.")
                || className.startsWith("org.assertj.")
                || className.endsWith("AssertionFailedError");
    }

    private static StackTraceElement[] frames(Object trace) {
        List<StackTraceElement> out = new ArrayList<>();
        if (trace instanceof List<?> lines) {
            for (Object line : lines) {
                Matcher m = FRAME.matcher(String.valueOf(line));
                if (!m.matches()) {
                    continue;
                }
                int number = m.group(4) == null ? -1 : Integer.parseInt(m.group(4));
                out.add(new StackTraceElement(m.group(1), m.group(2), m.group(3), number));
            }
        }
        return out.toArray(StackTraceElement[]::new);
    }
}
