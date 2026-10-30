package net.magicterra.stagewright.capability;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.magicterra.stagewright.test.Body;
import net.magicterra.stagewright.test.Game;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * A body's inventory, hunger and status effects, in string ids so script tests can use it too. The capability
 * {@code items}:
 *
 * <pre>{@code
 * Items items = game.capability("items", Items.class);
 * int before = items.foodLevel(bot);
 * items.hunger(bot, 10, 0).give(bot, "minecraft:bread").eat(bot, "minecraft:bread");
 * assertTrue(items.foodLevel(bot) > before);
 * }</pre>
 *
 * <h2>Nothing here outlives the test</h2>
 *
 * The first write to a body's inventory snapshots the whole inventory, and {@link Game#afterTest} puts it back when the
 * test ends, passed or not; hunger likewise. A player shared by several tests would otherwise carry one test's items
 * into the next.
 */
public final class Items {

    private final Game game;
    /** Bodies whose inventory, and whose hunger, this test has pinned for restoring. Touched on the test thread. */
    private final Set<String> pinned = new HashSet<>();

    private final Set<String> hungerPinned = new HashSet<>();

    Items(Game game) {
        this.game = game;
    }

    // ---- writes ----

    /** Put one of this item in the body's inventory. */
    public Items give(Body body, String itemId) {
        return give(body, itemId, 1);
    }

    /**
     * Put {@code count} of this item in the body's inventory. Fails when it has no room rather than dropping the
     * remainder: a dropped stack becomes an entity, and the test then fails on an assertion naming neither.
     */
    public Items give(Body body, String itemId, int count) {
        pin(body);
        body.runOnServer(player -> {
            ItemStack stack = Ids.stack(itemId, count);
            if (!player.getInventory().add(stack)) {
                throw new IllegalStateException(
                        "could not give " + count + " x '" + itemId + "' — " + body.name() + "'s inventory is full ("
                                + stack.getCount() + " would not fit). Clear it first, or give less.");
            }
        });
        return this;
    }

    /** Empty the inventory, including armour and offhand. */
    public Items clear(Body body) {
        pin(body);
        body.runOnServer(player -> player.getInventory().clearContent());
        return this;
    }

    /** Put this item in the main hand, replacing whatever was there. */
    public Items hold(Body body, String itemId) {
        pin(body);
        body.runOnServer(player -> player.setItemInHand(InteractionHand.MAIN_HAND, Ids.stack(itemId, 1)));
        return this;
    }

    /**
     * Consume one of this item as food through {@code ItemStack.finishUsingItem}, the route releasing right-click
     * takes, so a mod's override of it runs. Refuses a non-food, on which that call silently does nothing.
     */
    public Items eat(Body body, String itemId) {
        pin(body);
        body.runOnServer(player -> {
            ItemStack stack = Ids.stack(itemId, 1);
            if (stack.get(DataComponents.FOOD) == null) {
                throw new IllegalStateException("'" + itemId + "' is not food — it carries no food component, so"
                        + " finishUsingItem would return it unchanged and this line would do nothing");
            }
            player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            stack.finishUsingItem(player.level(), player);
        });
        return this;
    }

    /** Set hunger and saturation outright, for a test that needs the body in a known state; restored afterwards. */
    public Items hunger(Body body, int foodLevel, float saturation) {
        pin(body);
        pinHunger(body);
        body.runOnServer(player -> {
            player.getFoodData().setFoodLevel(foodLevel);
            player.getFoodData().setSaturation(saturation);
        });
        return this;
    }

    // ---- reads ----

    /** How many of this item the body is carrying, across the whole inventory. */
    public int count(Body body, String itemId) {
        return body.onServer(player -> {
            var item = Ids.item(itemId);
            Inventory inventory = player.getInventory();
            int total = 0;
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (stack.is(item)) total += stack.getCount();
            }
            return total;
        });
    }

    /** Whether the body is carrying any of this item. */
    public boolean has(Body body, String itemId) {
        return count(body, itemId) > 0;
    }

    /** The id of the item in the main hand, or {@code ""} when the hand is empty. */
    public String held(Body body) {
        return body.onServer(player -> Ids.idOf(player.getMainHandItem()));
    }

    /** Every distinct item id the body is carrying, in slot order. */
    public List<String> distinct(Body body) {
        return body.onServer(player -> {
            Inventory inventory = player.getInventory();
            List<String> out = new ArrayList<>();
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                String id = Ids.idOf(inventory.getItem(i));
                if (!id.isEmpty() && !out.contains(id)) out.add(id);
            }
            return out;
        });
    }

    /** Hunger, 0..20. */
    public int foodLevel(Body body) {
        return body.onServer(player -> player.getFoodData().getFoodLevel());
    }

    /** Saturation — the hidden half of hunger. */
    public float saturation(Body body) {
        return body.onServer(player -> player.getFoodData().getSaturationLevel());
    }

    /** The ids of every status effect currently on the body. */
    public List<String> effects(Body body) {
        return body.onServer(player -> {
            List<String> out = new ArrayList<>();
            for (MobEffectInstance instance : player.getActiveEffects()) out.add(effectId(instance));
            return out;
        });
    }

    /** Whether this status effect is on the body. */
    public boolean hasEffect(Body body, String effectId) {
        return body.onServer(player -> {
            MobEffect effect = Ids.require(BuiltInRegistries.MOB_EFFECT, effectId, "mob effect");
            for (MobEffectInstance instance : player.getActiveEffects()) {
                if (instance.getEffect().value() == effect) return true;
            }
            return false;
        });
    }

    /**
     * Every active effect with {@code [amplifier, remaining ticks]}. The amplifier is Minecraft's zero-based one —
     * "Regeneration II" is 1 — because that is what every other API in the game takes.
     */
    public Map<String, List<Integer>> effectDetails(Body body) {
        return body.onServer(player -> {
            Map<String, List<Integer>> out = new LinkedHashMap<>();
            for (MobEffectInstance instance : player.getActiveEffects()) {
                out.put(effectId(instance), List.of(instance.getAmplifier(), instance.getDuration()));
            }
            return out;
        });
    }

    /**
     * What is worn in a slot: {@code "head"}, {@code "chest"}, {@code "legs"}, {@code "feet"}, {@code "mainhand"} or
     * {@code "offhand"}. Empty string when the slot is bare.
     */
    public String worn(Body body, String slot) {
        EquipmentSlot equipmentSlot = equipmentSlot(slot);
        return body.onServer(player -> Ids.idOf(player.getItemBySlot(equipmentSlot)));
    }

    /**
     * Take the inventory snapshot now, without writing anything — for {@link Menus}, whose slots include the body's
     * bag, so there is one snapshot and one restore rather than two that fight.
     */
    void pinInventory(Body body) {
        pin(body);
    }

    // ---- internals ----

    /** Make sure this test's changes to the body's inventory are undone when it ends. */
    private void pin(Body body) {
        if (!pinned.add(body.name())) return;
        Runnable restore = body.onServer(player -> {
            Inventory inventory = player.getInventory();
            int size = inventory.getContainerSize();
            List<ItemStack> snapshot = new ArrayList<>(size);
            for (int i = 0; i < size; i++) snapshot.add(inventory.getItem(i).copy());
            int selected = inventory.selected;
            // Copied out again on restore, so the snapshot survives being installed.
            return () -> {
                for (int i = 0; i < size; i++)
                    inventory.setItem(i, snapshot.get(i).copy());
                inventory.selected = selected;
            };
        });
        game.afterTest(server -> restore.run());
    }

    private void pinHunger(Body body) {
        if (!hungerPinned.add(body.name())) return;
        Runnable restore = body.onServer(player -> {
            int food = player.getFoodData().getFoodLevel();
            float saturation = player.getFoodData().getSaturationLevel();
            return () -> {
                player.getFoodData().setFoodLevel(food);
                player.getFoodData().setSaturation(saturation);
            };
        });
        game.afterTest(server -> restore.run());
    }

    private static String effectId(MobEffectInstance instance) {
        var key = BuiltInRegistries.MOB_EFFECT.getKey(instance.getEffect().value());
        return key == null ? "<unregistered>" : key.toString();
    }

    /** Slot names lowercase and vanilla-spelled; an unknown one lists the whole set rather than defaulting. */
    static EquipmentSlot equipmentSlot(String slot) {
        if (slot != null) {
            for (EquipmentSlot candidate : EquipmentSlot.values()) {
                if (candidate.getName().equalsIgnoreCase(slot)) return candidate;
            }
        }
        List<String> names = new ArrayList<>();
        for (EquipmentSlot candidate : EquipmentSlot.values()) names.add(candidate.getName());
        throw new IllegalStateException(
                "'" + slot + "' is not an equipment slot — the slots are " + String.join(", ", names));
    }
}
