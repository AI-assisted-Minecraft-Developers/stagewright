package net.magicterra.stagewright.harness;

import java.util.List;
import net.magicterra.stagewright.StageWrightCommon;
import net.magicterra.stagewright.contract.Clock;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * One arena held open for as long as something is using it, and what has to happen when it is let go.
 *
 * <p>Acquiring forces the chunks; releasing sweeps what the user left, releases the chunks, puts the
 * suite's clock back and audits the world against the baseline taken when the user started. The
 * audit reports, it does not undo — anything else a scene changes is for its own cleanup to reverse.
 */
final class ArenaLease {
    private final MinecraftServer server;
    private final ServerLevel level;
    private final BlockPos gridOrigin;
    private final int radius;

    /** The world as it stood before the user started, for the leak audit. Null until
     *  {@link #baseline}, which is also how an arena that never became usable is told apart: nothing
     *  ever ran in it, so there is nothing to sweep or audit. */
    private ArenaAudit.Snapshot before;

    private ArenaLease(MinecraftServer server, ServerLevel level, BlockPos gridOrigin, int radius) {
        this.server = server;
        this.level = level;
        this.gridOrigin = gridOrigin;
        this.radius = radius;
    }

    /** Pin the arena at {@code gridOrigin} open; the chunks arrive over the ticks that follow. */
    static ArenaLease acquire(MinecraftServer server, ServerLevel level, BlockPos gridOrigin, int radius) {
        Arenas.forceChunks(level, gridOrigin, radius, true);
        return new ArenaLease(server, level, gridOrigin, radius);
    }

    ServerLevel level() {
        return level;
    }

    BlockPos gridOrigin() {
        return gridOrigin;
    }

    int radius() {
        return radius;
    }

    /** Take the audit baseline, at the context's origin rather than the grid slot's: a terrain scene
     *  plays at the surface, and a box at y=200 would neither sweep nor report what it left. */
    void baseline(BlockPos contextOrigin) {
        before = ArenaAudit.take(level, contextOrigin, radius);
    }

    /**
     * Let the arena go. Callable more than once: after the first call only the chunk release and the
     * clock are repeated, both harmless.
     *
     * @param user what to call the user in the log
     * @return one line per way the user left the world different from the baseline, empty when none
     */
    List<String> release(String user) {
        sweep(user);
        Arenas.forceChunks(level, gridOrigin, radius, false);
        // Back to the suite's frozen night BEFORE the audit's closing snapshot. A Clock.RUNNING
        // scene was GRANTED doDaylightCycle by the harness; leaving it set here would have the audit
        // report the scene for a gamerule the harness itself changed on its behalf.
        WorldPin.applyClock(server, Clock.MIDNIGHT);
        return audit(user);
    }

    /**
     * Discard whatever the user added to the arena and did not remove itself.
     *
     * <p>Before the chunks are released, because discarding an entity in an unloaded chunk does not
     * do anything and the leak survives to the next run's world.
     *
     * <p>The scene's own cleanups have already run at this point, so anything still standing here is
     * something the scene did not know it created — a mob's drops, a projectile, a spawned helper it
     * forgot. Sweeping is a backstop for that, not a replacement for the cleanups: a scene that
     * relies on this instead of discarding its own avatar still leaks everywhere the arena is not.
     */
    private void sweep(String user) {
        if (before == null) return;
        int swept = ArenaAudit.sweep(level, before);
        if (swept > 0) {
            StageWrightCommon.LOG.info("[{}] swept {} leftover entit{} from '{}' arena",
                    StageWrightCommon.MOD_ID, swept, swept == 1 ? "y" : "ies", user);
        }
    }

    /**
     * Report what the user left in the world after its own cleanups had their turn.
     *
     * <p>Logged and returned, not folded into the scene's result, which is already written: a leak
     * matters for what it does to the NEXT scene, so the suite total logged at the end is what counts.
     */
    private List<String> audit(String user) {
        if (before == null) return List.of();           // ENV_FAIL out of PREP: nothing ever ran
        List<String> leaks = ArenaAudit.diff(before, ArenaAudit.again(level, before));
        for (String leak : leaks) {
            StageWrightCommon.LOG.warn("[{}] LEAK after '{}': {}", StageWrightCommon.MOD_ID, user, leak);
        }
        before = null;
        return leaks;
    }
}
