package net.magicterra.stagewright.jupiter;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import net.magicterra.stagewright.test.Body;
import net.magicterra.stagewright.test.Bot;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Player;
import net.magicterra.worlddriver.api.methods.test.TestHarnessContracts.Press;
import net.magicterra.worlddriver.api.methods.test.TestInputContracts.HeldKeys;
import net.magicterra.worlddriver.bot.BotRegistry;
import net.magicterra.worlddriver.bot.BotTicker;
import net.magicterra.worlddriver.bot.Roster;
import net.magicterra.worlddriver.game.body.JoinedPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;

/**
 * A player found through its {@code player:<name>} ref on each call, as WorldDriver's methods find it, so a handle
 * never holds a player object that has since been replaced. A roster bot's ref name is not its profile name.
 */
class BodyImpl implements Body {

    final Game game;
    private final String name;

    BodyImpl(Game game, String name) {
        this.game = game;
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public <T> T onServer(Function<ServerPlayer, T> work) {
        return game.onServer(server -> {
            ServerPlayer player = BotRegistry.serverPlayer(server, ref());
            if (player == null) throw new IllegalStateException(name + " is not on the server");
            return work.apply(player);
        });
    }

    @Override
    public Body moveTo(ResourceKey<Level> dimension, BlockPos feet) {
        runOnServer(player -> {
            ServerLevel level = GameImpl.level(player.server, dimension);
            player.setDeltaMovement(Vec3.ZERO);
            player.resetFallDistance();
            player.teleportTo(level, feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, player.getYRot(),
                    player.getXRot());
        });
        return this;
    }

    @Override
    public Body give(Item item, int count) {
        runOnServer(player -> {
            ItemStack stack = new ItemStack(item, count);
            player.getInventory().add(stack);
            player.containerMenu.broadcastChanges();
            if (!stack.isEmpty()) {
                throw new IllegalStateException(name + "'s inventory took " + (count - stack.getCount()) + " of "
                        + count + " " + item + "; the rest did not fit");
            }
        });
        return this;
    }

    @Override
    public int count(Item item) {
        return onServer(player -> player.getInventory().countItem(item));
    }

    /** The real client's player; the first player on the server that WorldDriver did not join itself. */
    static Player player(Game game) {
        Optional<String> name = game.onServer(server -> server.getPlayerList().getPlayers().stream()
                .filter(p -> !(p instanceof JoinedPlayers.JoinedBody))
                .map(p -> p.getGameProfile().getName())
                .findFirst());
        if (name.isEmpty()) {
            throw new ParameterResolutionException("no real client is connected to this game, so there is no Player"
                    + " to inject: give the test @Requires(CLIENT)");
        }
        return new PlayerImpl(game, name.get());
    }

    static final class PlayerImpl extends BodyImpl implements Player {
        PlayerImpl(Game game, String name) {
            super(game, name);
        }
    }

    /**
     * Joined when leased, and taken out of the world when the test's context closes it, unless the test kept it for a
     * later one: then it is parked and put on the {@link Handovers} shelf instead.
     */
    static final class BotImpl extends BodyImpl implements Bot, ExtensionContext.Store.CloseableResource {

        private static final AtomicInteger NEXT = new AtomicInteger(1);

        /** The test keeping this bot for a later one; null when the bot leaves with its test. */
        private volatile String keeper;

        private BotImpl(Game game, String name) {
            super(game, name);
        }

        /**
         * Take over the bot a passed test kept under {@code name}, for {@code taker}: the same player, back on the
         * tick list unless {@code parked}.
         */
        static BotImpl take(Game game, String name, boolean parked, String taker) {
            Handovers.Kept kept = Handovers.take(name);
            if (kept == null) {
                throw new IllegalStateException("no passed test kept a bot named " + name + " for " + taker
                        + ": the test it depends on keeps one as @Bot.Keep under @Bot.Name(\"" + name + "\")");
            }
            BotImpl bot = new BotImpl(game, name);
            if (BotRegistry.get(bot.ref()) == null) {
                throw new IllegalStateException(name + ", kept by " + kept.keeper() + ", is no longer on the server");
            }
            if (!parked) bot.unpark();
            return bot;
        }

        /** Keep this bot for a later test instead of removing it when its test ends. */
        void keep(String keeper) {
            this.keeper = keeper;
        }

        boolean kept() {
            return keeper != null;
        }

        /** Let the bot leave with its test after all: it failed, or the bot stands where it cannot be kept. */
        void release() {
            keeper = null;
        }

        /** Takes the bot out of the world if it is still there. */
        void remove() {
            game.runOnServer(server -> {
                if (BotRegistry.get(ref()) != null) Roster.remove(ref());
            });
        }

        /** Join a bot, parked before the server ticks it once when {@code parked}. */
        static BotImpl join(Game game, String name, boolean parked) {
            String chosen = name != null ? name : "sw_bot_" + NEXT.getAndIncrement();
            BotImpl bot = new BotImpl(game, chosen);
            // One server task, so no tick falls between joining and parking.
            game.runOnServer(server -> {
                ServerLevel level = server.overworld();
                if (Roster.spawn(level, Vec3.atBottomCenterOf(level.getSharedSpawnPos()), chosen)
                        instanceof Roster.Refusal refused) {
                    throw new IllegalStateException("could not join " + chosen + ": " + refused.message());
                }
                if (parked) BotTicker.unregister(BotRegistry.get(bot.ref()));
                // A name used before in this world comes back with what it was saved with.
                ServerPlayer player = BotRegistry.serverPlayer(server, bot.ref());
                player.getInventory().clearContent();
                player.setGameMode(GameType.SURVIVAL);
                player.setHealth(player.getMaxHealth());
                player.getFoodData().setFoodLevel(20);
                player.getFoodData().setSaturation(5.0F);
                player.containerMenu.broadcastChanges();
            });
            return bot;
        }

        @Override
        public Bot park() {
            Drive.park(game, ref());
            return this;
        }

        @Override
        public Bot unpark() {
            Drive.unpark(game, ref());
            return this;
        }

        @Override
        public void tick(int ticks) {
            Drive.tick(game, ref(), ticks);
        }

        @Override
        public int tickUntilIdle(int maxTicks) {
            return Drive.tickUntilIdle(game, ref(), maxTicks);
        }

        @Override
        public void step(int ticks) {
            Drive.step(game, ref(), ticks);
        }

        @Override
        public void think() {
            Drive.think(game, ref());
        }

        @Override
        public HeldKeys press(UnaryOperator<Press> keys) {
            return Drive.press(game, ref(), keys);
        }

        @Override
        public void close() {
            String keeper = this.keeper;
            if (keeper == null) {
                remove();
                return;
            }
            // Parked so nothing ticks it between the two tests; the taker unparks it.
            if (BotRegistry.get(ref()) != null) park();
            Handovers.keep(this, keeper);
        }
    }
}
