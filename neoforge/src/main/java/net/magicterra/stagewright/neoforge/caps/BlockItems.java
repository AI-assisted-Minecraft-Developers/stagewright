package net.magicterra.stagewright.neoforge.caps;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import net.magicterra.stagewright.capability.BlockInventory;
import net.magicterra.stagewright.capability.CapabilityProvider;
import net.magicterra.stagewright.test.Game;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * The inventory of a block, through the item-handler capability rather than through {@code Container}. Every machine
 * in every tech mod — input slots that refuse output, sided access — exposes itself through this one capability, and
 * vanilla containers implement it too: a chest answers {@code slots() == 27}.
 *
 * <pre>{@code
 * BlockInventory inv = game.capability("itemhandler", BlockInventory.class);
 * inv.insert(chest, 0, "minecraft:diamond", 3);
 * assertEquals("minecraft:diamond", inv.item(chest, 0));
 * }</pre>
 *
 * <p>Positions are world positions in the overworld; every call runs on the server thread.
 */
public final class BlockItems implements BlockInventory {

    private final Game game;

    private BlockItems(Game game) {
        this.game = game;
    }

    @Override
    public boolean present(BlockPos pos) {
        return at(pos, handlers -> !handlers.isEmpty());
    }

    @Override
    public int slots(BlockPos pos) {
        return at(pos, handlers -> require(pos, handlers).getSlots());
    }

    @Override
    public String item(BlockPos pos, int slot) {
        return at(pos, handlers -> idOf(require(pos, handlers).getStackInSlot(slot)));
    }

    @Override
    public int count(BlockPos pos, int slot) {
        return at(pos, handlers -> require(pos, handlers).getStackInSlot(slot).getCount());
    }

    @Override
    public List<String> contents(BlockPos pos) {
        return at(pos, handlers -> {
            IItemHandler handler = require(pos, handlers);
            List<String> out = new ArrayList<>();
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack stack = handler.getStackInSlot(slot);
                if (!stack.isEmpty()) out.add(slot + "=" + stack.getCount() + " " + idOf(stack));
            }
            return out;
        });
    }

    /** Tries every side — see {@link BlockEnergy#handlers} for why. */
    @Override
    public int insert(BlockPos pos, int slot, String itemId, int amount) {
        return at(pos, handlers -> {
            require(pos, handlers);
            ItemStack stack = new ItemStack(item(itemId), amount);
            int leftover = amount;
            for (IItemHandler handler : handlers) {
                if (slot >= handler.getSlots()) continue;
                int remaining = handler.insertItem(slot, stack, false).getCount();
                if (remaining < amount) return remaining;
                leftover = remaining;
            }
            return leftover;
        });
    }

    @Override
    public boolean accepts(BlockPos pos, int slot, String itemId) {
        return at(pos, handlers -> {
            require(pos, handlers);
            ItemStack stack = new ItemStack(item(itemId), 1);
            for (IItemHandler handler : handlers) {
                if (slot < handler.getSlots() && handler.insertItem(slot, stack, true).isEmpty()) return true;
            }
            return false;
        });
    }

    @Override
    public int extract(BlockPos pos, int slot, int amount) {
        return at(pos, handlers -> {
            require(pos, handlers);
            for (IItemHandler handler : handlers) {
                if (slot >= handler.getSlots()) continue;
                int got = handler.extractItem(slot, amount, false).getCount();
                if (got > 0) return got;
            }
            return 0;
        });
    }

    private <T> T at(BlockPos pos, Function<List<IItemHandler>, T> work) {
        return game.onServer(server -> work.apply(handlers(server.overworld(), pos)));
    }

    /** The no-side view first, then the six faces, deduplicated. See {@link BlockEnergy#handlers}. */
    private static List<IItemHandler> handlers(ServerLevel level, BlockPos pos) {
        List<IItemHandler> out = new ArrayList<>();
        IItemHandler noSide = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        if (noSide != null) out.add(noSide);
        for (Direction side : Direction.values()) {
            IItemHandler sided = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, side);
            if (sided != null && out.stream().noneMatch(known -> known == sided)) out.add(sided);
        }
        return out;
    }

    private static String idOf(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static Item item(String itemId) {
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            throw new IllegalStateException("no item is registered as '" + itemId + "'");
        }
        return BuiltInRegistries.ITEM.get(id);
    }

    private static IItemHandler require(BlockPos pos, List<IItemHandler> handlers) {
        if (handlers.isEmpty()) {
            throw new IllegalStateException("the block at " + pos.toShortString() + " exposes no item handler here."
                    + " Use itemhandler.present(pos) to branch instead of asserting.");
        }
        return handlers.get(0);
    }

    /** See {@link BlockEnergy.Provider} — available wherever NeoForge is. */
    public static final class Provider implements CapabilityProvider {
        @Override
        public String name() {
            return "itemhandler";
        }

        @Override
        public boolean availableIn(Game game) {
            return true;
        }

        @Override
        public Object facet(Game game) {
            return new BlockItems(game);
        }

        @Override
        public String absentReason() {
            return "the 'itemhandler' capability is NeoForge's block item capability, and this run is not on NeoForge";
        }
    }
}
