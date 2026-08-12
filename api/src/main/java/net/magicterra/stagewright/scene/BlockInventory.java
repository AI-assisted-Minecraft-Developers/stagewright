package net.magicterra.stagewright.scene;

import java.util.List;

/**
 * The inventory of a block, as every modded machine exposes it.
 *
 * <p>Registered as the capability {@code "itemhandler"}.
 *
 * <h2>Why this is one facet and not one adapter per mod</h2>
 *
 * <p>Mekanism, Thermal, Create, AE2, Powah and several hundred others do not share an API. They
 * share a <i>capability</i>: a block's inventory is reached through one platform interface, and
 * every one of them implements it because that is how hoppers and pipes talk to them. So a facet
 * written against the capability covers all of them — including the mods written after this file —
 * and covers none of them wrongly, because a block that does not implement it is reported as not
 * implementing it rather than guessed at.
 *
 * <p>That is what makes this a "common implementation" in the sense worth having. An adapter per mod
 * is a subscription; an adapter per <i>standard</i> is a fixed cost.
 *
 * <p>Vanilla containers implement it too, which is what lets the mechanism be proved with no mod
 * installed: a chest answers {@code slots() == 27}.
 *
 * <pre>{@code
 * var inv = s.capability('itemhandler');
 * inv.insert(0, 0, 0, 0, 'minecraft:diamond', 3);
 * s.check('slot0', inv.item(0, 0, 0, 0)).isEqualTo('minecraft:diamond');
 * }</pre>
 *
 * <h2>The interface is here; the implementation is not</h2>
 *
 * <p>The capability belongs to a loader, and this module compiles against neither — so
 * {@code :stagewright-neoforge} implements this and ships the provider. Declaring the contract here
 * is what lets a scene in common code hold the type at all, and it is also the standing invitation:
 * a Fabric implementation over that platform's storage API satisfies the same scenes without one of
 * them changing. Until there is one, a Fabric run reports the capability absent and scenes asking
 * for it record a skip that says so.
 *
 * <p>Positions are arena-relative {@code (dx, dy, dz)}, like every other block verb on
 * {@link SceneContext}. Everything in and out is a primitive or a string id — the JS-safety rule in
 * {@link CapabilityProvider}, which a facet crossing a loader boundary has no way to bend.
 */
public interface BlockInventory {

    /** Whether the block at this position exposes an inventory at all. */
    boolean present(int dx, int dy, int dz);

    /** How many slots it has. */
    int slots(int dx, int dy, int dz);

    /** The item id in one slot, or {@code "minecraft:air"}. */
    String item(int dx, int dy, int dz, int slot);

    /** How many items are in one slot. */
    int count(int dx, int dy, int dz, int slot);

    /** Every non-empty slot as {@code "<slot>=<count> <itemid>"}, in slot order — one list a scene
     *  can hand to {@code s.record()} and a reader can diff between runs. */
    List<String> contents(int dx, int dy, int dz);

    /**
     * Put items in a slot; returns how many did NOT fit.
     *
     * <p>The leftover is the answer, not a detail. A machine's input slot refusing an item is the
     * behaviour most worth testing, and it refuses by handing the stack back rather than by throwing.
     */
    int insert(int dx, int dy, int dz, int slot, String itemId, int amount);

    /** Whether a slot would accept this item, changing nothing. */
    boolean accepts(int dx, int dy, int dz, int slot, String itemId);

    /** Take items out of a slot; returns how many actually came. */
    int extract(int dx, int dy, int dz, int slot, int amount);
}
