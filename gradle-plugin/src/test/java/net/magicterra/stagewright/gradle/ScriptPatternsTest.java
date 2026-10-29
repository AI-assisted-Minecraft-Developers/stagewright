package net.magicterra.stagewright.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashSet;
import java.util.List;

import org.junit.jupiter.api.Test;

class ScriptPatternsTest {

    private static List<String> rewritten(String... patterns) {
        return List.copyOf(ScriptPatterns.reachingScripts(new LinkedHashSet<>(List.of(patterns))));
    }

    @Test
    void aPatternNamingAScriptPathStartsWithAWildcard() {
        assertEquals(List.of("*stagewright/tests/validation/42_combat.js"),
                rewritten("stagewright/tests/validation/42_combat.js"));
        assertEquals(List.of("*stagewright/tests/validation/42_combat.js.42_combat"),
                rewritten("stagewright/tests/validation/42_combat.js.42_combat"));
    }

    @Test
    void classPatternsAndPatternsThatAlreadyStartWithAWildcardStayAsTheyAre() {
        assertEquals(List.of("com.example.FirstTest", "*JupiterBodyTest", "*42_combat*", "*validation/42_combat.js"),
                rewritten("com.example.FirstTest", "*JupiterBodyTest", "*42_combat*", "*validation/42_combat.js"));
    }
}
