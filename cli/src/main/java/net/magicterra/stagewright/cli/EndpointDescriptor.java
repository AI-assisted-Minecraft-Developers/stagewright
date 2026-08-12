package net.magicterra.stagewright.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The descriptor a held run publishes, as read from outside the game.
 *
 * <p>The file is the attach contract: it is how anything out of process learns where to connect
 * without being told a port, and its existence is the signal that the server is far enough along to
 * be talked to. Written by the game, read here and by {@code :stagewright-junit} — three readers, one
 * schema.
 *
 * <p><b>A partially-written file is expected, not exceptional.</b> The reader polls while the game is
 * still starting, so it will sometimes open a file the game is midway through writing. That is why a
 * parse failure returns null (keep waiting) rather than throwing (give up): treating a torn read as a
 * broken endpoint would turn a one-second race into a failed run, intermittently, on slower machines
 * only.
 */
record EndpointDescriptor(String topology, String loader, String rpcHost, int rpcPort) {

    /**
     * The bare-RPC websocket URI this endpoint listens on: {@code ws://host:port/rpc}.
     *
     * <p><b>The {@code /rpc} path is load-bearing and its absence does not look like a wrong path.</b>
     * The driver logs "RPC server listening on ws://127.0.0.1:PORT/rpc"; a handshake to the same host
     * and port with no path simply never completes, so the failure arrives as a TIMEOUT. The first
     * attached run spent five minutes retrying and reporting that the pack must still be starting,
     * which was a confident, well-phrased and entirely wrong diagnosis. {@code Endpoint} in
     * {@code :stagewright-junit} has always built the URI this way — this is the second reader of one
     * descriptor, and it had to learn the same detail separately, which is an argument for the two
     * eventually sharing a parser rather than a schema comment.
     */
    String wsUri() {
        return "ws://" + rpcHost + ":" + rpcPort + "/rpc";
    }

    /** Parse the descriptor, or null if it is absent, torn, or missing what it takes to connect. */
    static EndpointDescriptor read(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        JsonObject o;
        try {
            o = JsonParser.parseString(text).getAsJsonObject();
        } catch (RuntimeException e) {
            return null;
        }
        if (!o.has("rpcPort") || !o.has("rpcHost")) return null;
        return new EndpointDescriptor(
                o.has("topology") ? o.get("topology").getAsString() : null,
                o.has("loader") ? o.get("loader").getAsString() : null,
                o.get("rpcHost").getAsString(),
                o.get("rpcPort").getAsInt());
    }
}
