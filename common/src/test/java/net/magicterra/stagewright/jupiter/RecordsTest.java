package net.magicterra.stagewright.jupiter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.support.descriptor.ClasspathResourceSource;
import org.junit.platform.engine.support.descriptor.MethodSource;

class RecordsTest {

    private final List<String> sent = new ArrayList<>();

    private JsonObject only() {
        assertEquals(1, sent.size(), sent.toString());
        return JsonParser.parseString(sent.get(0)).getAsJsonObject();
    }

    @Test
    void aRecordIsATestEventCarryingItsNameAndValue() {
        Records.publish(sent::add, "[engine:x]/[test:t]", "t", "sum", "2");
        JsonObject o = only();
        assertEquals("record", o.get("type").getAsString());
        assertEquals("[engine:x]/[test:t]", o.get("id").getAsString());
        assertEquals("sum", o.get("name").getAsString());
        assertEquals("2", o.get("value").getAsString());
    }

    @Test
    void aBlankValueIsKeptAsTheEmptyString() {
        // A JUnit report entry refuses it outright, which is what killed a test recording an empty result.
        Records.publish(sent::add, "id", "t", "none found", "  ");
        assertEquals("", only().get("value").getAsString());
    }

    @Test
    void theLogNamesAScriptTestByFileAndAJavaTestByClassAndMethod() {
        assertEquals(
                "stagewright/tests/crops.js#cropDrying",
                Records.label(ClasspathResourceSource.from("stagewright/tests/crops.js"), "cropDrying"));
        assertEquals(
                "com.example.FurnaceTest#smelts",
                Records.label(MethodSource.from("com.example.FurnaceTest", "smelts", ""), "smelts()"));
        assertEquals("a\\nb", Records.oneLine("a\nb"));
    }
}
