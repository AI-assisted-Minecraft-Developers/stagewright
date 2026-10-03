package net.magicterra.stagewright.contract;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/** The plain value contract shared by local and websocket driver bindings. */
public final class DriverJson {
    private DriverJson() {}

    public static JsonObject params(Map<String, Object> params) {
        return toJson(params).getAsJsonObject();
    }

    public static JsonElement toJson(Object v) {
        if (v == null) return JsonNull.INSTANCE;
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

    public static Object fromJson(JsonElement e) {
        if (e == null || e.isJsonNull()) return null;
        if (e.isJsonObject()) {
            Map<String, Object> out = new LinkedHashMap<>();
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
            BigDecimal value = p.getAsBigDecimal();
            try { return value.longValueExact(); }
            catch (ArithmeticException fractionalOrLarge) {
                double d = value.doubleValue();
                return Double.isFinite(d) ? d : value;
            }
        }
        return p.getAsString();
    }
}
