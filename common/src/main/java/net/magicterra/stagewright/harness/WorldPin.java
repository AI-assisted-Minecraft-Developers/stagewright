package net.magicterra.stagewright.harness;

import net.magicterra.stagewright.StageWrightCommon;
import net.magicterra.stagewright.contract.Clock;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;

/**
 * Holds the world still for the duration of a suite.
 *
 * <p><b>The problem.</b> Every scene in a run shares one world, and left alone that world moves:
 * the clock advances, weather rolls, mobs spawn. So what a scene sees depends on how long the
 * scenes before it took — which is a property of the machine it ran on. That is not a theoretical
 * complaint. Measured on both loaders, the whole 191-scene worlddriver suite finishes inside
 * {@code dayTime}≈130, i.e. sunrise, because almost every scene resolves in one server tick. Sunrise
 * is where sky brightness climbs through the threshold vanilla dice-rolls against to decide whether
 * a sun-sensitive mob ignites — so "did the zombie burn" was a coin flip decided by tick alignment,
 * and three separate scenes had independently grown a defence against it.
 *
 * <p><b>What is pinned, and why each one is on the list.</b> Deliberately short: this list is a set
 * of lies the framework tells about the world, and every entry has to earn its place.
 * <ul>
 *   <li>{@code dayTime} = the scene's {@link Clock} (default midnight) — the fix above.</li>
 *   <li>{@code doDaylightCycle=false} — setting the time is not enough on its own; without freezing
 *       it, a long scene drifts back into the sunrise band it was moved out of.</li>
 *   <li>{@code doMobSpawning=false} — <b>required by the choice of night</b>, not independent of
 *       it. Arenas tick entities, and the arena audit reports an entity increase inside the box as
 *       the scene leaking; natural hostile spawning at night would turn that into a fresh source of
 *       false REDs.</li>
 *   <li>{@code doWeatherCycle=false} + clear weather — the audit already treats a rain or thunder
 *       flip as a leak, so a weather change that happened to straddle a scene would accuse it.
 *       At ~200 ticks a run this is insurance rather than a live bug, but it is one line.</li>
 * </ul>
 *
 * <p><b>What is deliberately NOT pinned:</b> {@code randomTickSpeed}, {@code doFireTick},
 * {@code mobGriefing}. Those are things a modpack's own scenes legitimately test — a crop that
 * grows, a fire that spreads, a creeper that craters — and freezing them would silently break the
 * author this framework exists for. They are also not sources of cross-scene drift the way the
 * clock is: nothing changes them unless a scene does, and the arena audit already catches a scene
 * that leaves one changed.
 *
 * <p><b>Everything here is announced.</b> The pin is logged at suite start and written into the
 * results header, because a framework that quietly runs your test in a world you did not ask for is
 * worse than one that does not pin at all.
 */
public final class WorldPin {

    /** Weather forced off for this many ticks; re-asserted per scene, so the value only has to
     *  outlast one. A full in-game day is far more than any scene gets. */
    private static final int CLEAR_TICKS = 24_000;

    /** The world as it was before {@link #applySuite} touched it, or null when nothing is pinned. */
    private static Saved saved;

    private record Saved(boolean daylight, boolean weatherCycle, boolean mobSpawning, long dayTime) {}

    private WorldPin() {}

    /** One line naming everything a run pins, for the log and the results header. */
    public static String description() {
        return "clock=frozen@" + Clock.MIDNIGHT.name().toLowerCase(java.util.Locale.ROOT)
                + " doDaylightCycle=false doWeatherCycle=false doMobSpawning=false weather=clear";
    }

    /**
     * Apply the suite-wide pin. Called once when the harness is built — i.e. when a suite actually
     * starts, NOT when the runtime merely arms. A held server that nobody has asked to run anything
     * keeps its vanilla world, so an interactive session is not quietly standing in a frozen night
     * it never asked for.
     */
    public static void applySuite(MinecraftServer server) {
        GameRules rules = server.getGameRules();
        ServerLevel overworld = server.overworld();
        saved = new Saved(rules.getBoolean(GameRules.RULE_DAYLIGHT),
                rules.getBoolean(GameRules.RULE_WEATHER_CYCLE),
                rules.getBoolean(GameRules.RULE_DOMOBSPAWNING),
                overworld == null ? 0L : overworld.getDayTime());
        set(rules, GameRules.RULE_DAYLIGHT, false, server);
        set(rules, GameRules.RULE_WEATHER_CYCLE, false, server);
        set(rules, GameRules.RULE_DOMOBSPAWNING, false, server);
        applyClock(server, Clock.MIDNIGHT);
        StageWrightCommon.LOG.warn("[{}] WORLD PINNED for this suite — {}. Scenes do NOT run in a"
                + " vanilla world; a scene that needs otherwise declares its own clock.",
                StageWrightCommon.MOD_ID, description());
    }

    /**
     * Give the world back at the end of a suite.
     *
     * <p>Only matters on a HELD server, which is the one case where something outlives the run: a
     * gate halts the JVM a moment later and a CLI run throws the world away. But that is also the
     * case where leaving it pinned is worst — someone attaches to a hold, triggers a suite, and their
     * world is silently stuck at midnight with nothing spawning for the rest of the session.
     */
    public static void release(MinecraftServer server) {
        Saved was = saved;
        if (was == null) return;
        saved = null;
        GameRules rules = server.getGameRules();
        set(rules, GameRules.RULE_DAYLIGHT, was.daylight(), server);
        set(rules, GameRules.RULE_WEATHER_CYCLE, was.weatherCycle(), server);
        set(rules, GameRules.RULE_DOMOBSPAWNING, was.mobSpawning(), server);
        ServerLevel overworld = server.overworld();
        if (overworld != null) overworld.setDayTime(was.dayTime());
        StageWrightCommon.LOG.info("[{}] world pin released — gamerules and clock are the pack's"
                + " again", StageWrightCommon.MOD_ID);
    }

    /**
     * Apply one scene's clock. Called before every scene rather than once per suite: that is what
     * makes a scene's time independent of the scene before it, including one that ran with
     * {@link Clock#RUNNING}.
     *
     * <p>Written to the OVERWORLD, always. StageWright's own dimensions use the vanilla overworld
     * dimension type and derive their clock and weather from the primary level data, so setting it
     * on the level a scene's arena happens to live in would either be redundant or (on a derived
     * level) silently do nothing.
     */
    public static void applyClock(MinecraftServer server, Clock clock) {
        ServerLevel overworld = server.overworld();
        if (overworld == null) return;
        set(server.getGameRules(), GameRules.RULE_DAYLIGHT, clock.cycleRuns(), server);
        overworld.setDayTime(clock.dayTime());
        // Clear rather than "leave alone": a scene that ended mid-storm would otherwise hand the
        // next one rain, and the audit would report the innocent scene as the one that left it on.
        overworld.setWeatherParameters(CLEAR_TICKS, 0, false, false);
    }

    private static void set(GameRules rules, GameRules.Key<GameRules.BooleanValue> key,
                            boolean value, MinecraftServer server) {
        rules.getRule(key).set(value, server);
    }
}
