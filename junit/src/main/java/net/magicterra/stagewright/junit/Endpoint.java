package net.magicterra.stagewright.junit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * The TESTKIT_ENDPOINT descriptor (schema v1), written by a held game — see
 * {@code EndpointDescriptor} on the mod side and the {@code stagewright<Topology>Hold} tasks that
 * ask for one. Immutable; carries all eight frozen required keys plus three optional extensions.
 *
 * <p><b>Required (frozen v1, all eight):</b> {@code version}, {@code topology},
 * {@code loader}, {@code rpcHost}, {@code rpcPort}, {@code worldName},
 * {@code holdPid}, {@code writtenAtEpochMs}. A missing REQUIRED key is a corrupt/stale
 * descriptor and fails fast rather than defaulting silently.
 *
 * <p><b>{@code rpcPort} is the face of the JVM that wrote this descriptor</b>, and {@code topology}
 * says which face that is. A client run publishes the client face — the only one a UI test can
 * assert against, since {@code mc.client.*} exists nowhere else. A dedicated server publishes its
 * own, which is what a bare-server contract suite wants and what a UI test would find empty. The
 * two-process topology holds both halves and writes one descriptor per run directory.
 *
 * <p><b>Optional (v1-compatible extensions):</b>
 * <ul>
 *   <li>{@code mcpPort} — the same JVM's MCP HTTP port. Bare RPC does not carry the schema catalog:
 *       schemas are advertised through MCP's {@code tools/list}, so anything asserting about a
 *       tool's declared shape needs this instead of {@code rpcPort}. {@link #mcpUri()} builds the
 *       endpoint; {@link #mcpPort()} is {@code null} when the MCP server did not come up.</li>
 *   <li>{@code mcpHost} — where the MCP server bound, written only when the driver was told to bind
 *       it somewhere of its own. {@link #mcpHost()} is {@code null} otherwise, and {@link #mcpUri()}
 *       then uses {@code rpcHost}, which is what both bind to by default.</li>
 *   <li>{@code serverRpcPort} — a client-face descriptor's pointer at the dedicated server it is
 *       joined to. {@link #serverRpcPort()} is {@code null} when the key is absent, which it is for
 *       every descriptor the current holds write; it survives because removing a tolerated optional
 *       key from a frozen schema buys nothing.</li>
 * </ul>
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
        Integer serverRpcPort,
        Integer mcpPort,
        String mcpHost) {

    /** The shape before {@code mcpHost}, kept so code that built one by hand still compiles. */
    public Endpoint(int version, String topology, String loader, String rpcHost, int rpcPort,
                    String worldName, long holdPid, long writtenAtEpochMs, Integer serverRpcPort,
                    Integer mcpPort) {
        this(version, topology, loader, rpcHost, rpcPort, worldName, holdPid, writtenAtEpochMs,
                serverRpcPort, mcpPort, null);
    }

    /**
     * Parse a descriptor from its JSON text. Requires all eight frozen keys; parses the
     * optional {@code serverRpcPort}, {@code mcpPort} and {@code mcpHost} when present
     * ({@code null} when absent); tolerates any unknown keys (they are simply not read).
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
                optInt(o, "serverRpcPort"),
                optInt(o, "mcpPort"),
                optString(o, "mcpHost"));
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
        return "ws://" + uriHost(rpcHost) + ":" + rpcPort + "/rpc";
    }

    private static final Pattern IPV4_LITERAL = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    /**
     * A bind address as something a URI can dial: a wildcard becomes the same family's loopback,
     * because it accepts on every interface but is not itself connectable, and an IPv6 literal is
     * bracketed, because {@code ws://::1:39801} has no way to say where the port starts.
     */
    static String uriHost(String host) {
        String bare = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1) : host;
        boolean v6 = bare.indexOf(':') >= 0;
        // Only literals are inspected: resolving a host name here would be a DNS lookup whose
        // answer the game never saw.
        if (!v6 && !IPV4_LITERAL.matcher(bare).matches()) return bare;
        boolean wildcard;
        try {
            wildcard = InetAddress.getByName(bare).isAnyLocalAddress();
        } catch (UnknownHostException e) {
            wildcard = false;
        }
        if (wildcard) return v6 ? "[::1]" : "127.0.0.1";
        return v6 ? "[" + bare + "]" : bare;
    }

    /**
     * The MCP HTTP URI this endpoint serves {@code tools/list} on: {@code http://host:port/mcp}.
     *
     * @throws IllegalStateException when the descriptor carries no {@code mcpPort} — a caller that
     *         needs the schema catalog cannot fall back to anything, and a null here would surface
     *         as a connection refusal to port 0 several frames later.
     */
    public String mcpUri() {
        if (mcpPort == null) {
            throw new IllegalStateException("endpoint " + topology + " carries no mcpPort — the MCP"
                    + " server did not come up in that JVM, so its schema catalog is unreachable");
        }
        return "http://" + uriHost(mcpHost != null ? mcpHost : rpcHost) + ":" + mcpPort + "/mcp";
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

    /** Optional string: the parsed value when the key is present + non-null, else {@code null}. */
    private static String optString(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) {
            return null;
        }
        return e.getAsString();
    }
}
