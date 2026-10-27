package net.magicterra.stagewright.jupiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

class ExpectationsTest {

    @Test
    void numbersCompareByValueWhateverTheirType() {
        Expectations.equals(7L, 7.0);
        Expectations.equals(Map.of("n", List.of(1, 2)), Map.of("n", List.of(1.0, 2.0)));
        Expectations.atLeast(3, 3.0);
        assertThrows(AssertionError.class, () -> Expectations.gt(3, 3.0));
        assertThrows(AssertionError.class, () -> Expectations.lt("3", 4));
    }

    @Test
    void containsReadsEachShapeItsOwnWay() {
        Expectations.contains(List.of("a", 2.0), 2L);
        Expectations.contains("diamond_ore", "ore");
        Expectations.contains(Map.of("a", 1, "b", 2), Map.of("a", 1.0));
        Expectations.contains(Map.of("a", 1), "a");
        AssertionError e = assertThrows(AssertionError.class, () -> Expectations.contains(List.of("x"), "y"));
        assertEquals("expected [\"x\"] to contain \"y\"", e.getMessage());
    }

    @Test
    void aFailedIsEmptyShowsTheFirstItemsAndTheCount() {
        List<Integer> many = IntStream.range(0, 25).boxed().toList();
        AssertionError e = assertThrows(AssertionError.class, () -> Expectations.isEmpty(many));
        assertEquals("expected nothing but was [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, …] (25 items)", e.getMessage());
        Expectations.isEmpty(null);
        Expectations.isEmpty(Map.of());
    }

    @Test
    void truthIsJudgedAsJavaScriptWould() {
        assertFalse(Expectations.truthy(0.0));
        assertFalse(Expectations.truthy(""));
        assertFalse(Expectations.truthy(null));
        assertFalse(Expectations.truthy(Double.NaN));
        assertTrue(Expectations.truthy(List.of()));
        assertTrue(Expectations.truthy("0"));
    }
}
