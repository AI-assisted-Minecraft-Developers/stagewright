package net.magicterra.stagewright.neoforge.caps;

import java.util.ArrayList;
import java.util.List;
import net.magicterra.stagewright.scene.CapabilityProvider;
import net.magicterra.stagewright.scene.Fluids;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.contract.SceneFailure;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/**
 * The tanks in a block, through the fluid-handler capability.
 *
 * <p>Vanilla has no fluid inventory at all — a bucket and a cauldron, and nothing a machine could
 * use. Every tank, pipe, boiler and reactor in every tech mod is built on this capability, which
 * makes it the second of the two seams (with {@link BlockItems}) that a modded process actually runs
 * through. A scene testing that a machine consumed water and produced steam has no other way to look.
 *
 * <pre>{@code
 * var t = s.capability('fluids');
 * t.fill(0, 0, 0, 'minecraft:water', 1000);
 * s.check('tank', t.fluid(0, 0, 0, 0)).isEqualTo('minecraft:water');
 * }</pre>
 *
 * <p>Amounts are millibuckets, NeoForge's unit: a bucket is 1000.
 */
public final class BlockFluids implements Fluids {

    private final SceneContext ctx;

    private BlockFluids(SceneContext ctx) {
        this.ctx = ctx;
    }

    /** Whether the block at this position exposes tanks. */
    @Override
    public boolean present(int dx, int dy, int dz) {
        return handlerOrNull(dx, dy, dz) != null;
    }

    /** How many tanks it has. */
    @Override
    public int tanks(int dx, int dy, int dz) {
        return require(dx, dy, dz).getTanks();
    }

    /** The fluid id in one tank, or {@code "minecraft:empty"}. */
    @Override
    public String fluid(int dx, int dy, int dz, int tank) {
        FluidStack stack = require(dx, dy, dz).getFluidInTank(tank);
        return BuiltInRegistries.FLUID.getKey(stack.getFluid()).toString();
    }

    /** How much is in one tank, in millibuckets. */
    @Override
    public int amount(int dx, int dy, int dz, int tank) {
        return require(dx, dy, dz).getFluidInTank(tank).getAmount();
    }

    /** How much that tank holds, in millibuckets. */
    @Override
    public int tankCapacity(int dx, int dy, int dz, int tank) {
        return require(dx, dy, dz).getTankCapacity(tank);
    }

    /** Every non-empty tank as {@code "<tank>=<mB> <fluidid>"}, in tank order. */
    @Override
    public List<String> contents(int dx, int dy, int dz) {
        IFluidHandler handler = require(dx, dy, dz);
        List<String> out = new ArrayList<>();
        for (int tank = 0; tank < handler.getTanks(); tank++) {
            FluidStack stack = handler.getFluidInTank(tank);
            if (!stack.isEmpty()) {
                out.add(tank + "=" + stack.getAmount() + " "
                        + BuiltInRegistries.FLUID.getKey(stack.getFluid()));
            }
        }
        return out;
    }

    /** Push fluid in; returns how many millibuckets were accepted. Tries every side — see
     *  {@link BlockEnergy#handlers} for why, which cost an ATM10 run to learn. */
    @Override
    public int fill(int dx, int dy, int dz, String fluidId, int amount) {
        require(dx, dy, dz);
        FluidStack stack = new FluidStack(fluid(fluidId), amount);
        for (IFluidHandler handler : handlers(dx, dy, dz)) {
            int took = handler.fill(stack, IFluidHandler.FluidAction.EXECUTE);
            if (took > 0) return took;
        }
        return 0;
    }

    /** Whether the block would take this fluid at all, changing nothing. */
    @Override
    public boolean accepts(int dx, int dy, int dz, String fluidId) {
        require(dx, dy, dz);
        FluidStack stack = new FluidStack(fluid(fluidId), 1);
        for (IFluidHandler handler : handlers(dx, dy, dz)) {
            if (handler.fill(stack, IFluidHandler.FluidAction.SIMULATE) > 0) return true;
        }
        return false;
    }

    /** Pull fluid out; returns how many millibuckets came. */
    @Override
    public int drain(int dx, int dy, int dz, String fluidId, int amount) {
        require(dx, dy, dz);
        FluidStack stack = new FluidStack(fluid(fluidId), amount);
        for (IFluidHandler handler : handlers(dx, dy, dz)) {
            int gave = handler.drain(stack, IFluidHandler.FluidAction.EXECUTE).getAmount();
            if (gave > 0) return gave;
        }
        return 0;
    }

    /** The no-side view first, then the six faces, deduplicated. See {@link BlockEnergy#handlers}. */
    private List<IFluidHandler> handlers(int dx, int dy, int dz) {
        BlockPos pos = ctx.rel(dx, dy, dz);
        List<IFluidHandler> out = new ArrayList<>();
        IFluidHandler noSide = ctx.level().getCapability(Capabilities.FluidHandler.BLOCK, pos, null);
        if (noSide != null) out.add(noSide);
        for (Direction side : Direction.values()) {
            IFluidHandler sided = ctx.level().getCapability(Capabilities.FluidHandler.BLOCK, pos, side);
            if (sided == null) continue;
            boolean seen = false;
            for (IFluidHandler known : out) if (known == sided) { seen = true; break; }
            if (!seen) out.add(sided);
        }
        return out;
    }

    private static Fluid fluid(String fluidId) {
        ResourceLocation id = ResourceLocation.tryParse(fluidId);
        Fluid found = id == null ? null : BuiltInRegistries.FLUID.get(id);
        if (id == null || found == null) {
            throw new SceneFailure("no fluid is registered as '" + fluidId + "'");
        }
        return found;
    }

    private IFluidHandler handlerOrNull(int dx, int dy, int dz) {
        List<IFluidHandler> all = handlers(dx, dy, dz);
        return all.isEmpty() ? null : all.get(0);
    }

    private IFluidHandler require(int dx, int dy, int dz) {
        IFluidHandler handler = handlerOrNull(dx, dy, dz);
        if (handler == null) {
            throw new SceneFailure("the block at (" + dx + "," + dy + "," + dz + ") is "
                    + ctx.blockAt(dx, dy, dz) + ", which exposes no fluid handler here."
                    + " Use fluids.present(dx,dy,dz) to branch instead of asserting.");
        }
        return handler;
    }

    /** See {@link BlockEnergy.Provider} — available wherever NeoForge is. */
    public static final class Provider implements CapabilityProvider {
        @Override
        public String name() {
            return "fluids";
        }

        @Override
        public boolean availableIn(SceneContext ctx) {
            return true;
        }

        @Override
        public Object facet(SceneContext ctx) {
            return new BlockFluids(ctx);
        }

        @Override
        public String absentReason() {
            return "the 'fluids' capability is NeoForge's block fluid capability, and this run is"
                    + " not on NeoForge";
        }
    }
}
