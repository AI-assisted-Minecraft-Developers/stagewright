package net.magicterra.stagewright.harness;

import net.magicterra.stagewright.scene.Canary;
import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneProvider;
import net.magicterra.stagewright.scene.Terrain;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import net.minecraft.world.level.block.Blocks;

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
                // -- canaries (spec §5): the framework must CATCH these, or the gate is dead --
                Scene.canary("canaryMustFail", 100, Canary.MUST_FAIL,
                        ctx -> ctx.fail("canary: this scene must be reported as FAIL")),
                Scene.canary("canaryMustTimeout", 60, Canary.MUST_TIMEOUT,
                        ctx -> ctx.await(() -> false).within(40).then(() -> {})),
                Scene.canary("canaryMustSwallow", 100, Canary.MUST_SWALLOW,
                        ctx -> { /* never executed by design; the harness skips it */ })
        );
    }
}
