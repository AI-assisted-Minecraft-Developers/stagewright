package net.magicterra.stagewright.neoforge.caps;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import net.magicterra.stagewright.capability.CapabilityProvider;
import net.magicterra.stagewright.capability.Fluids;
import net.magicterra.stagewright.test.Game;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/**
 * The tanks in a block, through the fluid-handler capability. Every tank, pipe, boiler and reactor in every tech mod is
 * built on it, which makes it the second of the two seams (with {@link BlockItems}) a modded process runs through.
 *
 * <pre>{@code
 * Fluids tanks = game.capability("fluids", Fluids.class);
 * tanks.fill(tank, "minecraft:water", 1000);
 * assertEquals("minecraft:water", tanks.fluid(tank, 0));
 * }</pre>
 *
 * <p>Amounts are millibuckets. Positions are world positions in the overworld; every call runs on the server thread.
 */
public final class BlockFluids implements Fluids {

    private final Game game;

    private BlockFluids(Game game) {
        this.game = game;
    }

    @Override
    public boolean present(BlockPos pos) {
        return at(pos, handlers -> !handlers.isEmpty());
    }

    @Override
    public int tanks(BlockPos pos) {
        return at(pos, handlers -> require(pos, handlers).getTanks());
    }

    @Override
    public String fluid(BlockPos pos, int tank) {
        return at(
                pos,
                handlers -> BuiltInRegistries.FLUID
                        .getKey(require(pos, handlers).getFluidInTank(tank).getFluid())
                        .toString());
    }

    @Override
    public int amount(BlockPos pos, int tank) {
        return at(pos, handlers -> require(pos, handlers).getFluidInTank(tank).getAmount());
    }

    @Override
    public int tankCapacity(BlockPos pos, int tank) {
        return at(pos, handlers -> require(pos, handlers).getTankCapacity(tank));
    }

    @Override
    public List<String> contents(BlockPos pos) {
        return at(pos, handlers -> {
            IFluidHandler handler = require(pos, handlers);
            List<String> out = new ArrayList<>();
            for (int tank = 0; tank < handler.getTanks(); tank++) {
                FluidStack stack = handler.getFluidInTank(tank);
                if (!stack.isEmpty()) {
                    out.add(tank + "=" + stack.getAmount() + " " + BuiltInRegistries.FLUID.getKey(stack.getFluid()));
                }
            }
            return out;
        });
    }

    /** Tries every side — see {@link BlockEnergy#handlers} for why. */
    @Override
    public int fill(BlockPos pos, String fluidId, int amount) {
        return at(pos, handlers -> {
            require(pos, handlers);
            FluidStack stack = new FluidStack(fluid(fluidId), amount);
            for (IFluidHandler handler : handlers) {
                int took = handler.fill(stack, IFluidHandler.FluidAction.EXECUTE);
                if (took > 0) {
                    return took;
                }
            }
            return 0;
        });
    }

    @Override
    public boolean accepts(BlockPos pos, String fluidId) {
        return at(pos, handlers -> {
            require(pos, handlers);
            FluidStack stack = new FluidStack(fluid(fluidId), 1);
            for (IFluidHandler handler : handlers) {
                if (handler.fill(stack, IFluidHandler.FluidAction.SIMULATE) > 0) {
                    return true;
                }
            }
            return false;
        });
    }

    @Override
    public int drain(BlockPos pos, String fluidId, int amount) {
        return at(pos, handlers -> {
            require(pos, handlers);
            FluidStack stack = new FluidStack(fluid(fluidId), amount);
            for (IFluidHandler handler : handlers) {
                int gave =
                        handler.drain(stack, IFluidHandler.FluidAction.EXECUTE).getAmount();
                if (gave > 0) {
                    return gave;
                }
            }
            return 0;
        });
    }

    private <T> T at(BlockPos pos, Function<List<IFluidHandler>, T> work) {
        return game.onServer(server -> work.apply(handlers(server.overworld(), pos)));
    }

    /** The no-side view first, then the six faces, deduplicated. See {@link BlockEnergy#handlers}. */
    private static List<IFluidHandler> handlers(ServerLevel level, BlockPos pos) {
        List<IFluidHandler> out = new ArrayList<>();
        IFluidHandler noSide = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, null);
        if (noSide != null) {
            out.add(noSide);
        }
        for (Direction side : Direction.values()) {
            IFluidHandler sided = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, side);
            if (sided != null && out.stream().noneMatch(known -> known == sided)) {
                out.add(sided);
            }
        }
        return out;
    }

    private static Fluid fluid(String fluidId) {
        ResourceLocation id = ResourceLocation.tryParse(fluidId);
        if (id == null || !BuiltInRegistries.FLUID.containsKey(id)) {
            throw new IllegalStateException("no fluid is registered as '" + fluidId + "'");
        }
        return BuiltInRegistries.FLUID.get(id);
    }

    private static IFluidHandler require(BlockPos pos, List<IFluidHandler> handlers) {
        if (handlers.isEmpty()) {
            throw new IllegalStateException("the block at " + pos.toShortString() + " exposes no fluid handler here."
                    + " Use fluids.present(pos) to branch instead of asserting.");
        }
        return handlers.get(0);
    }

    /** See {@link BlockEnergy.Provider} — available wherever NeoForge is. */
    public static final class Provider implements CapabilityProvider {
        @Override
        public String name() {
            return "fluids";
        }

        @Override
        public boolean availableIn(Game game) {
            return true;
        }

        @Override
        public Object facet(Game game) {
            return new BlockFluids(game);
        }

        @Override
        public String absentReason() {
            return "the 'fluids' capability is NeoForge's block fluid capability, and this run is not on NeoForge";
        }
    }
}
