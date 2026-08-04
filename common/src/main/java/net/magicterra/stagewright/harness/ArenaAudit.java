package net.magicterra.stagewright.harness;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.phys.AABB;

/**
 * What one scene left behind.
 *
 * <p>This exists to answer a question that kept being answered by assertion: does a scene need a
 * freshly created world to be isolated from the one before it? Recreating the world per scene is
 * possible and costs roughly forty times the wall clock of a whole suite, so the answer matters, and
 * "the grid already gives each scene its own arena" only covers terrain. It says nothing about the
 * state a world carries globally — entities that wandered out of the arena, weather a scene turned
 * on, a gamerule it flipped — which is exactly what a new world would reset and what a per-scene
 * origin cannot.
 *
 * <p>So: snapshot before the body runs, snapshot after teardown has drained the scene's own
 * cleanups, and report the difference. A clean diff across a full suite is evidence that the cheap
 * isolation is sufficient. A dirty one names the leak, which is more useful than a fresh world would
 * have been — a fresh world hides the leak rather than fixing it, and the scene keeps leaking into
 * whatever else shares its process.
 *
 * <p>Everything measured here is measured INSIDE the scene's arena, or is a single global flag the
 * scene could only have set deliberately. A level-wide entity count was tried and removed: on the
 * flat dedicated world it looked like a clean signal, and on a client topology's normal world it
 * reported 88 of 180 scenes as leaking, including a scene that ran for one tick and did nothing.
 * The entities were mobs spawning and despawning the way they do in any world. A metric that reads
 * "the world is alive" as "this scene is dirty" would have every run pointing at innocent scenes.
 */
final class ArenaAudit {

    /** The globals a new world would reset and a new arena would not. */
    record Snapshot(Set<UUID> arenaIds, boolean raining, boolean thundering,
                    int forcedChunks, long gameRuleFingerprint) {

        int arenaEntities() { return arenaIds.size(); }
    }

    private ArenaAudit() {}

    /** Everything within the scene's arena, plus the level-wide state around it. */
    static Snapshot take(ServerLevel level, BlockPos origin, int radius) {
        return new Snapshot(
                idsIn(level, arena(origin, radius)),
                level.isRaining(),
                level.isThundering(),
                level.getForcedChunks().size(),
                gameRuleFingerprint(level));
    }

    /**
     * Remove whatever the scene added to its own arena and did not take away.
     *
     * <p>Bounded to the arena box and to entities that were not there when the scene started, so it
     * cannot touch a fixture some other part of the run depends on. Players are never swept: a
     * client topology's real player is standing in the arena on purpose, and worlddriver's fake-player
     * avatars are players too and are already discarded by the scene's own cleanups.
     *
     * <p>"Not there when the scene started" includes a mob that wandered in on its own, which the
     * scene did not create and is not to blame for. Sweeping it anyway is deliberate: the arena
     * belongs to the scene for its duration, and the alternative is distinguishing a wanderer from a
     * spawn — which cannot be done from a snapshot, and would leave the wanderer standing either way.
     *
     * <p>This is the cheap half of what recreating the world per scene would buy. The expensive half
     * — a world with no history at all — buys nothing on top of it that the 512-block grid spacing
     * does not already provide.
     *
     * @return how many entities were removed
     */
    static int sweep(ServerLevel level, BlockPos origin, int radius, Snapshot before) {
        int swept = 0;
        for (Entity e : level.getEntities((Entity) null, arena(origin, radius), x -> true)) {
            if (e instanceof Player) continue;
            if (before.arenaIds().contains(e.getUUID())) continue;
            e.discard();
            swept++;
        }
        return swept;
    }

    /**
     * How the world differs from before the scene, one line per difference, empty when it does not.
     *
     * <p>Entity counts are compared rather than identities: an arena that ends with the same number
     * of entities it started with has not leaked, and tracking which ones would mean holding
     * references to entities across a teardown that exists to let go of them.
     *
     * <p>None of this measured anything until 2026-08-05. Arena chunks were never promoted to
     * entity-ticking, so every query behind these numbers returned nothing and the audit reported a
     * spotless world it could not see. A metric that cannot fail is worse than no metric, so if this
     * ever reads clean across a whole suite again, check that {@code entitiesLiveInTheArena} is
     * still in the registry and still passing before believing it.
     */
    static List<String> diff(Snapshot before, Snapshot after) {
        List<String> out = new ArrayList<>();
        // An INCREASE only. A drop is not a leak and reporting it produced exactly the wrong
        // headline: a scene that ran for 119 ticks while two mobs that were already there wandered
        // out of the box was named as the one scene that dirtied the world. Anything the scene
        // itself added and left has been swept by this point, and the sweep counts it.
        if (after.arenaEntities() > before.arenaEntities()) {
            out.add("entities left in the arena: " + before.arenaEntities()
                    + " -> " + after.arenaEntities());
        }
        if (after.raining != before.raining) out.add("rain left " + (after.raining ? "on" : "off"));
        if (after.thundering != before.thundering) {
            out.add("thunder left " + (after.thundering ? "on" : "off"));
        }
        // Every forced chunk here is the scene's doing: the harness pins its own arena with a region
        // ticket, which is not saved data and never appears in getForcedChunks. So the count needs no
        // window carved out of it — it is only ever chunks a scene pinned somewhere else with
        // /forceload and did not release.
        if (after.forcedChunks != before.forcedChunks) {
            out.add("force-loaded chunks: " + before.forcedChunks + " -> " + after.forcedChunks);
        }
        if (after.gameRuleFingerprint != before.gameRuleFingerprint) out.add("a gamerule was changed");
        return out;
    }

    private static AABB arena(BlockPos origin, int radius) {
        int reach = (radius * 2 + 1) * 8;               // half-width of the forced-chunk window
        return new AABB(origin).inflate(reach, 128, reach);
    }

    private static Set<UUID> idsIn(ServerLevel level, AABB box) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (Entity e : level.getEntities((Entity) null, box, x -> true)) ids.add(e.getUUID());
        return ids;
    }

    /**
     * A cheap value that changes when any gamerule does.
     *
     * <p>Serialising every rule per scene would be the thorough version and is not worth it here:
     * this only has to detect that something moved, and the scene that moved it is the one being
     * torn down. Which rule it was is a question for whoever reads the failure, and they have the
     * scene's name.
     */
    private static long gameRuleFingerprint(ServerLevel level) {
        GameRules rules = level.getGameRules();
        // The pack's own serialisation, which every rule type already implements for level.dat.
        return rules.copy().createTag().toString().hashCode();
    }
}
