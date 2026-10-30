package net.magicterra.stagewright.jupiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.api.registry.Scripting;
import net.magicterra.worlddriver.script.HostedScripts;
import org.junit.jupiter.api.Test;

/** {@code t.afterTest}: script functions run when a test ends, against WorldDriver's own {@link Scripting}. */
class ScriptAfterTestTest {

    private final Scripting scripting = new HostedScripts(new ApiRegistry());
    private final List<String> ran = new ArrayList<>();

    @SuppressWarnings("unchecked")
    private List<Scripting.Function> cleanups(String array) {
        Scripting.Function log = a -> {
            ran.add(String.valueOf(a[0]));
            return null;
        };
        Scripting.Function fail = a -> {
            throw new AssertionError("expected 0 but was 1");
        };
        return (List<Scripting.Function>) scripting.evaluate("t.js", array, Map.of("log", log, "fail", fail));
    }

    @Test
    void theyRunLastFirstAndAThrowDoesNotStopTheOthers() {
        List<Scripting.Function> cleanups = cleanups("[function () { log('first'); },"
                + " function () { log('second'); throw new Error('cleanup broke'); },"
                + " function () { log('third'); fail(); }]");
        Throwable failure = ScriptContext.runAfterTest(cleanups, null, null);
        assertEquals(List.of("third", "second", "first"), ran);
        // The first to throw, which ran last-registered first, is the failure; the next rides along.
        assertTrue(failure instanceof AssertionError, String.valueOf(failure));
        assertEquals(1, failure.getSuppressed().length);
        assertTrue(
                failure.getSuppressed()[0].getMessage().contains("cleanup broke"),
                failure.getSuppressed()[0].getMessage());
    }

    @Test
    void aTestThatAlreadyFailedKeepsItsFailureWithTheCleanupsAdded() {
        AssertionError body = new AssertionError("the body failed");
        Throwable failure =
                ScriptContext.runAfterTest(cleanups("[function () { throw new Error('cleanup broke'); }]"), body, null);
        assertSame(body, failure);
        assertEquals(1, body.getSuppressed().length);
    }

    @Test
    void cleanupsThatPassLeaveAPassingTestPassing() {
        assertNull(ScriptContext.runAfterTest(cleanups("[function () { log('a'); }]"), null, null));
        assertEquals(List.of("a"), ran);
    }
}
