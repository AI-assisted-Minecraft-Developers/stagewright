package net.magicterra.stagewright.jupiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class YamlTestsTest {

    @Test
    void aTestTakesTheFilesSceneUnlessItNamesItsOwn() {
        List<YamlTests.Test> tests = YamlTests.read("t.yaml", """
                scene: survival
                tests:
                  - name: first
                    steps:
                      - wait: 1
                  - name: second
                    scene: [a, b]
                    dependsOn: [first]
                    given:
                      - call: mc.world.block
                        params: { pos: { x: 0, y: 0, z: 0 } }
                        save: block
                    then:
                      - wait: 2
                """);
        assertEquals(List.of("first", "second"), tests.stream().map(YamlTests.Test::name).toList());
        assertEquals("survival", tests.get(0).options().get("scene"));
        assertEquals(List.of("a", "b"), tests.get(1).options().get("scene"));
        assertEquals(List.of("first"), tests.get(1).options().get("dependsOn"));
    }

    @Test
    void mistakesAreRefusedWhenTheFileIsRead() {
        String unknownKey = assertThrows(IllegalArgumentException.class, () -> YamlTests.read("t.yaml", """
                tests:
                  - name: x
                    stpes: []
                """)).getMessage();
        assertTrue(unknownKey.contains("unknown key 'stpes'"), unknownKey);
        String twoVerbs = assertThrows(IllegalArgumentException.class, () -> YamlTests.read("t.yaml", """
                tests:
                  - name: x
                    steps:
                      - { call: mc.a, wait: 3 }
                """)).getMessage();
        assertTrue(twoVerbs.contains("a step does one thing"), twoVerbs);
        assertThrows(IllegalArgumentException.class, () -> YamlTests.read("t.yaml", """
                tests:
                  - name: x
                    given: [ { wait: 1 } ]
                    steps: [ { wait: 1 } ]
                """));
    }

    @Test
    void aSavedValueIsItselfAloneAndTextInsideAString() {
        Map<String, Object> saved = Map.of("plot", Map.of("origin", Map.of("x", 96, "y", 200, "z", -200000)));
        assertEquals(Map.of("x", 96, "y", 200, "z", -200000), YamlTests.substitute("${plot.origin}", saved));
        assertEquals("at 96", YamlTests.substitute("at ${plot.origin.x}", saved));
        assertEquals(List.of(Map.of("p", 200)), YamlTests.substitute(List.of(Map.of("p", "${plot.origin.y}")), saved));
        assertThrows(IllegalArgumentException.class, () -> YamlTests.substitute("${nothing}", saved));
    }
}
