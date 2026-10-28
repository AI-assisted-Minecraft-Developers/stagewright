package net.magicterra.stagewright.test;

import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/**
 * A player in the game a test drives: a {@link Bot} or the real client's {@link Player}. Like {@link Game}, every
 * method may block and answers on the test thread.
 *
 * <p>WorldDriver's methods reach this body through {@link #ref}, the value their {@code bot} parameter takes, for
 * example {@code game.api(BotTaskBlockingApi::of).smelt(new SmeltParams(..., bot.ref()))}.
 */
public interface Body {

    /** The name {@link #ref} carries: a bot's roster name, which is not its player's profile name, or the client's. */
    String name();

    /** WorldDriver's reference to this body: {@code player:<name>}. */
    default String ref() {
        return "player:" + name();
    }

    /**
     * Run {@code work} on the server thread with this body's player, as {@link Game#onServer} does with the server.
     * Fails if the player has left.
     */
    <T> T onServer(Function<ServerPlayer, T> work);

    /** {@link #onServer(Function)} for work with no result. */
    default void runOnServer(Consumer<ServerPlayer> work) {
        onServer(player -> {
            work.accept(player);
            return null;
        });
    }

    /** The block the body's feet are in. */
    default BlockPos blockPos() {
        return onServer(ServerPlayer::blockPosition);
    }

    /** The dimension the body is in. */
    default ResourceKey<Level> dimension() {
        return onServer(player -> player.level().dimension());
    }

    /** Teleport the body to stand in {@code feet}, in the overworld, centred on the block. */
    default Body moveTo(BlockPos feet) {
        return moveTo(Level.OVERWORLD, feet);
    }

    /** Teleport the body to stand in {@code feet} in {@code dimension}, centred on the block. */
    Body moveTo(ResourceKey<Level> dimension, BlockPos feet);

    /** Add {@code count} of {@code item} to the inventory; whatever does not fit is refused, not dropped. */
    Body give(Item item, int count);

    /** How many of {@code item} the inventory holds, hands and armour included. */
    int count(Item item);
}
