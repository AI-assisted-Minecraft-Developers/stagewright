package net.magicterra.testkit.junit;

/**
 * Thrown by {@link Testkit#attach()} when no usable live endpoint is reachable:
 * {@code TESTKIT_ENDPOINT}/{@code testkit.endpoint} unset, the descriptor file
 * missing/unreadable, or the liveness probe ({@code mc.system.version}) failing.
 *
 * <p>Its message always contains the exact operator hint
 * {@code python3 scripts/testkit/t1.py --hold} so a developer who runs a UI test
 * without a held endpoint sees, loudly, what to start.
 */
public class TestkitAttachException extends RuntimeException {
    /** The exact hint substring every attach failure message must contain. */
    public static final String HINT = "python3 scripts/testkit/t1.py --hold";

    public TestkitAttachException(String message) {
        super(message + " — start a live endpoint with: " + HINT);
    }

    public TestkitAttachException(String message, Throwable cause) {
        super(message + " — start a live endpoint with: " + HINT, cause);
    }
}
