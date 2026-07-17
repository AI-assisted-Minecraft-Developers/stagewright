package net.magicterra.testkit.junit.ui;

import net.magicterra.testkit.junit.Testkit;
import net.magicterra.testkit.junit.TestkitExtension;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * {@code ui.containerFurnace} — NOT IMPLEMENTABLE on the pure instrument face.
 * Present as a visible, reasoned {@code @Disabled} marker (never silent shrinkage);
 * awaiting controller adjudication. Full evidence in {@code .superpowers/sdd/task-3-report.md}.
 *
 * <p><b>Why.</b> The scene needs to OPEN a block-entity container screen (FurnaceScreen)
 * by right-clicking a placed furnace. Opening a world container requires a right-click
 * on the crosshair target. The instrument face has no such verb:
 * <ul>
 *   <li>{@code mc.client.input.click} operates on GUI logical coords and returns
 *       {@code {"ok":false,"error":"no screen open"}} when {@code mc.screen == null}
 *       (verified in {@code ClientInput.click}, common) — it cannot right-click a world
 *       block, only widgets inside an already-open screen.</li>
 *   <li>{@code mc.client.input.key} routes keyboard keys/keybinds only; the vanilla
 *       "use item / place" action is bound to the RIGHT MOUSE BUTTON, which
 *       {@code glfwKeyCode} does not map — no keybind path opens a container.</li>
 *   <li>No {@code mc.client.input.*} verb performs a world use/interact; the ONLY verb
 *       that right-clicks a world block is {@code mc.bot.useItem}, which is a
 *       behavior-face ({@code mc.bot.*}) verb the module discipline forbids
 *       ("对 mc.bot.* 行为面仍禁依赖"). The brief is explicit: DO NOT fake it with a
 *       behavior verb.</li>
 * </ul>
 * No alternative instrument route opens a block-entity container: every vanilla
 * container (furnace/chest/crafting-table/enchanting/etc.) opens via right-click, and
 * no vanilla command opens a GUI. The only keyboard-openable {@code AbstractContainerScreen}
 * is the player inventory (E) — already covered by {@code screenTreeSlots}, but that is a
 * client-only screen, not the server-backed block-entity container this scene targets.
 *
 * <p><b>Adjudication options</b> (controller's call — not taken here): (a) accept a
 * player-inventory container-screen as the slot-bearing container proof and drop the
 * furnace-specific scene; (b) add a genuinely instrument-grade world-use verb to the
 * client surface (e.g. {@code mc.client.input.useOnBlock}) — a common/ Java change that
 * this task's Global Constraint forbids ("零 agent-driver common Java 改动 ... STOP→BLOCKED");
 * (c) relax the instrument-face discipline to admit {@code mc.bot.useItem} as a
 * single-shot primitive. Body kept as a reference sketch for whichever route wins.
 */
@ExtendWith(TestkitExtension.class)
@EnabledIfEnvironmentVariable(named = "TESTKIT_ENDPOINT", matches = ".+")
@Disabled("ui.containerFurnace: opening a block-entity container needs a world right-click, "
        + "which no instrument-face verb provides (only behavior-face mc.bot.useItem). "
        + "NOT IMPLEMENTABLE on the instrument face — awaiting controller adjudication. "
        + "See .superpowers/sdd/task-3-report.md.")
class ContainerFurnaceTest {

    @Test
    void containerFurnace(Testkit tk) {
        // Reference sketch (unreachable while @Disabled): place a furnace at a
        // player-relative block, open its screen, assert the type, then self-clean.
        //
        //   var p = tk.observePlayer();               // relative to observed pos, never absolute
        //   int fx = feetX(p) + 1, fy = feetY(p), fz = feetZ(p);
        //   tk.exec("setblock " + fx + " " + fy + " " + fz + " minecraft:furnace");
        //   <right-click the furnace block>            // <-- NO instrument verb exists for this
        //   tk.awaitCondition(() -> screenType(tk.screenInfo()).contains("Furnace"), UI);
        //   assertTrue(screenType(tk.screenInfo()).contains("Furnace"));
        //   tk.reset();
        //   tk.exec("setblock " + fx + " " + fy + " " + fz + " minecraft:air");  // self-clean
        throw new UnsupportedOperationException(
                "ui.containerFurnace is not implementable on the instrument face — see class javadoc");
    }
}
