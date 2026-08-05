package net.magicterra.stagewright.junit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads the driver's advertised tool schemas from an endpoint's MCP HTTP face.
 *
 * <p><b>Why this is not on the RPC socket.</b> Bare RPC dispatches verbs; it does not publish the
 * catalog. Schemas reach a client through MCP's {@code tools/list}, rendered from the SAME typed
 * {@code Schema} objects the route layer's validator enforces — one source, two readers. So a test
 * asserting what a tool <i>declares</i> (closed object, key count, hidden-from-catalog) has to come
 * in this way, while the test asserting what the validator <i>does</i> with that declaration goes
 * over RPC. Pairing the two is the point: a schema that is advertised but not enforced, or enforced
 * but not advertised, fails one of them.
 *
 * <p>Reading it in-JVM was tried and does not work either: this Rhino fork strips the
 * {@code Packages} global, so {@code mc.script.eval} cannot resolve the catalog class by name.
 */
public final class McpCatalog {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private McpCatalog() {}

    /**
     * Every tool the endpoint advertises, by name. A tool that is deliberately hidden (the
     * {@code mc.test.*} harness verbs) is absent here while still being routable and validated —
     * asserting that absence is the only remaining gate on what "hidden" means.
     *
     * @throws IllegalStateException if the endpoint carries no MCP port, the request fails, or the
     *         response is not a {@code tools/list} envelope. Never returns an empty map to mean
     *         "could not read": a caller cannot tell those apart, and one of them is a green test.
     */
    public static Map<String, JsonObject> tools(Endpoint endpoint) {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";
        HttpResponse<String> resp;
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()) {
            HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint.mcpUri()))
                    .header("Content-Type", "application/json")
                    .timeout(TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("tools/list request to " + endpoint.mcpUri() + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted reading tools/list", e);
        }
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("tools/list HTTP " + resp.statusCode() + ": "
                    + truncate(resp.body()));
        }
        JsonElement root = JsonParser.parseString(resp.body());
        JsonElement tools = root.isJsonObject() && root.getAsJsonObject().has("result")
                ? root.getAsJsonObject().getAsJsonObject("result").get("tools")
                : null;
        if (tools == null || !tools.isJsonArray()) {
            throw new IllegalStateException("tools/list shape drifted: " + truncate(resp.body()));
        }
        Map<String, JsonObject> byName = new LinkedHashMap<>();
        for (JsonElement e : tools.getAsJsonArray()) {
            if (!e.isJsonObject()) continue;
            JsonObject tool = e.getAsJsonObject();
            JsonElement name = tool.get("name");
            if (name != null && name.isJsonPrimitive()) {
                byName.put(name.getAsString(), tool);
            }
        }
        return byName;
    }

    private static String truncate(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }
}
