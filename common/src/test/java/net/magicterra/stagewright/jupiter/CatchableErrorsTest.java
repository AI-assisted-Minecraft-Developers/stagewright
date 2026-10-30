package net.magicterra.stagewright.jupiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.api.registry.Scripting;
import net.magicterra.worlddriver.script.HostedScripts;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

/** Against WorldDriver's own {@link Scripting}, the one a script test runs in. */
class CatchableErrorsTest {

    private final Scripting scripting = new HostedScripts(new ApiRegistry());
    private final CatchableErrors errors = new CatchableErrors(scripting);
    private final Scripting.Function refuse = a -> {
        throw new IllegalStateException("refused");
    };

    private Object run(String source, Scripting.Function f) {
        return scripting.evaluate("t.js", source, Map.of("f", f));
    }

    @Test
    void aWrappedFunctionsRefusalIsCaughtWithItsMessageAndType() {
        assertEquals(
                "refused|java.lang.IllegalStateException",
                run("try { f(); 'no' } catch (e) { e.message + '|' + e.type }", errors.wrap(refuse, false)));
    }

    @Test
    void theArgumentsArriveAsTheScriptGaveThem() {
        List<Object[]> seen = new ArrayList<>();
        Scripting.Function f = errors.wrap(
                a -> {
                    seen.add(a);
                    return "ok";
                },
                false);
        assertEquals("ok", run("f(1, 'two')", f));
        assertEquals(2, seen.get(0).length);
        assertEquals("two", seen.get(0)[1]);
        run("f()", f);
        assertEquals(0, seen.get(1).length);
    }

    @Test
    void aHandlesMethodsAreCatchableToo() {
        Scripting.Function handle = errors.wrap(a -> Map.of("moveTo", refuse, "name", "bot"), true);
        assertEquals(
                "bot: refused",
                run("var h = f(); try { h.moveTo(); 'no' } catch (e) { h.name + ': ' + e.message }", handle));
    }

    @Test
    void aFailedExpectationAndAnAbortStillCannotBeCaught() {
        assertThrows(
                AssertionError.class,
                () -> run(
                        "try { f(); } catch (e) { 'caught' }",
                        errors.wrap(
                                a -> {
                                    throw new AssertionError("expected 1 but was 2");
                                },
                                false)));
        TestAbortedException abort = new TestAbortedException("no such capability");
        Scripting.Function aborts = a -> {
            throw abort;
        };
        // Wrapped for catching, and plain, as every other function of t is.
        for (Scripting.Function f : List.of(
                errors.wrap(CatchableErrors.carryingAborts(aborts), false), CatchableErrors.carryingAborts(aborts))) {
            Throwable thrown = assertThrows(Throwable.class, () -> run("try { f(); 'no' } catch (e) { 'caught' }", f));
            assertSame(abort, CatchableErrors.unwrapAbort(thrown));
        }
    }

    @Test
    void anUncaughtOneFailsTheTestWithTheJavaExceptionAsItsCause() {
        Scripting.Function body = (Scripting.Function) run("(function () { f(); })", errors.wrap(refuse, false));
        RuntimeException failure = assertThrows(RuntimeException.class, body::call);
        Throwable explained = errors.explain(failure);
        assertSame(failure, explained);
        assertEquals("refused", explained.getCause().getMessage());
        assertEquals(IllegalStateException.class, explained.getCause().getClass());
    }
}
