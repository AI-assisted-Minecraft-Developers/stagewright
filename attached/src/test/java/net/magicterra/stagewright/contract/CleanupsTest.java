package net.magicterra.stagewright.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class CleanupsTest {

    private static AttachedContext ctx() {
        return new AttachedContext("pack.scene", (method, params) -> null, 1_000, System::currentTimeMillis);
    }

    @Test
    void aWrappedFailureKeepsTheWordsOfWhoeverWrappedIt() {
        // The innermost cause would read "disk full" and drop what the author said went wrong.
        assertEquals("unexpected IllegalStateException: could not unpin autoEat",
                Cleanups.reasonOf(new IllegalStateException("could not unpin autoEat", new IOException("disk full"))));
    }

    @Test
    void aFailureWithOnlyACauseReadsAsTheCauseWithoutItsClassName() {
        assertEquals("unexpected RuntimeException: disk full",
                Cleanups.reasonOf(new RuntimeException(new IOException("disk full"))));
    }

    @Test
    void aFailureWithNoMessageNamesItsClassOnce() {
        assertEquals("unexpected NullPointerException", Cleanups.reasonOf(new NullPointerException()));
    }

    @Test
    void ourOwnFailureIsFoundThroughAWrapper() {
        assertEquals("the chest is still there",
                Cleanups.reasonOf(new RuntimeException("Wrapped", new SceneFailure("the chest is still there"))));
        assertEquals("skipped mid-teardown: no curios",
                Cleanups.reasonOf(new RuntimeException("Wrapped", new SceneSkipped("no curios"))));
    }

    @Test
    void aCleanupThatDrainsAgainDoesNotReopenRegistration() {
        // The inner drain returning must not let the rest of the outer one take registrations, or a
        // helper that registers on every call loops again.
        AttachedContext owner = ctx();
        Cleanups cleanups = new Cleanups(owner);
        List<String> ran = new ArrayList<>();
        cleanups.add(() -> ran.add("second"));
        cleanups.add(() -> {
            ran.add("first");
            cleanups.run(msg -> { });
            cleanups.add(() -> ran.add("registered after the inner drain"));
        });

        List<String> warned = new ArrayList<>();
        assertEquals(List.of(), cleanups.run(warned::add));
        assertEquals(List.of("first", "second"), ran);
        assertEquals(List.of("1 cleanup(s) registered by a cleanup did not run"), warned,
                "counted once, by the outermost drain");
        assertEquals(1, owner.records().get("cleanupsNotRun"));
    }
}
