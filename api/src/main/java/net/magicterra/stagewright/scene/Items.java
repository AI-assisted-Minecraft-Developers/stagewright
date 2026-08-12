package net.magicterra.stagewright.scene;

import net.magicterra.stagewright.contract.Clock;
import net.magicterra.stagewright.contract.SceneFailure;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;

/**
 * A player's inventory, hunger and status effects — the ground floor every other conformance facet
 * stands on.
 *
 * <p>Reached as {@link SceneContext#items()}. Everything is expressed in string ids
 * ({@code "twilightforest:hydra_chop"}) rather than {@code Item} or {@code ItemStack}, so the same
 * calls work from a JavaScript scene file; see {@link Ids} for why that is a hard constraint rather
 * than a style.
 *
 * <pre>{@code
 * int before = s.items().foodLevel();
 * s.items().give("twilightforest:hydra_chop").eat("twilightforest:hydra_chop");
 * s.expect(s.items().foodLevel()).as("hunger after eating").isGreaterThan(before);
 * }</pre>
 *
 * <h2>Nothing here leaks into the next scene</h2>
 *
 * The first call that would change the player's inventory snapshots the whole thing and registers a
 * {@link SceneContext#cleanup} that puts it back — on PASS, FAIL and TIMEOUT alike. That is not
 * politeness. A player is shared by every scene in a suite, so without it the inventory becomes a
 * function of scene ORDER, and the failure that produces is a LATER scene finding an item it never
 * asked for. The Waystones suite paid for this lesson once already, with a database that reached its
 * fifth scene holding 156 entries, 144 of them left by a predecessor.
 *
 * <h2>It needs a player</h2>
 *
 * Every method routes through {@link SceneContext#player()}, which {@code skip}s the scene when
 * nobody is connected. So an inventory scene records {@code skipped:} with its reason on a bare
 * dedicated server and runs for real under the two topologies that have a player — the same shape
 * the Waystones activation scenes already have, and the reason three topologies are worth running.
 */
public final class Items {

    private final SceneContext ctx;

    /** Set once the inventory has been pinned for restoration, so a scene that gives twenty items
     *  registers one cleanup rather than twenty. */
    private boolean pinned;

    Items(SceneContext ctx) {
        this.ctx = ctx;
    }

    // ---- writes ----

    /** Put one of this item in the player's inventory. */
    public Items give(String itemId) {
        return give(itemId, 1);
    }

    /**
     * Put {@code count} of this item in the player's inventory.
     *
     * <p>Fails when the inventory has no room rather than dropping the remainder on the floor. A
     * silently-dropped stack becomes an entity that ticks, drifts, and despawns 5 minutes later —
     * and the scene that asked for the item fails on an assertion about not having it, naming
     * neither the full inventory nor the item on the ground beside it.
     */
    public Items give(String itemId, int count) {
        ServerPlayer player = pin();
        ItemStack stack = Ids.stack(itemId, count);
        int wanted = stack.getCount();
        if (!player.getInventory().add(stack)) {
            throw new SceneFailure("could not give " + wanted + " x '" + itemId + "' — the player's"
                    + " inventory is full (" + stack.getCount() + " would not fit)."
                    + " Call s.items().clear() first, or give less.");
        }
        return this;
    }

    /** Empty the inventory, including armour and offhand. */
    public Items clear() {
        ServerPlayer player = pin();
        player.getInventory().clearContent();
        return this;
    }

    /**
     * Put this item in the main hand, replacing whatever was there.
     *
     * <p>The held item is what block interaction, item use and most mod hooks read, so this is the
     * setup step for anything that is not purely about inventory contents.
     */
    public Items hold(String itemId) {
        ServerPlayer player = pin();
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, Ids.stack(itemId, 1));
        return this;
    }

    /**
     * Consume one of this item as food, through the game's own finish-using path.
     *
     * <p>{@code ItemStack.finishUsingItem} is the real route — it is what a player releasing
     * right-click reaches — so an item whose mod overrides {@code finishUsingItem} (Twilight
     * Forest's Brittle Flask and Experiment 115 both do) is exercised rather than bypassed. Setting
     * the stack in the main hand first matters for the same reason: an override that reads the hand
     * would otherwise see something else.
     *
     * <p><b>Refuses a non-food.</b> {@code finishUsingItem} on an item with no food component
     * returns the stack unchanged and does nothing at all, which from a scene's point of view is a
     * line that ran, asserted nothing and passed.
     */
    public Items eat(String itemId) {
        ServerPlayer player = pin();
        ItemStack stack = Ids.stack(itemId, 1);
        if (!isEdible(stack)) {
            throw new SceneFailure("'" + itemId + "' is not food — it carries no food component, so"
                    + " finishUsingItem would return it unchanged and this line would do nothing");
        }
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, stack);
        stack.finishUsingItem(player.level(), player);
        return this;
    }

    /**
     * Set hunger and saturation outright, for a scene that needs the player in a known state.
     *
     * <p>Setup, not simulation. A full player cannot eat, so any scene about food has to make room
     * first — and the obvious way to do that, {@code s.command("effect give @s minecraft:hunger …")},
     * does not work from a scene: {@link SceneContext#command} builds its source with a null entity,
     * so {@code @s} resolves to nobody and the command throws. That is documented on {@code command}
     * itself, and it is why this exists rather than being left to the command surface.
     *
     * <p>Registers the same inventory-restoring pin as everything else here, plus a restore of the
     * hunger it found — a scene that starved the player must not hand a starving one to its
     * successor.
     */
    public Items hunger(int foodLevel, float saturation) {
        ServerPlayer player = pin();
        pinHunger(player);
        player.getFoodData().setFoodLevel(foodLevel);
        player.getFoodData().setSaturation(saturation);
        return this;
    }

    // ---- reads ----

    /** How many of this item the player is carrying, across the whole inventory. */
    public int count(String itemId) {
        var item = Ids.item(itemId);
        Inventory inventory = ctx.player().getInventory();
        int total = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    /** Whether the player is carrying any of this item. */
    public boolean has(String itemId) {
        return count(itemId) > 0;
    }

    /** The id of the item in the main hand, or {@code ""} when the hand is empty. */
    public String held() {
        return Ids.idOf(ctx.player().getMainHandItem());
    }

    /** Every distinct item id the player is carrying, in slot order. For a scene that wants to
     *  report what it found rather than guess what to look for. */
    public List<String> distinct() {
        Inventory inventory = ctx.player().getInventory();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            String id = Ids.idOf(inventory.getItem(i));
            if (!id.isEmpty() && !out.contains(id)) out.add(id);
        }
        return out;
    }

    /** Hunger, 0..20. */
    public int foodLevel() {
        return ctx.player().getFoodData().getFoodLevel();
    }

    /** Saturation — the hidden half of hunger, and the half a food item's quality shows up in. */
    public float saturation() {
        return ctx.player().getFoodData().getSaturationLevel();
    }

    /** The ids of every status effect currently on the player. */
    public List<String> effects() {
        List<String> out = new ArrayList<>();
        for (MobEffectInstance instance : ctx.player().getActiveEffects()) {
            out.add(effectId(instance));
        }
        return out;
    }

    /** Whether this status effect is on the player. */
    public boolean hasEffect(String effectId) {
        MobEffect effect = Ids.require(BuiltInRegistries.MOB_EFFECT, effectId, "mob effect");
        for (MobEffectInstance instance : ctx.player().getActiveEffects()) {
            if (instance.getEffect().value() == effect) return true;
        }
        return false;
    }

    /**
     * Every active effect with its amplifier and remaining duration in ticks.
     *
     * <p>Amplifier is zero-based in Minecraft — "Regeneration II" is amplifier 1 — and this reports
     * it raw rather than adding one, because the raw number is what every other API in the game
     * takes and a helpfully-incremented one would not round-trip.
     */
    public Map<String, int[]> effectDetails() {
        Map<String, int[]> out = new LinkedHashMap<>();
        for (MobEffectInstance instance : ctx.player().getActiveEffects()) {
            out.put(effectId(instance), new int[] { instance.getAmplifier(), instance.getDuration() });
        }
        return out;
    }

    /** What is worn in an armour slot: {@code "head"}, {@code "chest"}, {@code "legs"},
     *  {@code "feet"}, {@code "mainhand"} or {@code "offhand"}. Empty string when the slot is bare. */
    public String worn(String slot) {
        return Ids.idOf(ctx.player().getItemBySlot(equipmentSlot(slot)));
    }

    /**
     * Take the inventory snapshot now, without writing anything.
     *
     * <p>For a facet that can change the player's inventory without going through this one — a menu
     * whose slots are the player's bag, and whose contents vanilla hands back when it closes. Those
     * writes need the same restore, and there must be only one snapshot: two facets each capturing
     * and re-installing the inventory would fight over which "before" was the real one.
     *
     * <p>Package-private on purpose. A scene has no reason to ask for this — every public write here
     * already does it — and a scene that called it would be pinning a state it had not written to,
     * which is a no-op it would have to be told to stop doing.
     */
    void pinInventory() {
        pin();
    }

    // ---- internals ----

    /** Resolve the player AND make sure this scene's inventory changes are undone afterwards. */
    private ServerPlayer pin() {
        ServerPlayer player = ctx.player();
        if (pinned) return player;
        pinned = true;

        Inventory inventory = player.getInventory();
        int size = inventory.getContainerSize();
        List<ItemStack> snapshot = new ArrayList<>(size);
        for (int i = 0; i < size; i++) snapshot.add(inventory.getItem(i).copy());
        int selected = inventory.selected;

        // Copied out again on restore: the snapshot must survive being installed, or a scene that
        // resolves twice (a cleanup running after a failure inside another cleanup) would hand the
        // same stack objects to the inventory a second time.
        ctx.cleanup(() -> {
            for (int i = 0; i < size; i++) inventory.setItem(i, snapshot.get(i).copy());
            inventory.selected = selected;
        });
        return player;
    }

    /** Set once the player's hunger has been captured for restoration. Separate from the inventory
     *  pin because a scene may legitimately touch one and not the other. */
    private boolean hungerPinned;

    private void pinHunger(ServerPlayer player) {
        if (hungerPinned) return;
        hungerPinned = true;
        int food = player.getFoodData().getFoodLevel();
        float saturation = player.getFoodData().getSaturationLevel();
        ctx.cleanup(() -> {
            player.getFoodData().setFoodLevel(food);
            player.getFoodData().setSaturation(saturation);
        });
    }

    /**
     * Whether a stack can be eaten.
     *
     * <p>Checks the food component, which is what {@code Item.finishUsingItem}'s default
     * implementation branches on. A mod that overrides {@code finishUsingItem} without declaring a
     * food component is doing something other than eating, and this method is honest to call that
     * not-food.
     */
    private static boolean isEdible(ItemStack stack) {
        FoodProperties food = stack.get(DataComponents.FOOD);
        return food != null;
    }

    private static String effectId(MobEffectInstance instance) {
        var key = BuiltInRegistries.MOB_EFFECT.getKey(instance.getEffect().value());
        return key == null ? "<unregistered>" : key.toString();
    }

    /** Slot names as a scene writes them, kept lowercase and vanilla-spelled. Unknown names list the
     *  whole set rather than defaulting, on the same reasoning as {@code Clock.parse}. */
    private static EquipmentSlot equipmentSlot(String slot) {
        if (slot == null) throw new SceneFailure("an equipment slot name is required");
        for (EquipmentSlot candidate : EquipmentSlot.values()) {
            if (candidate.getName().equalsIgnoreCase(slot)) return candidate;
        }
        List<String> names = new ArrayList<>();
        for (EquipmentSlot candidate : EquipmentSlot.values()) names.add(candidate.getName());
        throw new SceneFailure("'" + slot + "' is not an equipment slot — the slots are "
                + String.join(", ", names));
    }
}
