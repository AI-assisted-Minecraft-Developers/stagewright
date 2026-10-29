package net.magicterra.stagewright.jupiter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * The boxes of the plots leased right now, by dimension, and what a mob joining a level inside one is given: it is
 * kept. A plot is far from every player, and a mob with no player within 128 blocks is discarded on its first tick
 * unless it is persistent, so a mob a test summons into its plot would vanish before the test could look at it.
 */
public final class LeasedPlots {

    private static final Map<ResourceKey<Level>, List<BoundingBox>> BOXES = new ConcurrentHashMap<>();

    private LeasedPlots() {}

    static void add(ResourceKey<Level> dimension, BoundingBox box) {
        BOXES.computeIfAbsent(dimension, d -> new CopyOnWriteArrayList<>()).add(box);
    }

    static void remove(ResourceKey<Level> dimension, BoundingBox box) {
        List<BoundingBox> boxes = BOXES.get(dimension);
        if (boxes != null) boxes.remove(box);
    }

    /** Whether {@code pos} in {@code dimension} lies in a leased plot. */
    static boolean contains(ResourceKey<Level> dimension, BlockPos pos) {
        List<BoundingBox> boxes = BOXES.get(dimension);
        if (boxes == null) return false;
        for (BoundingBox box : boxes) if (box.isInside(pos)) return true;
        return false;
    }

    /**
     * Server thread, as an entity joins {@code level}: a mob inside a leased plot is made persistent. A mob a test
     * drives as an {@code Npc} already is, and stays so.
     */
    public static void onEntityJoin(Entity entity, Level level) {
        if (entity instanceof Mob mob && !mob.isPersistenceRequired()
                && contains(level.dimension(), entity.blockPosition())) {
            mob.setPersistenceRequired();
        }
    }
}
