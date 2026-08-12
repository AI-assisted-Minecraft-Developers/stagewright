package net.magicterra.stagewright.scene;

import net.magicterra.stagewright.contract.SceneFailure;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/**
 * Equipment slots — vanilla armour always, Curios accessory slots when the pack has Curios.
 *
 * <p>Reached as {@link SceneContext#equip()}. Accessories are where a large content mod puts the
 * effects that are hardest to test any other way: Twilight Forest's Charm of Life revives you,
 * its Charm of Keeping preserves your inventory, and both do it from a Curios slot rather than
 * from anywhere the vanilla equipment API can see.
 *
 * <h2>Curios is reached by name, never by dependency</h2>
 *
 * {@code :stagewright-api} is compiled by every mod that writes a suite. Adding a Curios dependency
 * to it would require Curios of all of them — including the ones testing a mod that has nothing to
 * do with accessories. So the whole Curios half of this facet goes through {@code Class.forName} and
 * reflection, exactly as {@code StageWrightCommon} already probes for worlddriver.
 *
 * <p>When Curios is absent, a curio call {@code skip}s the scene with a reason rather than throwing
 * {@code NoClassDefFoundError} and rather than passing quietly. That is the same answer
 * {@link SceneContext#player()} gives on a topology with no player, and it means one suite can carry
 * accessory scenes and still be green in a runtime without the mod — with the skip visible in the
 * results, so "green" never silently means "did not run".
 *
 * <pre>{@code
 * s.equip().curio("necklace", "twilightforest:charm_of_life_2");
 * s.expect(s.equip().inCurio("necklace")).isEqualTo("twilightforest:charm_of_life_2");
 * // ...then assert the effect the charm is supposed to have.
 * }</pre>
 */
public final class Equip {

    /** Probed by name. Present with the Curios mod, absent everywhere else, and this class must work
     *  in both cases. */
    private static final String CURIOS_API = "top.theillusivec4.curios.api.CuriosApi";

    private final SceneContext ctx;

    /** Set once armour or a curio has been captured for restoration. */
    private boolean pinned;

    Equip(SceneContext ctx) {
        this.ctx = ctx;
    }

    // ---- vanilla ----

    /**
     * Put an item in a vanilla equipment slot: {@code head}, {@code chest}, {@code legs},
     * {@code feet}, {@code mainhand} or {@code offhand}.
     */
    public Equip armor(String slot, String itemId) {
        ServerPlayer player = pin();
        player.setItemSlot(slotOf(slot), Ids.stack(itemId, 1));
        return this;
    }

    /** What is in a vanilla equipment slot, or {@code ""} when it is empty. */
    public String inArmor(String slot) {
        return Ids.idOf(ctx.player().getItemBySlot(slotOf(slot)));
    }

    // ---- curios ----

    /** Whether Curios is in this runtime at all. Lets a scene branch instead of skipping, for the
     *  cases where the accessory is a bonus rather than the subject. */
    public boolean curiosPresent() {
        return curiosApi() != null;
    }

    /**
     * Every curio slot type this player has, e.g. {@code necklace}, {@code ring}, {@code charm}.
     *
     * <p>Worth asserting on directly: a mod that ships a curio for a slot type nothing registers has
     * an item nobody can ever wear, and that failure is completely silent in game.
     */
    public List<String> curioSlots() {
        Object handler = requireInventory();
        Map<?, ?> curios = (Map<?, ?>) call(handler, "getCurios");
        List<String> out = new ArrayList<>();
        for (Object key : curios.keySet()) out.add(String.valueOf(key));
        out.sort(String::compareTo);
        return out;
    }

    /** Put an item in the first index of a curio slot type. */
    public Equip curio(String slotType, String itemId) {
        return curio(slotType, 0, itemId);
    }

    /** Put an item in a specific index of a curio slot type. */
    public Equip curio(String slotType, int index, String itemId) {
        Object handler = requireInventory();
        pin();
        requireSlotType(handler, slotType);
        pinCurio(handler, slotType, index);
        call(handler, "setEquippedCurio",
                new Class<?>[] { String.class, int.class, ItemStack.class },
                slotType, index, Ids.stack(itemId, 1));
        return this;
    }

    /** What is in the first index of a curio slot type, or {@code ""} when empty. */
    public String inCurio(String slotType) {
        return inCurio(slotType, 0);
    }

    /** What is in a specific index of a curio slot type, or {@code ""} when empty. */
    public String inCurio(String slotType, int index) {
        Object handler = requireInventory();
        Object stacks = stacksOf(handler, slotType);
        ItemStack stack = (ItemStack) call(stacks, "getStackInSlot", new Class<?>[] { int.class }, index);
        return stack == null ? "" : Ids.idOf(stack);
    }

    /** How many indices a curio slot type has for this player. */
    public int curioSlotCount(String slotType) {
        Object stacks = stacksOf(requireInventory(), slotType);
        return (int) call(stacks, "getSlots");
    }

    /**
     * The wearer's current value for an attribute, by registry id — on 1.21.1 that is
     * {@code "minecraft:generic.armor"}, {@code "minecraft:generic.armor_toughness"},
     * {@code "minecraft:generic.movement_speed"}, or any id a mod registers. The {@code generic.}
     * prefix is real and is easy to get wrong: Mojang flattened these ids to plain
     * {@code minecraft:armor} in 1.21.2, so every example written against a later version is wrong
     * here. An unknown id fails naming the registry rather than reading as "this item has no
     * effect", which is the whole reason that rule exists.
     *
     * <p>What makes equipment EFFECTS assertable rather than only equipment placement. Before this,
     * a suite could prove a chestplate went into the chest slot and could not prove it did anything —
     * which is the half that actually breaks, because an armour material's values live in a data file
     * a pack can override and a mod can renumber between versions. The same call covers a curio or a
     * trinket that grants a modifier, since a modifier is a modifier wherever it came from.
     *
     * <p>Reads the live value, so it is taken AFTER equipping and compared against a value taken
     * before. Comparing against a hardcoded number would be asserting the mod's balance choices,
     * which is the mod author's business and changes every release.
     *
     * <p>A double, and an unknown id is a failure naming the registry — the same rule every id in
     * this API follows, so a typo cannot read as "this item has no effect".
     */
    public double attribute(String attributeId) {
        ServerPlayer player = ctx.player();
        net.minecraft.resources.ResourceLocation id = Ids.location(attributeId, "attribute");
        Optional<net.minecraft.core.Holder.Reference<net.minecraft.world.entity.ai.attributes.Attribute>>
                holder = net.minecraft.core.registries.BuiltInRegistries.ATTRIBUTE.getHolder(id);
        if (holder.isEmpty()) {
            throw new SceneFailure("no attribute is registered as '" + id + "'");
        }
        net.minecraft.world.entity.ai.attributes.AttributeInstance instance =
                player.getAttributes().getInstance(holder.get());
        if (instance == null) {
            throw new SceneFailure("the attribute '" + id + "' is registered but this player has no"
                    + " instance of it — it applies to some other kind of entity");
        }
        return instance.getValue();
    }

    // ---- internals ----

    private ServerPlayer pin() {
        ServerPlayer player = ctx.player();
        if (pinned) return player;
        pinned = true;
        // Armour is a player-wide, scene-crossing state exactly like the inventory, so the same
        // no-leak rule applies: a scene that dressed the player must undress them, or the next
        // scene's "unarmoured player takes N damage" assertion is about a different player.
        java.util.Map<EquipmentSlot, ItemStack> before = new java.util.EnumMap<>(EquipmentSlot.class);
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            before.put(slot, player.getItemBySlot(slot).copy());
        }
        ctx.cleanup(() -> before.forEach((slot, stack) -> player.setItemSlot(slot, stack.copy())));
        return player;
    }

    /** Capture one curio index before it is overwritten. Per index rather than wholesale: reading
     *  every slot type of every index to snapshot them would be a lot of reflection for a scene that
     *  touches one necklace. */
    private void pinCurio(Object handler, String slotType, int index) {
        Object stacks = stacksOf(handler, slotType);
        ItemStack before = (ItemStack) call(stacks, "getStackInSlot", new Class<?>[] { int.class }, index);
        ItemStack copy = before == null ? ItemStack.EMPTY : before.copy();
        ctx.cleanup(() -> call(handler, "setEquippedCurio",
                new Class<?>[] { String.class, int.class, ItemStack.class }, slotType, index, copy.copy()));
    }

    private Class<?> curiosApi() {
        try {
            return Class.forName(CURIOS_API, false, Equip.class.getClassLoader());
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * This player's Curios inventory, or {@code skip} out of the scene when Curios is not here.
     *
     * <p>Skip rather than fail: a runtime without Curios is not a broken runtime, it is a runtime
     * where accessory scenes do not apply. The reason lands in the results, so the scene is visibly
     * un-run rather than quietly green.
     */
    private Object requireInventory() {
        Class<?> api = curiosApi();
        if (api == null) {
            ctx.skip("this scene needs the Curios mod, which is not in this runtime");
        }
        ServerPlayer player = ctx.player();
        Object result;
        try {
            Method m = api.getMethod("getCuriosInventory", net.minecraft.world.entity.LivingEntity.class);
            result = m.invoke(null, player);
        } catch (ReflectiveOperationException e) {
            throw new SceneFailure("cannot reach CuriosApi.getCuriosInventory — the Curios on this"
                    + " classpath has a different API than this facet knows (" + e + ")");
        }
        if (!(result instanceof Optional<?> optional) || optional.isEmpty()) {
            ctx.skip("Curios is present but this player has no curios inventory — the capability is"
                    + " attached on entity load, so this can also mean the player is not fully joined");
        }
        return ((Optional<?>) result).get();
    }

    /** Fail naming the slot types this player DOES have. A curio scene that names a slot the pack
     *  does not register is the common mistake, and the candidate list is the whole answer. */
    private void requireSlotType(Object handler, String slotType) {
        Map<?, ?> curios = (Map<?, ?>) call(handler, "getCurios");
        if (curios.containsKey(slotType)) return;
        List<String> have = new ArrayList<>();
        for (Object key : curios.keySet()) have.add(String.valueOf(key));
        have.sort(String::compareTo);
        throw new SceneFailure("this player has no curio slot type '" + slotType + "' — they have "
                + (have.isEmpty() ? "none at all" : String.join(", ", have)));
    }

    private Object stacksOf(Object handler, String slotType) {
        requireSlotType(handler, slotType);
        Object optional = call(handler, "getStacksHandler", new Class<?>[] { String.class }, slotType);
        if (!(optional instanceof Optional<?> o) || o.isEmpty()) {
            throw new SceneFailure("curio slot type '" + slotType + "' has no stacks handler");
        }
        return call(o.get(), "getStacks");
    }

    private static Object call(Object target, String method) {
        return call(target, method, new Class<?>[0]);
    }

    private static Object call(Object target, String method, Class<?>[] signature, Object... args) {
        try {
            Method m = target.getClass().getMethod(method, signature);
            m.setAccessible(true);
            return m.invoke(target, args);
        } catch (ReflectiveOperationException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new SceneFailure("Curios call " + method + " failed: " + cause);
        }
    }

    private static EquipmentSlot slotOf(String slot) {
        if (slot != null) {
            for (EquipmentSlot candidate : EquipmentSlot.values()) {
                if (candidate.getName().equalsIgnoreCase(slot)) return candidate;
            }
        }
        List<String> names = new ArrayList<>();
        for (EquipmentSlot candidate : EquipmentSlot.values()) names.add(candidate.getName());
        throw new SceneFailure("'" + slot + "' is not an equipment slot — the slots are "
                + String.join(", ", names));
    }
}
