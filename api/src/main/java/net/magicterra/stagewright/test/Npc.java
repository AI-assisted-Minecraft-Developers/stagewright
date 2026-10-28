package net.magicterra.stagewright.test;

import java.util.function.Consumer;
import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;

/**
 * A mob WorldDriver drives as a body — a piglin, a villager, any mob it can steer — spawned for one test by
 * {@link Game#npc} and removed when the test ends. While it is driven its own AI is off. WorldDriver's methods reach it
 * through {@link #ref}, {@code mob:<name>}. Like {@link Game}, every method may block and answers on the test thread.
 */
public interface Npc extends Driven {

    /** The name {@link #ref} carries. */
    String name();

    @Override
    default String ref() {
        return "mob:" + name();
    }

    /** Run {@code work} on the server thread with the mob. Fails if it is gone, dead or no longer driven. */
    <T> T onServer(Function<Mob, T> work);

    /** {@link #onServer(Function)} for work with no result. */
    default void runOnServer(Consumer<Mob> work) {
        onServer(mob -> {
            work.accept(mob);
            return null;
        });
    }

    /** The block the mob's feet are in. */
    default BlockPos blockPos() {
        return onServer(Mob::blockPosition);
    }

    /** The dimension the mob is in. */
    default ResourceKey<Level> dimension() {
        return onServer(mob -> mob.level().dimension());
    }

    /**
     * Teleport the mob to stand in {@code feet}, centred on the block, in the dimension it is in: a mob changing
     * dimension becomes a new entity, which WorldDriver no longer drives, so spawn it where it should be instead.
     */
    Npc moveTo(BlockPos feet);

    @Override
    Npc park();

    @Override
    Npc unpark();
}
