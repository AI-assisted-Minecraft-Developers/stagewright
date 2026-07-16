package net.magicterra.testkit.scene;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Per-scene handle: origin-relative world ops, assertions, and tick-continuation
 * steps. The body runs ONCE (synchronously) on the scene's first tick — it builds
 * the arena, asserts immediate state, and registers await-steps; the harness then
 * calls advance() every tick until DONE / STEP_TIMEOUT / budget exhaustion.
 * Bodies must never block or sleep (same rule as every in-game test in this repo).
 */
public final class SceneContext {
    /** One pending continuation: wait for cond (within N ticks of becoming current), then run. */
    private record Step(BooleanSupplier cond, int withinTicks, Runnable then) {}

    public enum Progress { RUNNING, DONE, STEP_TIMEOUT }

    private final ServerLevel level;
    private final BlockPos origin;
    private final Deque<Step> steps = new ArrayDeque<>();
    private int ticks;
    private int currentStepTicks;
    private String failureReason;

    public SceneContext(ServerLevel level, BlockPos origin) {
        this.level = level;
        this.origin = origin;
    }

    /** The backing server level — for scenes that drive entities/avatars directly. */
    public ServerLevel level() { return level; }

    /** Absolute origin of this scene's grid cell (scene code should prefer rel()). */
    public BlockPos origin() { return origin; }

    private final Deque<Runnable> cleanups = new ArrayDeque<>();

    /**
     * Register teardown to run when the scene resolves — on PASS, FAIL and
     * TIMEOUT alike (LIFO). Use for avatar discard, config unpin, entity kill:
     * anything that must not leak into the next scene.
     */
    public void cleanup(Runnable r) { cleanups.addFirst(r); }

    /** Harness-internal: drain cleanups; exceptions logged, never thrown. */
    public void runCleanups(Consumer<String> warn) {
        for (Runnable r : cleanups) {
            try { r.run(); } catch (Throwable t) { warn.accept("cleanup failed: " + t); }
        }
        cleanups.clear();
    }

    // ---- world ops (origin-relative; scenes never see absolute coordinates) ----

    public BlockPos rel(int dx, int dy, int dz) {
        return origin.offset(dx, dy, dz);
    }

    public void setBlock(int dx, int dy, int dz, Block block) {
        level.setBlockAndUpdate(rel(dx, dy, dz), block.defaultBlockState());
    }

    /** size x size stone-slab floor at dy=0, cleared air 4 above — the minimal clean pad. */
    public void floor(int size, Block block) {
        int half = size / 2;
        for (int dx = -half; dx <= half; dx++)
            for (int dz = -half; dz <= half; dz++) {
                setBlock(dx, 0, dz, block);
                for (int dy = 1; dy <= 4; dy++) setBlock(dx, dy, dz, Blocks.AIR);
            }
    }

    // ---- assertions ----

    public void assertBlock(int dx, int dy, int dz, Block expected) {
        Block actual = level.getBlockState(rel(dx, dy, dz)).getBlock();
        if (actual != expected) {
            throw new SceneFailure("block at rel(" + dx + "," + dy + "," + dz + ") is "
                    + actual + ", expected " + expected);
        }
    }

    public void fail(String reason) {
        throw new SceneFailure(reason);
    }

    // ---- continuation steps ----

    public AwaitBuilder await(BooleanSupplier cond) {
        return new AwaitBuilder(cond);
    }

    public final class AwaitBuilder {
        private final BooleanSupplier cond;
        private int within = 100;

        private AwaitBuilder(BooleanSupplier cond) { this.cond = cond; }

        public AwaitBuilder within(int ticksBudget) { this.within = ticksBudget; return this; }

        public void then(Runnable action) { steps.addLast(new Step(cond, within, action)); }
    }

    // ---- harness-side driving ----

    /** Run the body once; SceneFailure propagates to the harness as FAIL. */
    public void runBody(Consumer<SceneContext> body) {
        body.accept(this);
    }

    /** One tick of step processing. Greedy: consume every step whose cond is already true. */
    public Progress advance() {
        ticks++;
        while (!steps.isEmpty()) {
            Step head = steps.peekFirst();
            if (head.cond().getAsBoolean()) {
                steps.pollFirst();
                currentStepTicks = 0;
                head.then().run();               // SceneFailure propagates to the harness
                continue;
            }
            currentStepTicks++;
            if (currentStepTicks > head.withinTicks()) {
                failureReason = "await step exceeded within=" + head.withinTicks() + " ticks";
                return Progress.STEP_TIMEOUT;
            }
            return Progress.RUNNING;
        }
        return Progress.DONE;
    }

    public int ticks() { return ticks; }

    public String failureReason() { return failureReason; }
}
