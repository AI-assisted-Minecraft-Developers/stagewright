package net.magicterra.stagewright.test;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.file.Path;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/**
 * A box of a dimension leased to one test, injected as a test method parameter. It is handed over empty — every cell
 * air, its chunks loaded and ticking — and put back exactly as it was when the test ends, entities and dropped items
 * included. Plots never overlap, so tests holding different plots cannot disturb each other.
 *
 * <p>Coordinates passed to these methods are relative: from {@link #min} to {@link #max} on each axis, which is
 * {@code 0 .. size-1} from the minimum corner, or around the middle for a {@link Centered} plot. {@link #at} turns one
 * into a world position for anything else.
 */
public interface Plot {

    /** Blocks along each axis. */
    Vec3i size();

    /** The dimension the plot is in. */
    ResourceKey<Level> dimension();

    /** The lowest relative cell: {@code (0, 0, 0)}, or minus half the size on each axis for a centred plot. */
    Vec3i min();

    /** The highest relative cell. */
    Vec3i max();

    /** World position of relative {@code (0, 0, 0)}. */
    BlockPos origin();

    /** World position of relative {@code (x, y, z)}; refuses a cell outside the plot. */
    BlockPos at(int x, int y, int z);

    /** Fill the box between two relative corners, both inclusive, with {@code block}'s default state. */
    Plot fill(int x1, int y1, int z1, int x2, int y2, int z2, Block block);

    /** Set one relative cell to {@code block}'s default state. */
    default Plot place(int x, int y, int z, Block block) {
        return fill(x, y, z, x, y, z, block);
    }

    /**
     * Place the structure template {@code id} — one a datapack or a mod ships under {@code structure/}, or one a
     * structure block saved — with its minimum corner at relative {@code (x, y, z)}, entities included. Refuses a
     * template the game does not have or one that does not fit in the plot.
     */
    Plot structure(ResourceLocation id, int x, int y, int z);

    /** {@link #structure(ResourceLocation, int, int, int)} from a structure file, compressed NBT as a structure block saves it. */
    Plot structure(Path file, int x, int y, int z);

    /**
     * The size of the plot a parameter receives: {@code @Size(32)} for a cube, or each axis on its own,
     * {@code @Size(x = 120, y = 16, z = 8)}, where an axis left out takes {@link #value}. Without it, a 16-block cube.
     */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    @interface Size {

        /** The side of every axis not given on its own. */
        int value() default 16;

        /** From 1 to 256. */
        int x() default 0;

        /** From 1 to the dimension's build height; a tall plot is lowered to fit, down to the world's floor. */
        int y() default 0;

        /** From 1 to 256. */
        int z() default 0;
    }

    /** The parameter's plot has relative {@code (0, 0, 0)} in its middle, so coordinates run from minus half the size. */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    @interface Centered {}

    /** The dimension the parameter's plot is leased in, such as {@code "minecraft:the_nether"}; without it, the overworld. */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    @interface In {

        String value();
    }
}
