package net.magicterra.stagewright.jupiter;

import java.util.BitSet;
import java.util.Optional;

import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.worlddriver.api.methods.action.ActionBlockingApi;
import net.magicterra.worlddriver.api.methods.action.ActionContracts.FillParams;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.world.WorldBlockingApi;
import net.magicterra.worlddriver.api.methods.world.WorldContracts;
import net.magicterra.worlddriver.api.methods.world.WorldContracts.RestoreParams;
import net.magicterra.worlddriver.api.methods.world.WorldContracts.SnapshotParams;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * One leased plot. Plots sit on a row of cells in the overworld, {@value #SPACING} blocks apart, far from spawn and
 * from the scene arenas; a lease takes the lowest free cell. Its state is kept with WorldDriver's
 * {@code mc.world.snapshot} (entities included) and put back with {@code mc.world.restore}.
 */
final class PlotImpl implements Plot, ExtensionContext.Store.CloseableResource {

    static final int DEFAULT_SIZE = 16;
    static final int MAX_SIZE = 64;

    /** Cell pitch: the largest plot plus a two-chunk margin, chunk-aligned. */
    private static final int SPACING = 96;
    private static final int ROW_Z = -200_000;
    private static final int BASE_Y = 200;

    private static final BitSet LEASED = new BitSet();

    private final Game game;
    private final int cell;
    private final int size;
    private final BlockPos origin;

    private PlotImpl(Game game, int cell, int size) {
        this.game = game;
        this.cell = cell;
        this.size = size;
        this.origin = new BlockPos(cell * SPACING, BASE_Y, ROW_Z);
    }

    /** Lease a plot of side {@code size}: load its chunks, keep its state, and empty it. */
    static PlotImpl lease(Game game, int size) {
        int cell;
        synchronized (LEASED) {
            cell = LEASED.nextClearBit(0);
            LEASED.set(cell);
        }
        PlotImpl plot = new PlotImpl(game, cell, size);
        try {
            game.runOnServer(server -> plot.forceChunks(server.overworld(), true));
            game.api(WorldBlockingApi::of).snapshot(new SnapshotParams(position(plot.origin), position(plot.max()),
                    Optional.empty(), Optional.of(plot.snapshotId()), true, true));
            game.runOnServer(server -> plot.empty(server.overworld()));
            return plot;
        } catch (RuntimeException | Error e) {
            // Nothing was handed out yet: put the chunks back and free the cell.
            try {
                game.runOnServer(server -> plot.forceChunks(server.overworld(), false));
            } catch (RuntimeException suppressed) {
                e.addSuppressed(suppressed);
            }
            synchronized (LEASED) {
                LEASED.clear(cell);
            }
            throw e;
        }
    }

    /** Put the plot back as the lease found it and release its chunks and cell. */
    @Override
    public void close() {
        game.api(WorldBlockingApi::of).restore(new RestoreParams(snapshotId(), true, false));
        // Only a plot that was put back may be handed out again; a failed restore leaves its cell taken.
        game.runOnServer(server -> forceChunks(server.overworld(), false));
        synchronized (LEASED) {
            LEASED.clear(cell);
        }
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public BlockPos origin() {
        return origin;
    }

    @Override
    public BlockPos at(int x, int y, int z) {
        check(x, y, z);
        return origin.offset(x, y, z);
    }

    @Override
    public Plot fill(int x1, int y1, int z1, int x2, int y2, int z2, Block block) {
        check(x1, y1, z1);
        check(x2, y2, z2);
        int minY = Math.min(y1, y2);
        int maxY = Math.max(y1, y2);
        int column = (Math.abs(x2 - x1) + 1) * (Math.abs(z2 - z1) + 1);
        // One mc.action.fill holds at most MAX_VOLUME cells; a bigger box goes in horizontal slabs.
        int slab = Math.max(1, WorldContracts.MAX_VOLUME / column);
        String type = BuiltInRegistries.BLOCK.getKey(block).toString();
        ActionBlockingApi action = game.api(ActionBlockingApi::of);
        for (int y = minY; y <= maxY; y += slab) {
            int top = Math.min(maxY, y + slab - 1);
            action.fill(new FillParams(position(origin.offset(x1, y, z1)), position(origin.offset(x2, top, z2)), type,
                    false));
        }
        return this;
    }

    @Override
    public String toString() {
        return "Plot[cell " + cell + ", " + size + "³ at " + origin.toShortString() + "]";
    }

    private void check(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= size || y >= size || z >= size) {
            throw new IllegalArgumentException("(" + x + ", " + y + ", " + z + ") is outside the plot, whose cells run"
                    + " from 0 to " + (size - 1) + " on each axis");
        }
    }

    private BlockPos max() {
        return origin.offset(size - 1, size - 1, size - 1);
    }

    private String snapshotId() {
        return "stagewright.plot." + cell;
    }

    private void forceChunks(ServerLevel level, boolean forced) {
        BlockPos max = max();
        for (int cx = origin.getX() >> 4; cx <= max.getX() >> 4; cx++) {
            for (int cz = origin.getZ() >> 4; cz <= max.getZ() >> 4; cz++) {
                level.setChunkForced(cx, cz, forced);
            }
        }
    }

    /** Server thread: every cell to air and every non-player entity gone; the snapshot holds what was there. */
    private void empty(ServerLevel level) {
        BlockState air = Blocks.AIR.defaultBlockState();
        for (BlockPos pos : BlockPos.betweenClosed(origin, max())) {
            // No neighbour updates and no drops: the plot is emptied, not mined.
            if (!level.getBlockState(pos).isAir()) level.setBlock(pos, air, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
        AABB box = AABB.encapsulatingFullBlocks(origin, max());
        for (Entity e : level.getEntities((Entity) null, box, e -> !(e instanceof Player))) e.discard();
    }

    private static BlockPosition position(BlockPos pos) {
        return new BlockPosition(pos.getX(), pos.getY(), pos.getZ());
    }
}
