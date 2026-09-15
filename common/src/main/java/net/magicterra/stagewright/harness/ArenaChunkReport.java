package net.magicterra.stagewright.harness;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ChunkTaskPriorityQueue;
import net.minecraft.server.level.ChunkTaskPriorityQueueSorter;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * What the chunk system says about an arena PREP gave up on, appended to that ENV_FAIL.
 *
 * <p>The failure it exists for: on integrated NeoForge, scenes ENV_FAIL with every arena chunk present
 * and none entity-ticking, and the count never changes again. The first reading put the arena chunks'
 * tickets at entity-ticking level with full status ENTITY_TICKING and their entity-ticking future
 * pending: the promotion was asked for and never finished. That promotion is
 * {@code ChunkMap.prepareEntityTickingChunk}: generation to {@code FULL} of every chunk within two,
 * then a hop onto the main-thread executor. The second reading had every chunk within two that was not
 * already FULL stopped at {@code spawn}, the step before, with no generation task pending. The
 * {@code FULL} step runs on the main thread through {@code ChunkMap}'s task sorter, which holds a
 * chunk's tasks while an earlier one is acquired and puts an executor to sleep until a release.
 *
 * <p>So per arena chunk: the ticket level, the full status and the ticking and entity-ticking futures;
 * per chunk within two of the arena: the ticket level, how far generation got and the full-chunk
 * future; then the pending generation tasks, the main-thread tasks, and the sorter's state.
 *
 * <p>An instrument only: it reads, and never loads a chunk or adds a ticket. The private members are
 * found by their Mojang names, the runtime names in a development run and on NeoForge.
 */
final class ArenaChunkReport {

    private ArenaChunkReport() {}

    /**
     * {@code ChunkMap.getVisibleChunkIfPresent}, protected. Where that name does not exist the report
     * falls back to {@code ServerChunkCache.getChunkDebugData}, which is public but has no future.
     */
    private static final Method VISIBLE_HOLDER = visibleHolder();

    private static final Field GENERATION_TASKS = declaredField(ChunkMap.class, "pendingGenerationTasks");
    private static final Field SORTER = declaredField(ChunkMap.class, "queueSorter");
    private static final Field SORTER_QUEUES = declaredField(ChunkTaskPriorityQueueSorter.class, "queues");

    private static Method visibleHolder() {
        try {
            Method m = ChunkMap.class.getDeclaredMethod("getVisibleChunkIfPresent", long.class);
            m.setAccessible(true);
            return m;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** The private field, or null where it has another name. */
    private static Field declaredField(Class<?> owner, String name) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    static String describe(ServerLevel level, BlockPos origin, int radius) {
        ChunkPos centre = new ChunkPos(origin);
        StringBuilder out = new StringBuilder(
                "Arena chunks (x,z ticket-level full-status ticking-future entity-ticking-future):");
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                ChunkPos c = new ChunkPos(centre.x + dx, centre.z + dz);
                out.append(' ').append(c.x).append(',').append(c.z).append(' ').append(state(level, c)).append(';');
            }
        }
        int ring = radius + 2;
        out.append(" Within two of it, ").append(2 * ring + 1).append('x').append(2 * ring + 1)
                .append(" north row first, ticket-level/latest-status/full-future (+ done, . pending, x failed;"
                        + " - for no holder):");
        for (int dz = -ring; dz <= ring; dz++) {
            out.append(dz == -ring ? " " : " / ");
            for (int dx = -ring; dx <= ring; dx++) {
                if (dx > -ring) out.append(' ');
                out.append(neighbour(level, new ChunkPos(centre.x + dx, centre.z + dz)));
            }
        }
        out.append(". Queued: ").append(generationTasks(level)).append(" generation task(s), ")
                .append(level.getChunkSource().getPendingTasksCount()).append(" main-thread task(s). Sorter: ")
                .append(sorter(level)).append('.');
        return out.toString();
    }

    private static String state(ServerLevel level, ChunkPos c) {
        if (VISIBLE_HOLDER == null) return debugData(level, c);
        try {
            ChunkHolder h = holder(level, c);
            if (h == null) return "no-holder";
            return "L" + h.getTicketLevel() + " " + h.getFullStatus() + " " + future(h.getTickingChunkFuture())
                    + " " + future(h.getEntityTickingChunkFuture());
        } catch (ReflectiveOperationException | RuntimeException e) {
            return debugData(level, c) + " (holder unreadable: " + e + ")";
        }
    }

    /** {@code pending}, {@code done}, or why not: a holder never promoted still holds the unloaded result. */
    private static String future(CompletableFuture<ChunkResult<LevelChunk>> f) {
        if (!f.isDone()) return "pending";
        try {
            ChunkResult<LevelChunk> r = f.join();
            if (r == null) return "null";
            return r.isSuccess() ? "done" : "failed:" + r.getError();
        } catch (CompletionException e) {
            return "threw:" + e.getCause();
        } catch (CancellationException e) {
            return "cancelled";
        }
    }

    private static String neighbour(ServerLevel level, ChunkPos c) {
        if (VISIBLE_HOLDER == null) {
            String data = level.getChunkSource().getChunkDebugData(c);
            int nl = data.indexOf('\n');
            return nl < 0 ? "-" : data.substring(0, nl);
        }
        try {
            ChunkHolder h = holder(level, c);
            if (h == null) return "-";
            String f = future(h.getFullChunkFuture());
            char mark = f.equals("done") ? '+' : f.equals("pending") ? '.' : 'x';
            return h.getTicketLevel() + "/" + statusName(h.getLatestStatus()) + "/" + mark;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "?";
        }
    }

    private static String statusName(ChunkStatus s) {
        return s == null ? "none" : BuiltInRegistries.CHUNK_STATUS.getKey(s).getPath();
    }

    private static String generationTasks(ServerLevel level) {
        if (GENERATION_TASKS == null) return "?";
        try {
            return Integer.toString(((List<?>) GENERATION_TASKS.get(level.getChunkSource().chunkMap)).size());
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "?";
        }
    }

    /**
     * The sorter's own debug line (each executor's queue with the chunks it holds acquired, and how many
     * executors sleep waiting for a release), whether it has work, and each queue's name with its first
     * non-empty priority, which is {@link ChunkTaskPriorityQueue#PRIORITY_LEVEL_COUNT} when it is empty.
     * The sorter changes on its own mailbox thread, so a read that races it says so instead.
     */
    private static String sorter(ServerLevel level) {
        if (SORTER == null) return "?";
        try {
            ChunkTaskPriorityQueueSorter s = (ChunkTaskPriorityQueueSorter) SORTER.get(level.getChunkSource().chunkMap);
            StringBuilder out = new StringBuilder(s.getDebugStatus()).append("; has work ").append(s.hasWork());
            if (SORTER_QUEUES != null) {
                out.append("; first non-empty priority (empty = ").append(ChunkTaskPriorityQueue.PRIORITY_LEVEL_COUNT)
                        .append(") ").append(((Map<?, ?>) SORTER_QUEUES.get(s)).values());
            }
            return out.toString();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "? (" + e + ")";
        }
    }

    private static ChunkHolder holder(ServerLevel level, ChunkPos c) throws ReflectiveOperationException {
        return (ChunkHolder) VISIBLE_HOLDER.invoke(level.getChunkSource().chunkMap, c.toLong());
    }

    /** The public debug line: ticket level, status and full status, colour codes and newlines dropped. */
    private static String debugData(ServerLevel level, ChunkPos c) {
        return level.getChunkSource().getChunkDebugData(c).replaceAll("§.", "").replace('\n', ' ');
    }
}
