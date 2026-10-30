package net.magicterra.stagewright.capability;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.magicterra.stagewright.test.Body;
import net.magicterra.stagewright.test.Game;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.item.ItemStack;
import org.opentest4j.TestAbortedException;

/**
 * Equipment slots — vanilla armour always, Curios accessory slots when the pack has Curios. The capability
 * {@code equip}, and {@code curios} where Curios is installed:
 *
 * <pre>{@code
 * Equip equip = game.capability("curios", Equip.class);
 * equip.curio(bot, "necklace", "twilightforest:charm_of_life_2");
 * assertEquals("twilightforest:charm_of_life_2", equip.inCurio(bot, "necklace"));
 * }</pre>
 *
 * <h2>Curios is reached by name, never by dependency</h2>
 *
 * {@code :stagewright-api} is compiled by every mod that writes a suite, so a Curios dependency here would be imposed
 * on all of them. The Curios half therefore goes through reflection, and without Curios a curio call aborts the test
 * with a reason rather than throwing {@code NoClassDefFoundError}.
 *
 * <p>What a test changes — armour, a curio index — is put back by {@link Game#afterTest} when it ends.
 */
public final class Equip {

    /** Probed by name. Present with the Curios mod, absent everywhere else, and this class must work in both cases. */
    private static final String CURIOS_API = "top.theillusivec4.curios.api.CuriosApi";

    private final Game game;
    /** Bodies whose armour this test has pinned for restoring. Touched on the test thread. */
    private final Set<String> pinned = new HashSet<>();

    Equip(Game game) {
        this.game = game;
    }

    // ---- vanilla ----

    /**
     * Put an item in a vanilla equipment slot: {@code head}, {@code chest}, {@code legs}, {@code feet},
     * {@code mainhand} or {@code offhand}.
     */
    public Equip armor(Body body, String slot, String itemId) {
        EquipmentSlot equipmentSlot = Items.equipmentSlot(slot);
        pin(body);
        body.runOnServer(player -> player.setItemSlot(equipmentSlot, Ids.stack(itemId, 1)));
        return this;
    }

    /** What is in a vanilla equipment slot, or {@code ""} when it is empty. */
    public String inArmor(Body body, String slot) {
        EquipmentSlot equipmentSlot = Items.equipmentSlot(slot);
        return body.onServer(player -> Ids.idOf(player.getItemBySlot(equipmentSlot)));
    }

    // ---- curios ----

    /** Whether Curios is in this runtime at all, for a test where the accessory is a bonus rather than the subject. */
    public boolean curiosPresent() {
        return curiosInstalled();
    }

    /** Every curio slot type this body has, e.g. {@code necklace}, {@code ring}, {@code charm}. */
    public List<String> curioSlots(Body body) {
        return body.onServer(player -> {
            Map<?, ?> curios = (Map<?, ?>) call(requireInventory(player), "getCurios");
            List<String> out = new ArrayList<>();
            for (Object key : curios.keySet()) out.add(String.valueOf(key));
            out.sort(String::compareTo);
            return out;
        });
    }

    /** Put an item in the first index of a curio slot type. */
    public Equip curio(Body body, String slotType, String itemId) {
        return curio(body, slotType, 0, itemId);
    }

    /** Put an item in a specific index of a curio slot type. */
    public Equip curio(Body body, String slotType, int index, String itemId) {
        pin(body);
        // Captured and written in one hop; the restore is registered from this thread after it.
        Runnable restore = body.onServer(player -> {
            Object handler = requireInventory(player);
            requireSlotType(handler, slotType);
            ItemStack before =
                    (ItemStack) call(stacksOf(handler, slotType), "getStackInSlot", new Class<?>[] {int.class}, index);
            ItemStack copy = before == null ? ItemStack.EMPTY : before.copy();
            setCurio(handler, slotType, index, Ids.stack(itemId, 1));
            return () -> setCurio(handler, slotType, index, copy.copy());
        });
        game.afterTest(server -> restore.run());
        return this;
    }

    /** What is in the first index of a curio slot type, or {@code ""} when empty. */
    public String inCurio(Body body, String slotType) {
        return inCurio(body, slotType, 0);
    }

    /** What is in a specific index of a curio slot type, or {@code ""} when empty. */
    public String inCurio(Body body, String slotType, int index) {
        return body.onServer(player -> {
            Object stacks = stacksOf(requireInventory(player), slotType);
            ItemStack stack = (ItemStack) call(stacks, "getStackInSlot", new Class<?>[] {int.class}, index);
            return stack == null ? "" : Ids.idOf(stack);
        });
    }

    /** How many indices a curio slot type has for this body. */
    public int curioSlotCount(Body body, String slotType) {
        return body.onServer(player -> (int) call(stacksOf(requireInventory(player), slotType), "getSlots"));
    }

    /**
     * The body's current value for an attribute, by registry id — on 1.21.1 that is {@code "minecraft:generic.armor"},
     * {@code "minecraft:generic.movement_speed"}, or any id a mod registers (the {@code generic.} prefix went away in
     * 1.21.2). Read after equipping and compared with a value read before, so equipment EFFECTS are assertable, not
     * only placement. An unknown id fails naming the registry.
     */
    public double attribute(Body body, String attributeId) {
        return body.onServer(player -> {
            ResourceLocation id = Ids.location(attributeId, "attribute");
            Optional<Holder.Reference<Attribute>> holder = BuiltInRegistries.ATTRIBUTE.getHolder(id);
            if (holder.isEmpty()) throw new IllegalStateException("no attribute is registered as '" + id + "'");
            AttributeInstance instance = player.getAttributes().getInstance(holder.get());
            if (instance == null) {
                throw new IllegalStateException("the attribute '" + id + "' is registered but this player has no"
                        + " instance of it — it applies to some other kind of entity");
            }
            return instance.getValue();
        });
    }

    // ---- internals ----

    /** Whether Curios' API class is in this runtime. */
    static boolean curiosInstalled() {
        return curiosApi() != null;
    }

    /** Armour is shared state like the inventory: a test that dressed the body undresses it. */
    private void pin(Body body) {
        if (!pinned.add(body.name())) return;
        Runnable restore = body.onServer(player -> {
            Map<EquipmentSlot, ItemStack> before = new EnumMap<>(EquipmentSlot.class);
            for (EquipmentSlot slot : EquipmentSlot.values())
                before.put(slot, player.getItemBySlot(slot).copy());
            return () -> before.forEach((slot, stack) -> player.setItemSlot(slot, stack.copy()));
        });
        game.afterTest(server -> restore.run());
    }

    private static void setCurio(Object handler, String slotType, int index, ItemStack stack) {
        call(
                handler,
                "setEquippedCurio",
                new Class<?>[] {String.class, int.class, ItemStack.class},
                slotType,
                index,
                stack);
    }

    private static Class<?> curiosApi() {
        try {
            return Class.forName(CURIOS_API, false, Equip.class.getClassLoader());
        } catch (Throwable t) {
            return null;
        }
    }

    /** This player's Curios inventory; aborts the test when Curios is not here. */
    private static Object requireInventory(ServerPlayer player) {
        Class<?> api = curiosApi();
        if (api == null) throw new TestAbortedException("this test needs the Curios mod, which is not in this runtime");
        Object result;
        try {
            Method m = api.getMethod("getCuriosInventory", LivingEntity.class);
            result = m.invoke(null, player);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot reach CuriosApi.getCuriosInventory — the Curios on this"
                    + " classpath has a different API than this facet knows (" + e + ")");
        }
        if (!(result instanceof Optional<?> optional) || optional.isEmpty()) {
            throw new TestAbortedException("Curios is present but this player has no curios inventory — the"
                    + " capability is attached on entity load, so this can also mean the player is not fully joined");
        }
        return optional.get();
    }

    /** Fail naming the slot types this body DOES have. */
    private static void requireSlotType(Object handler, String slotType) {
        Map<?, ?> curios = (Map<?, ?>) call(handler, "getCurios");
        if (curios.containsKey(slotType)) return;
        List<String> have = new ArrayList<>();
        for (Object key : curios.keySet()) have.add(String.valueOf(key));
        have.sort(String::compareTo);
        throw new IllegalStateException("this player has no curio slot type '" + slotType + "' — they have "
                + (have.isEmpty() ? "none at all" : String.join(", ", have)));
    }

    private static Object stacksOf(Object handler, String slotType) {
        requireSlotType(handler, slotType);
        Object optional = call(handler, "getStacksHandler", new Class<?>[] {String.class}, slotType);
        if (!(optional instanceof Optional<?> o) || o.isEmpty()) {
            throw new IllegalStateException("curio slot type '" + slotType + "' has no stacks handler");
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
            throw new IllegalStateException("Curios call " + method + " failed: " + cause);
        }
    }
}
