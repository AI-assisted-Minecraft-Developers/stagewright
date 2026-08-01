package net.magicterra.stagewright.junit;

/**
 * Thrown when a bounded wait elapses: an RPC {@code call} exceeds its timeout, or
 * {@link StageWright#awaitCondition} never observes its predicate turn true within the
 * given {@link java.time.Duration}.
 *
 * <p>Deliberately its OWN type — NOT an {@link AssertionError} — so canary/UI tests
 * can distinguish "the driver timed out / the condition never held" from an ordinary
 * failed assertion.
 */
public class StageWrightTimeoutException extends RuntimeException {
    public StageWrightTimeoutException(String message) {
        super(message);
    }
}
