package net.magicterra.stagewright.jupiter;

import java.util.Optional;
import java.util.function.UnaryOperator;
import net.magicterra.stagewright.test.Game;
import net.magicterra.worlddriver.api.methods.test.TestHarnessApi;
import net.magicterra.worlddriver.api.methods.test.TestHarnessContracts.BotsOp;
import net.magicterra.worlddriver.api.methods.test.TestHarnessContracts.BotsReply;
import net.magicterra.worlddriver.api.methods.test.TestHarnessContracts.Park;
import net.magicterra.worlddriver.api.methods.test.TestHarnessContracts.Part;
import net.magicterra.worlddriver.api.methods.test.TestHarnessContracts.Press;
import net.magicterra.worlddriver.api.methods.test.TestHarnessContracts.Tick;
import net.magicterra.worlddriver.api.methods.test.TestHarnessContracts.Ticked;
import net.magicterra.worlddriver.api.methods.test.TestHarnessContracts.Unpark;
import net.magicterra.worlddriver.api.methods.test.TestInputContracts.HeldKeys;

/**
 * The {@link net.magicterra.stagewright.test.Driven} verbs for one ref, through {@code mc.test.bots} and
 * {@code mc.test.input.press}. Those answer within the call on the server thread, so each runs there as one task and no
 * server tick falls between a test's two calls unless it waits for one.
 */
final class Drive {

    private Drive() {}

    static void park(Game game, String ref) {
        bots(game, new Park(ref));
    }

    static void unpark(Game game, String ref) {
        bots(game, new Unpark(ref));
    }

    static void tick(Game game, String ref, int ticks) {
        bots(game, new Tick(positive(ticks), false, Optional.of(ref), Part.ALL));
    }

    static int tickUntilIdle(Game game, String ref, int maxTicks) {
        return ((Ticked) bots(game, new Tick(positive(maxTicks), true, Optional.of(ref), Part.ALL))).ticks();
    }

    static void step(Game game, String ref, int ticks) {
        bots(game, new Tick(positive(ticks), false, Optional.of(ref), Part.BODY));
    }

    static void think(Game game, String ref) {
        bots(game, new Tick(1, false, Optional.of(ref), Part.BRAIN));
    }

    static HeldKeys press(Game game, String ref, UnaryOperator<Press> keys) {
        Press press = keys.apply(Press.of(ref));
        if (!press.bot().equals(ref)) {
            throw new IllegalArgumentException("a press for " + ref + " was turned into one for " + press.bot());
        }
        return game.onServer(server -> TestHarnessApi.of(game.registry())
                        .press(press)
                        .toCompletableFuture()
                        .join())
                .held();
    }

    private static BotsReply bots(Game game, BotsOp op) {
        return game.onServer(server -> TestHarnessApi.of(game.registry())
                .bots(op)
                .toCompletableFuture()
                .join());
    }

    private static int positive(int ticks) {
        if (ticks < 1) {
            throw new IllegalArgumentException("a tick count is at least 1, but was " + ticks);
        }
        return ticks;
    }
}
