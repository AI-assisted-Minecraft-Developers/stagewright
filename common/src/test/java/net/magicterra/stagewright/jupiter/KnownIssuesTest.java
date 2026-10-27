package net.magicterra.stagewright.jupiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class KnownIssuesTest {

    @Test
    void aKnownItemLeavesEveryListItIsIn() {
        KnownIssues known = new KnownIssues(List.of(
                Map.of("item", "minecraft:wander_add_map", "reason", "dungeons-and-taverns 4.4.4"),
                Map.of("id", "x:y", "reason", "plain values too")));
        Object result = Map.of(
                "uncraftable", List.of(Map.of("item", "minecraft:wander_add_map", "recipe", "r1"), Map.of("item", "a:b")),
                "ids", List.of("x:y", "z:z"));
        assertEquals(Map.of("uncraftable", List.of(Map.of("item", "a:b")), "ids", List.of("z:z")),
                known.subtract(result));
        known.checkAllSeen();
    }

    @Test
    void anIssueThatTookNothingOutFailsSoItGetsRemoved() {
        KnownIssues known = new KnownIssues(List.of(Map.of("item", "fixed:long_ago", "reason", "was broken")));
        known.subtract(Map.of("uncraftable", List.of()));
        AssertionError stale = assertThrows(AssertionError.class, known::checkAllSeen);
        assertTrue(stale.getMessage().startsWith("known issues no longer seen, so remove them: "), stale.getMessage());
    }

    @Test
    void anIssueNeedsAReason() {
        assertThrows(IllegalArgumentException.class, () -> new KnownIssues(List.of(Map.of("item", "a:b"))));
    }
}
