package net.magicterra.stagewright.driver.verbs;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import net.magicterra.stagewright.StageWrightCommon;
import net.magicterra.stagewright.jupiter.ServerTicks;
import net.magicterra.stagewright.test.methods.ChunksContracts.ChunksOp;
import net.magicterra.stagewright.test.methods.ChunksContracts.ChunksReply;
import net.magicterra.stagewright.test.methods.ChunksContracts.Reload;
import net.magicterra.stagewright.test.methods.ChunksContracts.Reloaded;
import net.magicterra.stagewright.test.methods.ChunksContracts.Unload;
import net.magicterra.stagewright.test.methods.ChunksContracts.Unloaded;
import net.magicterra.stagewright.test.methods.TestQueriesApi;
import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.schema.ApiError;
import net.magicterra.worlddriver.api.schema.ApiException;
import net.magicterra.worlddriver.api.schema.ApiIssue;
import net.magicterra.worlddriver.api.thread.ServerThreadGuard;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/**
 * {@code mc.test.*} questions about the world. Each starts on the server thread, hopping there from a caller on
 * another thread; one that spans ticks answers once {@link ServerTicks} has seen it through, so it never holds the
 * server thread while it waits.
 */
final class TestQueriesService implements TestQueriesApi {

    @Override
    public CompletionStage<ChunksReply> chunks(ChunksOp op, CallContext ctx) {
        return onServer(server -> switch (op) {
            case Unload u -> unload(level(server, u.dimension()), u.pos(), u.withinTicks());
            case Reload r -> reload(level(server, r.dimension()), r.pos(), r.withinTicks());
        });
    }

    private static CompletionStage<ChunksReply> unload(ServerLevel level, BlockPosition pos, int within) {
        ChunkPos chunk = chunkOf(pos);
        level.setChunkForced(chunk.x, chunk.z, false);
        int start = level.getServer().getTickCount();
        return ServerTicks.await(() -> !loaded(level, chunk), within).thenApply(done -> new Unloaded(chunk.x, chunk.z,
                done, level.getServer().getTickCount() - start, done ? Optional.empty() : Optional.of(heldBy(level, chunk))));
    }

    private static CompletionStage<ChunksReply> reload(ServerLevel level, BlockPosition pos, int within) {
        ChunkPos chunk = chunkOf(pos);
        level.setChunkForced(chunk.x, chunk.z, true);
        int start = level.getServer().getTickCount();
        return ServerTicks.await(() -> level.getChunkSource().hasChunk(chunk.x, chunk.z)
                        && level.areEntitiesLoaded(chunk.toLong()), within)
                .thenApply(done -> new Reloaded(chunk.x, chunk.z, done, level.getServer().getTickCount() - start,
                        done ? entitiesIn(level, chunk) : 0));
    }

    private static boolean loaded(ServerLevel level, ChunkPos chunk) {
        return level.getChunkSource().hasChunk(chunk.x, chunk.z) || level.areEntitiesLoaded(chunk.toLong());
    }

    /** Why a chunk stayed: what the server has to say about it once the wait ran out. */
    private static String heldBy(ServerLevel level, ChunkPos chunk) {
        long players = level.players().stream()
                .filter(p -> p.chunkPosition().getChessboardDistance(chunk) <= level.getServer().getPlayerList()
                        .getViewDistance() + 1)
                .count();
        boolean forced = level.getForcedChunks().contains(chunk.toLong());
        return "chunk " + (level.getChunkSource().hasChunk(chunk.x, chunk.z) ? "loaded" : "unloaded") + ", entities "
                + (level.areEntitiesLoaded(chunk.toLong()) ? "loaded" : "unloaded") + "; " + players
                + " player(s) within view distance; " + (forced ? "still forced" : "not forced")
                + "; spawn chunks and other tickets keep a chunk too";
    }

    private static int entitiesIn(ServerLevel level, ChunkPos chunk) {
        AABB box = new AABB(chunk.getMinBlockX(), level.getMinBuildHeight(), chunk.getMinBlockZ(),
                chunk.getMaxBlockX() + 1, level.getMaxBuildHeight(), chunk.getMaxBlockZ() + 1);
        return level.getEntities((Entity) null, box, e -> true).size();
    }

    private static ChunkPos chunkOf(BlockPosition pos) {
        return new ChunkPos(pos.x() >> 4, pos.z() >> 4);
    }

    private static ServerLevel level(MinecraftServer server, Optional<String> dimension) {
        if (dimension.isEmpty()) return server.overworld();
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dimension.get()));
        ServerLevel level = server.getLevel(key);
        if (level == null) {
            throw ApiException.invalidParam("/dimension", ApiIssue.Code.INVALID_VALUE,
                    "no dimension " + dimension.get() + " on this server");
        }
        return level;
    }

    /** Run {@code work} on the server thread, there and then when already on it. */
    private static <R> CompletionStage<R> onServer(Function<MinecraftServer, CompletionStage<R>> work) {
        MinecraftServer server = StageWrightCommon.armedServer();
        if (server == null) {
            return CompletableFuture.failedFuture(ApiException.of(ApiError.Code.CAPABILITY_UNAVAILABLE,
                    "no server: StageWright answers mc.test.* questions on a running server"));
        }
        if (ServerThreadGuard.onServerThread()) {
            try {
                return work.apply(server);
            } catch (RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            }
        }
        return server.submit(() -> work.apply(server)).thenCompose(Function.identity());
    }
}
