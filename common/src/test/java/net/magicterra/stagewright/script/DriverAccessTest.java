package net.magicterra.stagewright.script;

import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.ContextFactory;
import dev.latvian.mods.rhino.ScriptableObject;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DriverAccessTest {
    @Test
    void scriptsReceiveNativeObjectsAndPassPlainJvmParametersThroughTheBinding() {
        ContextFactory factory = new ContextFactory();
        Context cx = factory.enter();
        ScriptableObject scope = cx.initStandardObjects();
        AtomicReference<Map<String, Object>> seen = new AtomicReference<>();
        DriverAccess.install(cx, scope, (method, params) -> {
            assertEquals("sample.read", method);
            seen.set(params);
            return Map.of("items", List.of(Map.of("x", 7)), "ok", true);
        });
        Object result = cx.evaluateString(scope,
                "var r = driver('sample.read', {nested:{name:'scene'}, values:[1,2]});"
                + " r.items[0].x === 7 && r.ok && Object.keys(r).length === 2;", "test", 1, null);
        assertEquals(Boolean.TRUE, result);
        assertEquals(Map.of("name", "scene"), seen.get().get("nested"));
        assertInstanceOf(List.class, seen.get().get("values"));
    }
}
