package net.magicterra.stagewright.jupiter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.magicterra.stagewright.test.Body;
import net.magicterra.stagewright.test.Client;
import net.magicterra.stagewright.test.Game;
import net.magicterra.worlddriver.api.registry.Scripting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.GameRules;
import org.junit.jupiter.api.extension.ExtensionContext.Store.CloseableResource;

/**
 * The handles a script test's {@code t} leases and the overrides it makes, as plain values: what {@link Game},
 * {@link PlotImpl}, {@link BodyImpl} and {@link ClientConnection} give a Java test. A position is {@code {x, y, z}};
 * a block or item is its id. What is leased is given back, and what is overridden undone, when the test ends.
 */
final class ScriptHandles {

    private ScriptHandles() {}

    static void install(Map<String, Object> t, Game game, List<CloseableResource> leases) {
        t.put("plot", (Scripting.Function) args -> {
            Object size = args.length > 0 && args[0] instanceof Map<?, ?> options ? options.get("size") : null;
            int side = size == null ? PlotImpl.DEFAULT_SIZE : whole(size, "plot size");
            if (side < 1 || side > PlotImpl.MAX_SIZE) {
                throw new IllegalArgumentException("t.plot({size: " + side + "}): a plot's side runs from 1 to "
                        + PlotImpl.MAX_SIZE);
            }
            PlotImpl plot = PlotImpl.lease(game, side);
            leases.add(plot);
            return plot(plot);
        });
        t.put("bot", (Scripting.Function) args -> {
            BodyImpl.BotImpl bot = BodyImpl.BotImpl.join(game, args.length > 0 && args[0] instanceof String n ? n : null);
            leases.add(bot);
            return body(bot);
        });
        t.put("player", (Scripting.Function) args -> body(BodyImpl.player(game)));
        t.put("client", (Scripting.Function) args -> {
            Client client = ClientConnection.client(game);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("call", (Scripting.Function) a -> ScriptContext.route(client.registry(), "client.call", a));
            return out;
        });
        t.put("override", (Scripting.Function) args -> {
            String rule = ScriptContext.string(args, 0, "t.override(rule, value)");
            Object value = args.length > 1 ? args[1] : null;
            Rule found = rule(rule);
            if (value instanceof Boolean b && found.bool() != null) game.override(found.bool(), b);
            else if (value instanceof Number n && found.integer() != null) game.override(found.integer(), whole(n, rule));
            else throw new IllegalArgumentException("t.override('" + rule + "', " + Expectations.show(value)
                    + "): the rule takes a " + (found.bool() != null ? "boolean" : "whole number"));
            return null;
        });
        t.put("overrideTime", (Scripting.Function) args -> {
            game.overrideTime(whole(args.length > 0 ? args[0] : null, "t.overrideTime(dayTime)"));
            return null;
        });
        t.put("overrideWeather", (Scripting.Function) args -> {
            game.overrideWeather(Expectations.truthy(args.length > 0 ? args[0] : null),
                    Expectations.truthy(args.length > 1 ? args[1] : null));
            return null;
        });
        // {warmup, sample, repeat, phases: [{name, run}]}: as YAML's perf, a phase's run a function, or none.
        t.put("perf", (Scripting.Function) args -> {
            if (!(args.length > 0 && args[0] instanceof Map<?, ?> perf) || !(perf.get("phases") instanceof List<?> list)) {
                throw new IllegalArgumentException("t.perf({phases: [{name, run}], warmup, sample, repeat})");
            }
            List<PerfSampler.Phase> phases = new ArrayList<>();
            for (Object p : list) {
                if (!(p instanceof Map<?, ?> phase) || !(phase.get("name") instanceof String name)) {
                    throw new IllegalArgumentException("t.perf: a phase is {name, run}");
                }
                Scripting.Function run = phase.get("run") instanceof Scripting.Function f ? f : null;
                phases.add(new PerfSampler.Phase(name, () -> {
                    if (run != null) run.call();
                }));
            }
            return PerfSampler.measure(game, phases, count(perf, "warmup", 200), count(perf, "sample", 600),
                    count(perf, "repeat", 3));
        });
        t.put("overrideTickRate", (Scripting.Function) args -> {
            if (!(args.length > 0 && args[0] instanceof Number n)) {
                throw new IllegalArgumentException("t.overrideTickRate(ticksPerSecond) takes a number");
            }
            game.overrideTickRate(n.floatValue());
            return null;
        });
    }

    private static Map<String, Object> plot(PlotImpl plot) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("size", plot.size());
        out.put("origin", position(plot.origin()));
        out.put("at", (Scripting.Function) a -> position(plot.at(whole(arg(a, 0), "x"), whole(arg(a, 1), "y"),
                whole(arg(a, 2), "z"))));
        out.put("fill", (Scripting.Function) a -> {
            plot.fill(whole(arg(a, 0), "x1"), whole(arg(a, 1), "y1"), whole(arg(a, 2), "z1"),
                    whole(arg(a, 3), "x2"), whole(arg(a, 4), "y2"), whole(arg(a, 5), "z2"),
                    lookup(BuiltInRegistries.BLOCK, arg(a, 6), "block"));
            return null;
        });
        out.put("place", (Scripting.Function) a -> {
            plot.place(whole(arg(a, 0), "x"), whole(arg(a, 1), "y"), whole(arg(a, 2), "z"),
                    lookup(BuiltInRegistries.BLOCK, arg(a, 3), "block"));
            return null;
        });
        return out;
    }

    private static Map<String, Object> body(Body body) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", body.name());
        out.put("ref", body.ref());
        out.put("pos", (Scripting.Function) a -> position(body.blockPos()));
        out.put("moveTo", (Scripting.Function) a -> {
            body.moveTo(blockPos(arg(a, 0)));
            return null;
        });
        out.put("give", (Scripting.Function) a -> {
            body.give(lookup(BuiltInRegistries.ITEM, arg(a, 0), "item"), a.length > 1 ? whole(a[1], "count") : 1);
            return null;
        });
        out.put("count", (Scripting.Function) a -> body.count(lookup(BuiltInRegistries.ITEM, arg(a, 0), "item")));
        return out;
    }

    static Map<String, Object> position(BlockPos pos) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("x", pos.getX());
        out.put("y", pos.getY());
        out.put("z", pos.getZ());
        return out;
    }

    private static BlockPos blockPos(Object v) {
        if (v instanceof Map<?, ?> m) return new BlockPos(whole(m.get("x"), "x"), whole(m.get("y"), "y"), whole(m.get("z"), "z"));
        throw new IllegalArgumentException("a position is {x, y, z}, but was " + Expectations.show(v));
    }

    /** Loud on a miss: the registry's own answer for an unknown id is its default, air or nothing. */
    private static <T> T lookup(Registry<T> registry, Object id, String what) {
        ResourceLocation location = id instanceof String s ? ResourceLocation.tryParse(s) : null;
        if (location == null) throw new IllegalArgumentException("a " + what + " is its id, but was " + Expectations.show(id));
        return registry.getOptional(location)
                .orElseThrow(() -> new IllegalArgumentException("no " + what + " '" + id + "' in this game"));
    }

    /** A game rule by id: one of the two keys is set, by whether it holds a boolean or a whole number. */
    private record Rule(GameRules.Key<GameRules.BooleanValue> bool, GameRules.Key<GameRules.IntegerValue> integer) {}

    private static Rule rule(String id) {
        Rule[] found = {null};
        GameRules.visitGameRuleTypes(new GameRules.GameRuleTypeVisitor() {
            @Override
            public void visitBoolean(GameRules.Key<GameRules.BooleanValue> key, GameRules.Type<GameRules.BooleanValue> type) {
                if (key.getId().equals(id)) found[0] = new Rule(key, null);
            }

            @Override
            public void visitInteger(GameRules.Key<GameRules.IntegerValue> key, GameRules.Type<GameRules.IntegerValue> type) {
                if (key.getId().equals(id)) found[0] = new Rule(null, key);
            }
        });
        if (found[0] == null) throw new IllegalArgumentException("no game rule '" + id + "'");
        return found[0];
    }

    private static int count(Map<?, ?> map, String key, int fallback) {
        return map.containsKey(key) ? whole(map.get(key), "perf " + key) : fallback;
    }

    private static Object arg(Object[] args, int i) {
        return args.length > i ? args[i] : null;
    }

    private static int whole(Object v, String what) {
        if (v instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) return n.intValue();
        throw new IllegalArgumentException(what + " is a whole number, but was " + Expectations.show(v));
    }
}
