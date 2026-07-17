package net.magicterra.testkit.junit;

/**
 * Thrown when an RPC call returns an error envelope
 * ({@code {"id":N,"error":"<string>"}}) or the transport fails. Carries the method
 * name and the raw error string verbatim so callers can assert on the driver's
 * error shape (mirroring the bare-RPC contract suites).
 */
public class TestkitRpcException extends RuntimeException {
    private final String method;
    private final String error;

    public TestkitRpcException(String method, String error) {
        super(method + " -> error: " + error);
        this.method = method;
        this.error = error;
    }

    /** The RPC method that failed (e.g. {@code mc.observe.player}). */
    public String method() {
        return method;
    }

    /** The raw error string from the envelope (or a transport-failure description). */
    public String error() {
        return error;
    }
}
