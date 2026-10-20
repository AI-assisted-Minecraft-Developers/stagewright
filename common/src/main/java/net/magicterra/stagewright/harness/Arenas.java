package net.magicterra.stagewright.harness;

import net.magicterra.stagewright.StageWrightCommon;
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
 * Where an arena sits and how it is held open, apart from who is using it.
 *
 * <p>Grid allocation: origin i = (GRID_X0 + i*GRID_STEP, GRID_Y, GRID_Z0), far from spawn so a flat
 * world's spawn chunks never overlap an arena. This is everything about an arena that does not
 * depend on the scheduler that hands it out, which is why it lives apart from
 * {@link StageWrightHarness}: the suite's scenes and any other caller that needs a plot in the same
 * world get the same ground, the same chunk ticket and the same build-height clamp.
 */
final class Arenas {
    private static final int GRID_X0 = 100_000;
    private static final int GRID_Z0 = 100_000;
    private static final int GRID_Y = 200;
    private static final int GRID_STEP = 512;

    private Arenas() {}

    /** The grid position of a slot, before {@link #arenaOrigin} moves it onto terrain. */
    static BlockPos originFor(int slot) {
        return new BlockPos(GRID_X0 + slot * GRID_STEP, GRID_Y, GRID_Z0);
    }

    /**
     * The level an arena is built in, or {@code null} if the terrain asked for names a dimension this
     * server does not have.
     *
     * <p>Null rather than a fallback to the overworld on purpose. A scene asking for
     * {@link Terrain#GENERATED} wants hills; handing it the run world's empty sky instead would let
     * it fail its own terrain assertions with a message about missing blocks, sending whoever reads
     * the run looking for a bug in the scene rather than for the datapack that did not load.
     *
     * @param named a dimension the scene names itself, or null
     */
    static ServerLevel levelFor(MinecraftServer server, String named, Terrain terrain) {
        if (named != null) return levelNamed(server, named);
        if (terrain.dimension() == null) return server.overworld();
        return levelNamed(server, terrain.dimension());
    }

    /** A loaded level by id, or null. Parsing is guarded because a scene's dimension string comes
     *  from an author rather than from {@link Terrain}'s fixed set, so it can be malformed. */
    private static ServerLevel levelNamed(MinecraftServer server, String id) {
        ResourceLocation parsed = ResourceLocation.tryParse(id);
        if (parsed == null) return null;
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, parsed));
    }

    /** Every dimension this server actually loaded, sorted — the only useful thing to say to
     *  somebody whose scene asked for one that is not here. */
    static String loadedDimensions(MinecraftServer server) {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) names.add(level.dimension().location().toString());
        names.sort(String::compareTo);
        return String.join(", ", names);
    }

    /**
     * Where the arena actually sits.
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
     *
     * @param named a dimension the scene names itself, or null
     */
    static BlockPos arenaOrigin(String named, Terrain terrain, ServerLevel level, BlockPos gridOrigin) {
        if (named != null) return withinBuildHeight(level, gridOrigin);
        if (!terrain.onSurface()) return gridOrigin;
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
    static void forceChunks(ServerLevel level, BlockPos origin, int radius, boolean force) {
        ChunkPos centre = new ChunkPos(origin);
        int reach = radius + ENTITY_TICKING_MARGIN;
        if (force) {
            level.getChunkSource().addRegionTicket(TicketType.FORCED, centre, reach, centre);
        } else {
            level.getChunkSource().removeRegionTicket(TicketType.FORCED, centre, reach, centre);
        }
    }

    /**
     * How many of the arena's (2r+1)x(2r+1) chunks are present, and with {@code requireTicking} also
     * entity-ticking.
     *
     * <p>Entity ticking is checked as well as presence, and the difference is not academic.
     * {@code hasChunkAt} answers yes at FULL, and the promotion to ENTITY_TICKING is an asynchronous
     * step after that: {@code ChunkMap.prepareEntityTickingChunk} waits for the whole 5×5 around the
     * chunk to be FULL, which at an arena 100k blocks out is fresh worldgen and takes real ticks. A
     * scene that starts inside that gap gets an arena which reads completely normal — blocks work,
     * and every entity in it is visible to {@code getEntities} and to command selectors, because
     * FULL already means {@code Visibility.TRACKED}. The only thing missing is that nothing in it
     * ever ticks, which no assertion notices until one depends on it. Waiting for it is also what
     * makes the ticket radius self-checking: get it wrong and scenes ENV_FAIL in PREP, instead of
     * passing while testing nothing.
     */
    static int countChunks(ServerLevel level, BlockPos origin, int radius, boolean requireTicking) {
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

    /** The number of chunks in an arena of this radius. */
    static int chunkTotal(int radius) {
        return (2 * radius + 1) * (2 * radius + 1);
    }
}
