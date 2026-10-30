package net.magicterra.stagewright.jupiter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Where plots go: each dimension has one row of them along +x, a lease taking the lowest stretch of the row that is
 * free, chunk-aligned and followed by a {@value #MARGIN}-block gap, so neighbours never share a chunk.
 */
final class PlotLayout {

    static final int MAX_SIDE = 256;
    static final int MARGIN = 32;

    /** Per dimension, each leased stretch of the row: its first x to the x after its gap. */
    private final Map<ResourceKey<Level>, NavigableMap<Integer, Integer>> leased = new HashMap<>();

    /** Lease a stretch for a plot {@code sizeX} wide; answers its first x. */
    synchronized int lease(ResourceKey<Level> dimension, int sizeX) {
        NavigableMap<Integer, Integer> row = leased.computeIfAbsent(dimension, d -> new TreeMap<>());
        int width = width(sizeX);
        int start = firstFree(row, width);
        row.put(start, start + width);
        return start;
    }

    synchronized void release(ResourceKey<Level> dimension, int start) {
        NavigableMap<Integer, Integer> row = leased.get(dimension);
        if (row != null) {
            row.remove(start);
        }
    }

    /** A plot's stretch: its width and the gap after it, rounded up to whole chunks. */
    static int width(int sizeX) {
        return (sizeX + MARGIN + 15) & ~15;
    }

    /** The lowest x from 0 where {@code width} blocks are free of every stretch in {@code row}. */
    static int firstFree(NavigableMap<Integer, Integer> row, int width) {
        int candidate = 0;
        for (Map.Entry<Integer, Integer> taken : row.entrySet()) {
            if (candidate + width <= taken.getKey()) {
                return candidate;
            }
            candidate = Math.max(candidate, taken.getValue());
        }
        return candidate;
    }

    /**
     * The y of a plot {@code height} tall: {@code preferred}, lowered as far as the world's floor {@code minY} so its
     * top stays below {@code maxY}, the first height nothing can be built at.
     */
    static int floorY(int preferred, int minY, int maxY, int height) {
        if (height > maxY - minY) {
            throw new IllegalArgumentException("a plot " + height + " tall does not fit in a dimension built from y "
                    + minY + " to " + (maxY - 1));
        }
        return Math.max(minY, Math.min(preferred, maxY - height));
    }

    /** {@code box} cut into boxes of at most {@code maxVolume} cells, full columns of whole layers where they fit. */
    static List<BoundingBox> tiles(BoundingBox box, int maxVolume) {
        int side = (int) Math.sqrt(maxVolume);
        int stepX = Math.min(box.getXSpan(), side);
        int stepZ = Math.min(box.getZSpan(), side);
        int stepY = Math.max(1, maxVolume / (stepX * stepZ));
        List<BoundingBox> out = new ArrayList<>();
        for (int x = box.minX(); x <= box.maxX(); x += stepX) {
            for (int z = box.minZ(); z <= box.maxZ(); z += stepZ) {
                for (int y = box.minY(); y <= box.maxY(); y += stepY) {
                    out.add(new BoundingBox(
                            x,
                            y,
                            z,
                            Math.min(box.maxX(), x + stepX - 1),
                            Math.min(box.maxY(), y + stepY - 1),
                            Math.min(box.maxZ(), z + stepZ - 1)));
                }
            }
        }
        return out;
    }
}
