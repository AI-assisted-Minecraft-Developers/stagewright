package net.magicterra.testkit.scene;

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
        return List.copyOf(out);
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
