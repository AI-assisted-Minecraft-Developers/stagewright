package net.magicterra.stagewright.neoforge.caps;

import java.util.ArrayList;
import java.util.List;
import net.magicterra.stagewright.scene.BlockInventory;
import net.magicterra.stagewright.scene.CapabilityProvider;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.contract.SceneFailure;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * The inventory of a block, through the item-handler capability rather than through
 * {@code Container}.
 *
 * <p>Vanilla's {@code Container} is a chest. Every machine in every tech mod is not — it has input
 * slots that refuse output, sided access, slot counts that change with an upgrade — and it exposes
 * all of that through this one capability. A scene that wants to put a stack in a machine and see
 * what comes out has exactly one portable way to do it, and this is it.
 *
 * <p>Vanilla containers implement it too, which is what makes this testable without any mod at all:
 * a chest answers {@code slots() == 27}.
 *
 * <pre>{@code
 * s.setBlock(0, 0, 0, 'minecraft:chest');
 * var inv = s.capability('itemhandler');
 * inv.insert(0, 0, 0, 0, 'minecraft:diamond', 3);
 * s.check('slot0', inv.item(0, 0, 0, 0)).isEqualTo('minecraft:diamond');
 * }</pre>
 */
public final class BlockItems implements BlockInventory {

    private final SceneContext ctx;

    private BlockItems(SceneContext ctx) {
        this.ctx = ctx;
    }

    /** Whether the block at this position exposes an item handler. */
    @Override
    public boolean present(int dx, int dy, int dz) {
        return handlerOrNull(dx, dy, dz) != null;
    }

    /** How many slots it has. */
    @Override
    public int slots(int dx, int dy, int dz) {
        return require(dx, dy, dz).getSlots();
    }

    /** The item id in one slot, or {@code "minecraft:air"} when empty. */
    @Override
    public String item(int dx, int dy, int dz, int slot) {
        return idOf(require(dx, dy, dz).getStackInSlot(slot));
    }

    /** How many are in one slot. */
    @Override
    public int count(int dx, int dy, int dz, int slot) {
        return require(dx, dy, dz).getStackInSlot(slot).getCount();
    }

    /** Every non-empty slot as {@code "<slot>=<count> <itemid>"}, in slot order — one string list is
     *  what a scene can put in {@code s.record()} and what a reader can compare between runs. */
    @Override
    public List<String> contents(int dx, int dy, int dz) {
        IItemHandler handler = require(dx, dy, dz);
        List<String> out = new ArrayList<>();
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (!stack.isEmpty()) {
                out.add(slot + "=" + stack.getCount() + " " + idOf(stack));
            }
        }
        return out;
    }

    /**
     * Put items in a slot; returns how many did NOT fit.
     *
     * <p>The leftover is the answer, not a detail: a machine's input slot refusing an item is the
     * behaviour most worth testing, and it refuses by returning the stack rather than by throwing.
     */
    @Override
    public int insert(int dx, int dy, int dz, int slot, String itemId, int amount) {
        require(dx, dy, dz);
        ItemStack stack = new ItemStack(item(itemId), amount);
        int leftover = amount;
        for (IItemHandler handler : handlers(dx, dy, dz)) {
            if (slot >= handler.getSlots()) continue;
            int remaining = handler.insertItem(slot, stack, false).getCount();
            if (remaining < amount) return remaining;
            leftover = remaining;
        }
        return leftover;
    }

    /** Whether a slot would accept this item at all, changing nothing. */
    @Override
    public boolean accepts(int dx, int dy, int dz, int slot, String itemId) {
        require(dx, dy, dz);
        ItemStack stack = new ItemStack(item(itemId), 1);
        for (IItemHandler handler : handlers(dx, dy, dz)) {
            if (slot < handler.getSlots() && handler.insertItem(slot, stack, true).isEmpty()) return true;
        }
        return false;
    }

    /** Take items out of a slot; returns how many actually came. */
    @Override
    public int extract(int dx, int dy, int dz, int slot, int amount) {
        require(dx, dy, dz);
        for (IItemHandler handler : handlers(dx, dy, dz)) {
            if (slot >= handler.getSlots()) continue;
            int got = handler.extractItem(slot, amount, false).getCount();
            if (got > 0) return got;
        }
        return 0;
    }

    /** The no-side view first, then the six faces, deduplicated. See {@link BlockEnergy#handlers} for
     *  why a write has to walk them. */
    private List<IItemHandler> handlers(int dx, int dy, int dz) {
        BlockPos pos = ctx.rel(dx, dy, dz);
        List<IItemHandler> out = new ArrayList<>();
        IItemHandler noSide = ctx.level().getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        if (noSide != null) out.add(noSide);
        for (Direction side : Direction.values()) {
            IItemHandler sided = ctx.level().getCapability(Capabilities.ItemHandler.BLOCK, pos, side);
            if (sided == null) continue;
            boolean seen = false;
            for (IItemHandler known : out) if (known == sided) { seen = true; break; }
            if (!seen) out.add(sided);
        }
        return out;
    }

    private static String idOf(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static Item item(String itemId) {
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        Item found = id == null ? null : BuiltInRegistries.ITEM.get(id);
        if (id == null || found == null || (found == net.minecraft.world.item.Items.AIR
                && !"minecraft:air".equals(itemId))) {
            throw new SceneFailure("no item is registered as '" + itemId + "'");
        }
        return found;
    }

    private IItemHandler handlerOrNull(int dx, int dy, int dz) {
        List<IItemHandler> all = handlers(dx, dy, dz);
        return all.isEmpty() ? null : all.get(0);
    }

    private IItemHandler require(int dx, int dy, int dz) {
        IItemHandler handler = handlerOrNull(dx, dy, dz);
        if (handler == null) {
            throw new SceneFailure("the block at (" + dx + "," + dy + "," + dz + ") is "
                    + ctx.blockAt(dx, dy, dz) + ", which exposes no item handler here."
                    + " Use itemhandler.present(dx,dy,dz) to branch instead of asserting.");
        }
        return handler;
    }

    /** See {@link BlockEnergy.Provider} — available wherever NeoForge is. */
    public static final class Provider implements CapabilityProvider {
        @Override
        public String name() {
            return "itemhandler";
        }

        @Override
        public boolean availableIn(SceneContext ctx) {
            return true;
        }

        @Override
        public Object facet(SceneContext ctx) {
            return new BlockItems(ctx);
        }

        @Override
        public String absentReason() {
            return "the 'itemhandler' capability is NeoForge's block item capability, and this run"
                    + " is not on NeoForge";
        }
    }
}
