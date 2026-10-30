package net.magicterra.stagewright.jupiter;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;

/**
 * The part of a scene's world that is set once the world is loaded, before the first test: game rules, the clock, the
 * weather, the tick rate, mob spawning. The test engine passes it as {@value #PROPERTY}; the part that applies when the
 * world is created reached the game through {@code server.properties}, and the datapacks among it are checked here,
 * since a pack id the game does not know is dropped without a word.
 */
final class SceneWorld {

    static final String PROPERTY = "stagewright.scene.world";

    /** Weather declared frozen or not lasts this long; not frozen, the cycle takes over after it. */
    private static final int WEATHER_TICKS = 1_000_000;

    private SceneWorld() {}

    /** Server thread: apply {@code w} and answer what was applied; refuses a declaration the game cannot honour. */
    static String apply(MinecraftServer server, JsonObject w) {
        checkPacks(server, strings(w, "enablePacks"), strings(w, "disablePacks"));
        GameRules rules = server.getGameRules();
        ServerLevel overworld = server.overworld();

        overworld.setDayTime(w.get("time").getAsLong());
        rules.getRule(GameRules.RULE_DAYLIGHT).set(!w.get("timeFrozen").getAsBoolean(), server);
        boolean raining = w.get("raining").getAsBoolean();
        boolean thundering = w.get("thundering").getAsBoolean();
        overworld.setWeatherParameters(raining ? 0 : WEATHER_TICKS, raining ? WEATHER_TICKS : 0, raining, thundering);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(!w.get("weatherFrozen").getAsBoolean(), server);
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(w.get("mobSpawning").getAsBoolean(), server);
        if (w.has("tickRate")) {
            server.tickRateManager().setTickRate(w.get("tickRate").getAsFloat());
        }

        // Declared rules last, so one the scene names outright wins over one derived from time or weather.
        Map<String, GameRules.Key<?>> keys = new HashMap<>();
        GameRules.visitGameRuleTypes(new GameRules.GameRuleTypeVisitor() {
            @Override
            public <T extends GameRules.Value<T>> void visit(GameRules.Key<T> key, GameRules.Type<T> type) {
                keys.put(key.getId(), key);
            }
        });
        for (Map.Entry<String, JsonElement> declared :
                w.getAsJsonObject("gamerules").entrySet()) {
            GameRules.Key<?> key = keys.get(declared.getKey());
            if (key == null) {
                throw new IllegalStateException(
                        "the scene sets game rule '" + declared.getKey() + "', which this game" + " does not have");
            }
            GameRules.Value<?> value = rules.getRule(key);
            if (value instanceof GameRules.BooleanValue b
                    && declared.getValue().getAsJsonPrimitive().isBoolean()) {
                b.set(declared.getValue().getAsBoolean(), server);
            } else if (value instanceof GameRules.IntegerValue i
                    && declared.getValue().getAsJsonPrimitive().isNumber()) {
                i.set(declared.getValue().getAsInt(), server);
            } else {
                throw new IllegalStateException("the scene sets game rule '" + declared.getKey() + "' to "
                        + declared.getValue() + ", which is not a value of its kind");
            }
        }
        return "time " + overworld.getDayTime() + (rules.getBoolean(GameRules.RULE_DAYLIGHT) ? "" : " (frozen)")
                + ", " + (thundering ? "thunder" : raining ? "rain" : "clear")
                + (rules.getBoolean(GameRules.RULE_WEATHER_CYCLE) ? "" : " (frozen)")
                + ", mob spawning " + rules.getBoolean(GameRules.RULE_DOMOBSPAWNING)
                + ", tick rate " + server.tickRateManager().tickrate()
                + ", datapacks " + server.getPackRepository().getSelectedIds();
    }

    private static void checkPacks(MinecraftServer server, List<String> enable, List<String> disable) {
        Collection<String> selected = server.getPackRepository().getSelectedIds();
        List<String> problems = new ArrayList<>();
        for (String id : enable) {
            if (!selected.contains(id)) {
                problems.add(id + " is not enabled");
            }
        }
        for (String id : disable) {
            if (selected.contains(id)) {
                problems.add(id + " is still enabled");
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("the scene's datapacks did not take: " + problems + ". The packs this game"
                    + " has are " + new TreeSet<>(server.getPackRepository().getAvailableIds()));
        }
    }

    private static List<String> strings(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        if (o.has(key)) {
            o.getAsJsonArray(key).forEach(e -> out.add(e.getAsString()));
        }
        return out;
    }
}
