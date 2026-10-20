package net.magicterra.stagewright.harness.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Which method a call lands on, from values that came off the wire. */
class DispatchTest {
    enum Mode { FAST, SLOW }

    /** A stand-in for the scene context: public, with overloads. */
    public static class Target {
        final List<String> calls = new ArrayList<>();

        public void setBlock(int x, int y, int z, String block) { calls.add("setBlock(int,int,int,String)" + x + y + z + block); }

        public void put(int a) { calls.add("put(int)" + a); }
        public void put(long a) { calls.add("put(long)" + a); }
        public void put(double a) { calls.add("put(double)" + a); }
        public void put(String a) { calls.add("put(String)" + a); }

        public void twice(Object a) { calls.add("twice(Object)" + a + a.getClass().getSimpleName()); }

        public void mode(Mode m) { calls.add("mode " + m); }

        public String join(String first, String... rest) { return first + rest.length; }

        public void list(List<?> items) { calls.add("list" + items); }

        public void nothingToDoWithIt(boolean flag) { calls.add("flag " + flag); }

        public int fails() { throw new IllegalArgumentException("no such block"); }

        public void ambiguous(Integer a, Long b) { }
        public void ambiguous(Long a, Integer b) { }
    }

    private final Target target = new Target();

    private Object call(String name, Object... args) {
        return Dispatch.invoke(target, name, List.of(args));
    }

    @Test
    void aWholeNumberFindsTheIntParameterAndAFractionTheDoubleOne() {
        call("put", 5L);
        call("put", 2.5);

        assertEquals(List.of("put(int)5", "put(double)2.5"), target.calls);
    }

    @Test
    void aStringFindsTheStringOverload() {
        call("put", "hi");

        assertEquals(List.of("put(String)hi"), target.calls);
    }

    @Test
    void aValueTooBigForIntGoesToTheLongOverload() {
        call("put", 9007199254740993L);

        assertEquals(List.of("put(long)9007199254740993"), target.calls);
    }

    @Test
    void aWholeValuedDoubleIsAnIntWhereAnIntIsWanted() {
        call("setBlock", 1L, 2.0, -3L, "x");

        assertEquals(List.of("setBlock(int,int,int,String)12-3x"), target.calls);
    }

    @Test
    void aFractionIsNotAnIntAndSaysSoByFindingNoOverload() {
        Dispatch.Unresolved e = assertThrows(Dispatch.Unresolved.class,
                () -> call("setBlock", 1.5, 2L, 3L, "x"));

        assertTrue(e.getMessage().contains("setBlock(int, int, int, String)"), e.getMessage());
    }

    @Test
    void anObjectParameterTakesAnIntegerAsAnIntAsAScriptEngineWould() {
        call("twice", 7L);

        assertEquals(List.of("twice(Object)7Integer"), target.calls);
    }

    @Test
    void anEnumIsNamedByItsConstant() {
        call("mode", "SLOW");
        call("mode", "fast");

        assertEquals(List.of("mode SLOW", "mode FAST"), target.calls);
    }

    @Test
    void varargsTakeWhatIsLeft() {
        assertEquals("a0", call("join", "a"));
        assertEquals("a2", call("join", "a", "b", "c"));
    }

    @Test
    void aListGoesToAListParameter() {
        call("list", List.of(1L, 2L));

        assertEquals(List.of("list[1, 2]"), target.calls);
    }

    @Test
    void anUnknownNameSaysWhatTheClassHas() {
        Dispatch.Unresolved e = assertThrows(Dispatch.Unresolved.class, () -> call("nope"));

        assertTrue(e.getMessage().contains("no method 'nope' on Target"), e.getMessage());
        assertTrue(e.getMessage().contains("setBlock"), e.getMessage());
    }

    @Test
    void theWrongNumberOfArgumentsListsTheOverloads() {
        Dispatch.Unresolved e = assertThrows(Dispatch.Unresolved.class, () -> call("put"));

        assertTrue(e.getMessage().contains("put(int)") && e.getMessage().contains("put(String)"), e.getMessage());
    }

    @Test
    void aTieIsAnErrorNotACoinToss() {
        Dispatch.Unresolved e = assertThrows(Dispatch.Unresolved.class, () -> call("ambiguous", 1L, 2L));

        assertTrue(e.getMessage().contains("ambiguous"), e.getMessage());
    }

    @Test
    void aMethodOfObjectIsNotReachable() {
        assertThrows(Dispatch.Unresolved.class, () -> call("getClass"));
        assertThrows(Dispatch.Unresolved.class, () -> call("wait"));
    }

    @Test
    void whatTheMethodThrowsIsWhatIsThrown() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> call("fails"));

        assertEquals("no such block", e.getMessage());
    }

    @Test
    void aMethodOnAPrivateClassIsReachedThroughThePublicTypeItImplements() {
        List<Object> hidden = java.util.Collections.unmodifiableList(new ArrayList<>(List.of("a", "b")));

        assertEquals(2, Dispatch.invoke(hidden, "size", List.of()));
    }

    @Test
    void nullGoesToAReferenceParameterAndNotToAPrimitiveOne() {
        List<Object> nothing = java.util.Collections.singletonList(null);

        assertThrows(NullPointerException.class, () -> Dispatch.invoke(target, "twice", nothing));
        assertThrows(Dispatch.Unresolved.class, () -> Dispatch.invoke(target, "nothingToDoWithIt", nothing));
    }
}
