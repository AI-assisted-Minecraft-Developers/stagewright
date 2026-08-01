package net.magicterra.stagewright.junit.ui;

import com.google.gson.JsonObject;
import net.magicterra.stagewright.junit.StageWright;
import net.magicterra.stagewright.junit.StageWrightExtension;
import net.magicterra.stagewright.junit.StageWrightRpcException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Duration;

import static net.magicterra.stagewright.junit.ui.UiSupport.hasScreen;
import static net.magicterra.stagewright.junit.ui.UiSupport.screenType;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code ui.containerFurnace} — the first live scene that OPENS a server-backed
 * block-entity container screen (FurnaceScreen), enabled by task#90's instrument-grade
 * {@code mc.test.input.useOnBlock} world right-click. Previously {@code @Disabled}: no
 * instrument-face verb could right-click a world block, and the module discipline forbids
 * the behaviour-face {@code mc.bot.useItem}. {@code mc.test.input.useOnBlock} closes that
 * gap on the pure instrument face (synthetic BlockHitResult → {@code gameMode.useItemOn},
 * no movement/aiming/behaviour-face).
 *
 * <p>This scene doubles as the FIRST live shape-pin of {@link StageWright#exec} (its ok/success
 * parsing had never been driven by a live test): the {@code setblock} that stages the furnace
 * pins the SUCCESS path (must not throw), and a Brigadier {@code success:false} command
 * (a predicate that matches nothing) pins the FAILURE path (must raise
 * {@link StageWrightRpcException} — {@code ok:true, success:false}).
 *
 * <p><b>Live gate.</b> {@code @EnabledIfEnvironmentVariable(TESTKIT_ENDPOINT)} — without the
 * env the class is skipped; with it, a broken attach is a LOUD container error (never a silent
 * skip). Coords are player-relative (server-authoritative {@code observePlayer}), never
 * absolute; teardown closes the screen and self-cleans the furnace back to air.
 */
@ExtendWith(StageWrightExtension.class)
@EnabledIfEnvironmentVariable(named = "TESTKIT_ENDPOINT", matches = ".+")
class ContainerFurnaceTest {

    private static final Duration UI = Duration.ofSeconds(5);

    @Test
    void containerFurnace(StageWright tk) {
        tk.reset();
        tk.awaitCondition(() -> !hasScreen(tk.screenInfo()), UI);

        // Furnace at a player-relative block: two blocks along +X at feet Y (in reach,
        // clear of the player's own body). observePlayer is server-authoritative.
        JsonObject pos = tk.observePlayer().getAsJsonObject("pos");
        int fx = (int) Math.floor(pos.get("x").getAsDouble()) + 2;
        int fy = (int) Math.floor(pos.get("y").getAsDouble());
        int fz = (int) Math.floor(pos.get("z").getAsDouble());

        try {
            // exec() SUCCESS path pin: setblock dispatches ok:true, success:true → no throw.
            tk.exec("setblock " + fx + " " + fy + " " + fz + " minecraft:furnace");

            // exec() FAILURE path pin: a predicate matching nothing dispatches (ok:true) but
            // reports Brigadier success:false → exec() must raise StageWrightRpcException. This is
            // the shape assertion the facade had never had a live witness for.
            assertThrows(StageWrightRpcException.class,
                    () -> tk.exec("execute if entity @e[type=minecraft:ender_dragon]"),
                    "a dispatched-but-success:false command must raise StageWrightRpcException from exec()");

            // Open the furnace via the instrument-grade world right-click (task#90).
            useOnBlock(tk, fx, fy, fz);
            tk.awaitCondition(() -> hasScreen(tk.screenInfo())
                    && screenType(tk.screenInfo()).contains("Furnace"), UI);
            assertTrue(screenType(tk.screenInfo()).contains("Furnace"),
                    "useOnBlock on a furnace should open a FurnaceScreen, got: "
                            + screenType(tk.screenInfo()));

            tk.reset();
            tk.awaitCondition(() -> !hasScreen(tk.screenInfo()), UI);
            assertFalse(hasScreen(tk.screenInfo()), "reset must close the furnace screen");
        } finally {
            tk.call("mc.client.screen.close", new JsonObject());
            tk.exec("setblock " + fx + " " + fy + " " + fz + " minecraft:air");
        }
    }

    /** {@code mc.test.input.useOnBlock} — instrument-grade right-click on a world block. */
    private static void useOnBlock(StageWright tk, int x, int y, int z) {
        JsonObject params = new JsonObject();
        params.addProperty("x", x);
        params.addProperty("y", y);
        params.addProperty("z", z);
        tk.call("mc.test.input.useOnBlock", params);
    }
}
