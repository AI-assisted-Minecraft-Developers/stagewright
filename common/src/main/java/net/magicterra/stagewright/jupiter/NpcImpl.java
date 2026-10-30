package net.magicterra.stagewright.jupiter;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Npc;
import net.magicterra.worlddriver.api.methods.test.TestHarnessContracts.Press;
import net.magicterra.worlddriver.api.methods.test.TestInputContracts.HeldKeys;
import net.magicterra.worlddriver.bot.Bot;
import net.magicterra.worlddriver.bot.BotRegistry;
import net.magicterra.worlddriver.bot.Roster;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** A mob spawned and adopted for one test; found through its ref on each call, as WorldDriver's methods find it. */
final class NpcImpl implements Npc {

    private static final AtomicInteger NEXT = new AtomicInteger(1);

    private final Game game;
    private final String name;

    private NpcImpl(Game game, String name) {
        this.game = game;
        this.name = name;
    }

    /** Spawn and adopt in one server task; the mob and its roster entry go when {@code scope}'s test ends. */
    static Npc spawn(
            Game game, TestScope scope, EntityType<? extends Mob> type, ResourceKey<Level> dimension, BlockPos feet) {
        NpcImpl npc = new NpcImpl(game, "sw_npc_" + NEXT.getAndIncrement());
        game.runOnServer(server -> {
            ServerLevel level = GameImpl.level(server, dimension);
            // As Entity: a script names its type by id, and only the spawned entity says whether it is a mob.
            Entity spawned = type.spawn(level, feet, MobSpawnType.COMMAND);
            if (spawned == null) {
                throw new IllegalStateException("could not spawn a " + EntityType.getKey(type) + " at "
                        + feet.toShortString() + " in " + dimension.location());
            }
            if (!(spawned instanceof Mob mob)) {
                spawned.discard();
                throw new IllegalArgumentException(
                        "a " + EntityType.getKey(type) + " is not a mob, so it cannot be a" + " body");
            }
            if (Roster.adopt(mob, npc.name) instanceof Roster.Refusal refused) {
                mob.discard();
                throw new IllegalArgumentException(
                        "WorldDriver will not drive a " + EntityType.getKey(type) + ": " + refused.message());
            }
            scope.onEnd(npc::remove);
        });
        return npc;
    }

    /** Server thread: let the mob go and take it out of the world. */
    private void remove(MinecraftServer server) {
        Bot bot = BotRegistry.get(ref());
        if (bot == null) {
            return;
        }
        Roster.remove(ref());
        if (bot.entity() != null) {
            bot.entity().discard();
        }
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public <T> T onServer(Function<Mob, T> work) {
        return game.onServer(server -> {
            Bot bot = BotRegistry.get(ref());
            if (bot == null || !(bot.entity() instanceof Mob mob) || !mob.isAlive()) {
                throw new IllegalStateException(
                        ref() + " is not a driven mob any more: it died, was removed or was" + " let go");
            }
            return work.apply(mob);
        });
    }

    @Override
    public Npc moveTo(BlockPos feet) {
        runOnServer(mob -> {
            mob.setDeltaMovement(Vec3.ZERO);
            mob.resetFallDistance();
            mob.teleportTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
        });
        return this;
    }

    @Override
    public Npc park() {
        Drive.park(game, ref());
        return this;
    }

    @Override
    public Npc unpark() {
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
    public String toString() {
        return ref();
    }
}
