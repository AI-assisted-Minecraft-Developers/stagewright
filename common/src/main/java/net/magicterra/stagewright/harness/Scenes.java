package net.magicterra.stagewright.harness;

import net.magicterra.stagewright.contract.Canary;
import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneProvider;
import net.magicterra.stagewright.contract.Terrain;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/**
 * The explicit scene registry — the single source both the harness executes from
 * and the suite header (reconciliation side) is dumped from. Order = execution order.
 */
public final class Scenes {
    private Scenes() {}

    /** Built-in scenes first, then downstream {@link SceneProvider} contributions in
     *  ServiceLoader discovery order — execution order mirrors this concatenation. */
    public static List<Scene> all() {
        List<Scene> out = new ArrayList<>(builtin());
        for (SceneProvider p : ServiceLoader.load(SceneProvider.class)) {
            out.addAll(p.scenes());
        }
        out.addAll(scriptScenes());
        return List.copyOf(filter(out, System.getProperty(FILTER_PROPERTY)));
    }

    /** Rhino, probed by name for the same reason worlddriver is: to answer "can we run JS here"
     *  without linking against a library that may not be present. */
    private static final String RHINO_PROBE = "dev.latvian.mods.rhino.Context";

    /** Where a pack keeps its scenes. Duplicated from {@code JsScenes} deliberately — this side must
     *  be able to say the path in the message below without loading that class. */
    private static final String SCENES_DIR = "config/stagewright/scenes";

    /**
     * Scenes a modpack contributed as JavaScript files, after the compiled ones.
     *
     * <p>Last, so a pack's scenes can never shift the origin slots of the mod scenes they run
     * beside: slot assignment follows registry order, and a pack that added a file would otherwise
     * move every mod scene's arena and turn a byte-pinned result into a diff.
     *
     * <p>The Rhino check is a presence probe, not a feature flag, and its absence is reported rather
     * than passed over. A pack author who wrote scene files, ran the gate, and got GREEN without a
     * single one of them executing has been told nothing — and the results file would look complete,
     * because the scenes that did not load are not in it to be missed.
     */
    private static List<Scene> scriptScenes() {
        if (!java.nio.file.Files.isDirectory(java.nio.file.Path.of(SCENES_DIR))) {
            return List.of();
        }
        try {
            Class.forName(RHINO_PROBE, false, Scenes.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(SCENES_DIR + " holds scene files, but Rhino is not on this"
                    + " runtime's classpath, so none of them can run. Rhino ships with worlddriver —"
                    + " add it to the pack, or remove the directory if the scenes were not meant to"
                    + " run here.");
        }
        return net.magicterra.stagewright.script.JsScenes.load();
    }

    /** {@code -Dstagewright.filter=sb.magnet*,wd.bridge*} — comma-separated globs ({@code *} and
     *  {@code ?}) matched against whole scene names. */
    public static final String FILTER_PROPERTY = "stagewright.filter";

    /**
     * Narrow the suite for iteration. A full run costs minutes and grows with the suite, so
     * re-running 165 scenes to look at one is the single biggest tax on writing scenes.
     *
     * <p><b>Canaries are never filtered out.</b> They are what proves the harness can still catch a
     * failure at all; a filtered run that dropped them would report the same GREEN whether or not
     * the framework was working. Keeping them costs three scenes and means even a one-scene run
     * carries its own proof.
     *
     * <p>A filtered run is deliberately NOT gate-worthy: the orchestrator reconciles against the
     * expected-scenes manifest, so the scenes left out surface as MISSING-EXPECTED and the verdict
     * is RED. That is the intended relationship — iterate filtered, gate whole.
     */
    static List<Scene> filter(List<Scene> scenes, String spec) {
        if (spec == null || spec.isBlank()) return scenes;
        List<java.util.regex.Pattern> globs = new ArrayList<>();
        for (String part : spec.split(",")) {
            String g = part.trim();
            if (!g.isEmpty()) globs.add(java.util.regex.Pattern.compile(globToRegex(g)));
        }
        if (globs.isEmpty()) return scenes;

        List<Scene> kept = new ArrayList<>();
        for (Scene s : scenes) {
            if (s.canary() != Canary.NONE) {
                kept.add(s);
                continue;
            }
            for (java.util.regex.Pattern p : globs) {
                if (p.matcher(s.name()).matches()) {
                    kept.add(s);
                    break;
                }
            }
        }
        return kept;
    }

    private static String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append('.');
                default -> sb.append(java.util.regex.Pattern.quote(String.valueOf(c)));
            }
        }
        return sb.toString();
    }

    private static List<Scene> builtin() {
        return List.of(
                // -- walking-skeleton scenes --
                Scene.of("floorAssert", 100, ctx -> {
                    ctx.floor(5, Blocks.STONE);
                    ctx.assertBlock(0, 0, 0, Blocks.STONE);
                    ctx.assertBlock(-2, 0, -2, Blocks.STONE);
                    ctx.assertBlock(2, 0, 2, Blocks.STONE);
                    ctx.assertBlock(0, 1, 0, Blocks.AIR);
                }),
                Scene.of("awaitTicks", 200, ctx -> {
                    ctx.setBlock(0, 0, 0, Blocks.STONE);
                    ctx.await(() -> ctx.ticks() >= 40).within(100).then(() -> {
                        if (ctx.ticks() < 40) ctx.fail("await fired before its condition held");
                        ctx.assertBlock(0, 0, 0, Blocks.STONE);
                    });
                }),
                // -- terrain --
                /*
                 * The two shipped dimensions, asserted the only way that means anything: on what is
                 * actually under the arena. A missing datapack, a dimension that failed to register,
                 * or an arena left at the grid altitude instead of dropped to the surface all produce
                 * air at dy=-1, and all three are the same bug from a scene author's chair — "I asked
                 * for ground and did not get any".
                 */
                Scene.of("terrainSuperflatHasGroundUnderfoot", 100, ctx -> {
                    ctx.assertBlock(0, -1, 0, Blocks.GRASS_BLOCK);
                    ctx.assertBlock(0, 0, 0, Blocks.AIR);
                    ctx.record("surfaceY", ctx.originY());
                }).withTerrain(Terrain.SUPERFLAT),
                /*
                 * Asserts the two things that are true of generated terrain wherever the grid puts
                 * an arena, and records the rest rather than asserting it. What is actually under a
                 * given slot is worldgen's business: slot 2001 is open ocean, so `underfoot` reads
                 * `water` and `relief` is 0. A scene demanding dry land here would be a scene about
                 * where the grid happened to land, and it would break the day the grid moved.
                 *
                 * Pinned, because the whole point is that the landscape under this scene is the same
                 * every run: fixed seed plus fixed slot is what makes generated terrain reproducible,
                 * and an auto slot moves as the suite grows.
                 */
                Scene.of("terrainGeneratedPutsTheArenaOnTheSurface", 200, ctx -> {
                    // Not air: the arena was dropped onto the world instead of left at y=200.
                    ctx.assertNotBlock(0, -1, 0, Blocks.AIR);
                    // Both flat generators in play — the run world's level-type and the superflat
                    // dimension — surface below y=0. A noise overworld's is up at sea level, so the
                    // altitude alone says which generator built this.
                    int here = ctx.surfaceY(0, 0);
                    int relief = 0;
                    for (int d = 4; d <= 12; d += 4) {
                        relief = Math.max(relief, Math.abs(ctx.surfaceY(d, 0) - here));
                        relief = Math.max(relief, Math.abs(ctx.surfaceY(0, d) - here));
                    }
                    ctx.record("surfaceY", here);
                    ctx.record("relief", relief);
                    ctx.record("underfoot", ctx.blockAt(0, -1, 0).toString());
                    if (here <= 0) {
                        ctx.fail("generated terrain surfaces at y=" + here + ", below sea level for the"
                                + " whole arena — this is a flat generator, not the noise one");
                    }
                }).withTerrain(Terrain.GENERATED).withOriginSlot(2001),
                // -- topology probe --
                /*
                 * The client-joins-server topology's whole claim is that the scenes ran with a real
                 * remote player attached to a real dedicated server. This asserts exactly that, and
                 * it lives here rather than in an orchestrator because it is an assertion: the
                 * out-of-process version had to open two RPC connections, ask the client whether it
                 * had a position and the server whether its PlayerList held a ServerPlayer, and
                 * agree with itself about what "both ends live" meant. In here it is one line, and
                 * it fails the run through the same path every other assertion does.
                 *
                 * Skips on a bare dedicated server, where no player is expected — so the same suite
                 * is honest on all three topologies without a per-topology scene list.
                 */
                Scene.of("remotePlayerIsPresent", 100, ctx -> {
                    var player = ctx.player();
                    ctx.record("player", player.getGameProfile().getName());
                    ctx.record("dedicated", ctx.server().isDedicatedServer());
                    ctx.expect(ctx.players()).as("players on the server").isNotEmpty();
                    ctx.expect(player.connection).as("the player's network connection").isNotNull();
                }),
                /*
                 * Commands are the widest thing a scene file can reach, so this pins the three
                 * properties the rest of that reach depends on, in the order they can break.
                 *
                 * Relative first: the whole surface is worthless if `~ ~ ~` is not the arena, and it
                 * would be wrong in a way that looks right — a scene writing at absolute 0,0,0 still
                 * runs, still reports, and quietly tests a chunk nobody is watching.
                 *
                 * Then that output comes back at all, which is what turns commands from a way to
                 * change the world into a way to read it — `data get` and every mod command that
                 * answers with text.
                 *
                 * Then that a bad command throws. That one is the reason the other two are safe to
                 * rely on: vanilla's own performPrefixedCommand reports errors to the source and
                 * returns normally, so without this a typo in a pack's scene is a line that does
                 * nothing and passes.
                 */
                Scene.of("commandsRunAtTheArena", 100, ctx -> {
                    // Reverted by hand: setBlock records what it overwrote and puts it back, a
                    // command does not, and nothing downstream of this line would notice a diamond
                    // block left in an arena nobody visits again. Through the Java call rather than
                    // another command, because cleanups also run on the failure path — where the
                    // block may never have been placed, and `/setblock` refuses to set air on air.
                    ctx.cleanup(() -> ctx.setBlock(0, 0, 0, Blocks.AIR));
                    ctx.command("setblock ~ ~ ~ minecraft:diamond_block");
                    ctx.expectBlock(0, 0, 0).as("a block placed by a relative command")
                            .isEqualTo(Blocks.DIAMOND_BLOCK);

                    // A query rather than a `time set`: the clock is server-wide, and a scene that
                    // moved it would be changing the world every later scene runs in.
                    var said = ctx.command("time query daytime");
                    ctx.record("commandOutput", said.text());
                    ctx.expect(said.text()).as("what the command said doing it").isNotEmpty();

                    try {
                        ctx.command("setblock ~ ~ ~ stagewright:no_such_block");
                        ctx.fail("a command naming a block that does not exist was accepted");
                    } catch (IllegalArgumentException expected) {
                        ctx.record("badCommandThrew", true);
                    }
                }),
                /*
                 * The arena has to be a place where entities live, and for a long time it was not:
                 * the chunk carried a FORCED ticket at the entity-ticking level, reported
                 * ENTITY_TICKING when asked, and had still never been promoted — so every entity put
                 * there landed in a HIDDEN section, never ticked, and was invisible to
                 * getEntities and to every command selector.
                 *
                 * Nothing caught it, because blocks were unaffected: scenes placed, read back and
                 * passed. Even the leak audit agreed the arena was clean, using the same query that
                 * could not see anything. That is why this scene asserts the capability rather than
                 * the fix — a margin constant can be tuned away by someone who does not know what it
                 * was for, and this fails the moment it is.
                 */
                Scene.of("entitiesLiveInTheArena", 200, ctx -> {
                    ctx.expect(ctx.level().isPositionEntityTicking(ctx.origin()))
                            .as("the arena's chunk ticks entities").isEqualTo(true);

                    ctx.command("summon minecraft:armor_stand ~ ~ ~");
                    var stands = ctx.level().getEntitiesOfClass(ArmorStand.class,
                            new AABB(ctx.origin()).inflate(4));
                    ctx.expect(stands).as("a summoned entity, visible the tick it was made").hasSize(1);
                    ctx.command("execute if entity @e[type=minecraft:armor_stand,distance=..4]");

                    // And it has to actually run: an untickable entity is still queryable, so
                    // visibility alone would have passed against the very bug this scene is for.
                    // Empty sky at the arena, so gravity is the cheapest proof of a running tick.
                    //
                    // The state going in is recorded BEFORE the wait, not inside the then(). A
                    // timeout has no continuation to record from, so a scene that only reports on
                    // success reports "it did not fall" and nothing else — which is the same line
                    // whether the entity never ticked, or ticked perfectly well while resting on a
                    // block. Those need opposite fixes, and telling them apart cost a full gate run
                    // in a third-party pack before these three lines existed.
                    double startY = stands.get(0).getY();
                    ctx.record("startY", String.format("%.2f", startY));
                    ctx.record("onGroundAtSummon", stands.get(0).onGround());
                    ctx.record("underfoot", ctx.blockAt(0, -1, 0).toString());
                    // The poll writes the record, so a TIMEOUT carries the entity's LAST state and
                    // not just its first. An await has no failure hook — but its condition runs every
                    // tick, and records are attached to the scene whatever the outcome. Without this
                    // a timeout says only "it did not fall 1 block", which cannot distinguish an
                    // entity that never ticked from one that ticked, drifted, and was removed and
                    // replaced by some mod's cleanup while this held a stale reference.
                    ctx.await(() -> {
                        var stand = stands.get(0);
                        ctx.record("lastY", String.format("%.2f", stand.getY()));
                        ctx.record("lastRemoved", stand.isRemoved());
                        // tickCount is THE discriminator, and everything else here is only context
                        // for it. It increments in Entity.baseTick, so it separates the two failures
                        // that look identical from the outside: not ticked at all (the arena is not
                        // really entity-ticking, and the readiness test is lying) versus ticked but
                        // not falling (something suppressed gravity — NoGravity, or a mod's mixin).
                        // Those need opposite fixes, and no amount of position data distinguishes
                        // them.
                        ctx.record("lastTickCount", stand.tickCount);
                        ctx.record("noGravity", stand.isNoGravity());
                        return stand.getY() < startY - 1;
                    }).within(60).then(() ->
                            ctx.record("fellBy", String.format("%.2f", startY - stands.get(0).getY())));
                }),
                // -- canaries (spec §5): the framework must CATCH these, or the gate is dead --
                Scene.canary("canaryMustFail", 100, Canary.MUST_FAIL,
                        ctx -> ctx.fail("canary: this scene must be reported as FAIL")),
                Scene.canary("canaryMustTimeout", 60, Canary.MUST_TIMEOUT,
                        ctx -> ctx.await(() -> false).within(40).then(() -> {})),
                // A skip after a failed soft check: were the skip allowed to win, a real finding
                // would be recorded as an absent subject and pass.
                Scene.canary("canaryCheckThenSkipMustFail", 100, Canary.MUST_FAIL, ctx -> {
                    ctx.check(1).as("canary: a soft check that must fail").isEqualTo(2);
                    ctx.skip("canary: skipping after a failed check must not hide it");
                }),
                Scene.canary("canaryMustSwallow", 100, Canary.MUST_SWALLOW,
                        ctx -> { /* never executed by design; the harness skips it */ })
        );
    }

}
