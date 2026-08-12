package net.magicterra.stagewright.scene;

import java.util.List;

/**
 * The tanks in a block, as every tech mod exposes them.
 *
 * <p>Registered as the capability {@code "fluids"}. See {@link BlockInventory} for why this is one
 * facet over a platform capability rather than an adapter per mod, and for where the implementation
 * lives.
 *
 * <p>Vanilla has a bucket and a cauldron and nothing a machine could use, so with
 * {@link BlockInventory} this is the second of the two seams a modded process actually runs through:
 * items in, fluid out, or the reverse. A scene asserting that a machine consumed water and produced
 * steam has no other way to look.
 *
 * <p>Amounts are millibuckets — a bucket is 1000.
 */
public interface Fluids {

    /** Whether the block at this position exposes tanks. */
    boolean present(int dx, int dy, int dz);

    /** How many tanks it has. */
    int tanks(int dx, int dy, int dz);

    /** The fluid id in one tank, or {@code "minecraft:empty"}. */
    String fluid(int dx, int dy, int dz, int tank);

    /** How much is in one tank, in millibuckets. */
    int amount(int dx, int dy, int dz, int tank);

    /** How much that tank holds, in millibuckets. */
    int tankCapacity(int dx, int dy, int dz, int tank);

    /** Every non-empty tank as {@code "<tank>=<mB> <fluidid>"}, in tank order. */
    List<String> contents(int dx, int dy, int dz);

    /** Push fluid in; returns how many millibuckets were accepted. */
    int fill(int dx, int dy, int dz, String fluidId, int amount);

    /** Whether the block would take this fluid, changing nothing. */
    boolean accepts(int dx, int dy, int dz, String fluidId);

    /** Pull fluid out; returns how many millibuckets came. */
    int drain(int dx, int dy, int dz, String fluidId, int amount);
}
