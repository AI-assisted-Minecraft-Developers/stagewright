package net.magicterra.stagewright.contract;

/**
 * The socket could not carry the call: a handshake that failed, timed out or was interrupted, a
 * broken socket, or an interrupted wait for a reply. Not a {@link StageWrightRpcException}, so
 * nothing written to handle a refusal can take one for it.
 */
public class StageWrightTransportException extends RuntimeException {
    private final String method;
    private final String error;

    public StageWrightTransportException(String method, String error) {
        super(method + " -> error: " + error);
        this.method = method;
        this.error = error;
    }

    /** The RPC method the socket could not carry, or {@code <connect>} for the handshake. */
    public String method() {
        return method;
    }

    /** What went wrong with the transport. */
    public String error() {
        return error;
    }
}
