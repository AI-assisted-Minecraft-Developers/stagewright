package net.magicterra.stagewright.testengine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
        String className = String.valueOf(f.get("class"));
        String message = f.get("message") instanceof String s ? s : null;
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

    /** A non-assertion throwable from the game, under its own class's name. */
    static final class GameException extends RuntimeException {
        GameException(String message, Throwable cause) {
            super(message, cause);
        }

        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }

    private static boolean isAssertion(String className) {
        return className.equals("java.lang.AssertionError")
                || className.startsWith("org.opentest4j.")
                || className.startsWith("org.assertj.")
                || className.endsWith("AssertionFailedError");
    }

    private static StackTraceElement[] frames(Object trace) {
        List<StackTraceElement> out = new ArrayList<>();
        if (trace instanceof List<?> lines) {
            for (Object line : lines) {
                Matcher m = FRAME.matcher(String.valueOf(line));
                if (!m.matches()) continue;
                int number = m.group(4) == null ? -1 : Integer.parseInt(m.group(4));
                out.add(new StackTraceElement(m.group(1), m.group(2), m.group(3), number));
            }
        }
        return out.toArray(StackTraceElement[]::new);
    }
}
