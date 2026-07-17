package net.magicterra.testkit.junit;

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
 * {@code scripts/testkit/t1.py --hold}. Immutable; carries all eight frozen keys.
 *
 * <p>Schema (see t1.py {@code write_endpoint}): {@code version}, {@code topology},
 * {@code loader}, {@code rpcHost}, {@code rpcPort}, {@code worldName},
 * {@code holdPid}, {@code writtenAtEpochMs}. Every key is required — a missing key
 * is a corrupt/stale descriptor and fails fast rather than defaulting silently.
 */
public record Endpoint(
        int version,
        String topology,
        String loader,
        String rpcHost,
        int rpcPort,
        String worldName,
        long holdPid,
        long writtenAtEpochMs) {

    /** Parse a descriptor from its JSON text, requiring all eight keys. */
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
                reqLong(o, "writtenAtEpochMs"));
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
}
