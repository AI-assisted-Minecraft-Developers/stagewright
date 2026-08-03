package net.magicterra.stagewright.harness;

import net.magicterra.stagewright.scene.Canary;
import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneProvider;

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
        return List.copyOf(filter(out, System.getProperty(FILTER_PROPERTY)));
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
