package net.magicterra.stagewright.harness;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.magicterra.stagewright.StageWrightCommon;
import net.magicterra.stagewright.contract.Canary;
import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.contract.SceneFailure;
import net.magicterra.stagewright.contract.SceneOutcome;
import net.magicterra.stagewright.contract.SceneSkipped;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Serial scheduler, on a dedicated or an integrated server alike. One scene at a time,
 * each on its own grid-allocated origin in force-loaded chunks; per-scene tick budget;
 * results to the contract-v0 JSONL; halts the server when the registry is drained,
 * unless the run is a hold.
 *
 * Grid allocation: see {@link Arenas}. Chunks are force-loaded for the scene's lifetime and
 * released afterwards. Serial execution + per-scene origins keep arenas apart; teardown then
 * sweeps leftover entities from the arena, restores the suite clock and logs what the scene
 * leaked. The audit reports, it does not undo — anything else a scene changes is for its own
 * cleanup to reverse.
 */
public final class StageWrightHarness {
    private enum Phase { PREP, RUN, ADVANCE_DONE }

    /** The current scene's wait for its arena, from the first PREP tick until the arena is usable or
     *  given up on; null between scenes. */
    private ArenaPrep prep;

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

    /** The current scene's hold on its arena: the forced chunks, and the world as it stood before the
     *  scene's body ran, for the teardown leak audit. Null until the level resolves. */
    private ArenaLease lease;

    /** Every scene that left something behind, named once per suite. */
    private final Set<String> leakedScenes = new java.util.LinkedHashSet<>();

    private long sceneStartMs;
    private boolean finished;

    /** Bumped every tick, read by {@link StallWatchdog} from its own thread — volatile because
     *  those are different threads and a cached read would report a stall that is not happening. */
    private volatile long ticksObserved;

    /** The scene the tick is inside, so a stall can be blamed on it by name rather than on the run. */
    private volatile String runningScene = "<arming>";

    /** Whether the running scene's body has been entered; volatile for the same reason, since the
     *  watchdog's record has to say it too. */
    private volatile boolean bodyEntered;

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
        this.stallWatchdog = new StallWatchdog(out, () -> ticksObserved, () -> runningScene,
                () -> bodyEntered);
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
     *  whole registry loudly before the suite header is ever written. */
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
            // flag exactly this omission, proving the swallow gate is alive.
            StageWrightCommon.LOG.info("[{}] skipping swallow-canary '{}'", StageWrightCommon.MOD_ID, scene.name());
            nextScene();
            return;
        }

        BlockPos origin = Arenas.originFor(slotByName.get(scene.name()));
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
            if (lease != null) teardown(scene);
            starvation.reset(ticksObserved);
            return;
        }

        switch (phase) {
            case PREP -> {
                if (phaseTicks == 0) {
                    sceneStartMs = System.currentTimeMillis();
                    sceneLevel = Arenas.levelFor(server, scene.dimension(), scene.terrain());
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
                                    + " that registers it is not here. Loaded: " + Arenas.loadedDimensions(server),
                                    true);
                            // Nothing to tear down: this branch is reached before forceChunks and
                            // before any SceneContext exists, exactly like the ENV_FAIL below.
                            return;
                        }
                        record(scene, SceneOutcome.ENV_FAIL, 0, "terrain " + scene.terrain()
                                + " needs dimension '" + scene.terrain().dimension() + "', which this"
                                + " server does not have — StageWright's datapack did not load."
                                + " Loaded: " + Arenas.loadedDimensions(server));
                        return;
                    }
                    lease = ArenaLease.acquire(server, sceneLevel, origin, radius);
                    prep = new ArenaPrep(server, sceneLevel, origin, radius, scene.arena());
                }
                phaseTicks++;
                ServerLevel level = sceneLevel;
                // The plot is force-loaded above and audited below whether or not the scene waits
                // for it; ArenaPrep decides what a scene that never enters its plot is made to wait for.
                ArenaPrep.State arena = prep.poll(phaseTicks);
                if (arena == ArenaPrep.State.READY) {
                    ctx = new SceneContext(level,
                            Arenas.arenaOrigin(scene.dimension(), scene.terrain(), level, origin), radius);
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
                    lease.baseline(ctx.origin());
                    // Per scene, not per suite: this is what stops one scene's clock from being a
                    // function of how long its predecessors took — or of a predecessor having asked
                    // for Clock.RUNNING. AFTER the audit baseline, so a Clock.RUNNING scene's
                    // borrowed doDaylightCycle is not in the baseline that teardown's restore is
                    // compared against; and at the RUN edge rather than at PREP's first tick, so
                    // however many ticks chunk loading took are not ticks of a RUNNING scene's day.
                    WorldPin.applyClock(server, scene.clock());
                    phase = Phase.RUN;
                    phaseTicks = 0;
                    prep = null;
                } else if (arena == ArenaPrep.State.STUCK) {
                    record(scene, SceneOutcome.ENV_FAIL, 0, prep.stuckMessage(phaseTicks));
                    prep.stop();
                    teardown(scene);
                }
            }
            case RUN -> {
                phaseTicks++;
                try {
                    if (phaseTicks == 1) {
                        bodyEntered = true;
                        ctx.runBody(scene.body());
                    }
                    SceneContext.Progress p = ctx.advance();
                    if (p == SceneContext.Progress.DONE) {
                        record(scene, SceneOutcome.PASS, ctx.ticks(), ctx.passNote());
                        teardown(scene);
                    } else if (p == SceneContext.Progress.STEP_TIMEOUT) {
                        record(scene, SceneOutcome.TIMEOUT, ctx.ticks(), ctx.failureReason());
                        teardown(scene);
                    } else if (ctx.ticks() > scene.budgetTicks()) {
                        record(scene, SceneOutcome.TIMEOUT, ctx.ticks(),
                                "scene budget " + scene.budgetTicks() + " ticks exhausted");
                        teardown(scene);
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
                    teardown(scene);
                } catch (SceneFailure f) {
                    record(scene, SceneOutcome.FAIL, ctx.ticks(), f.getMessage());
                    teardown(scene);
                } catch (Throwable t) {
                    record(scene, SceneOutcome.FAIL, ctx.ticks(),
                            net.magicterra.stagewright.contract.Reasons.unexpected(t));
                    teardown(scene);
                }
            }
            case ADVANCE_DONE -> nextScene();
        }
    }

    private void record(Scene scene, SceneOutcome outcome, int ticks, String reason) {
        record(scene, outcome, ticks, reason, false);
    }

    /** How a scene resolved, before and after its cleanups had their say. */
    record Resolution(SceneOutcome outcome, String reason, boolean skipped) {
        /** A cleanup that threw turns a PASS into a FAIL. A skip stays a skip, as the attached runner
         *  keeps it: the scene still tested nothing, and coverage must not count it as executed. */
        Resolution afterCleanups(List<String> failed) {
            if (failed.isEmpty() || outcome != SceneOutcome.PASS) return this;
            return new Resolution(SceneOutcome.FAIL, "cleanup failed: " + String.join("; ", failed), skipped);
        }
    }

    /** @param skipped the scene never reached its subject. Stated by the two call sites that know
     *                 it rather than inferred downstream from the reason's prefix, so that a reworded
     *                 message cannot quietly turn a skip into a pass in every consumer at once. */
    private void record(Scene scene, SceneOutcome outcome, int ticks, String reason, boolean skipped) {
        // Before the cleanups: wallMs is the scene's own time, and teardown is not what it measures.
        long wallMs = System.currentTimeMillis() - sceneStartMs;
        // Before the write: a PASS on disk whose cleanup then threw would be a pass over a scene
        // that left the world broken for the next one.
        if (ctx != null) {
            List<String> failed = ctx.runCleanups(msg -> StageWrightCommon.LOG.warn("[{}] {}: {}",
                    StageWrightCommon.MOD_ID, scene.name(), msg));
            Resolution resolved = new Resolution(outcome, reason, skipped).afterCleanups(failed);
            outcome = resolved.outcome();
            reason = resolved.reason();
            skipped = resolved.skipped();
        }
        StageWrightCommon.LOG.info("[{}] scene '{}' -> {} ({} ticks, {} ms){}", StageWrightCommon.MOD_ID,
                scene.name(), outcome, ticks, wallMs, reason == null ? "" : " — " + reason);
        // Recorded values travel with EVERY outcome, not just failures. On a PASS they are the
        // measurement the scene exists to take (a tick cost, a channel count, a TPS ratio), and the
        // results file is the only place downstream tooling can read them; a value that lives solely
        // in a failure message is unavailable exactly when the run is healthy.
        // ctx is null when a scene ENV_FAILs out of PREP, before any context exists.
        out.writeScene(scene.name(), outcome, ticks, wallMs, reason,
                ctx == null ? java.util.Map.of() : ctx.records(), skipped, bodyEntered);
        phase = Phase.ADVANCE_DONE;
    }

    /** Every outcome ends here: release the arena's forced chunks. {@link #record} has already drained
     *  the cleanups so their failures reach the result; draining again is then a no-op, and stays so
     *  that no path can release the arena with a cleanup still pending. */
    private void teardown(Scene scene) {
        if (ctx != null) {
            ctx.runCleanups(msg -> StageWrightCommon.LOG.warn("[{}] {}: {}", StageWrightCommon.MOD_ID, scene.name(), msg));
        }
        // Logged by the lease, not folded into the scene's result, which is already written: a leak
        // matters for what it does to the NEXT scene, so the suite total logged at finish() counts.
        if (!lease.release(scene.name()).isEmpty()) leakedScenes.add(scene.name());
    }

    private void nextScene() {
        index++;
        phase = Phase.PREP;
        phaseTicks = 0;
        prep = null;
        lease = null;
        ctx = null;
        bodyEntered = false;
        sceneLevel = null;
        // A fresh starvation window per scene, for the same reason the clock is applied per scene:
        // one scene's verdict must not be a function of how long its predecessor took.
        starvation.reset(ticksObserved);
        if (index >= scenes.size()) finish();
    }

    /** Cancel resources on world shutdown without writing a successful completion footer. */
    public void stop() {
        stallWatchdog.stop();
        try {
            if (prep != null) prep.stop();
            if (!finished && index < scenes.size() && lease != null) {
                teardown(scenes.get(index));
            }
        } finally {
            WorldPin.release(server);
            finished = true;
        }
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

}
