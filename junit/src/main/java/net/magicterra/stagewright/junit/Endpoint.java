package net.magicterra.stagewright.junit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The TESTKIT_ENDPOINT descriptor (schema v1) written by
 * {@code scripts/stagewright/t1.py --hold} (T1, {@code integrated_plus_client}) and
 * {@code scripts/stagewright/t2.py --hold} (T2, {@code dedicated_plus_client}). Immutable;
 * carries all eight frozen required keys plus an optional T2-only extension.
 *
 * <p><b>Required (frozen v1, all eight):</b> {@code version}, {@code topology},
 * {@code loader}, {@code rpcHost}, {@code rpcPort}, {@code worldName},
 * {@code holdPid}, {@code writtenAtEpochMs}. A missing REQUIRED key is a corrupt/stale
 * descriptor and fails fast rather than defaulting silently.
 *
 * <p><b>Optional (v1-compatible extension):</b> {@code serverRpcPort} — the dedicated
 * server's RPC port. Present on T2 ({@code dedicated_plus_client}) endpoints, ABSENT on
 * T1 ({@code integrated_plus_client}) endpoints; {@link #serverRpcPort()} is {@code null}
 * when the key is absent. {@code rpcPort} is always the CLIENT-face RPC port (the JUnit UI
 * scenes only touch the client), so {@link #wsUri()} is topology-agnostic.
 *
 * <p><b>Unknown keys are TOLERATED</b> (forward compatibility): the parser reads only the
 * keys it knows, so a future schema addition never breaks an older reader.
 */
public record Endpoint(
        int version,
        String topology,
        String loader,
        String rpcHost,
        int rpcPort,
        String worldName,
        long holdPid,
        long writtenAtEpochMs,
        Integer serverRpcPort) {

    /**
     * Parse a descriptor from its JSON text. Requires all eight frozen keys; parses the
     * optional {@code serverRpcPort} when present ({@code null} when absent); tolerates any
     * unknown keys (they are simply not read).
     */
    public static Endpoint parse(String json) {
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("endpoint descriptor is not valid JSON: " + e.getMessage(), e);
        }
        if (root == null || !root.isJsonObject()) {
            throw new IllegalArgumentException("endpoint descriptor is not a JSON object");
        }
        JsonObject o = root.getAsJsonObject();
        return new Endpoint(
                reqInt(o, "version"),
                reqString(o, "topology"),
                reqString(o, "loader"),
                reqString(o, "rpcHost"),
                reqInt(o, "rpcPort"),
                reqString(o, "worldName"),
                reqLong(o, "holdPid"),
                reqLong(o, "writtenAtEpochMs"),
                optInt(o, "serverRpcPort"));
    }

    /** Read and parse a descriptor from a file. */
    public static Endpoint read(Path file) {
        String json;
        try {
            json = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read endpoint descriptor at " + file, e);
        }
        return parse(json);
    }

    /** The bare-RPC websocket URI this endpoint listens on: {@code ws://host:port/rpc}. */
    public String wsUri() {
        return "ws://" + rpcHost + ":" + rpcPort + "/rpc";
    }

    private static JsonElement req(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) {
            throw new IllegalArgumentException("endpoint descriptor is missing required key '" + key + "'");
        }
        return e;
    }

    private static String reqString(JsonObject o, String key) {
        return req(o, key).getAsString();
    }

    private static int reqInt(JsonObject o, String key) {
        return req(o, key).getAsInt();
    }

    private static long reqLong(JsonObject o, String key) {
        return req(o, key).getAsLong();
    }

    /** Optional int: the parsed value when the key is present + non-null, else {@code null}. */
    private static Integer optInt(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) {
            return null;
        }
        return e.getAsInt();
    }
}
