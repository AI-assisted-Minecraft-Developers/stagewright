package net.magicterra.stagewright.harness;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.magicterra.stagewright.StageWrightCommon;
import net.magicterra.stagewright.scene.Canary;
import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneFailure;
import net.magicterra.stagewright.scene.SceneOutcome;
import net.magicterra.stagewright.scene.SceneSkipped;
import net.magicterra.stagewright.scene.Terrain;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;

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
public final class StageWrightHarness {
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

    /** The level the current scene's arena lives in — its {@link Scene#terrain()}'s dimension, or the
     *  overworld. Resolved once at PREP rather than per tick: a scene must not be able to build its
     *  arena in one level and assert about it in another if a dimension is unloaded mid-scene. */
    private ServerLevel sceneLevel;

    /** The world as it stood before the current scene's body ran, for the teardown leak audit. */
    private ArenaAudit.Snapshot arenaBefore;

    /** Every scene that left something behind, named once per suite. */
    private final Set<String> leakedScenes = new java.util.LinkedHashSet<>();

    private long sceneStartMs;
    private boolean finished;

    /** Bumped every tick, read by {@link StallWatchdog} from its own thread — volatile because
     *  those are different threads and a cached read would report a stall that is not happening. */
    private volatile long ticksObserved;

    /** The scene the tick is inside, so a stall can be blamed on it by name rather than on the run. */
    private volatile String runningScene = "<arming>";

    private final StallWatchdog stallWatchdog;

    public StageWrightHarness(MinecraftServer server, String loader, List<Scene> scenes, ResultsJsonl out) {
        this.server = server;
        this.scenes = scenes;
        this.out = out;
        rejectDuplicateNames(scenes);
        this.slotByName = assignSlots(scenes);
        out.writeSuiteHeader(loader, scenes, SceneFilter.pattern());
        // Started at arming, not at the first scene: a mod that wedges the tick does it during its
        // own setup as readily as inside a scene, and that stall has to be nameable too.
        this.stallWatchdog = new StallWatchdog(out, () -> ticksObserved, () -> runningScene);
        this.stallWatchdog.start();
        StageWrightCommon.LOG.info("[{}] harness armed: {} scenes", StageWrightCommon.MOD_ID, scenes.size());
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
     *  Read by {@code StageWrightCommon.triggerOnDemandRun} to phrase the idempotency error precisely
     *  ("already run" vs "already been started"). */
    public boolean isFinished() {
        return finished;
    }

    /**
     * Liveness signal for the stall watchdog, called every server tick.
     *
     * <p>Separate from {@link #tick()} because tick() is gated behind the startup settle barrier and
     * is not called at all while tick debt drains. Counting there would read a server that is
     * healthily catching up as a wedged one, and the watchdog would kill a run that was fine.
     */
    public void observeServerTick() {
        ticksObserved++;
    }

    public void tick() {
        if (finished) return;
        if (index >= scenes.size()) { finish(); return; }

        Scene scene = scenes.get(index);
        runningScene = scene.name();
        if (scene.canary() == Canary.MUST_SWALLOW) {
            // Deliberately never executed and never recorded: the orchestrator must
            // flag exactly this omission, proving the swallow gate is alive (spec §5).
            StageWrightCommon.LOG.info("[{}] skipping swallow-canary '{}'", StageWrightCommon.MOD_ID, scene.name());
            nextScene();
            return;
        }

        BlockPos origin = originFor(slotByName.get(scene.name()));
        int radius = scene.chunkRadius();

        switch (phase) {
            case PREP -> {
                if (phaseTicks == 0) {
                    sceneStartMs = System.currentTimeMillis();
                    sceneLevel = levelFor(scene);
                    if (sceneLevel == null) {
                        record(scene, SceneOutcome.ENV_FAIL, 0, "terrain " + scene.terrain()
                                + " needs dimension '" + scene.terrain().dimension() + "', which this"
                                + " server does not have — StageWright's datapack did not load");
                        return;
                    }
                    forceChunks(sceneLevel, origin, radius, true);
                }
                phaseTicks++;
                ServerLevel level = sceneLevel;
                if (arenaReady(level, origin, radius)) {
                    ctx = new SceneContext(level, arenaOrigin(scene, level, origin), radius);
                    arenaBefore = ArenaAudit.take(level, origin, radius);
                    phase = Phase.RUN;
                    phaseTicks = 0;
                } else if (phaseTicks > PREP_BUDGET_TICKS) {
                    record(scene, SceneOutcome.ENV_FAIL, 0, "arena chunks were not loaded and"
                            + " entity-ticking within " + PREP_BUDGET_TICKS + " ticks");
                    teardown(scene, level, origin, radius);
                }
            }
            case RUN -> {
                ServerLevel level = sceneLevel;
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
                } catch (SceneSkipped s) {
                    // Entered, resolved, and carrying its reason into the results — the scene is
                    // accounted for, so coverage reconciliation still sees it, and the one thing
                    // it must not do is look like a scene that quietly did its job.
                    record(scene, SceneOutcome.PASS, ctx.ticks(), "skipped: " + s.getMessage());
                    teardown(scene, level, origin, radius);
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
        StageWrightCommon.LOG.info("[{}] scene '{}' -> {} ({} ticks, {} ms){}", StageWrightCommon.MOD_ID,
                scene.name(), outcome, ticks, wallMs, reason == null ? "" : " — " + reason);
        // Recorded values travel with EVERY outcome, not just failures. On a PASS they are the
        // measurement the scene exists to take (a tick cost, a channel count, a TPS ratio), and the
        // results file is the only place downstream tooling can read them; a value that lives solely
        // in a failure message is unavailable exactly when the run is healthy.
        // ctx is null when a scene ENV_FAILs out of PREP, before any context exists.
        out.writeScene(scene.name(), outcome, ticks, wallMs, reason,
                ctx == null ? java.util.Map.of() : ctx.records());
        phase = Phase.ADVANCE_DONE;
    }

    /** Single confluence point for every outcome (PASS/FAIL/TIMEOUT/ENV_FAIL): drain the
     *  scene's cleanups — if it got far enough to have a ctx — before releasing the arena's
     *  forced chunks. A leaked avatar or dangling cleanup here poisons the next scene, so
     *  this runs regardless of how the scene resolved. ENV_FAIL fires from PREP before ctx
     *  is ever constructed, so there is nothing to drain in that case. */
    private void teardown(Scene scene, ServerLevel level, BlockPos origin, int radius) {
        if (ctx != null) {
            ctx.runCleanups(msg -> StageWrightCommon.LOG.warn("[{}] {}: {}", StageWrightCommon.MOD_ID, scene.name(), msg));
        }
        sweepArena(scene, level, origin, radius);
        forceChunks(level, origin, radius, false);
        auditLeaks(scene, level, origin, radius);
    }

    /**
     * Discard whatever the scene added to its arena and did not remove itself.
     *
     * <p>Before the chunks are released, because discarding an entity in an unloaded chunk does not
     * do anything and the leak survives to the next run's world.
     *
     * <p>The scene's own cleanups have already run at this point, so anything still standing here is
     * something the scene did not know it created — a mob's drops, a projectile, a spawned helper it
     * forgot. Sweeping is a backstop for that, not a replacement for the cleanups: a scene that
     * relies on this instead of discarding its own avatar still leaks everywhere the arena is not.
     */
    private void sweepArena(Scene scene, ServerLevel level, BlockPos origin, int radius) {
        if (arenaBefore == null) return;
        int swept = ArenaAudit.sweep(level, origin, radius, arenaBefore);
        if (swept > 0) {
            StageWrightCommon.LOG.info("[{}] swept {} leftover entit{} from '{}' arena",
                    StageWrightCommon.MOD_ID, swept, swept == 1 ? "y" : "ies", scene.name());
        }
    }

    /**
     * Report what the scene left in the world after its own cleanups had their turn.
     *
     * <p>Logged rather than folded into the scene's result: the record for this scene is already
     * written by the time teardown runs, and moving the write after teardown would mean a cleanup
     * that throws could take the result with it. A leak is a property of the suite anyway — it
     * matters because of what it does to the NEXT scene — so the count that gets acted on is the
     * suite total, logged at {@link #finish()}.
     */
    private void auditLeaks(Scene scene, ServerLevel level, BlockPos origin, int radius) {
        if (arenaBefore == null) return;                // ENV_FAIL out of PREP: nothing ever ran
        for (String leak : ArenaAudit.diff(arenaBefore, ArenaAudit.take(level, origin, radius))) {
            leakedScenes.add(scene.name());
            StageWrightCommon.LOG.warn("[{}] LEAK after '{}': {}", StageWrightCommon.MOD_ID,
                    scene.name(), leak);
        }
        arenaBefore = null;
    }

    /**
     * PREP waits for the full (2r+1)x(2r+1) arena, not just the origin chunk — matching
     * forceChunks' footprint so scene bodies never touch a not-yet-loaded neighbour chunk on their
     * first tick.
     *
     * <p>Entity ticking is checked as well as presence, and the difference is not academic.
     * {@code hasChunkAt} answers yes at FULL, and the promotion to ENTITY_TICKING is an asynchronous
     * step after that: {@code ChunkMap.prepareEntityTickingChunk} waits for the whole 5×5 around the
     * chunk to be FULL, which at an arena 100k blocks out is fresh worldgen and takes real ticks. A
     * scene that starts inside that gap gets an arena which reads completely normal — blocks work,
     * and every entity in it is visible to {@code getEntities} and to command selectors, because
     * FULL already means {@code Visibility.TRACKED}. The only thing missing is that nothing in it
     * ever ticks, which no assertion notices until one depends on it. Waiting here is also what
     * makes the ticket radius self-checking: get it wrong and scenes ENV_FAIL in PREP, instead of
     * passing while testing nothing.
     */
    private boolean arenaReady(ServerLevel level, BlockPos origin, int radius) {
        for (int dx = -radius; dx <= radius; dx++)
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos p = origin.offset(dx * 16, 0, dz * 16);
                if (!level.hasChunkAt(p)) return false;
                if (!level.isPositionEntityTicking(p)) return false;
            }
        return true;
    }

    private void nextScene() {
        index++;
        phase = Phase.PREP;
        phaseTicks = 0;
        ctx = null;
        sceneLevel = null;
        if (index >= scenes.size()) finish();
    }

    private void finish() {
        if (finished) return;
        finished = true;
        // Before the footer: the suite is over, so a server that now takes its time shutting down is
        // not a stall, and a watchdog still armed would eventually call it one.
        stallWatchdog.stop();
        long executed = scenes.stream().filter(s -> s.canary() != Canary.MUST_SWALLOW).count();
        if (leakedScenes.isEmpty()) {
            StageWrightCommon.LOG.info("[{}] arena audit: no scene left anything behind",
                    StageWrightCommon.MOD_ID);
        } else {
            StageWrightCommon.LOG.warn("[{}] arena audit: {} of {} scenes leaked into the world — {}",
                    StageWrightCommon.MOD_ID, leakedScenes.size(), executed, leakedScenes);
        }
        out.writeDone((int) executed);
        if (StageWrightCommon.holding()) {
            // A hold outlives its suite. Halting here would take the endpoint down underneath
            // whatever attached to it — and the one thing you cannot do without triggering a run is
            // prove that a SECOND mc.test.run is refused, so an attached test that exercises the
            // idempotency guard would be destroying the server it is talking to.
            StageWrightCommon.LOG.info("[{}] suite complete ({} scenes executed) — held, so the"
                    + " server stays up", StageWrightCommon.MOD_ID, executed);
            return;
        }
        StageWrightCommon.LOG.info("[{}] suite complete ({} scenes executed) — halting server",
                StageWrightCommon.MOD_ID, executed);
        server.halt(false);
        armExitWatchdog();
    }

    /** Grace period between the server halting and forcing the JVM down, long enough for the world
     *  save that {@code halt} triggers to finish. The save is synchronous on the server thread, so
     *  this is bounded by disk rather than by anything that could legitimately still be running. */
    private static final long EXIT_GRACE_MS = 30_000;

    /**
     * Force the JVM down if halting the server did not end it.
     *
     * <p>Third-party mods leave non-daemon threads behind — a scheduled executor that was never shut
     * down is enough — and one of them keeps the process alive forever after the world has saved and
     * the results file is closed. From outside, that is indistinguishable from a suite still running:
     * the gate waits, the wall clock runs, and the verdict it is waiting for is already sitting
     * complete on disk. Exit code 0 because the results file, not the process, carries the verdict.
     *
     * <p>Dedicated servers only. Under the client topology this JVM is a game the director is still
     * shutting down in an orderly way, and halting it here would race that.
     */
    private void armExitWatchdog() {
        if (!server.isDedicatedServer()) return;
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(EXIT_GRACE_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            StageWrightCommon.LOG.warn("[{}] the JVM is still up {}s after the suite halted the"
                    + " server — a mod is holding a non-daemon thread. Forcing exit; the results file"
                    + " is already complete.", StageWrightCommon.MOD_ID, EXIT_GRACE_MS / 1000);
            Runtime.getRuntime().halt(0);
        }, "stagewright-exit-watchdog");
        // Daemon, so it cannot itself become the thread that keeps the JVM alive on a clean shutdown.
        watchdog.setDaemon(true);
        watchdog.start();
    }

    /**
     * The level this scene's arena is built in, or {@code null} if the terrain it asked for names a
     * dimension this server does not have.
     *
     * <p>Null rather than a fallback to the overworld on purpose. A scene asking for
     * {@link Terrain#GENERATED} wants hills; handing it the run world's empty sky instead would let
     * it fail its own terrain assertions with a message about missing blocks, sending whoever reads
     * the run looking for a bug in the scene rather than for the datapack that did not load.
     */
    private ServerLevel levelFor(Scene scene) {
        Terrain terrain = scene.terrain();
        if (terrain.dimension() == null) return server.overworld();
        return server.getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse(terrain.dimension())));
    }

    /**
     * Where the scene's arena actually sits.
     *
     * <p>For {@link Terrain#RUN_WORLD} that is the grid position unchanged — {@code y=200} in empty
     * sky, which is what every scene got before terrain was a choice. For the terrain dimensions it
     * is the surface at the grid's x/z, one block up, so {@code dy=0} is the block a player standing
     * there occupies and {@code setBlock(0, -1, 0, …)} replaces the ground under their feet. Without
     * this a scene that asked for terrain would get its arena 200 blocks above it, which is the
     * least useful possible answer to "put me on the ground".
     *
     * <p>Safe to call only once the arena chunks are loaded: the heightmap of an ungenerated chunk
     * answers for terrain that does not exist yet.
     */
    private static BlockPos arenaOrigin(Scene scene, ServerLevel level, BlockPos gridOrigin) {
        if (!scene.terrain().onSurface()) return gridOrigin;
        return level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, gridOrigin);
    }

    private static BlockPos originFor(int slot) {
        return new BlockPos(GRID_X0 + slot * GRID_STEP, GRID_Y, GRID_Z0);
    }

    /**
     * Chunks forced beyond the arena itself, so the arena can tick entities.
     *
     * <p>A chunk only becomes {@code ENTITY_TICKING} once the 5×5 around it is FULL — that is what
     * {@code ChunkMap.prepareEntityTickingChunk} waits for. A region ticket cut to exactly the
     * arena's radius cannot deliver it: that puts level 32 on the centre, which is BLOCK_TICKING,
     * and no entity in the arena would ever tick. Two chunks of skirt is the smallest margin that
     * leaves the arena at 31 and its 5×5 at FULL.
     */
    private static final int ENTITY_TICKING_MARGIN = 2;

    /**
     * Pin the arena open, wide enough that it will tick entities.
     *
     * <p>One region ticket, not a grid of {@code setChunkForced} calls. Both pin the same chunks, but
     * {@code setChunkForced} loads each newly forced chunk SYNCHRONOUSLY — and an arena sits 100k
     * blocks out, so every one of those is a fresh worldgen. Nine of them fit in a tick. Twenty-five
     * took sixty seconds, and the server's own watchdog declared the tick crashed and killed the run.
     * A region ticket asks for the same chunks and lets the chunk system deliver them across the
     * ticks that follow, which is what {@code PREP} is already waiting through.
     *
     * <p>The radius is {@code chunkRadius + 2} because a region ticket puts level
     * {@code 33 - radius} on the centre and spreads outward by one per chunk: the arena's own chunks
     * land at or under 31, which is entity-ticking, and the two-chunk skirt lands at 32 and 33, which
     * is FULL. That skirt is not decoration: drop it and the centre lands at 32, and an arena that
     * cannot tick an entity looks exactly like one that can.
     *
     * <p>Region tickets are also not persisted, so a run killed mid-scene leaves nothing pinned for
     * the next one to inherit — which {@code setChunkForced} does, through the saved data.
     */
    private static void forceChunks(ServerLevel level, BlockPos origin, int radius, boolean force) {
        ChunkPos centre = new ChunkPos(origin);
        int reach = radius + ENTITY_TICKING_MARGIN;
        if (force) {
            level.getChunkSource().addRegionTicket(TicketType.FORCED, centre, reach, centre);
        } else {
            level.getChunkSource().removeRegionTicket(TicketType.FORCED, centre, reach, centre);
        }
    }
}
