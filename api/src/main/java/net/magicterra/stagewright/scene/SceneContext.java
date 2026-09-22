package net.magicterra.stagewright.scene;

import net.magicterra.stagewright.contract.Expect;
import net.magicterra.stagewright.contract.SceneFailure;
import net.magicterra.stagewright.contract.SceneSkipped;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * Per-scene handle: origin-relative world ops, assertions, and tick-continuation
 * steps. The body runs ONCE (synchronously) on the scene's first tick — it builds
 * the arena, asserts immediate state, and registers await-steps; the harness then
 * calls advance() every tick until DONE / STEP_TIMEOUT / budget exhaustion.
 * Bodies must never block or sleep (same rule as every in-game test in this repo).
 */
public final class SceneContext implements net.magicterra.stagewright.contract.SceneReport {
    /** One pending continuation: wait for cond (within N ticks of becoming current), then run. */
    private record Step(BooleanSupplier cond, int withinTicks, Runnable then) {}

    public enum Progress { RUNNING, DONE, STEP_TIMEOUT }

    private final ServerLevel level;
    private final BlockPos origin;
    private final int chunkRadius;
    private final Deque<Step> steps = new ArrayDeque<>();
    private final java.util.List<String> softViolations = new java.util.ArrayList<>();
    private final java.util.Map<String, Object> records = new java.util.LinkedHashMap<>();
    private int ticks;
    private int currentStepTicks;
    private String failureReason;

    public SceneContext(ServerLevel level, BlockPos origin) {
        this(level, origin, 1);
    }

    /** @param chunkRadius the scene's force-loaded window, so the context can tell a scene when it
     *                     is reaching outside it instead of letting the write vanish. */
    public SceneContext(ServerLevel level, BlockPos origin, int chunkRadius) {
        this.level = level;
        this.origin = origin;
        this.chunkRadius = chunkRadius;
    }

    /** This scene's force-loaded window, in chunks either side of the origin chunk. */
    public int chunkRadius() { return chunkRadius; }

    /** True when an origin-relative column falls outside the force-loaded window. Writes there are
     *  silently lost, and the scene then fails on an assertion about terrain that never existed. */
    public boolean outsideForcedChunks(int dx, int dz) {
        int originChunkX = origin.getX() >> 4;
        int originChunkZ = origin.getZ() >> 4;
        int cellChunkX = (origin.getX() + dx) >> 4;
        int cellChunkZ = (origin.getZ() + dz) >> 4;
        return Math.abs(cellChunkX - originChunkX) > chunkRadius
                || Math.abs(cellChunkZ - originChunkZ) > chunkRadius;
    }

    /** The backing server level — for scenes that drive entities/avatars directly. */
    public ServerLevel level() { return level; }

    /**
     * The id of the dimension this scene's arena is in, as {@code "minecraft:overworld"}.
     *
     * <p>A string rather than the {@code ResourceKey} {@link #level()} would give, so a JavaScript
     * scene can read it: the rule stated on {@link #originX()} is that a scene file may hold a
     * Minecraft object but must never call a method on one, and {@code level().dimension().location()}
     * is three such calls.
     */
    public String dimension() { return level.dimension().location().toString(); }

    /** The server running this suite. */
    public MinecraftServer server() { return level.getServer(); }

    // ---- players ----

    /**
     * Everyone currently connected, across all levels.
     *
     * <p>Empty on a bare dedicated-server run and non-empty under both the client and the
     * client-joins-server topologies — which is the whole reason those topologies exist rather than
     * being three ways to run the same scenes.
     */
    public List<ServerPlayer> players() {
        return level.getServer().getPlayerList().getPlayers();
    }

    /** The first connected player, or null when nobody is on. */
    public ServerPlayer playerOrNull() {
        List<ServerPlayer> players = players();
        return players.isEmpty() ? null : players.get(0);
    }

    /**
     * The first connected player, or {@link #skip} out of the body when there is none.
     *
     * <p>The player is left exactly where they are. Use {@link #playerHere()} when the interaction
     * under test cares about proximity — most do, and a player standing at world spawn 100k blocks
     * from the arena fails those tests for a reason that has nothing to do with the mod.
     */
    public ServerPlayer player() {
        ServerPlayer player = playerOrNull();
        if (player == null) {
            skip("no connected player — this scene only runs on a topology that has one");
        }
        return player;
    }

    /**
     * The first connected player, moved into this scene's arena, restored to where they were when
     * the scene resolves.
     *
     * <p>Restoring is not politeness: the grid places consecutive scenes 512 blocks apart, so a
     * player left standing in scene N's arena keeps those chunks hot and their entity ticking while
     * scene N+1 measures tick cost. The cleanup runs on FAIL and TIMEOUT too, so one broken scene
     * cannot drag the player through the rest of the suite.
     */
    public ServerPlayer playerHere() {
        ServerPlayer player = player();
        ServerLevel from = player.serverLevel();
        double x = player.getX(), y = player.getY(), z = player.getZ();
        float yaw = player.getYRot(), pitch = player.getXRot();
        cleanup(() -> player.teleportTo(from, x, y, z, java.util.Set.of(), yaw, pitch));
        player.teleportTo(level, origin.getX() + 0.5, origin.getY() + 1, origin.getZ() + 0.5,
                java.util.Set.of(), 0f, 0f);
        return player;
    }

    /**
     * Stop the body here; the scene resolves PASS carrying this reason — unless a {@link #check} has
     * already failed, which makes it a FAIL naming both. See {@link SceneSkipped} for why a skip is a
     * recorded outcome rather than a silent one.
     */
    public void skip(String why) {
        throw new SceneSkipped(why);
    }

    /** Absolute origin of this scene's grid cell (scene code should prefer rel()). */
    public BlockPos origin() { return origin; }

    /**
     * Absolute origin coordinates as plain integers.
     *
     * <p>These exist for scenes written in JavaScript, and the reason is worth stating because it is
     * invisible until it costs someone a day. A Java scene calls {@code origin().getX()} and loom
     * remaps that call site along with the rest of the mod, so it keeps working on both loaders. A JS
     * scene resolves {@code getX} by NAME at runtime, against whatever the jar was remapped to — and
     * a production Fabric jar is intermediary, where {@link BlockPos} is {@code class_2338} and
     * {@code getX} is {@code method_10263}. The same file passes on NeoForge (which runs mojmap) and
     * fails on Fabric with {@code Cannot find function getX in object class_2338}, which names
     * neither the cause nor the fix.
     *
     * <p>The rule that follows, and the one the JS surface is built to keep: <b>a scene file may call
     * StageWright's own methods and pass Minecraft objects around, but must never call a method ON a
     * Minecraft object.</b> Our names are not remapped; theirs are. So anything a scene legitimately
     * needs from a Minecraft type has to be reachable through an accessor like these.
     */
    public int originX() { return origin.getX(); }

    public int originY() { return origin.getY(); }

    public int originZ() { return origin.getZ(); }

    private final Deque<Runnable> cleanups = new ArrayDeque<>();

    /**
     * Register teardown to run when the scene resolves — on PASS, FAIL and
     * TIMEOUT alike (LIFO). Use for avatar discard, config unpin, entity kill:
     * anything that must not leak into the next scene.
     */
    public void cleanup(Runnable r) { cleanups.addFirst(r); }

    /** Harness-internal: drain cleanups; exceptions logged, never thrown. */
    public void runCleanups(Consumer<String> warn) {
        for (Runnable r : cleanups) {
            try { r.run(); } catch (Throwable t) { warn.accept("cleanup failed: " + t); }
        }
        cleanups.clear();
    }

    // ---- world ops (origin-relative; scenes never see absolute coordinates) ----

    public BlockPos rel(int dx, int dy, int dz) {
        return origin.offset(dx, dy, dz);
    }

    /**
     * Place a block, reverting it at teardown if it carries a block entity.
     *
     * <p>Same reasoning as {@link #playerHere()}, applied to the other thing that keeps ticking after
     * a scene resolves. Releasing the force-load does not stop a block entity: the chunk stays
     * resident while anything holds it, and a hopper or a beacon left in scene N's arena spends tick
     * budget for the rest of the suite. The failure that would produce — a LATER scene missing a
     * timing assertion by a hair, with nothing in its own arena to explain it — is the kind that
     * gets diagnosed as flakiness, so this closes it by symmetry with the player rather than after
     * being made to. No run has been observed failing this way.
     *
     * <p>Only block-entity placements are tracked. Plain blocks are inert once set, the grid keeps
     * them 512 blocks from the next arena, and reverting every {@code floor()} cell would cost more
     * teardown than it saves.
     */
    public void setBlock(int dx, int dy, int dz, Block block) {
        BlockPos pos = rel(dx, dy, dz);
        BlockState state = block.defaultBlockState();
        if (state.hasBlockEntity() && tickingPlacements.add(pos.immutable())) {
            cleanup(() -> level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState()));
        }
        level.setBlockAndUpdate(pos, state);
    }

    /** Positions this scene gave a block entity, so a re-set of one is not queued for cleanup twice. */
    private final java.util.Set<BlockPos> tickingPlacements = new java.util.HashSet<>();

    /** size x size stone-slab floor at dy=0, cleared air 4 above — the minimal clean pad. */
    public void floor(int size, Block block) {
        int half = size / 2;
        for (int dx = -half; dx <= half; dx++)
            for (int dz = -half; dz <= half; dz++) {
                setBlock(dx, 0, dz, block);
                for (int dy = 1; dy <= 4; dy++) setBlock(dx, dy, dz, Blocks.AIR);
            }
    }

    /** Build this scene's terrain from a text grid per layer. See {@link Arena}. */
    public Arena arena() {
        return new Arena(this);
    }

    /** Measure TPS / tick cadence / heap / CPU over a window of ticks. See {@link Perf}. */
    public Perf perf() {
        return new Perf(this);
    }

    /**
     * The player's inventory, hunger and status effects. See {@link Items}.
     *
     * <p>Held rather than rebuilt per call, because the facet remembers whether it has already
     * snapshotted the inventory for restoration — a fresh instance each time would register one
     * cleanup per {@code give}.
     */
    public Items items() {
        if (items == null) items = new Items(this);
        return items;
    }

    private Items items;

    /**
     * The player's advancement progress. See {@link Advancements}.
     *
     * <p>Held for the same reason as {@link #items()}: the facet remembers which advancements it has
     * already arranged to revoke at teardown.
     */
    public Advancements advancements() {
        if (advancements == null) advancements = new Advancements(this);
        return advancements;
    }

    private Advancements advancements;

    /**
     * The run's recipe graph. See {@link Recipes}.
     *
     * <p>Held because the facet indexes the whole {@code RecipeManager} on first use — a pack with
     * tens of thousands of recipes would otherwise pay for that index on every call, on the tick
     * thread.
     */
    public Recipes recipes() {
        if (recipes == null) recipes = new Recipes(this);
        return recipes;
    }

    private Recipes recipes;

    /**
     * Container menus, driven server-side. See {@link Menus}.
     *
     * <p>Held because the facet owns the menu it opened: a fresh instance per call would lose track
     * of what to close at teardown, and would re-register a close cleanup on every question asked.
     */
    public Menus menu() {
        if (menu == null) menu = new Menus(this);
        return menu;
    }

    private Menus menu;

    /** Loot tables — rolled, counted, checked. See {@link Loot}. */
    public Loot loot() {
        if (loot == null) loot = new Loot(this);
        return loot;
    }

    private Loot loot;

    /** Worldgen structures — registered, generated here, or nearest. See {@link Structures}. */
    public Structures structures() {
        if (structures == null) structures = new Structures(this);
        return structures;
    }

    private Structures structures;

    /** Equipment slots — vanilla armour always, Curios accessory slots when the pack has Curios.
     *  See {@link Equip}. */
    public Equip equip() {
        if (equip == null) equip = new Equip(this);
        return equip;
    }

    private Equip equip;

    /** The FTB Quests book, when the pack has one. See {@link Quests}. */
    public Quests quests() {
        if (quests == null) quests = new Quests(this);
        return quests;
    }

    private Quests quests;

    /** What is installed in this run. See {@link Mods} — the thing every conditional scene starts
     *  from, and the one number worth recording in a pack suite. */
    public Mods mods() {
        if (mods == null) mods = new Mods(this);
        return mods;
    }

    private Mods mods;

    // ---- capabilities Minecraft does not have ----

    /**
     * A capability some mod in this run contributed, or a recorded skip naming what is missing.
     *
     * <p>The facets above cover the vanilla server API. This is how everything else arrives —
     * Curios' slots, a quest graph, a mod's own machines — without StageWright having to ship code
     * for it. See {@link CapabilityProvider} for writing one.
     *
     * <pre>{@code
     * s.capability("mymod:rituals")          // Java, when the scene will cast or only chain
     * }</pre>
     * <pre>{@code
     * s.capability('mymod:rituals').cast()   // JS — the same object, straight through Rhino
     * }</pre>
     *
     * <p>Absent is a skip, not a failure, matching {@link #player()}: a mod that is not installed is
     * not the pack's defect, and must not read as a pass either. Use {@link #hasCapability} to
     * branch instead of skipping.
     */
    public Object capability(String name) {
        return capabilities.get(name);
    }

    /**
     * {@link #capability(String)} with the type checked, for a Java scene that owns both sides.
     *
     * <p>The check earns its keep across a version bump: an adapter whose facet type changed
     * otherwise surfaces as a {@code ClassCastException} in the scene, which names the scene rather
     * than the adapter.
     */
    public <T> T capability(String name, Class<T> type) {
        Object facet = capabilities.get(name);
        if (!type.isInstance(facet)) {
            throw new SceneFailure("the '" + name + "' capability is a " + facet.getClass().getName()
                    + ", not a " + type.getName() + " — its provider and this scene disagree about"
                    + " what that name means");
        }
        return type.cast(facet);
    }

    /** Whether this runtime offers a capability, for a scene that wants to branch rather than skip. */
    public boolean hasCapability(String name) {
        return capabilities.has(name);
    }

    /** Every capability this runtime offers, sorted. Worth recording in a suite that skips: it turns
     *  "this scene skipped" into "this scene skipped, and here is what was actually installed". */
    public List<String> capabilities() {
        return capabilities.available();
    }

    /**
     * Every capability provider that LOADED, whether or not its mod is here, sorted.
     *
     * <p>Not the same question as {@link #capabilities()}, and the difference is the one worth
     * asserting on. A runtime where discovery found nothing and a runtime where it found providers
     * whose mods are absent answer identically to every {@link #hasCapability} call — so a suite
     * that only asks that stays green through a dropped service file or a shadow merge that ate the
     * {@code META-INF/services} entry. Registered-but-unavailable is a working framework reporting
     * an absent mod; nothing registered is a broken framework reporting the same thing.
     */
    public List<String> capabilityProviders() {
        return capabilities.registered();
    }

    private final Capabilities capabilities = new Capabilities(this);

    /**
     * Reflection into a mod's own API, for a scene that cannot compile against it.
     *
     * <p>The hatch behind {@link CapabilityProvider}, for a modpack author with {@code .js} files and
     * no build. Absent class is a skip, like an absent capability.
     *
     * <p><b>{@code net.minecraft.*} is refused.</b> Not caution — correctness. A production Fabric
     * jar carries intermediary names, so a by-name call on a Minecraft class works on NeoForge and
     * throws on Fabric. A mod's own classes are not remapped, which is what makes the same technique
     * sound for them. See {@link Probe}.
     */
    public Probe probe(String className) {
        if (className.startsWith("net.minecraft.")) {
            throw new SceneFailure("probe('" + className + "') — reflection into net.minecraft is"
                    + " refused, and would not have worked: a production Fabric jar carries"
                    + " intermediary names, so getX is method_10263 there and this call would pass"
                    + " on NeoForge and throw on Fabric. The facets (items, recipes, loot, menu,"
                    + " structures, advancements) exist so a scene never has to. Probe is for a"
                    + " MOD's classes, which are not remapped.");
        }
        Class<?> type = classOrNull(className);
        if (type == null) {
            skip("this scene needs the class '" + className + "', which is not in this runtime —"
                    + " the mod that ships it is not installed here");
        }
        return new Probe(type, null);
    }

    /** Whether a class is in this runtime, so a scene can branch rather than skip. */
    public boolean hasClass(String className) {
        return classOrNull(className) != null;
    }

    private static Class<?> classOrNull(String className) {
        try {
            return Class.forName(className, false, SceneContext.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    /** The block currently at an origin-relative position. */
    public Block blockAt(int dx, int dy, int dz) {
        return level.getBlockState(rel(dx, dy, dz)).getBlock();
    }

    // ---- assertions ----

    public void assertBlock(int dx, int dy, int dz, Block expected) {
        Block actual = blockAt(dx, dy, dz);
        if (actual != expected) {
            throw new SceneFailure("block at rel(" + dx + "," + dy + "," + dz + ") is "
                    + actual + ", expected " + expected + note());
        }
    }

    /** The inverse: for scenes that care that something is there without caring what. Mostly
     *  {@code assertNotBlock(dx, -1, dz, Blocks.AIR)} — "there is ground under this" — which is the
     *  only honest assertion about generated terrain, whose surface block is a biome's business. */
    public void assertNotBlock(int dx, int dy, int dz, Block unwanted) {
        Block actual = blockAt(dx, dy, dz);
        if (actual == unwanted) {
            throw new SceneFailure("block at rel(" + dx + "," + dy + "," + dz + ") is "
                    + actual + ", which is exactly what it must not be" + note());
        }
    }

    /** The world y of the first free block above the surface at an origin-relative column — the same
     *  heightmap the harness uses to drop a terrain arena onto the ground, so a scene can measure
     *  the shape of what it landed on. */
    public int surfaceY(int dx, int dz) {
        return level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                origin.offset(dx, 0, dz)).getY();
    }

    /** What a command did: its numeric result, and everything it said doing it. */
    public record CommandResult(int result, List<String> output) {
        /** The feedback as one string — what an assertion usually wants to look at. */
        public String text() { return String.join("\n", output); }
    }

    /**
     * Run a command at the scene's origin.
     *
     * <p>This is the widest surface in the whole API for the smallest addition to it. A scene file
     * cannot construct an ItemStack, summon a mob, grant an effect or set a gamerule without calling
     * methods on Minecraft classes — whose names are remapped, so the same line passes on NeoForge
     * and fails on an intermediary Fabric jar. Commands are strings. One binding therefore reaches
     * items, entities, effects, time, weather, gamerules, advancements and every command any mod in
     * the pack registers, in the vocabulary a pack author already has from playing the game.
     *
     * <p><b>Origin-relative.</b> The source stands at the scene's origin, so {@code ~ ~ ~} is the
     * arena and a scene still never writes an absolute coordinate. That is the same discipline
     * {@link #rel} enforces, and it is what lets the same command run in whichever grid slot the
     * scheduler handed this scene.
     *
     * <p><b>Loud.</b> A misspelled command, an unknown id, a selector that matches nothing where the
     * command requires a match — all of them throw. {@code Commands.performPrefixedCommand} would
     * instead report the error to the source and return, which in a scene means a line that did
     * nothing, asserted nothing, and passed. Same reasoning as the JS {@code block()} bridge
     * answering AIR for a typo.
     *
     * <p><b>Silent.</b> Output is captured rather than broadcast: {@code shouldInformAdmins} is
     * false, so a scene running {@code /kill} does not fill an operator's chat, and in the
     * client topologies it does not put text on the screen of the player the scenes are observing.
     *
     * <p>Two caveats worth knowing.
     *
     * <ul>
     *   <li><b>{@code @p} is rarely what you want.</b> The {@code dedicatedServer} topology has no
     *       player at all, and in the client topologies the player is wherever the client left it,
     *       not in this arena. Prefer {@code @e[…]} with a range, or {@link #playerHere()}.</li>
     *   <li><b>Nothing a command does is reverted.</b> {@link #setBlock} records what it overwrote
     *       and puts it back; a command goes through the game's own paths and leaves no such
     *       record, so what it placed outlives the scene unless the scene {@link #cleanup}s it.</li>
     * </ul>
     *
     * <p>Note that {@code execute if …} reports a false condition as a command error, so through
     * this method it reads as an assertion: the line either holds or fails the scene.
     */
    public CommandResult command(String command) {
        MinecraftServer server = server();
        List<String> output = new java.util.ArrayList<>();
        CommandSource sink = new CommandSource() {
            @Override public void sendSystemMessage(Component message) { output.add(message.getString()); }
            @Override public boolean acceptsSuccess() { return true; }
            @Override public boolean acceptsFailure() { return true; }
            @Override public boolean shouldInformAdmins() { return false; }
        };
        CommandSourceStack source = new CommandSourceStack(sink,
                Vec3.atCenterOf(origin), Vec2.ZERO, level, 4, "StageWright",
                Component.literal("StageWright"), server, null);
        try {
            int result = server.getCommands().getDispatcher()
                    .execute(command, source);
            return new CommandResult(result, List.copyOf(output));
        } catch (CommandSyntaxException e) {
            throw new IllegalArgumentException("command '" + command + "' failed: " + e.getMessage()
                    + (output.isEmpty() ? "" : " — said: " + String.join(" / ", output)), e);
        }
    }

    /**
     * Assert about a value, failing the scene at the first violation.
     *
     * <p>Prefer {@link #check} when a body probes a SET of things — twenty blocks, every upgrade in
     * a list — because the first offender is rarely the informative one.
     */
    public Expect expect(Object actual) {
        return new Expect(this, false, actual);
    }

    /**
     * Assert about a value, recording the violation and carrying on. Every violation collected this
     * way is reported together when the scene finishes, and the scene still fails.
     */
    public Expect check(Object actual) {
        return new Expect(this, true, actual);
    }

    /** {@link #expect} on a block, pre-labelled with its position. */
    public Expect expectBlock(int dx, int dy, int dz) {
        return expect(blockAt(dx, dy, dz)).as("block at rel(" + dx + "," + dy + "," + dz + ")");
    }

    /** {@link #check} on a block, pre-labelled with its position. */
    public Expect checkBlock(int dx, int dy, int dz) {
        return check(blockAt(dx, dy, dz)).as("block at rel(" + dx + "," + dy + "," + dz + ")");
    }

    public void fail(String reason) {
        throw new SceneFailure(reason + note());
    }

    /**
     * Attach a named value to this scene's record.
     *
     * <p>Recorded values are appended to EVERY failure message this scene produces, and travel with
     * a passing scene into the results JSONL. The reason this is framework machinery rather than
     * something each scene does by hand: the failure {@code reason} is the only diagnostic channel
     * that reliably survives a full suite, because the async logger drops bursts precisely when a
     * long run is finishing. Scenes that hand-assembled their evidence into the failure string were
     * doing the framework's job.
     */
    public void record(String key, Object value) {
        records.put(key, value);
    }

    /** Harness-internal: the values {@link #record}ed by this scene, in insertion order. */
    public java.util.Map<String, Object> records() {
        return java.util.Collections.unmodifiableMap(records);
    }

    /** Harness-internal: every {@link #check} that has failed so far, in the order it failed. */
    public java.util.List<String> softViolations() {
        return java.util.List.copyOf(softViolations);
    }

    /** Expect-internal: report one violation, hard or soft. Public because {@code Expect} now lives
     *  in {@code :stagewright-attached} so that BOTH homes share one implementation of it — see
     *  {@link net.magicterra.stagewright.contract.SceneReport}. */
    @Override
    public void violation(String message, boolean soft) {
        if (soft) {
            softViolations.add(message);
            return;
        }
        throw new SceneFailure(message + note());
    }

    /** Renders the record as a message suffix. Empty when nothing was recorded, so scenes that do
     *  not use it produce byte-identical messages to before. */
    private String note() {
        if (records.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(" [");
        boolean first = true;
        for (java.util.Map.Entry<String, Object> e : records.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.append(']').toString();
    }

    private String passNote;

    /**
     * Attach a visible note to a PASS outcome — surfaced verbatim in the results
     * JSONL {@code reason} field (and the harness log line) when the scene resolves
     * PASS. Null (the default) yields the usual empty reason, so every other scene is
     * byte-unchanged. Use for a deliberate, auditable PASS marker such as a topology
     * guard that skips a body off its supported topology — this is NOT a swallow: the
     * scene is still counted entered and the reason records WHY it passed trivially.
     */
    public void passNote(String note) { this.passNote = note; }

    /** Harness-internal: the PASS note set by the body, or null. */
    public String passNote() { return passNote; }

    // ---- continuation steps ----

    public AwaitBuilder await(BooleanSupplier cond) {
        return new AwaitBuilder(cond);
    }

    public final class AwaitBuilder {
        private final BooleanSupplier cond;
        private int within = 100;

        private AwaitBuilder(BooleanSupplier cond) { this.cond = cond; }

        public AwaitBuilder within(int ticksBudget) { this.within = ticksBudget; return this; }

        public void then(Runnable action) { steps.addLast(new Step(cond, within, action)); }
    }

    // ---- harness-side driving ----

    /** Run the body once; SceneFailure propagates to the harness as FAIL. */
    public void runBody(Consumer<SceneContext> body) {
        body.accept(this);
    }

    /** One tick of step processing. Greedy: consume every step whose cond is already true. */
    public Progress advance() {
        ticks++;
        while (!steps.isEmpty()) {
            Step head = steps.peekFirst();
            if (head.cond().getAsBoolean()) {
                steps.pollFirst();
                currentStepTicks = 0;
                head.then().run();               // SceneFailure propagates to the harness
                continue;
            }
            currentStepTicks++;
            if (currentStepTicks > head.withinTicks()) {
                failureReason = "await step exceeded within=" + head.withinTicks() + " ticks";
                return Progress.STEP_TIMEOUT;
            }
            return Progress.RUNNING;
        }
        // Every step has drained, so this is the scene's last word: soft violations turn it into a
        // FAIL now, reported together. Deliberately NOT checked on the STEP_TIMEOUT path above — a
        // timeout already names its own cause, and burying it under a list of soft findings would
        // hide which of the two actually stopped the scene.
        if (!softViolations.isEmpty()) {
            throw new SceneFailure(softViolations.size() + " check(s) failed: "
                    + String.join("; ", softViolations) + note());
        }
        return Progress.DONE;
    }

    public int ticks() { return ticks; }

    public String failureReason() { return failureReason; }
}
