package net.magicterra.stagewright.harness;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.magicterra.stagewright.StageWrightCommon;
import net.magicterra.stagewright.contract.Canary;
import net.magicterra.stagewright.contract.Clock;
import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.contract.SceneFailure;
import net.magicterra.stagewright.contract.SceneOutcome;
import net.magicterra.stagewright.contract.SceneSkipped;
import net.magicterra.stagewright.contract.Terrain;
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
    /**
     * How long PREP tolerates NO new arena chunk becoming ready before calling it stuck.
     *
     * <p>Progress, not elapsed time. How long an arena takes to generate is a property of the pack —
     * fifty structure mods 100k blocks out is not vanilla — so a fixed total budget is a statement
     * about somebody else's mod list. While chunks keep arriving, waiting costs only wall clock;
     * once the count stops moving, more waiting cannot help.
     */
    private static final int PREP_STALL_TICKS = 200;

    /** Backstop for a chunk system that dribbles one chunk at a time forever, which would defeat the
     *  stall test. Deliberately far above any real arena so it is never the thing that fires. */
    private static final int PREP_CEILING_TICKS = 6_000;

    private enum Phase { PREP, RUN, ADVANCE_DONE }

    /** Arena chunks ready as of the last PREP tick, the level's loaded chunks and chunks turned ticking
     *  then, and how long none of the three has changed. */
    private int prepReadyChunks;
    private int prepLevelLoaded = -1;
    private int prepLevelTicking = -1;
    private int prepStalledTicks;
    /** Samples the server thread from half way through a PREP stall; null the rest of the time. */
    private ServerThreadSampler prepSampler;

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

    /**
     * The one budget that is not denominated in the thing it is measuring.
     *
     * <p>Every other budget here counts ticks, which is right for everything a scene asserts about
     * and wrong for the one failure a scene cannot survive: a world whose ticks stopped. A tick
     * budget in a stopped world never expires, so the scene never ends, so no row is written, and
     * the run dies as a wall-clock kill with no verdict and no name — which is exactly how three
     * playthrough runs died before this existed.
     *
     * <p>Re-opened at every scene boundary, so the verdict is always about the scene it names and no
     * scene inherits its predecessor's partial window.
     */
    private final TickStarvation starvation;

    public StageWrightHarness(MinecraftServer server, String loader, List<Scene> scenes, ResultsJsonl out) {
        this.server = server;
        this.scenes = scenes;
        this.out = out;
        rejectDuplicateNames(scenes);
        this.slotByName = assignSlots(scenes);
        // Before the header, so the header can state the world these results were produced in — and
        // before the first scene's arena audit takes its baseline, or that scene is reported as the
        // one that changed a gamerule.
        WorldPin.applySuite(server);
        out.writeSuiteHeader(loader, scenes, SceneFilter.pattern(), WorldPin.description());
        // Started at arming, not at the first scene: a mod that wedges the tick does it during its
        // own setup as readily as inside a scene, and that stall has to be nameable too.
        this.starvation = TickStarvation.forScene(ticksObserved);
        this.stallWatchdog = new StallWatchdog(out, () -> ticksObserved, () -> runningScene);
        this.stallWatchdog.start();
        StageWrightCommon.LOG.info("[{}] harness armed: {} scenes", StageWrightCommon.MOD_ID, scenes.size());
    }

    /**
     * The structural checks a registry must pass before a harness is built for it: unique names and
     * legal origin-slot pins. Throws {@link IllegalStateException} naming the offender.
     * {@link SuiteRegistry} calls it so a rejection becomes a recorded result rather than a crash.
     */
    public static void validate(List<Scene> scenes) {
        rejectDuplicateNames(scenes);
        assignSlots(scenes);
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

        // Before the phase work, and applying to PREP as much as to RUN: PREP gives up on chunk
        // PROGRESS, which is a tick-counted judgement too, so a world that stops delivering ticks
        // hangs there just as readily. Whatever this scene was doing, it cannot finish in a world
        // that is not running, and saying so now is what keeps the remaining scenes reachable.
        String starved = starvation.starved(ticksObserved);
        if (starved != null) {
            record(scene, SceneOutcome.TIMEOUT, ctx == null ? 0 : ctx.ticks(), starved
                    + " — this scene's budget is counted in ticks and cannot expire in a world that"
                    + " is not delivering them, so the harness is ending it on wall clock instead."
                    + " The run continues; if the world does not recover, every scene after this one"
                    + " reports the same thing and the suite still produces a verdict.");
            // sceneLevel is null when the very first PREP tick has not resolved a level yet — the
            // same state the dimension/ENV_FAIL branches below return from, and with the same reason
            // there is nothing to tear down: no chunks were forced and no context exists.
            if (sceneLevel != null) teardown(scene, sceneLevel, origin, radius);
            starvation.reset(ticksObserved);
            return;
        }

        switch (phase) {
            case PREP -> {
                if (phaseTicks == 0) {
                    sceneStartMs = System.currentTimeMillis();
                    sceneLevel = levelFor(scene);
                    if (sceneLevel == null) {
                        // Two different absences, deliberately reported differently.
                        //
                        // A scene-NAMED dimension belongs to some other mod, and this runtime simply
                        // not having that mod is not anybody's bug — it is the same situation as a
                        // scene needing a player on a bare dedicated server, and gets the same
                        // answer: a recorded PASS carrying the reason. Reporting it as a failure
                        // would make every conformance suite RED in every runtime but one.
                        //
                        // A missing TERRAIN dimension is the opposite: those ship with StageWright,
                        // so their absence means our own datapack did not load, and letting that
                        // read as "the mod isn't here" would hide a broken framework.
                        if (scene.dimension() != null) {
                            record(scene, SceneOutcome.PASS, 0, "skipped: dimension '"
                                    + scene.dimension() + "' is not loaded in this runtime — the mod"
                                    + " that registers it is not here. Loaded: " + loadedDimensions(),
                                    true);
                            // Nothing to tear down: this branch is reached before forceChunks and
                            // before any SceneContext exists, exactly like the ENV_FAIL below.
                            return;
                        }
                        record(scene, SceneOutcome.ENV_FAIL, 0, "terrain " + scene.terrain()
                                + " needs dimension '" + scene.terrain().dimension() + "', which this"
                                + " server does not have — StageWright's datapack did not load."
                                + " Loaded: " + loadedDimensions());
                        return;
                    }
                    forceChunks(sceneLevel, origin, radius, true);
                }
                phaseTicks++;
                ServerLevel level = sceneLevel;
                // `!scene.arena()` short-circuits the WAIT, not the arena: the plot is still
                // force-loaded above and still audited below, so only the blocking changes. For a
                // scene that plays in the live world and never enters its plot, that wait is pure
                // inherited risk — a stall in worldgen a hundred thousand blocks away fails a scene
                // whose claim has nothing to do with that ground.
                if (!scene.arena() || arenaReady(level, origin, radius)) {
                    ctx = new SceneContext(level, arenaOrigin(scene, level, origin), radius);
                    // HOW LONG THE ARENA TOOK, on every scene and every outcome — the number PREP
                    // spends its whole phase producing and then throws away. Until this line the
                    // only scene that ever reported it was one that FAILED to get an arena, which
                    // is a criterion success cannot satisfy: a run where every scene is one tick
                    // from the stall ceiling and a run where every scene is ready immediately both
                    // print GREEN and nothing else. It is what decides whether an ENV_FAIL(10001ms)
                    // that later PASSes at 3336ms was fixed or merely got lucky — a question two
                    // published commits could not answer because neither run had the distribution.
                    ctx.record("prep.ticks", phaseTicks);
                    ctx.record("prep.ms", System.currentTimeMillis() - sceneStartMs);
                    arenaBefore = ArenaAudit.take(level, origin, radius);
                    // Per scene, not per suite: this is what stops one scene's clock from being a
                    // function of how long its predecessors took — or of a predecessor having asked
                    // for Clock.RUNNING. AFTER the audit baseline, so a Clock.RUNNING scene's
                    // borrowed doDaylightCycle is not in the baseline that teardown's restore is
                    // compared against; and at the RUN edge rather than at PREP's first tick, so
                    // however many ticks chunk loading took are not ticks of a RUNNING scene's day.
                    WorldPin.applyClock(server, scene.clock());
                    phase = Phase.RUN;
                    phaseTicks = 0;
                    prepReadyChunks = 0;
                    prepStalledTicks = 0;
                    prepSampler = ServerThreadSampler.stop(prepSampler);
                } else {
                    // Give up on STALL, not on elapsed time. A fixed tick budget is really a
                    // statement about how fast chunks generate, and that is a property of the pack:
                    // 200 ticks is generous in vanilla and marginal in a 450-mod pack, where an
                    // arena 100k blocks out is worldgen through fifty structure mods. All the Mods
                    // 10 ENV_FAILed one run at ~10s and passed the next at ~9s, which is the
                    // signature of a budget that is measuring the wrong thing — and the scene it
                    // lands on is whichever one drew the slow arena, so it reads as a different bug
                    // every time.
                    //
                    // Progress is the honest test. While chunks keep arriving, the pack is working
                    // and waiting costs nothing but wall clock; once nothing has changed for
                    // PREP_STALL_TICKS, something is actually wrong and no amount of further waiting
                    // will fix it. The hard ceiling stays as a backstop for a chunk system that
                    // dribbles forever.
                    int ready = readyChunks(level, origin, radius);
                    // Progress is the level's, not only the arena's. An arena waits its turn behind
                    // whatever else the level is loading: on integrated NeoForge, a scene that adopted
                    // the real player sends it back where it came from, the chunks there reload ahead
                    // of the next arena's neighbours, and the arena stood still past PREP_STALL_TICKS
                    // while they did. Stalled means nothing in the level loaded, unloaded or started
                    // ticking either.
                    int loaded = level.getChunkSource().getLoadedChunksCount();
                    int ticking = level.getChunkSource().getTickingGenerated();
                    boolean arenaMoved = ready > prepReadyChunks;
                    if (arenaMoved || loaded != prepLevelLoaded || ticking != prepLevelTicking) {
                        if (arenaMoved) prepReadyChunks = ready;
                        prepLevelLoaded = loaded;
                        prepLevelTicking = ticking;
                        prepStalledTicks = 0;
                        prepSampler = ServerThreadSampler.stop(prepSampler);
                    } else {
                        prepStalledTicks++;
                    }
                    // Half way to giving up, start watching the server thread, so a stall that ends
                    // in ENV_FAIL says where the thread spent the ticks it waited.
                    if (prepStalledTicks == PREP_STALL_TICKS / 2 && prepSampler == null) {
                        prepSampler = ServerThreadSampler.start(server.getRunningThread());
                    }
                    if (prepStalledTicks > PREP_STALL_TICKS || phaseTicks > PREP_CEILING_TICKS) {
                        int total = (2 * radius + 1) * (2 * radius + 1);
                        // Both halves of the AND, separately — see loadedChunks. "0 of 9 ready" is
                        // two different failures depending on whether the chunks are there.
                        int present = loadedChunks(level, origin, radius);
                        String what = present < total
                                ? "only " + present + " of " + total + " arena chunks ever loaded"
                                        + " (of those, " + ready + " reached entity-ticking)"
                                : "all " + total + " arena chunks loaded but only " + ready
                                        + " reached entity-ticking";
                        record(scene, SceneOutcome.ENV_FAIL, 0, "the arena never became usable: "
                                + what + " after " + phaseTicks + " ticks, and neither that nor the level's chunks ("
                                + loaded + " loaded, " + ticking + " turned ticking so far) had changed for "
                                + prepStalledTicks + " ticks. Dimension "
                                + level.dimension().location() + " at " + origin.getX() + ","
                                + origin.getZ() + ". " + ArenaChunkReport.describe(level, origin, radius)
                                + (prepSampler == null ? "" : " Server thread while stalled: " + prepSampler.describe() + "."));
                        prepSampler = ServerThreadSampler.stop(prepSampler);
                        teardown(scene, level, origin, radius);
                    }
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
                    String failed = SceneSkipped.failureAfterChecks(ctx.softViolations(), s.getMessage());
                    if (failed != null) {
                        record(scene, SceneOutcome.FAIL, ctx.ticks(), failed);
                    } else {
                        record(scene, SceneOutcome.PASS, ctx.ticks(), "skipped: " + s.getMessage(), true);
                    }
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
        record(scene, outcome, ticks, reason, false);
    }

    /** @param skipped the scene never reached its subject. Stated by the two call sites that know
     *                 it rather than inferred downstream from the reason's prefix, so that a reworded
     *                 message cannot quietly turn a skip into a pass in every consumer at once. */
    private void record(Scene scene, SceneOutcome outcome, int ticks, String reason, boolean skipped) {
        long wallMs = System.currentTimeMillis() - sceneStartMs;
        StageWrightCommon.LOG.info("[{}] scene '{}' -> {} ({} ticks, {} ms){}", StageWrightCommon.MOD_ID,
                scene.name(), outcome, ticks, wallMs, reason == null ? "" : " — " + reason);
        // Recorded values travel with EVERY outcome, not just failures. On a PASS they are the
        // measurement the scene exists to take (a tick cost, a channel count, a TPS ratio), and the
        // results file is the only place downstream tooling can read them; a value that lives solely
        // in a failure message is unavailable exactly when the run is healthy.
        // ctx is null when a scene ENV_FAILs out of PREP, before any context exists.
        out.writeScene(scene.name(), outcome, ticks, wallMs, reason,
                ctx == null ? java.util.Map.of() : ctx.records(), skipped);
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
        // Back to the suite's frozen night BEFORE the audit's closing snapshot. A Clock.RUNNING
        // scene was GRANTED doDaylightCycle by the harness; leaving it set here would have the audit
        // report the scene for a gamerule the harness itself changed on its behalf.
        WorldPin.applyClock(server, Clock.MIDNIGHT);
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
        int total = (2 * radius + 1) * (2 * radius + 1);
        return readyChunks(level, origin, radius) == total;
    }

    /** How many of the arena's chunks are loaded AND entity-ticking. Counted rather than short-
     *  circuited so PREP can tell "still arriving" from "stuck", which is the difference between a
     *  slow pack and a broken one. */
    private int readyChunks(ServerLevel level, BlockPos origin, int radius) {
        return countChunks(level, origin, radius, true);
    }

    /**
     * How many are merely PRESENT — {@code hasChunkAt} alone, without the entity-ticking half.
     *
     * <p>Only ever used in the failure message, and it is the difference between two diagnoses that
     * were printing as one. {@link #readyChunks} ANDs two conditions, so a count of zero has two
     * causes that want opposite investigations: no chunk arrived at all (worldgen, or the ticket
     * never took), or all nine arrived and none was promoted to ENTITY_TICKING (the async step
     * {@code ChunkMap.prepareEntityTickingChunk} performs, which is a different mechanism with
     * different owners on each loader). The old message said "only 0 of 9 arena chunks ever loaded"
     * for both — and its alternative phrasing about entity-ticking sat on the {@code ready == total}
     * branch, which is unreachable while {@code ready < total}. So the entity-ticking case could not
     * be reported at all.
     *
     * <p>Measured 2026-08-23 in worlddriver: {@code pack.placesAndReadsBack} ENV_FAILs on
     * <b>integrated NeoForge only</b> — it passes on dedicated NeoForge and on integrated Fabric,
     * with {@code tps=20} on all three, so neither slow ticking nor the arena coordinate explains
     * it. Which of the two causes it is decides where to look next, and until this split the run
     * could not say.
     */
    private int loadedChunks(ServerLevel level, BlockPos origin, int radius) {
        return countChunks(level, origin, radius, false);
    }

    private int countChunks(ServerLevel level, BlockPos origin, int radius, boolean requireTicking) {
        int n = 0;
        for (int dx = -radius; dx <= radius; dx++)
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos p = origin.offset(dx * 16, 0, dz * 16);
                if (!level.hasChunkAt(p)) continue;
                if (requireTicking && !level.isPositionEntityTicking(p)) continue;
                n++;
            }
        return n;
    }

    private void nextScene() {
        index++;
        phase = Phase.PREP;
        phaseTicks = 0;
        prepReadyChunks = 0;
        prepLevelLoaded = -1;
        prepLevelTicking = -1;
        prepStalledTicks = 0;
        ctx = null;
        sceneLevel = null;
        // A fresh starvation window per scene, for the same reason the clock is applied per scene:
        // one scene's verdict must not be a function of how long its predecessor took.
        starvation.reset(ticksObserved);
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
        // After the footer, so the results file is complete before anything else can go wrong, and
        // before the hold returns: a held server hands the world back to whoever is using it.
        WorldPin.release(server);
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
        haltAfterResults(server);
    }

    /** End the run once its results file is complete: halt the server, and make sure the JVM goes
     *  with it. Shared with the path that records a registry which could not be built. */
    public static void haltAfterResults(MinecraftServer server) {
        server.halt(false);
        armExitWatchdog(server);
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
     *
     * <p><b>{@code halt} is already the most forceful thing a JVM can do to itself, and it is not
     * always enough.</b> It skips shutdown hooks — which is the point, since a hook that waits on
     * the server thread would deadlock against the thread that called it — but it still has to reach
     * a safepoint to bring the VM down, and a process wedged in native code never gets there. Measured
     * on a 262-mod pack: this warning is the last line in the log, the process then sits in futex
     * wait, {@code jcmd} cannot attach and {@code SIGQUIT} produces no thread dump — all three being
     * what a JVM stuck inside {@code VM_Exit} looks like from outside. So this is best-effort by
     * construction, and the supervisor is the backstop: the CLI stops waiting once the results file
     * carries its done footer and kills the process tree itself.
     */
    private static void armExitWatchdog(MinecraftServer server) {
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
        String named = scene.dimension();
        if (named != null) return levelNamed(named);
        Terrain terrain = scene.terrain();
        if (terrain.dimension() == null) return server.overworld();
        return levelNamed(terrain.dimension());
    }

    /** A loaded level by id, or null. Parsing is guarded because a scene's dimension string comes
     *  from an author rather than from {@link Terrain}'s fixed set, so it can be malformed. */
    private ServerLevel levelNamed(String id) {
        ResourceLocation parsed = ResourceLocation.tryParse(id);
        if (parsed == null) return null;
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, parsed));
    }

    /** Every dimension this server actually loaded, sorted — the only useful thing to say to
     *  somebody whose scene asked for one that is not here. */
    private String loadedDimensions() {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) names.add(level.dimension().location().toString());
        names.sort(String::compareTo);
        return String.join(", ", names);
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
        if (scene.dimension() != null) return withinBuildHeight(level, gridOrigin);
        if (!scene.terrain().onSurface()) return gridOrigin;
        return level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, gridOrigin);
    }

    /** Headroom kept between the arena origin and either build limit, so a scene that clears four
     *  blocks up and replaces the ground one down still lands inside the world. */
    private static final int BUILD_HEIGHT_MARGIN = 16;

    /**
     * The grid altitude, pulled inside a dimension's build limits.
     *
     * <p>{@code GRID_Y = 200} is an overworld number. The Nether's build limit is 127, and a mod
     * dimension may declare anything at all — so a scene running in one would have its arena placed
     * outside the world, where every {@code setBlock} is silently dropped and the scene then fails
     * asserting about blocks it "placed". Nothing throws on an out-of-range write, which is what
     * makes this worth a clamp rather than a check.
     *
     * <p>Clamped rather than surface-resolved on purpose: a named dimension is somebody else's, and
     * its heightmap under a bedrock ceiling (the Nether's is exactly this) answers with the ceiling.
     * Empty space at a predictable altitude is the same deal {@link Terrain#RUN_WORLD} offers, and
     * it is the one a scene can reason about without knowing the dimension's worldgen.
     */
    private static BlockPos withinBuildHeight(ServerLevel level, BlockPos gridOrigin) {
        int min = level.getMinBuildHeight() + BUILD_HEIGHT_MARGIN;
        int max = level.getMaxBuildHeight() - BUILD_HEIGHT_MARGIN;
        int y = Math.max(min, Math.min(gridOrigin.getY(), max));
        if (y == gridOrigin.getY()) return gridOrigin;
        StageWrightCommon.LOG.info("[{}] arena altitude {} is outside {}'s build range — using {}",
                StageWrightCommon.MOD_ID, gridOrigin.getY(), level.dimension().location(), y);
        return new BlockPos(gridOrigin.getX(), y, gridOrigin.getZ());
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
