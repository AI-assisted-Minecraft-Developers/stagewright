package net.magicterra.stagewright.neoforge.caps;

import java.util.ArrayList;
import java.util.List;
import net.magicterra.stagewright.scene.CapabilityProvider;
import net.magicterra.stagewright.scene.Energy;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.contract.SceneFailure;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * Energy in a block, for any mod that implements NeoForge's energy capability.
 *
 * <p>This is what a "common implementation" means here, and why it is not one adapter per mod.
 * Mekanism, Thermal, Powah, Industrial Foregoing, Immersive Engineering and a hundred others do not
 * share an API — they share a <i>capability</i>. Testing against the capability covers every one of
 * them, including the ones written after this file, and covers none of them wrongly: a block that
 * does not implement it is reported as not implementing it rather than guessed at.
 *
 * <pre>{@code
 * s.setBlock(0, 0, 0, 'mekanism:basic_energy_cube');
 * var e = s.capability('energy');
 * s.check('capacity', e.capacity(0, 0, 0)).isAbove(0);
 * e.receive(0, 0, 0, 1000);
 * s.check('stored', e.stored(0, 0, 0)).isAbove(0);
 * }</pre>
 *
 * <h2>Positions are arena-relative</h2>
 *
 * <p>Same convention as every other block verb on {@link SceneContext}: {@code (dx, dy, dz)} from the
 * scene's own origin. A facet taking absolute coordinates would be the one place in the API where a
 * scene has to know where it was placed, which is exactly the knowledge the grid exists to remove.
 */
public final class BlockEnergy implements Energy {

    private final SceneContext ctx;

    private BlockEnergy(SceneContext ctx) {
        this.ctx = ctx;
    }

    /** Whether the block at this position offers energy at all. */
    @Override
    public boolean present(int dx, int dy, int dz) {
        return storageOrNull(dx, dy, dz) != null;
    }

    /** Energy currently in the block. */
    @Override
    public int stored(int dx, int dy, int dz) {
        return require(dx, dy, dz).getEnergyStored();
    }

    /** How much the block can hold. */
    @Override
    public int capacity(int dx, int dy, int dz) {
        return require(dx, dy, dz).getMaxEnergyStored();
    }

    /** Whether the block accepts energy — a generator's output side does not, and a scene that
     *  cannot tell the difference reports "it took 0" as a bug in the generator. */
    @Override
    public boolean canReceive(int dx, int dy, int dz) {
        return require(dx, dy, dz).canReceive();
    }

    /** Whether the block gives energy out. */
    @Override
    public boolean canExtract(int dx, int dy, int dz) {
        return require(dx, dy, dz).canExtract();
    }

    /** Push energy in; returns how much was actually accepted. Tries every side — see
     *  {@link #handlers}. */
    @Override
    public int receive(int dx, int dy, int dz, int amount) {
        require(dx, dy, dz);
        for (IEnergyStorage storage : handlers(dx, dy, dz)) {
            int took = storage.receiveEnergy(amount, false);
            if (took > 0) return took;
        }
        return 0;
    }

    /** Pull energy out; returns how much actually came. */
    @Override
    public int extract(int dx, int dy, int dz, int amount) {
        require(dx, dy, dz);
        for (IEnergyStorage storage : handlers(dx, dy, dz)) {
            int gave = storage.extractEnergy(amount, false);
            if (gave > 0) return gave;
        }
        return 0;
    }

    /** Fill to capacity; returns what went in. The setup verb — a scene about what a machine DOES
     *  should not be a scene about how its owner charges it. */
    @Override
    public int fill(int dx, int dy, int dz) {
        return receive(dx, dy, dz, require(dx, dy, dz).getMaxEnergyStored());
    }

    private IEnergyStorage storageOrNull(int dx, int dy, int dz) {
        BlockPos pos = ctx.rel(dx, dy, dz);
        IEnergyStorage noSide = ctx.level().getCapability(Capabilities.EnergyStorage.BLOCK, pos, null);
        if (noSide != null) return noSide;
        for (Direction side : Direction.values()) {
            IEnergyStorage sided = ctx.level().getCapability(Capabilities.EnergyStorage.BLOCK, pos, side);
            if (sided != null) return sided;
        }
        return null;
    }

    /**
     * Every handler this block exposes: the no-side view first, then the six faces.
     *
     * <p><b>Why more than one, and why writes walk the list.</b> A block capability is looked up with
     * a {@link Direction} context, and {@code null} means "no particular side". Most mods answer that
     * with an internal view carrying the machine's real numbers — which is why reads work through it —
     * while insertion is governed by the machine's side configuration and granted only on the faces
     * configured for input.
     *
     * <p>Found on All the Mods 10, and worth writing down because the symptom names no cause: a
     * Mekanism energy cube answered {@code capacity = 1_600_000} and {@code canReceive() = true}
     * through the no-side handler, and took 0 of every 1000 offered — before ticking and ten ticks
     * later alike. Nothing in that says "wrong side"; a scene author would have concluded the machine
     * was broken, or that the arena was not running.
     *
     * <p>A write therefore tries each handler until one accepts, and stops there — first success
     * wins, so nothing is inserted twice. Handlers are deduplicated by identity, because a block that
     * exposes the same handler on every face would otherwise be asked seven times.
     *
     * <p>This is the right default for a testing API: a scene asserting that a machine charges should
     * not first have to be a scene about that machine's side configuration.
     */
    private List<IEnergyStorage> handlers(int dx, int dy, int dz) {
        BlockPos pos = ctx.rel(dx, dy, dz);
        List<IEnergyStorage> out = new ArrayList<>();
        IEnergyStorage noSide = ctx.level().getCapability(Capabilities.EnergyStorage.BLOCK, pos, null);
        if (noSide != null) out.add(noSide);
        for (Direction side : Direction.values()) {
            IEnergyStorage sided = ctx.level().getCapability(Capabilities.EnergyStorage.BLOCK, pos, side);
            if (sided == null) continue;
            boolean seen = false;
            for (IEnergyStorage known : out) if (known == sided) { seen = true; break; }
            if (!seen) out.add(sided);
        }
        return out;
    }

    /** Absent capability is a FAIL naming the block, not a null. A scene asserting about the energy
     *  in a block that has none has already found something — either the block id is wrong or the
     *  mod stopped exposing it — and both deserve a sentence rather than a NullPointerException. */
    private IEnergyStorage require(int dx, int dy, int dz) {
        IEnergyStorage storage = storageOrNull(dx, dy, dz);
        if (storage == null) {
            throw new SceneFailure("the block at (" + dx + "," + dy + "," + dz + ") is "
                    + ctx.blockAt(dx, dy, dz) + ", which offers no energy capability here."
                    + " Use energy.present(dx,dy,dz) to branch instead of asserting.");
        }
        return storage;
    }

    /** Available wherever NeoForge is, because the capability is NeoForge's rather than a mod's — a
     *  runtime with no energy-carrying block simply reports {@code present() == false} everywhere,
     *  which is a true answer and not a reason to skip. */
    public static final class Provider implements CapabilityProvider {
        @Override
        public String name() {
            return "energy";
        }

        @Override
        public boolean availableIn(SceneContext ctx) {
            return true;
        }

        @Override
        public Object facet(SceneContext ctx) {
            return new BlockEnergy(ctx);
        }

        @Override
        public String absentReason() {
            return "the 'energy' capability is NeoForge's block energy capability, and this run is"
                    + " not on NeoForge — Fabric has no single energy standard in the platform, so"
                    + " there is nothing here to wrap";
        }
    }
}
