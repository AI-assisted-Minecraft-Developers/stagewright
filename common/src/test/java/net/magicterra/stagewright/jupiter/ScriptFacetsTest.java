package net.magicterra.stagewright.jupiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import net.magicterra.stagewright.test.Body;
import net.magicterra.worlddriver.api.registry.Scripting;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** A facet as a script sees it: plain values in, converted to what each method takes, and plain values out. */
class ScriptFacetsTest {

    public static final class Facet {
        public String last = "";

        public Facet put(Body body, BlockPos pos, int count) {
            last = body.name() + "@" + pos.toShortString() + "x" + count;
            return this;
        }

        public String put(Body body, int slot) {
            return body.name() + "#" + slot;
        }

        public List<Integer> pair(double a) {
            return List.of((int) a, (int) a + 1);
        }

        public BlockPos where() {
            return new BlockPos(1, 2, 3);
        }

        public String joined(String head, Object... rest) {
            return head + rest.length;
        }
    }

    private static Object call(Map<String, Object> facet, String name, Object... args) {
        return ((Scripting.Function) facet.get(name)).call(args);
    }

    @Test
    void argumentsAreConvertedAndOverloadsChosenByCount() {
        Facet target = new Facet();
        Map<String, Object> facet = ScriptFacets.wrap(null, target);
        Map<String, Object> bot = Map.of("name", "sw_bot_1", "ref", "player:sw_bot_1");

        Object chained = call(facet, "put", bot, Map.of("x", 1.0, "y", 64.0, "z", -3.0), 2.0);

        assertSame(facet, chained);
        assertEquals("sw_bot_1@1, 64, -3x2", target.last);
        assertEquals("sw_bot_1#4", call(facet, "put", bot, 4.0));
        assertEquals("a2", call(facet, "joined", "a", 1.0, "b"));
    }

    @Test
    void resultsComeBackPlain() {
        Map<String, Object> facet = ScriptFacets.wrap(null, new Facet());
        assertEquals(List.of(5, 6), call(facet, "pair", 5.0));
        assertEquals(Map.of("x", 1, "y", 2, "z", 3), call(facet, "where"));
    }

    @Test
    void argumentsThatFitNoOverloadNameTheShapes() {
        Map<String, Object> facet = ScriptFacets.wrap(null, new Facet());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> call(facet, "put", "x"));
        assertEquals(true, e.getMessage().contains("put(Body, int)"), e.getMessage());
    }
}
