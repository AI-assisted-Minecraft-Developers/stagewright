package net.magicterra.stagewright.capability;

import java.util.List;
import net.minecraft.core.BlockPos;

/**
 * The inventory of a block, as every modded machine exposes it. Registered as the capability {@code "itemhandler"}.
 *
 * <h2>Why this is one facet and not one adapter per mod</h2>
 *
 * <p>Mekanism, Thermal, Create, AE2 and several hundred others do not share an API. They share a <i>capability</i>: a
 * block's inventory is reached through one platform interface, because that is how hoppers and pipes talk to them.
 * So a facet written against the capability covers all of them, including mods written after this file, and a block
 * that does not implement it is reported as not implementing it. Vanilla containers implement it too: a chest
 * answers {@code slots() == 27}.
 *
 * <pre>{@code
 * BlockInventory inv = game.capability("itemhandler", BlockInventory.class);
 * inv.insert(plot.at(0, 0, 0), 0, "minecraft:diamond", 3);
 * }</pre>
 *
 * <h2>The interface is here; the implementation is not</h2>
 *
 * <p>The capability belongs to a loader, and this module compiles against neither — so {@code :stagewright-neoforge}
 * implements this and ships the provider. A Fabric implementation over that platform's storage API would satisfy the
 * same tests; until there is one, a Fabric run reports the capability absent and tests asking for it are aborted.
 *
 * <p>Positions are world positions in the overworld ({@code {x, y, z}} from a script test). Implementations run on the
 * server thread themselves.
 */
public interface BlockInventory {

    /** Whether the block at this position exposes an inventory at all. */
    boolean present(BlockPos pos);

    /** How many slots it has. */
    int slots(BlockPos pos);

    /** The item id in one slot, or {@code "minecraft:air"}. */
    String item(BlockPos pos, int slot);

    /** How many items are in one slot. */
    int count(BlockPos pos, int slot);

    /** Every non-empty slot as {@code "<slot>=<count> <itemid>"}, in slot order. */
    List<String> contents(BlockPos pos);

    /** Put items in a slot; returns how many did NOT fit — a machine's input slot refuses by handing them back. */
    int insert(BlockPos pos, int slot, String itemId, int amount);

    /** Whether a slot would accept this item, changing nothing. */
    boolean accepts(BlockPos pos, int slot, String itemId);

    /** Take items out of a slot; returns how many actually came. */
    int extract(BlockPos pos, int slot, int amount);
}
