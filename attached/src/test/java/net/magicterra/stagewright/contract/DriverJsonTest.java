package net.magicterra.stagewright.contract;

import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DriverJsonTest {
    @Test
    void gameSeedsAndEventSequencesDoNotPassThroughDouble() {
        Map<?, ?> result = (Map<?, ?>) DriverJson.fromJson(JsonParser.parseString(
                "{\"seed\":9223372036854775807,\"seq\":9007199254740993,\"fraction\":0.25,\"count\":1e0}"));
        assertEquals(Long.MAX_VALUE, result.get("seed"));
        assertEquals(9007199254740993L, result.get("seq"));
        assertEquals(0.25, result.get("fraction"));
        assertEquals(1L, result.get("count"));
    }

    @Test
    void nestedValuesUseTheSameShapesInBothBindings() {
        Map<String, Object> input = Map.of("items", List.of(Map.of("x", 2L)), "ok", true);
        assertEquals(input, DriverJson.fromJson(DriverJson.params(input)));
    }
}
