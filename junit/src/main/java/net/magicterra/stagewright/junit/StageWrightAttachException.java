package net.magicterra.stagewright.junit;

/**
 * Thrown by {@link StageWright#attach()} when no usable live endpoint is reachable:
 * {@code TESTKIT_ENDPOINT}/{@code stagewright.endpoint} unset, the descriptor file
 * missing/unreadable, or the liveness probe ({@code mc.system.version}) failing.
 *
 * <p>Its message always contains the exact operator hint {@link #HINT}, so a developer who
 * runs a UI test without a held endpoint sees, loudly, what to start. The hint names the task
 * pattern rather than one task: which topology to hold is the developer's choice — a client
 * face for UI tests, a dedicated server for anything asserting the bare server surface — and a
 * hint that named a single one would send half its readers to the wrong endpoint.
 */
public class StageWrightAttachException extends RuntimeException {
    /** The exact hint substring every attach failure message must contain. */
    public static final String HINT = "./gradlew stagewright<Topology>Hold";

    public StageWrightAttachException(String message) {
        super(message + " — start a live endpoint with: " + HINT);
    }

    public StageWrightAttachException(String message, Throwable cause) {
        super(message + " — start a live endpoint with: " + HINT, cause);
    }
}
