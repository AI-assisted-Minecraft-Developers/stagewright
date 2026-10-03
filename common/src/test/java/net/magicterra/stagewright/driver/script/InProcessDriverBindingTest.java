package net.magicterra.stagewright.driver.script;

import java.util.Map;
import net.magicterra.stagewright.contract.SceneFailure;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InProcessDriverBindingTest {
    @Test
    void wireValuesStayPlainAndLargeIntegersRemainExact() {
        InProcessDriverBinding binding = new InProcessDriverBinding((method, params) -> {
            assertEquals("sample.read", method);
            assertEquals("{\"seed\":9007199254740993}", params);
            return "{\"seed\":9007199254740993,\"pos\":{\"x\":1},\"fraction\":0.25}";
        });
        Map<?, ?> result = (Map<?, ?>) binding.route("sample.read", Map.of("seed", 9007199254740993L));
        assertEquals(9007199254740993L, result.get("seed"));
        assertEquals(Map.of("x", 1L), result.get("pos"));
        assertEquals(0.25, result.get("fraction"));
    }

    @Test
    void readinessAndRouteFailuresKeepTheirCause() {
        IllegalStateException cause = new IllegalStateException("WorldDriver API is not ready");
        InProcessDriverBinding binding = new InProcessDriverBinding((method, params) -> { throw cause; });
        SceneFailure failure = assertThrows(SceneFailure.class, () -> binding.route("sample.read", Map.of()));
        assertSame(cause, failure.getCause());
        assertTrue(failure.getMessage().contains("API is not ready"));
    }
}
