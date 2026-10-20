package net.magicterra.stagewright.harness;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Waiting for one arena's chunks to arrive: the PREP phase of a single scene, with the judgement of
 * when waiting has stopped helping.
 *
 * <p>One instance per arena, because the judgement is made of state that carries from one tick to
 * the next — what had arrived last tick, and for how long nothing has changed. Whoever hands out
 * arenas calls {@link #poll} once a tick until it answers anything but {@link State#WAITING}.
 */
final class ArenaPrep {
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

    enum State { READY, WAITING, STUCK }

    private final MinecraftServer server;
    private final ServerLevel level;
    private final BlockPos origin;
    private final int radius;
    private final boolean waitForArena;

    /** Arena chunks ready as of the last tick, the level's loaded chunks and chunks turned ticking
     *  then, and how long none of the three has changed. */
    private int readyChunks;
    private int levelLoaded = -1;
    private int levelTicking = -1;
    private int stalledTicks;
    /** The numbers behind the latest {@link State#STUCK}, for the message. */
    private int lastReady;
    private int lastLoaded;
    private int lastTicking;
    /** Samples the server thread from half way through a stall; null the rest of the time. */
    private ServerThreadSampler sampler;

    /**
     * @param origin       the grid origin whose chunks were forced, not the context origin
     * @param waitForArena false for a scene that plays in the live world and never enters its plot
     */
    ArenaPrep(MinecraftServer server, ServerLevel level, BlockPos origin, int radius, boolean waitForArena) {
        this.server = server;
        this.level = level;
        this.origin = origin;
        this.radius = radius;
        this.waitForArena = waitForArena;
    }

    /** One tick of waiting; {@code ticksWaited} counts this one. */
    State poll(int ticksWaited) {
        // `!arena` short-circuits the WAIT, not the arena: the plot is still force-loaded and still
        // audited, so only the blocking changes. For a scene that plays in the live world and never
        // enters its plot, that wait is pure inherited risk — a stall in worldgen a hundred thousand
        // blocks away fails a scene whose claim has nothing to do with that ground.
        if (!waitForArena) {
            stop();
            return State.READY;
        }
        // Give up on STALL, not on elapsed time. A fixed tick budget is really a statement about how
        // fast chunks generate, and that is a property of the pack: 200 ticks is generous in vanilla
        // and marginal in a 450-mod pack, where an arena 100k blocks out is worldgen through fifty
        // structure mods. All the Mods 10 ENV_FAILed one run at ~10s and passed the next at ~9s,
        // which is the signature of a budget that is measuring the wrong thing — and the scene it
        // lands on is whichever one drew the slow arena, so it reads as a different bug every time.
        //
        // Progress is the honest test. While chunks keep arriving, the pack is working and waiting
        // costs nothing but wall clock; once nothing has changed for PREP_STALL_TICKS, something is
        // actually wrong and no amount of further waiting will fix it. The hard ceiling stays as a
        // backstop for a chunk system that dribbles forever.
        int ready = Arenas.countChunks(level, origin, radius, true);
        if (ready == Arenas.chunkTotal(radius)) {
            stop();
            return State.READY;
        }
        // Progress is the level's, not only the arena's. An arena waits its turn behind whatever else
        // the level is loading: on integrated NeoForge, a scene that adopted the real player sends it
        // back where it came from, the chunks there reload ahead of the next arena's neighbours, and
        // the arena stood still past PREP_STALL_TICKS while they did. Stalled means nothing in the
        // level loaded, unloaded or started ticking either.
        int loaded = level.getChunkSource().getLoadedChunksCount();
        int ticking = level.getChunkSource().getTickingGenerated();
        boolean arenaMoved = ready > readyChunks;
        if (arenaMoved || loaded != levelLoaded || ticking != levelTicking) {
            if (arenaMoved) readyChunks = ready;
            levelLoaded = loaded;
            levelTicking = ticking;
            stalledTicks = 0;
            sampler = ServerThreadSampler.stop(sampler);
        } else {
            stalledTicks++;
        }
        // Half way to giving up, start watching the server thread, so a stall that ends in ENV_FAIL
        // says where the thread spent the ticks it waited.
        if (stalledTicks == PREP_STALL_TICKS / 2 && sampler == null) {
            sampler = ServerThreadSampler.start(server.getRunningThread());
        }
        if (stalledTicks > PREP_STALL_TICKS || ticksWaited > PREP_CEILING_TICKS) {
            lastReady = ready;
            lastLoaded = loaded;
            lastTicking = ticking;
            return State.STUCK;
        }
        return State.WAITING;
    }

    /** Why the arena never became usable; only meaningful after {@link #poll} answered STUCK, and
     *  to be read before {@link #stop} since it names where the server thread spent the wait. */
    String stuckMessage(int ticksWaited) {
        int total = Arenas.chunkTotal(radius);
        // Both halves of the AND, separately — see loadedChunks. "0 of 9 ready" is two different
        // failures depending on whether the chunks are there.
        int present = loadedChunks();
        String what = present < total
                ? "only " + present + " of " + total + " arena chunks ever loaded"
                        + " (of those, " + lastReady + " reached entity-ticking)"
                : "all " + total + " arena chunks loaded but only " + lastReady
                        + " reached entity-ticking";
        return "the arena never became usable: "
                + what + " after " + ticksWaited + " ticks, and neither that nor the level's chunks ("
                + lastLoaded + " loaded, " + lastTicking + " turned ticking so far) had changed for "
                + stalledTicks + " ticks. Dimension "
                + level.dimension().location() + " at " + origin.getX() + ","
                + origin.getZ() + ". " + ArenaChunkReport.describe(level, origin, radius)
                + (sampler == null ? "" : " Server thread while stalled: " + sampler.describe() + ".");
    }

    /**
     * How many are merely PRESENT — {@code hasChunkAt} alone, without the entity-ticking half.
     *
     * <p>Only ever used in the failure message, and it is the difference between two diagnoses that
     * were printing as one. The ready count ANDs two conditions, so a count of zero has two
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
    private int loadedChunks() {
        return Arenas.countChunks(level, origin, radius, false);
    }

    /** Stop sampling; safe to call any number of times. */
    void stop() {
        sampler = ServerThreadSampler.stop(sampler);
    }
}
