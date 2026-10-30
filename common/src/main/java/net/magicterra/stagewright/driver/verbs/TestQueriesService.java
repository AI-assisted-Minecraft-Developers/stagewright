package net.magicterra.stagewright.driver.verbs;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import net.magicterra.stagewright.StageWrightCommon;
import net.magicterra.stagewright.capability.Mods;
import net.magicterra.stagewright.jupiter.ServerTicks;
import net.magicterra.stagewright.test.methods.AdvancementsContracts;
import net.magicterra.stagewright.test.methods.ChunksContracts.ChunksOp;
import net.magicterra.stagewright.test.methods.ChunksContracts.ChunksReply;
import net.magicterra.stagewright.test.methods.ChunksContracts.Reload;
import net.magicterra.stagewright.test.methods.ChunksContracts.Reloaded;
import net.magicterra.stagewright.test.methods.ChunksContracts.Unload;
import net.magicterra.stagewright.test.methods.ChunksContracts.Unloaded;
import net.magicterra.stagewright.test.methods.LootContracts;
import net.magicterra.stagewright.test.methods.ModsContracts;
import net.magicterra.stagewright.test.methods.RecipesContracts;
import net.magicterra.stagewright.test.methods.StructuresContracts;
import net.magicterra.stagewright.test.methods.TestQueriesApi;
import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.schema.ApiError;
import net.magicterra.worlddriver.api.schema.ApiException;
import net.magicterra.worlddriver.api.schema.ApiIssue;
import net.magicterra.worlddriver.api.thread.ServerThreadGuard;
import net.magicterra.worlddriver.bot.BotRegistry;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * {@code mc.test.*} questions about the world. Each starts on the server thread, hopping there from a caller on another
 * thread; one that spans ticks answers once {@link ServerTicks} has seen it through, so it never holds the server
 * thread while it waits.
 */
final class TestQueriesService implements TestQueriesApi {

    private static final int LIST_MAX = 100_000;

    @Override
    public CompletionStage<ChunksReply> chunks(ChunksOp op, CallContext ctx) {
        return onServer(server -> switch (op) {
            case Unload u -> unload(level(server, u.dimension()), u.pos(), u.withinTicks());
            case Reload r -> reload(level(server, r.dimension()), r.pos(), r.withinTicks());
        });
    }

    @Override
    public CompletionStage<RecipesContracts.RecipesReply> recipes(RecipesContracts.RecipesOp op, CallContext ctx) {
        return onServer(server -> {
            RecipeGraph graph = new RecipeGraph(server.overworld());
            RecipesContracts.RecipesReply reply = switch (op) {
                case RecipesContracts.Producing p -> graph.producing(p.item());
                case RecipesContracts.Ingredients i -> graph.ingredients(i.recipe());
                case RecipesContracts.ClosureOf c ->
                    graph.closure(
                            c.item(),
                            c.maxNodes().orElse(RecipesContracts.DEFAULT_MAX_NODES),
                            c.maxDepth().orElse(RecipesContracts.DEFAULT_MAX_DEPTH));
                case RecipesContracts.Uncraftable() -> graph.uncraftable();
                case RecipesContracts.Audit() -> graph.audit();
                case RecipesContracts.Count() -> graph.count();
            };
            return CompletableFuture.completedFuture(reply);
        });
    }

    /**
     * Loot tables are rolled with chest parameters at the overworld origin. A table of another context set is refused
     * up front: the game would not, it returns an empty list, which reads as "this table drops nothing".
     */
    @Override
    public CompletionStage<LootContracts.LootReply> loot(LootContracts.LootOp op, CallContext ctx) {
        return onServer(server -> {
            LootContracts.LootReply reply = switch (op) {
                case LootContracts.Exists e ->
                    new LootContracts.Existence(lootTable(server, e.table()) != LootTable.EMPTY);
                case LootContracts.Roll r -> roll(server, r);
                case LootContracts.ListTables l -> {
                    List<String> ids =
                            listed(server.reloadableRegistries().getKeys(Registries.LOOT_TABLE), l.namespace());
                    yield new LootContracts.TableList(ids.size(), cut(ids));
                }
            };
            return CompletableFuture.completedFuture(reply);
        });
    }

    private static LootTable lootTable(MinecraftServer server, String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        if (location == null) {
            throw ApiException.invalidParam("/table", ApiIssue.Code.INVALID_VALUE, id + " is not a resource id");
        }
        // getLootTable answers the EMPTY stand-in, not null, for an id nothing registered.
        return server.reloadableRegistries().getLootTable(ResourceKey.create(Registries.LOOT_TABLE, location));
    }

    private static LootContracts.Rolled roll(MinecraftServer server, LootContracts.Roll r) {
        LootTable table = lootTable(server, r.table());
        if (table == LootTable.EMPTY) {
            throw ApiException.invalidParam(
                    "/table", ApiIssue.Code.INVALID_VALUE, "no loot table is registered as " + r.table());
        }
        if (table.getParamSet() != LootContextParamSets.CHEST) {
            throw ApiException.invalidParam(
                    "/table",
                    ApiIssue.Code.INVALID_VALUE,
                    r.table()
                            + " is not a chest-shaped loot table; rolling it needs a killer, a tool or a block state");
        }
        LootParams params = new LootParams.Builder(server.overworld())
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(BlockPos.ZERO))
                .create(LootContextParamSets.CHEST);
        Map<String, long[]> counts = new LinkedHashMap<>(); // item -> {stacks, total}
        int empty = 0;
        for (int i = 0; i < r.times(); i++) {
            boolean any = false;
            for (ItemStack stack : table.getRandomItems(params)) {
                if (stack.isEmpty()) {
                    continue;
                }
                any = true;
                long[] c = counts.computeIfAbsent(
                        BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), k -> new long[2]);
                c[0]++;
                c[1] += stack.getCount();
            }
            if (!any) {
                empty++;
            }
        }
        List<LootContracts.ItemCount> items = new ArrayList<>();
        counts.forEach((id, c) -> items.add(new LootContracts.ItemCount(id, (int) c[0], c[1])));
        return new LootContracts.Rolled(r.times(), empty, items);
    }

    @Override
    public CompletionStage<AdvancementsContracts.AdvancementsReply> advancements(
            AdvancementsContracts.AdvancementsOp op, CallContext ctx) {
        return onServer(server -> {
            AdvancementsContracts.AdvancementsReply reply = switch (op) {
                case AdvancementsContracts.Grant g -> {
                    ServerPlayer player = player(server, g.bot());
                    AdvancementHolder holder = advancement(server, g.id());
                    PlayerAdvancements progress = player.getAdvancements();
                    // Snapshot before awarding: these are exactly the criteria this call is responsible for.
                    List<String> awarded = new ArrayList<>();
                    progress.getOrStartProgress(holder).getRemainingCriteria().forEach(awarded::add);
                    for (String criterion : awarded) {
                        progress.award(holder, criterion);
                    }
                    yield new AdvancementsContracts.Granted(
                            progress.getOrStartProgress(holder).isDone(), awarded);
                }
                case AdvancementsContracts.Revoke v -> {
                    ServerPlayer player = player(server, v.bot());
                    AdvancementHolder holder = advancement(server, v.id());
                    PlayerAdvancements progress = player.getAdvancements();
                    List<String> completed = new ArrayList<>();
                    progress.getOrStartProgress(holder).getCompletedCriteria().forEach(completed::add);
                    for (String criterion : completed) {
                        progress.revoke(holder, criterion);
                    }
                    yield new AdvancementsContracts.Revoked(
                            progress.getOrStartProgress(holder).isDone(), completed);
                }
                case AdvancementsContracts.Progress p -> {
                    ServerPlayer player = player(server, p.bot());
                    AdvancementProgress progress =
                            player.getAdvancements().getOrStartProgress(advancement(server, p.id()));
                    List<String> completed = new ArrayList<>();
                    progress.getCompletedCriteria().forEach(completed::add);
                    List<String> remaining = new ArrayList<>();
                    progress.getRemainingCriteria().forEach(remaining::add);
                    yield new AdvancementsContracts.Progressed(progress.isDone(), completed, remaining);
                }
                case AdvancementsContracts.ListAdvancements l -> {
                    List<String> ids = listed(
                            server.getAdvancements().getAllAdvancements().stream()
                                    .map(AdvancementHolder::id)
                                    .toList(),
                            l.namespace());
                    yield new AdvancementsContracts.AdvancementList(ids.size(), cut(ids));
                }
            };
            return CompletableFuture.completedFuture(reply);
        });
    }

    private static ServerPlayer player(MinecraftServer server, String ref) {
        ServerPlayer player = BotRegistry.serverPlayer(server, ref);
        if (player == null) {
            throw ApiException.invalidParam("/bot", ApiIssue.Code.INVALID_VALUE, "no server player " + ref);
        }
        return player;
    }

    private static AdvancementHolder advancement(MinecraftServer server, String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        AdvancementHolder holder =
                location == null ? null : server.getAdvancements().get(location);
        if (holder == null) {
            throw ApiException.invalidParam(
                    "/id", ApiIssue.Code.INVALID_VALUE, "no advancement is registered as " + id);
        }
        return holder;
    }

    @Override
    public CompletionStage<StructuresContracts.StructuresReply> structures(
            StructuresContracts.StructuresOp op, CallContext ctx) {
        return onServer(server -> {
            StructuresContracts.StructuresReply reply = switch (op) {
                case StructuresContracts.Registered r ->
                    new StructuresContracts.IsRegistered(
                            structureRegistry(server.overworld()).containsKey(structureId(r.id())));
                case StructuresContracts.At a -> {
                    ServerLevel level = level(server, a.dimension());
                    BlockPos pos =
                            new BlockPos(a.pos().x(), a.pos().y(), a.pos().z());
                    Registry<Structure> registry = structureRegistry(level);
                    List<String> out = new ArrayList<>();
                    for (ResourceLocation id : registry.keySet()) {
                        Structure structure = registry.get(id);
                        if (structure == null) {
                            continue;
                        }
                        StructureStart start = level.structureManager().getStructureAt(pos, structure);
                        if (start != null && start.isValid()) {
                            out.add(id.toString());
                        }
                    }
                    out.sort(String::compareTo);
                    yield new StructuresContracts.Covering(out);
                }
                case StructuresContracts.Locate l -> locate(level(server, l.dimension()), l);
                case StructuresContracts.ListStructures l -> {
                    List<String> ids =
                            listed(structureRegistry(server.overworld()).keySet(), l.namespace());
                    yield new StructuresContracts.StructureList(ids.size(), cut(ids));
                }
            };
            return CompletableFuture.completedFuture(reply);
        });
    }

    /** The loader's list, which needs no server: a client answers it too. */
    @Override
    public CompletionStage<ModsContracts.ModsReply> mods(ModsContracts.ModsOp op, CallContext ctx) {
        if (!Mods.isInstalled()) {
            return CompletableFuture.failedFuture(ApiException.of(
                    ApiError.Code.CAPABILITY_UNAVAILABLE, "the loader never handed StageWright its mod list"));
        }
        return CompletableFuture.completedFuture(
                switch (op) {
                    case ModsContracts.ListMods() -> {
                        List<ModsContracts.Mod> mods = new ArrayList<>();
                        for (String id : Mods.ids()) {
                            mods.add(new ModsContracts.Mod(id, clip(Mods.version(id), 256)));
                        }
                        yield new ModsContracts.ModList(mods.size(), mods.subList(0, Math.min(mods.size(), 10_000)));
                    }
                    case ModsContracts.Has h ->
                        new ModsContracts.Present(
                                Mods.loaded(h.id()),
                                Mods.loaded(h.id()) ? Optional.of(clip(Mods.version(h.id()), 256)) : Optional.empty());
                });
    }

    private static String clip(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** {@code ids} in {@code namespace}, or all of them, sorted. */
    private static List<String> listed(Collection<ResourceLocation> ids, Optional<String> namespace) {
        return ids.stream()
                .filter(id -> namespace.isEmpty() || id.getNamespace().equals(namespace.get()))
                .map(ResourceLocation::toString)
                .sorted()
                .toList();
    }

    /** The first {@code LIST_MAX} of {@code ids}: what a list reply may carry. */
    private static List<String> cut(List<String> ids) {
        return ids.subList(0, Math.min(ids.size(), LIST_MAX));
    }

    /** Expensive: it scans and generates chunks, so the milliseconds are reported found or not. */
    private static StructuresContracts.Located locate(ServerLevel level, StructuresContracts.Locate l) {
        Registry<Structure> registry = structureRegistry(level);
        Structure structure = registry.get(structureId(l.id()));
        if (structure == null) {
            throw ApiException.invalidParam(
                    "/id", ApiIssue.Code.INVALID_VALUE, "no structure is registered as " + l.id());
        }
        Holder<Structure> holder = registry.wrapAsHolder(structure);
        BlockPos from = new BlockPos(l.pos().x(), l.pos().y(), l.pos().z());
        long startedAt = System.currentTimeMillis();
        var found = level.getChunkSource()
                .getGenerator()
                .findNearestMapStructure(level, HolderSet.direct(holder), from, l.chunkRadius(), false);
        long ms = System.currentTimeMillis() - startedAt;
        if (found == null) {
            return new StructuresContracts.Located(false, Optional.empty(), ms);
        }
        BlockPos at = found.getFirst();
        return new StructuresContracts.Located(
                true, Optional.of(new BlockPosition(at.getX(), at.getY(), at.getZ())), ms);
    }

    private static Registry<Structure> structureRegistry(ServerLevel level) {
        return level.registryAccess().registryOrThrow(Registries.STRUCTURE);
    }

    private static ResourceLocation structureId(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        if (location == null) {
            throw ApiException.invalidParam("/id", ApiIssue.Code.INVALID_VALUE, id + " is not a resource id");
        }
        return location;
    }

    private static CompletionStage<ChunksReply> unload(ServerLevel level, BlockPosition pos, int within) {
        ChunkPos chunk = chunkOf(pos);
        level.setChunkForced(chunk.x, chunk.z, false);
        int start = level.getServer().getTickCount();
        return ServerTicks.await(() -> !loaded(level, chunk), within)
                .thenApply(done -> new Unloaded(
                        chunk.x,
                        chunk.z,
                        done,
                        level.getServer().getTickCount() - start,
                        done ? Optional.empty() : Optional.of(heldBy(level, chunk))));
    }

    private static CompletionStage<ChunksReply> reload(ServerLevel level, BlockPosition pos, int within) {
        ChunkPos chunk = chunkOf(pos);
        level.setChunkForced(chunk.x, chunk.z, true);
        int start = level.getServer().getTickCount();
        return ServerTicks.await(
                        () -> level.getChunkSource().hasChunk(chunk.x, chunk.z)
                                && level.areEntitiesLoaded(chunk.toLong()),
                        within)
                .thenApply(done -> new Reloaded(
                        chunk.x,
                        chunk.z,
                        done,
                        level.getServer().getTickCount() - start,
                        done ? entitiesIn(level, chunk) : 0));
    }

    private static boolean loaded(ServerLevel level, ChunkPos chunk) {
        return level.getChunkSource().hasChunk(chunk.x, chunk.z) || level.areEntitiesLoaded(chunk.toLong());
    }

    /** Why a chunk stayed: what the server has to say about it once the wait ran out. */
    private static String heldBy(ServerLevel level, ChunkPos chunk) {
        long players = level.players().stream()
                .filter(p -> p.chunkPosition().getChessboardDistance(chunk)
                        <= level.getServer().getPlayerList().getViewDistance() + 1)
                .count();
        boolean forced = level.getForcedChunks().contains(chunk.toLong());
        return "chunk " + (level.getChunkSource().hasChunk(chunk.x, chunk.z) ? "loaded" : "unloaded") + ", entities "
                + (level.areEntitiesLoaded(chunk.toLong()) ? "loaded" : "unloaded") + "; " + players
                + " player(s) within view distance; " + (forced ? "still forced" : "not forced")
                + "; spawn chunks and other tickets keep a chunk too";
    }

    private static int entitiesIn(ServerLevel level, ChunkPos chunk) {
        AABB box = new AABB(
                chunk.getMinBlockX(),
                level.getMinBuildHeight(),
                chunk.getMinBlockZ(),
                chunk.getMaxBlockX() + 1,
                level.getMaxBuildHeight(),
                chunk.getMaxBlockZ() + 1);
        return level.getEntities((Entity) null, box, e -> true).size();
    }

    private static ChunkPos chunkOf(BlockPosition pos) {
        return new ChunkPos(pos.x() >> 4, pos.z() >> 4);
    }

    private static ServerLevel level(MinecraftServer server, Optional<String> dimension) {
        if (dimension.isEmpty()) {
            return server.overworld();
        }
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dimension.get()));
        ServerLevel level = server.getLevel(key);
        if (level == null) {
            throw ApiException.invalidParam(
                    "/dimension", ApiIssue.Code.INVALID_VALUE, "no dimension " + dimension.get() + " on this server");
        }
        return level;
    }

    /** Run {@code work} on the server thread, there and then when already on it. */
    private static <R> CompletionStage<R> onServer(Function<MinecraftServer, CompletionStage<R>> work) {
        MinecraftServer server = StageWrightCommon.armedServer();
        if (server == null) {
            return CompletableFuture.failedFuture(ApiException.of(
                    ApiError.Code.CAPABILITY_UNAVAILABLE,
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
