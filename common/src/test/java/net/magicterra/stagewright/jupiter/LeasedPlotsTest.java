package net.magicterra.stagewright.jupiter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;

class LeasedPlotsTest {

    private static final ResourceKey<Level> OVERWORLD =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("overworld"));
    private static final ResourceKey<Level> NETHER =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("the_nether"));

    @Test
    void aPositionIsInALeasedPlotOnlyInsideItsBoxInItsDimensionWhileLeased() {
        BoundingBox box = new BoundingBox(1000, 200, -200000, 1015, 215, -199985);
        BlockPos inside = new BlockPos(1004, 201, -199990);
        assertFalse(LeasedPlots.contains(OVERWORLD, inside));

        LeasedPlots.add(OVERWORLD, box);
        try {
            assertTrue(LeasedPlots.contains(OVERWORLD, inside));
            assertTrue(LeasedPlots.contains(OVERWORLD, new BlockPos(1015, 215, -199985)));
            assertFalse(LeasedPlots.contains(OVERWORLD, new BlockPos(1016, 201, -199990)));
            assertFalse(LeasedPlots.contains(OVERWORLD, new BlockPos(1004, 199, -199990)));
            assertFalse(LeasedPlots.contains(NETHER, inside));
        } finally {
            LeasedPlots.remove(OVERWORLD, box);
        }
        assertFalse(LeasedPlots.contains(OVERWORLD, inside));
    }

    @Test
    void releasingOnePlotLeavesItsNeighbourLeased() {
        BoundingBox first = new BoundingBox(2000, 200, -200000, 2015, 215, -199985);
        BoundingBox second = new BoundingBox(2048, 200, -200000, 2063, 215, -199985);
        LeasedPlots.add(OVERWORLD, first);
        LeasedPlots.add(OVERWORLD, second);
        LeasedPlots.remove(OVERWORLD, first);
        try {
            assertFalse(LeasedPlots.contains(OVERWORLD, new BlockPos(2004, 201, -199990)));
            assertTrue(LeasedPlots.contains(OVERWORLD, new BlockPos(2050, 201, -199990)));
        } finally {
            LeasedPlots.remove(OVERWORLD, second);
        }
    }
}
