package net.magicterra.stagewright.capability;

import java.util.List;
import net.minecraft.core.BlockPos;

/**
 * The tanks in a block, as every tech mod exposes them. Registered as the capability {@code "fluids"}. See
 * {@link BlockInventory} for why this is one facet over a platform capability, where the implementation lives, and what
 * positions are.
 *
 * <p>Amounts are millibuckets — a bucket is 1000.
 */
public interface Fluids {

    /** Whether the block at this position exposes tanks. */
    boolean present(BlockPos pos);

    /** How many tanks it has. */
    int tanks(BlockPos pos);

    /** The fluid id in one tank, or {@code "minecraft:empty"}. */
    String fluid(BlockPos pos, int tank);

    /** How much is in one tank, in millibuckets. */
    int amount(BlockPos pos, int tank);

    /** How much that tank holds, in millibuckets. */
    int tankCapacity(BlockPos pos, int tank);

    /** Every non-empty tank as {@code "<tank>=<mB> <fluidid>"}, in tank order. */
    List<String> contents(BlockPos pos);

    /** Push fluid in; returns how many millibuckets were accepted. */
    int fill(BlockPos pos, String fluidId, int amount);

    /** Whether the block would take this fluid, changing nothing. */
    boolean accepts(BlockPos pos, String fluidId);

    /** Pull fluid out; returns how many millibuckets came. */
    int drain(BlockPos pos, String fluidId, int amount);
}
