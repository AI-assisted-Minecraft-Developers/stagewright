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
    void aCleanupThatRegistersAnotherRunsEachOnce() {
        // What a restoring helper does when a cleanup calls it.
        SceneContext ctx = new SceneContext(null, BlockPos.ZERO);
        List<String> ran = new ArrayList<>();
        ctx.cleanup(() -> {
            ran.add("outer");
            ctx.cleanup(() -> ran.add("inner"));
        });

        assertEquals(List.of(), ctx.runCleanups(msg -> { }));
        assertEquals(List.of("outer", "inner"), ran);
        assertEquals(List.of(), ctx.runCleanups(msg -> { }));
        assertEquals(List.of("outer", "inner"), ran, "a drained cleanup must not run a second time");
    }
}
