package net.magicterra.stagewright.harness.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** What crosses between the game's objects and the runner's JSON, and what is refused. */
class MarshalTest {
    /** A thing a registry names. */
    record Stone(String id) {}

    /** An object nobody names, with methods of its own that a copy would lose. */
    record Position(int x, int y) {
        int sum() {
            return x + y;
        }
    }

    private static final Marshal.Registries REGISTRIES = new Marshal.Registries() {
        @Override
        public String[] nameOf(Object o) {
            return o instanceof Stone s ? new String[] {"block", s.id()} : null;
        }

        @Override
        public Object find(String kind, String id) {
            if (!kind.equals("block") || !id.startsWith("minecraft:")) {
                throw new IllegalArgumentException("no " + kind + " registered as '" + id + "'");
            }
            return new Stone(id);
        }
    };

    private final Object context = new Object();
    private final Handles handles = new Handles(context);
    private final Marshal marshal = new Marshal(handles, REGISTRIES);

    private static JsonElement json(String text) {
        return JsonParser.parseString(text);
    }

    @Test
    void plainDataGoesAsItself() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("n", 3);
        value.put("big", 9007199254740993L);
        value.put("half", 0.5);
        value.put("ok", true);
        value.put("none", null);
        value.put("list", List.of("a", 2));

        assertEquals(json("{\"n\":3,\"big\":9007199254740993,\"half\":0.5,\"ok\":true,\"none\":null,"
                + "\"list\":[\"a\",2]}"), marshal.encode(value));
    }

    @Test
    void aRegistryEntryGoesAsItsIdAndComesBackAsTheEntry() {
        JsonElement wire = marshal.encode(new Stone("minecraft:stone"));

        assertEquals(json("{\"$id\":\"block\",\"id\":\"minecraft:stone\"}"), wire);
        assertEquals(new Stone("minecraft:stone"), marshal.decode(wire));
    }

    @Test
    void anIdTheRegistryDoesNotHaveIsRefusedWithTheRegistrysWords() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> marshal.decode(json("{\"$id\":\"block\",\"id\":\"pack:nope\"}")));

        assertTrue(e.getMessage().contains("pack:nope"), e.getMessage());
    }

    @Test
    void anObjectGoesAsAHandleNotAsACopyAndTheSameObjectKeepsItsNumber() {
        Position p = new Position(1, 2);

        JsonElement first = marshal.encode(p);
        JsonElement again = marshal.encode(p);

        assertEquals(json("{\"$handle\":1,\"type\":\"Position\"}"), first);
        assertEquals(first, again);
        assertSame(p, marshal.decode(first), "a call on the handle reaches the object, whose methods a copy would not have");
    }

    @Test
    void theContextIsHandleZero() {
        assertEquals(json("{\"$handle\":0,\"type\":\"Object\"}"), marshal.encode(context));
        assertSame(context, marshal.decode(json("{\"$handle\":0}")));
    }

    @Test
    void aNullArgumentGoesAsAMarkerBecauseTheArgumentListCannotHoldABareNull() {
        assertEquals(null, marshal.decode(json("{\"$null\":true}")));
    }

    @Test
    void aHandleFromNowhereIsRefusedByNumber() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> marshal.decode(json("{\"$handle\":7}")));

        assertTrue(e.getMessage().contains("#7"), e.getMessage());
    }

    @Test
    void wholeNumbersComeInAsLongsAndTheRestAsDoubles() {
        List<?> in = (List<?>) marshal.decode(json("[1, 2.5, 3.0, 4e2, 9007199254740993]"));

        assertEquals(List.of(1L, 2.5, 3.0, 400.0, 9007199254740993L), in);
    }

    @Test
    void aMapWithADollarKeyIsWrappedSoItCannotBeTakenForAMarker() {
        Map<String, Object> value = Map.of("$id", "mine");

        JsonElement wire = marshal.encode(value);

        assertEquals(json("{\"$map\":{\"$id\":\"mine\"}}"), wire);
        assertEquals(value, marshal.decode(wire));
    }

    @Test
    void collectionsAndArraysGoAsLists() {
        assertEquals(json("[1,2]"), marshal.encode(new java.util.LinkedHashSet<>(List.of(1, 2))));
        assertEquals(json("[1,2]"), marshal.encode(new int[] {1, 2}));
        assertEquals(json("[\"a\"]"), marshal.encode(new String[] {"a"}));
    }

    @Test
    void aNumberJsonCannotSpellIsRefusedRatherThanSentAsText() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> marshal.encode(Double.NaN));

        assertTrue(e.getMessage().contains("NaN"), e.getMessage());
    }

    @Test
    void theTableIsCappedSoALoopCannotGrowTheServersHeapForever() {
        for (int i = 0; i < Handles.LIMIT - 1; i++) handles.numberFor(new Object());

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> handles.numberFor(new Object()));

        assertTrue(e.getMessage().contains("cannot hold more"), e.getMessage());
    }
}
