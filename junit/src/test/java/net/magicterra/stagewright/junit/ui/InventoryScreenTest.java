package net.magicterra.stagewright.junit.ui;

import net.magicterra.stagewright.junit.StageWright;
import net.magicterra.stagewright.junit.StageWrightExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Duration;

import static net.magicterra.stagewright.junit.ui.UiSupport.hasScreen;
import static net.magicterra.stagewright.junit.ui.UiSupport.screenType;
import static net.magicterra.stagewright.junit.ui.UiSupport.slotCount;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live UI scenes over the JUnit attach surface — the player-inventory screen.
 *
 * <p>Two scenes from the P2c list, both on the pure instrument face
 * ({@code input.key}/{@code screen.info}/{@code screen.tree}/{@code mc.test.reset}):
 * <ul>
 *   <li>{@code ui.inventoryOpenClose} — E opens an InventoryScreen; reset closes it.</li>
 *   <li>{@code ui.screenTreeSlots} — the open inventory's tree carries slot nodes;
 *       after close the tree reflects no screen.</li>
 * </ul>
 *
 * <p><b>Live gate.</b> {@code @EnabledIfEnvironmentVariable(TESTKIT_ENDPOINT)} is a
 * DISCOVERY gate only: without the env the class is skipped and the pure-JVM SelfTest
 * still runs; WITH the env set, a broken attach is a LOUD container error via
 * {@link StageWrightExtension#beforeAll} (never a silent skip).
 *
 * <p><b>Self-clean.</b> Each scene {@code reset()}s at entry (clean slate) and at exit
 * (screen closed, keys released, chat cleared) — post-state == pre-state.
 */
@ExtendWith(StageWrightExtension.class)
@EnabledIfEnvironmentVariable(named = "TESTKIT_ENDPOINT", matches = ".+")
class InventoryScreenTest {

    private static final Duration UI = Duration.ofSeconds(5);

    @Test
    void inventoryOpenClose(StageWright tk) {
        tk.reset();
        // clean slate: no screen open
        tk.awaitCondition(() -> !hasScreen(tk.screenInfo()), UI);

        // E keybind opens the player inventory. The keybind is consumed on the NEXT
        // client tick (KeyboardHandler.keyPress -> KeyMapping click count -> handled in
        // tick), so poll rather than assert-immediately.
        tk.key("E");
        tk.awaitCondition(() -> {
            var info = tk.screenInfo();
            return hasScreen(info) && screenType(info).contains("Inventory");
        }, UI);
        assertTrue(screenType(tk.screenInfo()).contains("Inventory"),
                "E should open an InventoryScreen, got: " + screenType(tk.screenInfo()));

        tk.reset();
        tk.awaitCondition(() -> !hasScreen(tk.screenInfo()), UI);
        assertFalse(hasScreen(tk.screenInfo()), "reset must close the inventory screen");
    }

    @Test
    void screenTreeSlots(StageWright tk) {
        tk.reset();
        tk.awaitCondition(() -> !hasScreen(tk.screenInfo()), UI);

        tk.key("E");
        tk.awaitCondition(() -> hasScreen(tk.screenInfo()) && slotCount(tk.screenTree()) > 0, UI);

        int slots = slotCount(tk.screenTree());
        assertTrue(slots > 0, "open inventory tree must expose ContainerSlots nodes, got " + slots);

        tk.reset();
        tk.awaitCondition(() -> !hasScreen(tk.screenInfo()), UI);
        // Tree reflects no-screen: hasScreen false and no slot nodes remain.
        var closedTree = tk.screenTree();
        assertFalse(hasScreen(closedTree), "tree must report no screen after reset");
        assertEquals(0, slotCount(closedTree), "no slot nodes should survive the close");
    }
}
