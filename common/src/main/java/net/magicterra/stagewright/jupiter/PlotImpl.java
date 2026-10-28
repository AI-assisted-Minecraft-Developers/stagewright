package net.magicterra.stagewright.jupiter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.world.WorldBlockingApi;
import net.magicterra.worlddriver.api.methods.world.WorldContracts;
import net.magicterra.worlddriver.api.methods.world.WorldContracts.RestoreParams;
import net.magicterra.worlddriver.api.methods.world.WorldContracts.SnapshotParams;
import net.magicterra.worlddriver.game.edit.BlockEdits;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * One leased plot, on its dimension's row ({@link PlotLayout}), far from spawn. Its state is kept with WorldDriver's
 * {@code mc.world.snapshot} (entities included), in as many snapshots as its volume needs, and put back with
 * {@code mc.world.restore}.
 */
final class PlotImpl implements Plot, ExtensionContext.Store.CloseableResource {

    static final int DEFAULT_SIZE = 16;

    private static final int ROW_Z = -200_000;
    private static final int BASE_Y = 200;

    private static final PlotLayout LAYOUT = new PlotLayout();
    private static final AtomicInteger LEASES = new AtomicInteger();

    private final Game game;
    private final ResourceKey<Level> dimension;
    private final int start;
    private final int lease;
    private final Vec3i size;
    private final Vec3i anchor;
    /** The world box the plot covers. */
    private final BoundingBox box;
    private final List<BoundingBox> snapshots;

    private PlotImpl(Game game, ResourceKey<Level> dimension, int start, int floorY, Vec3i size, boolean centered) {
        this.game = game;
        this.dimension = dimension;
        this.start = start;
        this.lease = LEASES.incrementAndGet();
        this.size = size;
        this.anchor = centered ? new Vec3i(size.getX() / 2, size.getY() / 2, size.getZ() / 2) : Vec3i.ZERO;
        this.box = new BoundingBox(start, floorY, ROW_Z, start + size.getX() - 1, floorY + size.getY() - 1,
                ROW_Z + size.getZ() - 1);
        this.snapshots = PlotLayout.tiles(box, WorldContracts.MAX_SNAPSHOT_VOLUME);
    }

    /** Lease a plot of {@code size} in {@code dimension}: load its chunks, keep its state, and empty it. */
    static PlotImpl lease(Game game, Vec3i size, boolean centered, ResourceKey<Level> dimension) {
        for (int side : new int[] {size.getX(), size.getZ()}) {
            if (side < 1 || side > PlotLayout.MAX_SIDE) {
                throw new IllegalArgumentException("a plot's x and z sides run from 1 to " + PlotLayout.MAX_SIDE
                        + ", but this one is " + size.getX() + " × " + size.getY() + " × " + size.getZ());
            }
        }
        if (size.getY() < 1) throw new IllegalArgumentException("a plot is at least 1 tall, but this one is " + size.getY());
        int floorY = game.onServer(server -> {
            ServerLevel level = GameImpl.level(server, dimension);
            return PlotLayout.floorY(BASE_Y, level.getMinBuildHeight(), level.getMaxBuildHeight(), size.getY());
        });
        int start = LAYOUT.lease(dimension, size.getX());
        PlotImpl plot = new PlotImpl(game, dimension, start, floorY, size, centered);
        try {
            game.runOnServer(server -> plot.forceChunks(server, true));
            WorldBlockingApi world = game.api(WorldBlockingApi::of);
            for (int i = 0; i < plot.snapshots.size(); i++) {
                BoundingBox tile = plot.snapshots.get(i);
                world.snapshot(new SnapshotParams(position(tile.minX(), tile.minY(), tile.minZ()),
                        position(tile.maxX(), tile.maxY(), tile.maxZ()), Optional.of(dimension.location().toString()),
                        Optional.of(plot.snapshotId(i)), true, true));
            }
            game.runOnServer(plot::empty);
            return plot;
        } catch (RuntimeException | Error e) {
            // Nothing was handed out yet: put the chunks back and free the stretch.
            try {
                game.runOnServer(server -> plot.forceChunks(server, false));
            } catch (RuntimeException suppressed) {
                e.addSuppressed(suppressed);
            }
            LAYOUT.release(dimension, start);
            throw e;
        }
    }

    /** Put the plot back as the lease found it and release its chunks and stretch. */
    @Override
    public void close() {
        // Every entity out first: one that walked from one snapshot's box into another's would otherwise be there
        // twice when the first puts it back.
        game.runOnServer(server -> discardEntities(level(server)));
        WorldBlockingApi world = game.api(WorldBlockingApi::of);
        for (int i = 0; i < snapshots.size(); i++) world.restore(new RestoreParams(snapshotId(i), true, false));
        // Only a plot that was put back may be handed out again; a failed restore leaves its stretch taken.
        game.runOnServer(server -> forceChunks(server, false));
        LAYOUT.release(dimension, start);
    }

    @Override
    public Vec3i size() {
        return size;
    }

    @Override
    public ResourceKey<Level> dimension() {
        return dimension;
    }

    @Override
    public Vec3i min() {
        return Vec3i.ZERO.subtract(anchor);
    }

    @Override
    public Vec3i max() {
        return size.offset(-1, -1, -1).subtract(anchor);
    }

    @Override
    public BlockPos origin() {
        return new BlockPos(box.minX(), box.minY(), box.minZ()).offset(anchor);
    }

    @Override
    public BlockPos at(int x, int y, int z) {
        check(x, y, z);
        return origin().offset(x, y, z);
    }

    @Override
    public Plot fill(int x1, int y1, int z1, int x2, int y2, int z2, Block block) {
        BlockPos a = at(x1, y1, z1);
        BlockPos b = at(x2, y2, z2);
        BlockState state = block.defaultBlockState();
        // One server task per tile, so a large fill lets the server tick between them.
        for (BoundingBox tile : PlotLayout.tiles(BoundingBox.fromCorners(a, b), WorldContracts.MAX_VOLUME)) {
            game.runOnServer(server -> BlockEdits.fill(level(server), new BlockPos(tile.minX(), tile.minY(), tile.minZ()),
                    new BlockPos(tile.maxX(), tile.maxY(), tile.maxZ()), state));
        }
        return this;
    }

    @Override
    public Plot structure(ResourceLocation id, int x, int y, int z) {
        return place(server -> server.getStructureManager().get(id).orElseThrow(() ->
                new IllegalArgumentException("this game has no structure template " + id)), id.toString(), x, y, z);
    }

    @Override
    public Plot structure(Path file, int x, int y, int z) {
        CompoundTag tag;
        try {
            tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read the structure file " + file.toAbsolutePath() + ": "
                    + (Files.exists(file) ? e.getMessage() : "there is no such file"), e);
        }
        return place(server -> server.getStructureManager().readStructure(tag), file.toString(), x, y, z);
    }

    private Plot place(Function<MinecraftServer, StructureTemplate> template, String what,
                       int x, int y, int z) {
        BlockPos corner = at(x, y, z);
        game.runOnServer(server -> {
            StructureTemplate t = template.apply(server);
            Vec3i far = t.getSize().offset(-1, -1, -1);
            if (!box.isInside(corner) || !box.isInside(corner.offset(far))) {
                throw new IllegalArgumentException("the structure " + what + " is " + t.getSize().toShortString()
                        + " and does not fit in the plot at (" + x + ", " + y + ", " + z + "); the plot's cells run"
                        + " from " + min().toShortString() + " to " + max().toShortString());
            }
            t.placeInWorld(level(server), corner, corner, new StructurePlaceSettings(), level(server).random,
                    Block.UPDATE_CLIENTS);
        });
        return this;
    }

    @Override
    public String toString() {
        return "Plot[" + size.getX() + "×" + size.getY() + "×" + size.getZ() + " in " + dimension.location() + " at "
                + box.minX() + ", " + box.minY() + ", " + box.minZ() + "]";
    }

    private void check(int x, int y, int z) {
        Vec3i min = min();
        Vec3i max = max();
        if (x < min.getX() || y < min.getY() || z < min.getZ() || x > max.getX() || y > max.getY() || z > max.getZ()) {
            throw new IllegalArgumentException("(" + x + ", " + y + ", " + z + ") is outside the plot, whose cells run"
                    + " from " + min.toShortString() + " to " + max.toShortString());
        }
    }

    private ServerLevel level(MinecraftServer server) {
        return GameImpl.level(server, dimension);
    }

    private String snapshotId(int tile) {
        return "stagewright.plot." + lease + "." + tile;
    }

    private void forceChunks(MinecraftServer server, boolean forced) {
        ServerLevel level = level(server);
        for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
            for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                level.setChunkForced(cx, cz, forced);
            }
        }
    }

    /** Server thread: every cell to air and every non-player entity gone; the snapshots hold what was there. */
    private void empty(MinecraftServer server) {
        ServerLevel level = level(server);
        BlockState air = Blocks.AIR.defaultBlockState();
        for (BlockPos pos : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
            // No neighbour updates and no drops: the plot is emptied, not mined.
            if (!level.getBlockState(pos).isAir()) level.setBlock(pos, air, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
        discardEntities(level);
    }

    private void discardEntities(ServerLevel level) {
        AABB area = AABB.of(box);
        for (Entity e : level.getEntities((Entity) null, area, e -> !(e instanceof Player))) e.discard();
    }

    private static BlockPosition position(int x, int y, int z) {
        return new BlockPosition(x, y, z);
    }
}
