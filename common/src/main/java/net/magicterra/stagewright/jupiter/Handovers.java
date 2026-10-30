package net.magicterra.stagewright.jupiter;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.magicterra.stagewright.StageWrightCommon;

/**
 * The bots passed tests kept for a dependent to take, by name. A bot is put here when the test that kept it closes,
 * taken out by the test that claims it, and whatever is left is removed once the run's tests are over.
 *
 * <p>When a later round follows ({@link Rounds}), what is left is carried across the restart instead: the bots leave
 * the world, which saves each one's player data as a disconnect does, and their names and keepers go to {@value #FILE}
 * in the game's {@code stagewright/}. The next round reads that file, and a test there that claims one of those names
 * joins the bot again with no position, so it comes back where its save left it.
 */
final class Handovers {

    /** The bots kept across a restart, name to keeper, under the game's {@code stagewright/}. */
    static final String FILE = "handovers.json";

    /** A kept bot and the test that kept it, for the message when nobody takes it. */
    record Kept(BodyImpl.BotImpl bot, String keeper) {}

    private static final Map<String, Kept> KEPT = new ConcurrentHashMap<>();

    /** The bots kept before the restart and not yet taken: name to the test that kept it. */
    private static final Map<String, String> BEFORE_RESTART = new ConcurrentHashMap<>();

    private Handovers() {}

    static void keep(BodyImpl.BotImpl bot, String keeper) {
        KEPT.put(bot.name(), new Kept(bot, keeper));
    }

    /** The bot kept under {@code name}, no longer on the shelf; null when none is. */
    static Kept take(String name) {
        return KEPT.remove(name);
    }

    /** Who kept the bot named {@code name} before the restart, no longer on the shelf; null when nobody did. */
    static String takeFromBeforeRestart(String name) {
        return BEFORE_RESTART.remove(name);
    }

    /**
     * What a test's end does with the bots it kept: a failed test releases them, and a kept bot standing in one of the
     * test's plots is released and the test fails, since the plot is put back under it. The exception to throw, or
     * null.
     */
    // The first plot that holds the bot settles it: a bot standing in two plots must not be released or reported twice.
    @SuppressWarnings("PMD.AvoidBranchingStatementAsLastInLoop")
    static IllegalStateException endOfTest(
            Collection<BodyImpl.BotImpl> bots, Collection<PlotImpl> plots, boolean failed) {
        IllegalStateException misplaced = null;
        for (BodyImpl.BotImpl bot : bots) {
            if (!bot.kept()) {
                continue;
            }
            if (failed) {
                bot.release();
                continue;
            }
            for (PlotImpl plot : plots) {
                if (!plot.contains(bot.dimension(), bot.blockPos())) {
                    continue;
                }
                bot.release();
                IllegalStateException e = new IllegalStateException(bot.name() + " is kept for a later test but"
                        + " stands in this test's plot, which is put back when the test ends: move it out first");
                if (misplaced == null) {
                    misplaced = e;
                } else {
                    misplaced.addSuppressed(e);
                }
                break;
            }
        }
        return misplaced;
    }

    /** Once, before the round's tests: the bots the round before carried across, from the file in {@code dir}. */
    static void load(Path dir, int round) {
        BEFORE_RESTART.clear();
        // A scene's first game finds a file only a run of some earlier scene left.
        if (round > 0) {
            BEFORE_RESTART.putAll(read(dir));
        }
    }

    /**
     * Once, after the round's tests: removes the kept bots no test took, and when {@code restarting} writes which they
     * were, with those still carried from before, so the next round may take them.
     */
    static void sweep(Path dir, boolean restarting) throws IOException {
        Map<String, String> carried = new LinkedHashMap<>();
        for (Kept kept : KEPT.values()) {
            if (restarting) {
                if (StageWrightCommon.LOG.isInfoEnabled()) {
                    StageWrightCommon.LOG.info(
                            "[{}] {} kept {}; it leaves for the restart and joins again where it" + " stood",
                            StageWrightCommon.MOD_ID,
                            kept.keeper(),
                            kept.bot().name());
                }
                carried.put(kept.bot().name(), kept.keeper());
            } else {
                if (StageWrightCommon.LOG.isWarnEnabled()) {
                    StageWrightCommon.LOG.warn(
                            "[{}] {} kept {} for a later test, and none took it",
                            StageWrightCommon.MOD_ID,
                            kept.keeper(),
                            kept.bot().name());
                }
            }
            // Leaving saves its player data, which is what a later round's join reads.
            kept.bot().remove();
        }
        KEPT.clear();
        BEFORE_RESTART.forEach((name, keeper) -> {
            if (restarting) {
                carried.put(name, keeper);
            } else {
                StageWrightCommon.LOG.warn(
                        "[{}] {} kept {} for a test after a restart, and none took it",
                        StageWrightCommon.MOD_ID,
                        keeper,
                        name);
            }
        });
        BEFORE_RESTART.clear();
        write(dir, carried);
    }

    /** The file in {@code dir}: name to keeper; empty when there is none. */
    static Map<String, String> read(Path dir) {
        Path file = dir.resolve(FILE);
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        try {
            JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            Map<String, String> out = new LinkedHashMap<>();
            o.entrySet().forEach(e -> out.put(e.getKey(), e.getValue().getAsString()));
            return out;
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("could not read the bots kept across the restart from " + file, e);
        }
    }

    static void write(Path dir, Map<String, String> carried) throws IOException {
        JsonObject o = new JsonObject();
        carried.forEach(o::addProperty);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(FILE), new Gson().toJson(o), StandardCharsets.UTF_8);
    }
}
