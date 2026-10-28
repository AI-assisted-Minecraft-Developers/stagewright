package net.magicterra.stagewright.jupiter;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.magicterra.stagewright.StageWrightCommon;
import net.magicterra.stagewright.test.Game;

/**
 * The bots passed tests kept for a dependent to take, by name. A bot is put here when the test that kept it closes,
 * taken out by the test that claims it, and whatever is left is removed once the run's tests are over.
 */
final class Handovers {

    /** A kept bot and the test that kept it, for the message when nobody takes it. */
    record Kept(BodyImpl.BotImpl bot, String keeper) {}

    private static final Map<String, Kept> KEPT = new ConcurrentHashMap<>();

    private Handovers() {}

    static void keep(BodyImpl.BotImpl bot, String keeper) {
        KEPT.put(bot.name(), new Kept(bot, keeper));
    }

    /** The bot kept under {@code name}, no longer on the shelf; null when none is. */
    static Kept take(String name) {
        return KEPT.remove(name);
    }

    /**
     * What a test's end does with the bots it kept: a failed test releases them, and a kept bot standing in one of the
     * test's plots is released and the test fails, since the plot is put back under it. The exception to throw, or
     * null.
     */
    static IllegalStateException endOfTest(Collection<BodyImpl.BotImpl> bots, Collection<PlotImpl> plots,
                                           boolean failed) {
        IllegalStateException misplaced = null;
        for (BodyImpl.BotImpl bot : bots) {
            if (!bot.kept()) continue;
            if (failed) {
                bot.release();
                continue;
            }
            for (PlotImpl plot : plots) {
                if (!plot.contains(bot.dimension(), bot.blockPos())) continue;
                bot.release();
                IllegalStateException e = new IllegalStateException(bot.name() + " is kept for a later test but"
                        + " stands in this test's plot, which is put back when the test ends: move it out first");
                if (misplaced == null) misplaced = e;
                else misplaced.addSuppressed(e);
                break;
            }
        }
        return misplaced;
    }

    /** Removes the kept bots no test took. Once, after the run's tests. */
    static void sweep(Game game) {
        for (Kept kept : KEPT.values()) {
            StageWrightCommon.LOG.warn("[{}] {} kept {} for a later test, and none took it", StageWrightCommon.MOD_ID,
                    kept.keeper(), kept.bot().name());
            kept.bot().remove();
        }
        KEPT.clear();
    }
}
