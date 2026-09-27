package net.magicterra.stagewright.contract;

/**
 * The driver answered with a failure: an error envelope, or a command {@code StageWright.exec} saw
 * fail. A call the socket could not carry throws {@link StageWrightTransportException} instead, and
 * one whose reply does not come in time {@link StageWrightTimeoutException}.
 */
public class StageWrightRpcException extends RuntimeException {
    private final String method;
    private final String error;

    public StageWrightRpcException(String method, String error) {
        super(method + " -> error: " + error);
        this.method = method;
        this.error = error;
    }

    /** The RPC method that failed (e.g. {@code mc.observe.player}). */
    public String method() {
        return method;
    }

    /** The envelope's raw error string, or what {@code StageWright.exec} found wrong. */
    public String error() {
        return error;
    }
}
