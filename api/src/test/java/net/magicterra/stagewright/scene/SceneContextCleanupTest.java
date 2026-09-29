package net.magicterra.stagewright.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class SceneContextCleanupTest {

    @Test
    void aBlockEntityTheBodyPlacedIsRevertedOnce() {
        SceneContext ctx = new SceneContext(null, BlockPos.ZERO);
        List<String> world = new ArrayList<>();
        ctx.place(BlockPos.ZERO, true, () -> world.add("chest"), () -> world.add("air"));
        ctx.place(BlockPos.ZERO, true, () -> world.add("chest"), () -> world.add("air"));
        ctx.runCleanups(msg -> { });
        assertEquals(List.of("chest", "chest", "air"), world);
    }

    @Test
    void aPlainBlockTheBodyPlacesOverABlockEntityIsNotRevertedToAir() {
        SceneContext ctx = new SceneContext(null, BlockPos.ZERO);
        List<String> world = new ArrayList<>();
        ctx.place(BlockPos.ZERO, true, () -> world.add("chest"), () -> world.add("air"));
        ctx.place(BlockPos.ZERO, false, () -> world.add("stone"), () -> world.add("air"));
        ctx.runCleanups(msg -> { });
        assertEquals(List.of("chest", "stone"), world);
    }

    @Test
    void aBlockEntityPlacedAgainOverAPlainBlockIsRevertedOnce() {
        SceneContext ctx = new SceneContext(null, BlockPos.ZERO);
        List<String> world = new ArrayList<>();
        ctx.place(BlockPos.ZERO, true, () -> world.add("chest"), () -> world.add("air"));
        ctx.place(BlockPos.ZERO, false, () -> world.add("stone"), () -> world.add("air"));
        ctx.place(BlockPos.ZERO, true, () -> world.add("hopper"), () -> world.add("air"));
        ctx.runCleanups(msg -> { });
        assertEquals(List.of("chest", "stone", "hopper", "air"), world);
    }

    @Test
    void whatACleanupPutsBackIsNotRevertedAfterIt() {
        // A restoring helper: place the block under test, then register putting the old one back.
        SceneContext ctx = new SceneContext(null, BlockPos.ZERO);
        List<String> world = new ArrayList<>();
        ctx.place(BlockPos.ZERO, true, () -> world.add("chest"), () -> world.add("air"));
        ctx.cleanup(() -> ctx.place(BlockPos.ZERO, true, () -> world.add("furnace"), () -> world.add("air")));
        ctx.runCleanups(msg -> { });
        assertEquals(List.of("chest", "furnace"), world);
        ctx.runCleanups(msg -> { });
        assertEquals(List.of("chest", "furnace"), world, "the cleanup's placement queued no revert of its own");
    }

    @Test
    void aBlockEntityACleanupPlacesElsewhereIsLeftToIt() {
        SceneContext ctx = new SceneContext(null, BlockPos.ZERO);
        List<String> world = new ArrayList<>();
        ctx.cleanup(() -> ctx.place(BlockPos.ZERO.above(), true, () -> world.add("hopper"), () -> world.add("air")));
        ctx.runCleanups(msg -> { });
        assertEquals(List.of("hopper"), world, "a revert queued in the drain would run next and undo it");
    }

    @Test
    void aCleanupACleanupRegistersDoesNotRun() {
        // A restoring helper called from a cleanup: its "put the old value back" would undo the
        // restoration, and one that re-registers on every call would never let the drain end.
        SceneContext ctx = new SceneContext(null, BlockPos.ZERO);
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
