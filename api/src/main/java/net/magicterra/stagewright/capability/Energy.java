package net.magicterra.stagewright.capability;

import net.minecraft.core.BlockPos;

/**
 * Energy stored in a block, as every tech mod exposes it. Registered as the capability {@code "energy"}. See
 * {@link BlockInventory} for why this is one facet over a platform capability, where the implementation lives, and what
 * positions are.
 *
 * <p>Nothing in vanilla implements this, so a runtime with no tech mod answers {@code present() == false} everywhere.
 * That is a true answer, not a reason to abort.
 *
 * <pre>{@code
 * Energy energy = game.capability("energy", Energy.class);
 * assertTrue(energy.capacity(cube) > 0);
 * energy.fill(cube);
 * }</pre>
 */
public interface Energy {

    /** Whether the block at this position offers energy at all. */
    boolean present(BlockPos pos);

    /** Energy currently in the block. */
    int stored(BlockPos pos);

    /** How much the block can hold. */
    int capacity(BlockPos pos);

    /** Whether the block accepts energy. A generator's output side does not. */
    boolean canReceive(BlockPos pos);

    /** Whether the block gives energy out. */
    boolean canExtract(BlockPos pos);

    /** Push energy in; returns how much was accepted. */
    int receive(BlockPos pos, int amount);

    /** Pull energy out; returns how much came. */
    int extract(BlockPos pos, int amount);

    /** Fill to capacity; returns what went in. */
    int fill(BlockPos pos);
}
