package net.magicterra.testkit.harness;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.magicterra.testkit.TestkitCommon;
import net.magicterra.testkit.scene.Canary;
import net.magicterra.testkit.scene.Scene;
import net.magicterra.testkit.scene.SceneContext;
import net.magicterra.testkit.scene.SceneFailure;
import net.magicterra.testkit.scene.SceneOutcome;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Serial T0 scheduler on a PLAIN dedicated server. One scene at a time, each on
 * its own grid-allocated origin in force-loaded chunks; per-scene tick budget;
 * results to the contract-v0 JSONL; halts the server when the registry is drained.
 *
 * Grid allocation: origin i = (GRID_X0 + i*GRID_STEP, GRID_Y, GRID_Z0), far from
 * spawn so a flat world's spawn chunks never overlap an arena. Chunks are
 * force-loaded for the scene's lifetime and released afterwards — serial
 * execution + per-scene origins is the whole isolation story at P1a (no shared
 * body yet; body reset arrives with dogfood migration).
 */
public final class TestkitHarness {
    private static final int GRID_X0 = 100_000;
    private static final int GRID_Z0 = 100_000;
    private static final int GRID_Y = 200;
    private static final int GRID_STEP = 512;
    private static final int PREP_BUDGET_TICKS = 200;

    private enum Phase { PREP, RUN, ADVANCE_DONE }

    private final MinecraftServer server;
    private final List<Scene> scenes;
    private final ResultsJsonl out;
    private final Map<String, Integer> slotByName;

    private int index;
    private Phase phase = Phase.PREP;
    private int phaseTicks;
    private SceneContext ctx;
    private long sceneStartMs;
    private boolean finished;

    public TestkitHarness(MinecraftServer server, String loader, List<Scene> scenes, ResultsJsonl out) {
        this.server = server;
        this.scenes = scenes;
        this.out = out;
        rejectDuplicateNames(scenes);
        this.slotByName = assignSlots(scenes);
        out.writeSuiteHeader(loader, scenes);
        TestkitCommon.LOG.info("[{}] harness armed: {} scenes", TestkitCommon.MOD_ID, scenes.size());
    }

    /** A duplicate scene name lets a later record silently overwrite an earlier one
     *  in the orchestrator's last-wins map, masking a real FAIL as GREEN. Reject the
     *  whole registry loudly before the suite header is ever written (spec §5/§10). */
    private static void rejectDuplicateNames(List<Scene> scenes) {
        Set<String> seen = new HashSet<>();
        for (Scene s : scenes) {
            if (!seen.add(s.name())) {
                throw new IllegalStateException("duplicate scene name '" + s.name()
                        + "' in registry — scene names must be unique");
            }
        }
    }

    /** Two-pass origin slot allocation: explicit pins (spec'd scenes needing byte-
     *  determinism) claim their slot first, then auto scenes fill the remaining
     *  slots in registry order, skipping any slot a pin already claimed. With no
     *  pins in the registry this reduces to slot == registry index — identical to
     *  the pre-pinning origin assignment (Step-4 regression proves this). */
    private static Map<String, Integer> assignSlots(List<Scene> scenes) {
        Map<String, Integer> out = new LinkedHashMap<>();
        Set<Integer> taken = new HashSet<>();
        for (Scene s : scenes) {                       // pass 1: explicit pins
            if (s.originSlot() >= 0) {
                if (s.originSlot() < 1024) {
                    throw new IllegalStateException("explicit origin slot " + s.originSlot()
                            + " below floor 1024 (scene " + s.name()
                            + ") — low pins displace auto slots, defeating pinning");
                }
                if (!taken.add(s.originSlot())) {
                    throw new IllegalStateException("origin slot collision: " + s.originSlot()
                            + " (scene " + s.name() + ")");
                }
                out.put(s.name(), s.originSlot());
            }
        }
        int next = 0;
        for (Scene s : scenes) {                       // pass 2: auto scenes skip pinned slots
            if (s.originSlot() < 0) {
                while (taken.contains(next)) next++;
                taken.add(next);
                out.put(s.name(), next);
            }
        }
        return out;
    }

    /** True once the suite has drained the registry and written the done footer (server halted).
     *  Read by {@code TestkitCommon.triggerOnDemandRun} to phrase the idempotency error precisely
     *  ("already run" vs "already been started"). */
    public boolean isFinished() {
        return finished;
    }

    public void tick() {
        if (finished) return;
        if (index >= scenes.size()) { finish(); return; }

        Scene scene = scenes.get(index);
        if (scene.canary() == Canary.MUST_SWALLOW) {
            // Deliberately never executed and never recorded: the orchestrator must
            // flag exactly this omission, proving the swallow gate is alive (spec §5).
            TestkitCommon.LOG.info("[{}] skipping swallow-canary '{}'", TestkitCommon.MOD_ID, scene.name());
            nextScene();
            return;
        }

        ServerLevel level = server.overworld();
        BlockPos origin = originFor(slotByName.get(scene.name()));
        int radius = scene.chunkRadius();

        switch (phase) {
            case PREP -> {
                if (phaseTicks == 0) {
                    forceChunks(level, origin, radius, true);
                    sceneStartMs = System.currentTimeMillis();
                }
                phaseTicks++;
                if (allChunksLoaded(level, origin, radius)) {
                    ctx = new SceneContext(level, origin);
                    phase = Phase.RUN;
                    phaseTicks = 0;
                } else if (phaseTicks > PREP_BUDGET_TICKS) {
                    record(scene, SceneOutcome.ENV_FAIL, 0, "arena chunks not loaded within "
                            + PREP_BUDGET_TICKS + " ticks");
                    teardown(scene, level, origin, radius);
                }
            }
            case RUN -> {
                phaseTicks++;
                try {
                    if (phaseTicks == 1) ctx.runBody(scene.body());
                    SceneContext.Progress p = ctx.advance();
                    if (p == SceneContext.Progress.DONE) {
                        record(scene, SceneOutcome.PASS, ctx.ticks(), ctx.passNote());
                        teardown(scene, level, origin, radius);
                    } else if (p == SceneContext.Progress.STEP_TIMEOUT) {
                        record(scene, SceneOutcome.TIMEOUT, ctx.ticks(), ctx.failureReason());
                        teardown(scene, level, origin, radius);
                    } else if (ctx.ticks() > scene.budgetTicks()) {
                        record(scene, SceneOutcome.TIMEOUT, ctx.ticks(),
                                "scene budget " + scene.budgetTicks() + " ticks exhausted");
                        teardown(scene, level, origin, radius);
                    }
                } catch (SceneFailure f) {
                    record(scene, SceneOutcome.FAIL, ctx.ticks(), f.getMessage());
                    teardown(scene, level, origin, radius);
                } catch (Throwable t) {
                    record(scene, SceneOutcome.FAIL, ctx.ticks(),
                            "unexpected " + t.getClass().getSimpleName() + ": " + t.getMessage());
                    teardown(scene, level, origin, radius);
                }
            }
            case ADVANCE_DONE -> nextScene();
        }
    }

    private void record(Scene scene, SceneOutcome outcome, int ticks, String reason) {
        long wallMs = System.currentTimeMillis() - sceneStartMs;
        TestkitCommon.LOG.info("[{}] scene '{}' -> {} ({} ticks, {} ms){}", TestkitCommon.MOD_ID,
                scene.name(), outcome, ticks, wallMs, reason == null ? "" : " — " + reason);
        out.writeScene(scene.name(), outcome, ticks, wallMs, reason);
        phase = Phase.ADVANCE_DONE;
    }

    /** Single confluence point for every outcome (PASS/FAIL/TIMEOUT/ENV_FAIL): drain the
     *  scene's cleanups — if it got far enough to have a ctx — before releasing the arena's
     *  forced chunks. A leaked avatar or dangling cleanup here poisons the next scene, so
     *  this runs regardless of how the scene resolved. ENV_FAIL fires from PREP before ctx
     *  is ever constructed, so there is nothing to drain in that case. */
    private void teardown(Scene scene, ServerLevel level, BlockPos origin, int radius) {
        if (ctx != null) {
            ctx.runCleanups(msg -> TestkitCommon.LOG.warn("[{}] {}: {}", TestkitCommon.MOD_ID, scene.name(), msg));
        }
        forceChunks(level, origin, radius, false);
    }

    /** PREP waits for the full (2r+1)x(2r+1) force-loaded neighborhood, not just the
     *  origin chunk — matching forceChunks' footprint so scene bodies never touch a
     *  not-yet-loaded neighbor chunk on their first tick. */
    private boolean allChunksLoaded(ServerLevel level, BlockPos origin, int radius) {
        for (int dx = -radius; dx <= radius; dx++)
            for (int dz = -radius; dz <= radius; dz++)
                if (!level.hasChunkAt(origin.offset(dx * 16, 0, dz * 16))) return false;
        return true;
    }

    private void nextScene() {
        index++;
        phase = Phase.PREP;
        phaseTicks = 0;
        ctx = null;
        if (index >= scenes.size()) finish();
    }

    private void finish() {
        if (finished) return;
        finished = true;
        long executed = scenes.stream().filter(s -> s.canary() != Canary.MUST_SWALLOW).count();
        out.writeDone((int) executed);
        TestkitCommon.LOG.info("[{}] suite complete ({} scenes executed) — halting server",
                TestkitCommon.MOD_ID, executed);
        server.halt(false);
    }

    private static BlockPos originFor(int slot) {
        return new BlockPos(GRID_X0 + slot * GRID_STEP, GRID_Y, GRID_Z0);
    }

    private static void forceChunks(ServerLevel level, BlockPos origin, int radius, boolean force) {
        int cx = origin.getX() >> 4, cz = origin.getZ() >> 4;
        for (int dx = -radius; dx <= radius; dx++)
            for (int dz = -radius; dz <= radius; dz++)
                level.setChunkForced(cx + dx, cz + dz, force);
    }
}
