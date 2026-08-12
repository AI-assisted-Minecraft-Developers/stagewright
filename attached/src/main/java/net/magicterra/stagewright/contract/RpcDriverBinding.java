package net.magicterra.stagewright.contract;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * {@link DriverBinding} over the RPC websocket — the out-of-process half of {@code driver(...)}.
 *
 * <p>Thin on purpose. It converts JVM values to JSON on the way out and JSON back to plain JVM
 * values on the way in, and does nothing else: no verb list, no schema, no retries. WorldDriver
 * already collapsed every verb to one {@code route(method, params)} and asserts that its three
 * existing transports return byte-identical results; this is a fourth caller of that same route, and
 * a fourth transport that started reinterpreting results would break the property those assertions
 * exist to hold.
 *
 * <p><b>Results come back as plain maps and lists, not as gson nodes.</b> A scene that received a
 * {@code JsonObject} would have to call gson methods on it, and the in-process home hands back a
 * {@code Map} — so the same line of script would need two spellings. Converting here is what makes
 * {@code driver('mc.observe.player').pos.y} mean the same thing in both homes.
 */
public final class RpcDriverBinding implements DriverBinding {

    private final StageWrightRpc rpc;
    private final long timeoutMs;

    public RpcDriverBinding(StageWrightRpc rpc, long timeoutMs) {
        this.rpc = rpc;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public Object route(String method, Map<String, Object> params) {
        JsonObject json = new JsonObject();
        params.forEach((k, v) -> json.add(k, toJson(v)));
        try {
            return fromJson(rpc.call(method, json, timeoutMs));
        } catch (StageWrightRpcException e) {
            // The driver refused. That is a scene failure carrying the driver's own words — not a
            // transport problem and not something to translate, because the message is usually the
            // most specific thing anyone will ever learn about why the verb said no.
            throw new SceneFailure("driver('" + method + "') failed: " + e.getMessage());
        } catch (StageWrightTimeoutException e) {
            throw new SceneFailure("driver('" + method + "') did not answer within " + timeoutMs
                    + "ms — the game is alive enough to hold the socket open and not alive enough to"
                    + " answer, which usually means the server thread is wedged rather than busy");
        }
    }

    private static JsonElement toJson(Object v) {
        if (v == null) return com.google.gson.JsonNull.INSTANCE;
        if (v instanceof JsonElement e) return e;
        if (v instanceof Number n) return new JsonPrimitive(n);
        if (v instanceof Boolean b) return new JsonPrimitive(b);
        if (v instanceof Map<?, ?> m) {
            JsonObject o = new JsonObject();
            m.forEach((k, val) -> o.add(String.valueOf(k), toJson(val)));
            return o;
        }
        if (v instanceof Iterable<?> it) {
            JsonArray a = new JsonArray();
            for (Object o : it) a.add(toJson(o));
            return a;
        }
        return new JsonPrimitive(String.valueOf(v));
    }

    private static Object fromJson(JsonElement e) {
        if (e == null || e.isJsonNull()) return null;
        if (e.isJsonObject()) {
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : e.getAsJsonObject().entrySet()) {
                out.put(entry.getKey(), fromJson(entry.getValue()));
            }
            return out;
        }
        if (e.isJsonArray()) {
            List<Object> out = new ArrayList<>();
            for (JsonElement child : e.getAsJsonArray()) out.add(fromJson(child));
            return out;
        }
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (p.isBoolean()) return p.getAsBoolean();
        if (p.isNumber()) {
            double d = p.getAsDouble();
            // Integral values come back as Long so a script comparing against 3 does not have to
            // reason about 3.0 — Rhino's == would cope, but a record written to the results file
            // would read "3.0" and a reader would wonder what the fraction meant.
            return d == Math.rint(d) && !Double.isInfinite(d) ? (Object) (long) d : (Object) d;
        }
        return p.getAsString();
    }
}
