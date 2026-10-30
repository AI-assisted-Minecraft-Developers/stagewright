package net.magicterra.stagewright.jupiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.TreeMap;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;

class PlotLayoutTest {

    private static final ResourceKey<Level> OVERWORLD =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("overworld"));
    private static final ResourceKey<Level> NETHER =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("the_nether"));

    @Test
    void aStretchIsTheWidthAndItsGapInWholeChunks() {
        assertEquals(48, PlotLayout.width(16));
        assertEquals(160, PlotLayout.width(115));
        assertEquals(288, PlotLayout.width(256));
    }

    @Test
    void leasesTakeTheLowestFreeStretchAndReuseAReleasedOne() {
        PlotLayout layout = new PlotLayout();
        assertEquals(0, layout.lease(OVERWORLD, 16));
        assertEquals(48, layout.lease(OVERWORLD, 115));
        assertEquals(208, layout.lease(OVERWORLD, 16));
        layout.release(OVERWORLD, 0);
        // A wide plot does not fit the 48 freed at 0, so it goes after the last.
        assertEquals(256, layout.lease(OVERWORLD, 64));
        assertEquals(0, layout.lease(OVERWORLD, 16));
    }

    @Test
    void eachDimensionHasItsOwnRow() {
        PlotLayout layout = new PlotLayout();
        layout.lease(OVERWORLD, 16);
        assertEquals(0, layout.lease(NETHER, 16));
    }

    @Test
    void firstFreeFindsAGapBetweenStretches() {
        TreeMap<Integer, Integer> row = new TreeMap<>();
        row.put(0, 48);
        row.put(96, 144);
        assertEquals(48, PlotLayout.firstFree(row, 48));
        assertEquals(144, PlotLayout.firstFree(row, 64));
    }

    @Test
    void aTallPlotIsLoweredToFitAndOneTallerThanTheWorldIsRefused() {
        assertEquals(200, PlotLayout.floorY(200, -64, 320, 16));
        assertEquals(192, PlotLayout.floorY(200, -64, 320, 128));
        assertEquals(-64, PlotLayout.floorY(200, -64, 320, 384));
        assertEquals(128, PlotLayout.floorY(200, 0, 256, 128));
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> PlotLayout.floorY(200, 0, 256, 300));
        assertTrue(e.getMessage().contains("from y 0 to 255"), e.getMessage());
    }

    @Test
    void tilesCoverTheBoxExactlyAndEachStaysWithinTheVolume() {
        BoundingBox box = new BoundingBox(10, -64, 5, 10 + 255, -64 + 383, 5 + 199);
        List<BoundingBox> tiles = PlotLayout.tiles(box, 262_144);
        long covered = 0;
        for (BoundingBox t : tiles) {
            long volume = (long) t.getXSpan() * t.getYSpan() * t.getZSpan();
            assertTrue(volume <= 262_144, t + " holds " + volume);
            assertTrue(box.isInside(t.minX(), t.minY(), t.minZ()) && box.isInside(t.maxX(), t.maxY(), t.maxZ()));
            covered += volume;
        }
        assertEquals((long) box.getXSpan() * box.getYSpan() * box.getZSpan(), covered);
    }

    @Test
    void aSmallBoxIsOneTile() {
        BoundingBox box = new BoundingBox(0, 0, 0, 15, 15, 15);
        assertEquals(List.of(box), PlotLayout.tiles(box, 32_768));
    }
}
