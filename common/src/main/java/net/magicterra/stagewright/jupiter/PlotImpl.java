package net.magicterra.stagewright.jupiter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.worlddriver.api.methods.world.WorldContracts;
import net.magicterra.worlddriver.game.edit.BlockEdits;
import net.magicterra.worlddriver.game.edit.RegionSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
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
 * One leased plot, on its dimension's row ({@link PlotLayout}), far from spawn. Its state, block entities and
 * entities included, is kept in a WorldDriver {@link RegionSnapshot} the plot holds itself rather than in
 * {@code mc.world.snapshot}'s store, whose few dozen slots every test running at once would be competing for.
 */
final class PlotImpl implements Plot, ExtensionContext.Store.CloseableResource {

    static final int DEFAULT_SIZE = 16;

    private static final int ROW_Z = -200_000;
    private static final int BASE_Y = 200;

    private static final PlotLayout LAYOUT = new PlotLayout();

    /** Loads a plot's chunks until the lease forces them, keyed by lease. */
    private static final TicketType<Integer> TICKET = TicketType.create("stagewright_plot", Integer::compare);
    /** Distance 2 from full is the level {@code setChunkForced} gives: entity-ticking. */
    private static final int TICKET_DISTANCE = 2;
    /** Generating fresh chunks is the slow case; a minute of ticks covers it while the whole suite starts at once. */
    private static final int CHUNK_LOAD_TICKS = 1200;
    private static final AtomicInteger LEASES = new AtomicInteger();

    private final Game game;
    private final int lease = LEASES.incrementAndGet();
    private final ResourceKey<Level> dimension;
    private final int start;
    private final Vec3i size;
    private final Vec3i anchor;
    /** The world box the plot covers. */
    private final BoundingBox box;
    /** What the box held before the lease; set once the lease has kept it. */
    private RegionSnapshot before;

    private PlotImpl(Game game, ResourceKey<Level> dimension, int start, int floorY, Vec3i size, boolean centered) {
        this.game = game;
        this.dimension = dimension;
        this.start = start;
        this.size = size;
        this.anchor = centered ? new Vec3i(size.getX() / 2, size.getY() / 2, size.getZ() / 2) : Vec3i.ZERO;
        this.box = new BoundingBox(start, floorY, ROW_Z, start + size.getX() - 1, floorY + size.getY() - 1,
                ROW_Z + size.getZ() - 1);
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
            // The chunks load, and mostly generate, on worker threads while the server keeps ticking; reading the box
            // before they have would generate them inside one server task and stall every other test's call.
            MinecraftServer held = game.onServer(server -> {
                plot.loadChunks(server, true);
                return server;
            });
            // Evaluated on the server thread, once a tick.
            game.await().within(CHUNK_LOAD_TICKS).as("the chunks of " + plot + " to load")
                    .until(() -> plot.chunksLoaded(held));
            // One server task: nothing changes the box between keeping it and emptying it.
            game.runOnServer(server -> {
                // Forcing a loaded chunk costs nothing, and a forced chunk keeps the dimension ticking with no player in it.
                plot.forceChunks(server, true);
                plot.loadChunks(server, false);
                plot.before = RegionSnapshot.capture(plot.level(server),
                        new BlockPos(plot.box.minX(), plot.box.minY(), plot.box.minZ()), size.getX(), size.getY(),
                        size.getZ(), true, true);
                plot.empty(server);
            });
            return plot;
        } catch (RuntimeException | Error e) {
            // Nothing was handed out yet: put the chunks back and free the stretch.
            try {
                game.runOnServer(server -> {
                    plot.loadChunks(server, false);
                    plot.forceChunks(server, false);
                });
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
        game.runOnServer(server -> {
            ServerLevel level = level(server);
            before.restore(level);
            before.restoreEntities(level);
            forceChunks(server, false);
        });
        // Only a plot that was put back may be handed out again; a failed restore leaves its stretch taken.
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

    /** Whether {@code pos} in {@code dimension} lies in the plot's box. */
    boolean contains(ResourceKey<Level> dimension, BlockPos pos) {
        return this.dimension.equals(dimension) && box.isInside(pos);
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

    /**
     * Server thread: add or drop this lease's ticket on each of its chunks, which has them loaded over the
     * next ticks. {@code setChunkForced} on a chunk not yet loaded would load it on the spot.
     */
    private void loadChunks(MinecraftServer server, boolean load) {
        ServerChunkCache chunks = level(server).getChunkSource();
        for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
            for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                ChunkPos pos = new ChunkPos(cx, cz);
                if (load) chunks.addRegionTicket(TICKET, pos, TICKET_DISTANCE, lease);
                else chunks.removeRegionTicket(TICKET, pos, TICKET_DISTANCE, lease);
            }
        }
    }

    private void forceChunks(MinecraftServer server, boolean forced) {
        ServerLevel level = level(server);
        for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
            for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                level.setChunkForced(cx, cz, forced);
            }
        }
    }

    /** Server thread: whether every chunk of the plot is loaded, its entities included. */
    private boolean chunksLoaded(MinecraftServer server) {
        ServerLevel level = level(server);
        for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
            for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                if (level.getChunkSource().getChunkNow(cx, cz) == null
                        || !level.areEntitiesLoaded(ChunkPos.asLong(cx, cz))) return false;
            }
        }
        return true;
    }

    /** Server thread: every cell to air and every non-player entity gone; the snapshot holds what was there. */
    private void empty(MinecraftServer server) {
        ServerLevel level = level(server);
        BlockState air = Blocks.AIR.defaultBlockState();
        for (BlockPos pos : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
            // No neighbour updates and no drops: the plot is emptied, not mined.
            if (!level.getBlockState(pos).isAir()) level.setBlock(pos, air, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
        AABB area = AABB.of(box);
        for (Entity e : level.getEntities((Entity) null, area, e -> !(e instanceof Player))) e.discard();
    }
}
