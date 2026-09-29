package net.magicterra.stagewright.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class AttachedContextTest {

    private static AttachedContext context() {
        return new AttachedContext("pack.scene", (method, params) -> null, 1_000,
                System::currentTimeMillis);
    }

    @Test
    void aCleanupACleanupRegistersDoesNotRun() {
        // Nothing bounds an attached run's teardown, so a helper that re-registers on every call
        // would keep the run from ever ending.
        AttachedContext ctx = context();
        List<String> ran = new ArrayList<>();
        Runnable[] helper = new Runnable[1];
        helper[0] = () -> {
            ran.add("restore");
            // Bounded only so that a drain which runs it fails this test rather than hanging it.
            if (ran.size() < 100) ctx.cleanup(helper[0]);
        };
        ctx.cleanup(helper[0]);

        List<String> warned = new ArrayList<>();
        assertEquals(List.of(), ctx.runCleanups(warned::add));
        assertEquals(List.of("restore"), ran);
        assertEquals(List.of("1 cleanup(s) registered by a cleanup did not run"), warned);

        ctx.cleanup(() -> ran.add("after"));
        ctx.runCleanups(msg -> { });
        assertEquals(List.of("restore", "after"), ran, "registering works again once the drain is over");
    }
}
