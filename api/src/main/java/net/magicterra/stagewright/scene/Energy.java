package net.magicterra.stagewright.scene;

/**
 * Energy stored in a block, as every tech mod exposes it.
 *
 * <p>Registered as the capability {@code "energy"}. See {@link BlockInventory} for why this is one
 * facet over a platform capability rather than an adapter per mod, and for where the implementation
 * lives.
 *
 * <p>Unlike {@link BlockInventory}, nothing in vanilla implements this — there is no vanilla energy —
 * so a runtime with no tech mod answers {@code present() == false} everywhere. That is a true answer
 * and not a reason to skip: the capability is the platform's, and it is here whether or not anything
 * uses it.
 *
 * <pre>{@code
 * s.setBlock(0, 0, 0, block('mekanism:basic_energy_cube'));
 * var e = s.capability('energy');
 * s.check('capacity', e.capacity(0, 0, 0)).isGreaterThan(0);
 * e.fill(0, 0, 0);
 * }</pre>
 */
public interface Energy {

    /** Whether the block at this position offers energy at all. */
    boolean present(int dx, int dy, int dz);

    /** Energy currently in the block. */
    int stored(int dx, int dy, int dz);

    /** How much the block can hold. */
    int capacity(int dx, int dy, int dz);

    /** Whether the block accepts energy. A generator's output side does not, and a scene that cannot
     *  tell the difference reports "it took 0" as a bug in the generator. */
    boolean canReceive(int dx, int dy, int dz);

    /** Whether the block gives energy out. */
    boolean canExtract(int dx, int dy, int dz);

    /** Push energy in; returns how much was accepted. */
    int receive(int dx, int dy, int dz, int amount);

    /** Pull energy out; returns how much came. */
    int extract(int dx, int dy, int dz, int amount);

    /** Fill to capacity; returns what went in. The setup verb — a scene about what a machine DOES
     *  should not first have to be a scene about how its owner charges it. */
    int fill(int dx, int dy, int dz);
}
