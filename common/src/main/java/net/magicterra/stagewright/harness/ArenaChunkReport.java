package net.magicterra.stagewright.harness;

import java.lang.reflect.Method;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * What the chunk system says about an arena PREP gave up on, appended to that ENV_FAIL.
 *
 * <p>The failure it exists for: on integrated NeoForge, scenes ENV_FAIL with every arena chunk present
 * and none entity-ticking, and the count never changes again. Present-but-not-ticking has three
 * readings that want different investigations — the ticket is not at an entity-ticking level, the
 * holder was never promoted (its entity-ticking future still holds the unloaded result), or the
 * promotion ran and failed — and a neighbour short of FULL is what holds a promotion back. So per
 * arena chunk: the ticket level, the full status and that future; and the ticket levels of every chunk
 * within two of the arena, the ring a promotion waits on.
 *
 * <p>An instrument only: it reads, and never loads a chunk or adds a ticket.
 */
final class ArenaChunkReport {

    private ArenaChunkReport() {}

    /**
     * {@code ChunkMap.getVisibleChunkIfPresent} is protected, so it is looked up by its Mojang name: the
     * runtime name in a development run and on NeoForge. Where that name does not exist the report
     * falls back to {@code ServerChunkCache.getChunkDebugData}, which is public but has no future.
     */
    private static final Method VISIBLE_HOLDER = visibleHolder();

    private static Method visibleHolder() {
        try {
            Method m = ChunkMap.class.getDeclaredMethod("getVisibleChunkIfPresent", long.class);
            m.setAccessible(true);
            return m;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    static String describe(ServerLevel level, BlockPos origin, int radius) {
        ChunkPos centre = new ChunkPos(origin);
        StringBuilder out = new StringBuilder("Arena chunks (x,z ticket-level full-status entity-ticking-future):");
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                ChunkPos c = new ChunkPos(centre.x + dx, centre.z + dz);
                out.append(' ').append(c.x).append(',').append(c.z).append(' ').append(state(level, c)).append(';');
            }
        }
        int ring = radius + 2;
        out.append(" Ticket levels ").append(2 * ring + 1).append('x').append(2 * ring + 1)
                .append(" around it, north row first, - for no holder:");
        for (int dz = -ring; dz <= ring; dz++) {
            out.append(dz == -ring ? " " : " / ");
            for (int dx = -ring; dx <= ring; dx++) {
                if (dx > -ring) out.append(' ');
                out.append(ticketLevel(level, new ChunkPos(centre.x + dx, centre.z + dz)));
            }
        }
        return out.toString();
    }

    private static String state(ServerLevel level, ChunkPos c) {
        if (VISIBLE_HOLDER == null) return debugData(level, c);
        try {
            ChunkHolder h = holder(level, c);
            if (h == null) return "no-holder";
            return "L" + h.getTicketLevel() + " " + h.getFullStatus() + " " + future(h.getEntityTickingChunkFuture());
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

    private static String ticketLevel(ServerLevel level, ChunkPos c) {
        if (VISIBLE_HOLDER == null) {
            String data = level.getChunkSource().getChunkDebugData(c);
            int nl = data.indexOf('\n');
            return nl < 0 ? "-" : data.substring(0, nl);
        }
        try {
            ChunkHolder h = holder(level, c);
            return h == null ? "-" : Integer.toString(h.getTicketLevel());
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "?";
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
