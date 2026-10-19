package net.magicterra.stagewright.driver.script;

import java.util.Map;
import java.util.function.BiFunction;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.magicterra.stagewright.contract.DriverBinding;
import net.magicterra.stagewright.contract.DriverJson;
import net.magicterra.stagewright.contract.SceneFailure;
import net.magicterra.stagewright.contract.StageWrightRpc;
import net.magicterra.stagewright.driver.DriverRuntime;
import net.magicterra.worlddriver.script.ScriptCalls;

/** Uses the current validated router and its wire encoding, with the same value shapes as RPC. */
public final class InProcessDriverBinding implements DriverBinding {
    private final BiFunction<String, String, String> invokeJson;

    public InProcessDriverBinding() {
        this((method, params) -> ScriptCalls.standalone(DriverRuntime.requireApi()).invokeJson(method, params));
    }

    InProcessDriverBinding(BiFunction<String, String, String> invokeJson) {
        this.invokeJson = invokeJson;
    }

    /** {@code invokeJson} answers an envelope: {@code {"result":…}}, or {@code {"error":…}} for a refused call. */
    @Override
    public Object route(String method, Map<String, Object> params) {
        JsonObject envelope;
        try {
            envelope = JsonParser.parseString(invokeJson.apply(method, DriverJson.params(params).toString())).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new SceneFailure("driver('" + method + "') failed: " + e.getMessage(), e);
        }
        if (envelope.has("error")) {
            // The envelope's error is the API error itself, what a JSON-RPC error carries as its data.
            JsonObject rpc = new JsonObject();
            JsonObject api = envelope.getAsJsonObject("error");
            rpc.add("message", api.get("message"));
            rpc.add("data", api);
            throw new SceneFailure("driver('" + method + "') failed: " + StageWrightRpc.describeError(rpc));
        }
        return envelope.has("result") ? DriverJson.fromJson(envelope.get("result")) : null;
    }
}
