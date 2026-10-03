package net.magicterra.stagewright.driver.script;

import java.util.Map;
import java.util.function.BiFunction;
import com.google.gson.JsonParser;
import net.magicterra.stagewright.contract.DriverBinding;
import net.magicterra.stagewright.contract.DriverJson;
import net.magicterra.stagewright.contract.SceneFailure;
import net.magicterra.stagewright.driver.DriverRuntime;

/** Uses the current validated router and its wire encoding, with the same value shapes as RPC. */
public final class InProcessDriverBinding implements DriverBinding {
    private final BiFunction<String, String, String> invokeJson;

    public InProcessDriverBinding() {
        this((method, params) -> DriverRuntime.requireApi().invokeJson(method, params));
    }

    InProcessDriverBinding(BiFunction<String, String, String> invokeJson) {
        this.invokeJson = invokeJson;
    }

    @Override
    public Object route(String method, Map<String, Object> params) {
        try {
            String result = invokeJson.apply(method, DriverJson.params(params).toString());
            return DriverJson.fromJson(JsonParser.parseString(result));
        } catch (RuntimeException e) {
            throw new SceneFailure("driver('" + method + "') failed: " + e.getMessage(), e);
        }
    }
}
