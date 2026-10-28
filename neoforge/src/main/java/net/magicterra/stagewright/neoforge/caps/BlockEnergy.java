package net.magicterra.stagewright.neoforge.caps;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import net.magicterra.stagewright.capability.CapabilityProvider;
import net.magicterra.stagewright.capability.Energy;
import net.magicterra.stagewright.test.Game;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * Energy in a block, for any mod that implements NeoForge's energy capability. Mekanism, Thermal, Powah and a hundred
 * others share no API, only this capability, so testing against it covers all of them, and a block that does not
 * implement it is reported as not implementing it.
 *
 * <pre>{@code
 * Energy energy = game.capability("energy", Energy.class);
 * energy.receive(cube, 1000);
 * assertTrue(energy.stored(cube) > 0);
 * }</pre>
 *
 * <p>Positions are world positions in the overworld; every call runs on the server thread.
 */
public final class BlockEnergy implements Energy {

    private final Game game;

    private BlockEnergy(Game game) {
        this.game = game;
    }

    @Override
    public boolean present(BlockPos pos) {
        return at(pos, handlers -> !handlers.isEmpty());
    }

    @Override
    public int stored(BlockPos pos) {
        return required(pos, IEnergyStorage::getEnergyStored);
    }

    @Override
    public int capacity(BlockPos pos) {
        return required(pos, IEnergyStorage::getMaxEnergyStored);
    }

    @Override
    public boolean canReceive(BlockPos pos) {
        return required(pos, IEnergyStorage::canReceive);
    }

    @Override
    public boolean canExtract(BlockPos pos) {
        return required(pos, IEnergyStorage::canExtract);
    }

    /** Tries every side — see {@link #handlers}. */
    @Override
    public int receive(BlockPos pos, int amount) {
        return at(pos, handlers -> receive(pos, handlers, amount));
    }

    @Override
    public int extract(BlockPos pos, int amount) {
        return at(pos, handlers -> {
            require(pos, handlers);
            for (IEnergyStorage storage : handlers) {
                int gave = storage.extractEnergy(amount, false);
                if (gave > 0) return gave;
            }
            return 0;
        });
    }

    @Override
    public int fill(BlockPos pos) {
        return at(pos, handlers -> receive(pos, handlers, require(pos, handlers).getMaxEnergyStored()));
    }

    private static int receive(BlockPos pos, List<IEnergyStorage> handlers, int amount) {
        require(pos, handlers);
        for (IEnergyStorage storage : handlers) {
            int took = storage.receiveEnergy(amount, false);
            if (took > 0) return took;
        }
        return 0;
    }

    private <T> T required(BlockPos pos, Function<IEnergyStorage, T> read) {
        return at(pos, handlers -> read.apply(require(pos, handlers)));
    }

    /** {@code work} over the block's handlers, on the server thread. */
    private <T> T at(BlockPos pos, Function<List<IEnergyStorage>, T> work) {
        return game.onServer(server -> work.apply(handlers(server.overworld(), pos)));
    }

    /**
     * Every handler this block exposes: the no-side view first, then the six faces, deduplicated by identity.
     *
     * <p><b>Why writes walk the list.</b> {@code null} means "no particular side", and most mods answer it with an
     * internal view carrying the machine's real numbers — so reads work through it — while insertion is granted only
     * on the faces configured for input. Found on All the Mods 10: a Mekanism energy cube answered
     * {@code canReceive() = true} through the no-side handler and took 0 of every 1000 offered. A write therefore
     * tries each handler until one accepts, and stops there.
     */
    static List<IEnergyStorage> handlers(ServerLevel level, BlockPos pos) {
        List<IEnergyStorage> out = new ArrayList<>();
        IEnergyStorage noSide = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, null);
        if (noSide != null) out.add(noSide);
        for (Direction side : Direction.values()) {
            IEnergyStorage sided = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, side);
            if (sided != null && out.stream().noneMatch(known -> known == sided)) out.add(sided);
        }
        return out;
    }

    /** Absent capability is a failure naming the position, not a null. */
    private static IEnergyStorage require(BlockPos pos, List<IEnergyStorage> handlers) {
        if (handlers.isEmpty()) {
            throw new IllegalStateException("the block at " + pos.toShortString() + " offers no energy capability"
                    + " here. Use energy.present(pos) to branch instead of asserting.");
        }
        return handlers.get(0);
    }

    /** Available wherever NeoForge is: the capability is NeoForge's rather than a mod's. */
    public static final class Provider implements CapabilityProvider {
        @Override
        public String name() {
            return "energy";
        }

        @Override
        public boolean availableIn(Game game) {
            return true;
        }

        @Override
        public Object facet(Game game) {
            return new BlockEnergy(game);
        }

        @Override
        public String absentReason() {
            return "the 'energy' capability is NeoForge's block energy capability, and this run is not on NeoForge"
                    + " — Fabric has no single energy standard in the platform, so there is nothing here to wrap";
        }
    }
}
