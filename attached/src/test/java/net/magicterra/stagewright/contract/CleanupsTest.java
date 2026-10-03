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
}
