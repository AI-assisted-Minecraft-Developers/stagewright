package net.magicterra.stagewright.test;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

/**
 * A cube of the overworld leased to one test, injected as a test method parameter. It is handed over empty — every
 * cell air, its chunks loaded and ticking — and put back exactly as it was when the test ends, entities and dropped
 * items included. Plots never overlap, so tests holding different plots cannot disturb each other.
 *
 * <p>Coordinates passed to these methods are relative to the plot's minimum corner, {@code 0 .. size-1} on each
 * axis; {@link #at} turns one into a world position for anything else.
 */
public interface Plot {

    /** Side length in blocks; the cube is this wide, deep and tall. */
    int size();

    /** World position of the minimum corner, relative {@code (0, 0, 0)}. */
    BlockPos origin();

    /** World position of relative {@code (x, y, z)}; refuses a cell outside the plot. */
    BlockPos at(int x, int y, int z);

    /** Fill the box between two relative corners, both inclusive, with {@code block}'s default state. */
    Plot fill(int x1, int y1, int z1, int x2, int y2, int z2, Block block);

    /** Set one relative cell to {@code block}'s default state. */
    default Plot place(int x, int y, int z, Block block) {
        return fill(x, y, z, x, y, z, block);
    }

    /** The side length of the plot a parameter receives; without it, 16. */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    @interface Size {

        /** From 1 to 64. */
        int value();
    }
}
