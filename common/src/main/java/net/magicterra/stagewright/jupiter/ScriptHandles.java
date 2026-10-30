package net.magicterra.stagewright.jupiter;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.magicterra.stagewright.capability.Capabilities;
import net.magicterra.stagewright.test.Body;
import net.magicterra.stagewright.test.Client;
import net.magicterra.stagewright.test.Driven;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Npc;
import net.magicterra.stagewright.test.Perf;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.test.TestHarnessContracts.Press;
import net.magicterra.worlddriver.api.methods.test.TestInputContracts.HeldKeys;
import net.magicterra.worlddriver.api.registry.Scripting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.extension.ExtensionContext.Store.CloseableResource;

/**
 * The handles a script test's {@code t} leases and the overrides it makes, as plain values: what {@link Game},
 * {@link PlotImpl}, {@link BodyImpl} and {@link ClientConnection} give a Java test. A position is {@code {x, y, z}}; a
 * block or item is its id. What is leased is given back, and what is overridden undone, when the test ends.
 * {@code t.capability(name)} hands out a capability's facet through {@link ScriptFacets}.
 */
final class ScriptHandles {

    private ScriptHandles() {}

    static void install(Map<String, Object> t, Game game, List<CloseableResource> leases, String test) {
        // t.plot({size, x, y, z, centered, dimension}), every option optional; x, y and z override size on their axis.
        t.put("plot", (Scripting.Function) args -> {
            Map<?, ?> options = args.length > 0 && args[0] instanceof Map<?, ?> m ? m : Map.of();
            int side = options.containsKey("size") ? whole(options.get("size"), "plot size") : PlotImpl.DEFAULT_SIZE;
            Vec3i size = new Vec3i(
                    options.containsKey("x") ? whole(options.get("x"), "plot x") : side,
                    options.containsKey("y") ? whole(options.get("y"), "plot y") : side,
                    options.containsKey("z") ? whole(options.get("z"), "plot z") : side);
            PlotImpl plot = PlotImpl.lease(
                    game,
                    size,
                    Expectations.truthy(options.get("centered")),
                    options.containsKey("dimension") ? dimension(options.get("dimension")) : Level.OVERWORLD);
            leases.add(plot);
            return plot(plot);
        });
        // t.bot(), t.bot(name) or t.bot({name, parked, keep, kept}).
        t.put("bot", (Scripting.Function) args -> {
            Object a = arg(args, 0);
            String name = a instanceof String n
                    ? n
                    : a instanceof Map<?, ?> m && m.get("name") instanceof String n ? n : null;
            boolean parked = a instanceof Map<?, ?> m && Expectations.truthy(m.get("parked"));
            boolean keep = a instanceof Map<?, ?> m && Expectations.truthy(m.get("keep"));
            boolean kept = a instanceof Map<?, ?> m && Expectations.truthy(m.get("kept"));
            if ((keep || kept) && name == null) {
                throw new IllegalArgumentException(
                        "a bot kept for a later test, or taken from one, is named: give" + " t.bot a name");
            }
            BodyImpl.BotImpl bot =
                    kept ? BodyImpl.BotImpl.take(game, name, parked, test) : BodyImpl.BotImpl.join(game, name, parked);
            if (keep) bot.keep(test);
            leases.add(bot);
            Map<String, Object> out = body(bot);
            driven(out, bot);
            return out;
        });
        t.put("player", (Scripting.Function) args -> body(BodyImpl.player(game)));
        // t.npc(type, pos) in the overworld, or t.npc(type, pos, dimension).
        t.put("npc", (Scripting.Function) args -> {
            // Whether it is a mob is only known once one is spawned, which refuses anything else.
            @SuppressWarnings("unchecked")
            EntityType<? extends Mob> type =
                    (EntityType<? extends Mob>) lookup(BuiltInRegistries.ENTITY_TYPE, arg(args, 0), "entity type");
            Npc npc = game.npc(type, args.length > 2 ? dimension(args[2]) : Level.OVERWORLD, blockPos(arg(args, 1)));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("name", npc.name());
            out.put("ref", npc.ref());
            out.put("pos", (Scripting.Function) a -> position(npc.blockPos()));
            out.put("dimension", (Scripting.Function)
                    a -> npc.dimension().location().toString());
            out.put("moveTo", (Scripting.Function) a -> {
                npc.moveTo(blockPos(arg(a, 0)));
                return null;
            });
            driven(out, npc);
            return out;
        });
        t.put("translate", (Scripting.Function) args ->
                position(game.translate(blockPos(arg(args, 0)), dimension(arg(args, 1)), dimension(arg(args, 2)))));
        t.put("client", (Scripting.Function) args -> {
            Client client = ClientConnection.client(game);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("call", (Scripting.Function) a -> ScriptContext.route(client.registry(), "client.call", a));
            return out;
        });
        // A capability's facet, its methods by name; absent aborts the test, as game.capability does.
        t.put("capability", (Scripting.Function)
                args -> ScriptFacets.wrap(game, game.capability(ScriptContext.string(args, 0, "t.capability(name)"))));
        // What this game offers, without aborting: {has(name), registered(), available()}, as game.capabilities().
        t.put("capabilities", (Scripting.Function) args -> {
            Capabilities capabilities = game.capabilities();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("has", (Scripting.Function) a -> capabilities.has(ScriptContext.string(a, 0, "has(name)")));
            out.put("registered", (Scripting.Function) a -> capabilities.registered());
            out.put("available", (Scripting.Function) a -> capabilities.available());
            return out;
        });
        // Whether a class by this name loads in the game; it is not initialised.
        t.put("hasClass", (Scripting.Function) args -> hasClass(ScriptContext.string(args, 0, "t.hasClass(name)")));
        t.put("override", (Scripting.Function) args -> {
            String rule = ScriptContext.string(args, 0, "t.override(rule, value)");
            Object value = args.length > 1 ? args[1] : null;
            Rule found = rule(rule);
            if (value instanceof Boolean b && found.bool() != null) game.override(found.bool(), b);
            else if (value instanceof Number n && found.integer() != null)
                game.override(found.integer(), whole(n, rule));
            else
                throw new IllegalArgumentException("t.override('" + rule + "', " + Expectations.show(value)
                        + "): the rule takes a " + (found.bool() != null ? "boolean" : "whole number"));
            return null;
        });
        t.put("overrideTime", (Scripting.Function) args -> {
            game.overrideTime(whole(args.length > 0 ? args[0] : null, "t.overrideTime(dayTime)"));
            return null;
        });
        t.put("overrideWeather", (Scripting.Function) args -> {
            game.overrideWeather(
                    Expectations.truthy(args.length > 0 ? args[0] : null),
                    Expectations.truthy(args.length > 1 ? args[1] : null));
            return null;
        });
        // {warmup, sample, repeat, phases: [{name, run}]}: as YAML's perf, a phase's run a function, or none.
        t.put("perf", (Scripting.Function) args -> {
            if (!(args.length > 0 && args[0] instanceof Map<?, ?> perf)
                    || !(perf.get("phases") instanceof List<?> list)) {
                throw new IllegalArgumentException("t.perf({phases: [{name, run}], warmup, sample, repeat})");
            }
            Perf measurement = Perf.phases();
            for (Object p : list) {
                if (!(p instanceof Map<?, ?> phase) || !(phase.get("name") instanceof String name)) {
                    throw new IllegalArgumentException("t.perf: a phase is {name, run}");
                }
                Scripting.Function run = phase.get("run") instanceof Scripting.Function f ? f : null;
                measurement.phase(name, () -> {
                    if (run != null) run.call();
                });
            }
            measurement
                    .warmup(count(perf, "warmup", 200))
                    .sample(count(perf, "sample", 600))
                    .repeat(count(perf, "repeat", 3));
            return PerfSampler.report(game.perf(measurement));
        });
        t.put("overrideTickRate", (Scripting.Function) args -> {
            if (!(args.length > 0 && args[0] instanceof Number n)) {
                throw new IllegalArgumentException("t.overrideTickRate(ticksPerSecond) takes a number");
            }
            game.overrideTickRate(n.floatValue());
            return null;
        });
        // Frozen for the rest of the test; t.wait(n) then steps the world n ticks.
        t.put("freezeTicks", (Scripting.Function) args -> {
            game.freezeTicks();
            return null;
        });
    }

    /**
     * Whether {@code name} loads, through the loader that loaded StageWright — the game's, which sees every mod — and
     * then the thread's. A class that is there but fails to link counts as absent.
     */
    static boolean hasClass(String name) {
        for (ClassLoader loader : new ClassLoader[] {
            ScriptHandles.class.getClassLoader(), Thread.currentThread().getContextClassLoader()
        }) {
            if (loader == null) continue;
            try {
                Class.forName(name, false, loader);
                return true;
            } catch (ClassNotFoundException | LinkageError absent) {
                // Try the next loader.
            }
        }
        return false;
    }

    private static Map<String, Object> plot(PlotImpl plot) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("size", position(new BlockPos(plot.size())));
        out.put("min", position(new BlockPos(plot.min())));
        out.put("max", position(new BlockPos(plot.max())));
        out.put("dimension", plot.dimension().location().toString());
        out.put("origin", position(plot.origin()));
        out.put("structure", (Scripting.Function) a -> {
            ResourceLocation id = arg(a, 0) instanceof String s ? ResourceLocation.tryParse(s) : null;
            if (id == null)
                throw new IllegalArgumentException("structure(id, x, y, z): id is a structure template's id");
            plot.structure(id, whole(arg(a, 1), "x"), whole(arg(a, 2), "y"), whole(arg(a, 3), "z"));
            return null;
        });
        out.put("structureFile", (Scripting.Function) a -> {
            plot.structure(
                    Path.of(ScriptContext.string(a, 0, "structureFile(path, x, y, z)")),
                    whole(arg(a, 1), "x"),
                    whole(arg(a, 2), "y"),
                    whole(arg(a, 3), "z"));
            return null;
        });
        out.put("at", (Scripting.Function) a ->
                position(plot.at(whole(arg(a, 0), "x"), whole(arg(a, 1), "y"), whole(arg(a, 2), "z"))));
        out.put("fill", (Scripting.Function) a -> {
            plot.fill(
                    whole(arg(a, 0), "x1"),
                    whole(arg(a, 1), "y1"),
                    whole(arg(a, 2), "z1"),
                    whole(arg(a, 3), "x2"),
                    whole(arg(a, 4), "y2"),
                    whole(arg(a, 5), "z2"),
                    lookup(BuiltInRegistries.BLOCK, arg(a, 6), "block"));
            return null;
        });
        out.put("place", (Scripting.Function) a -> {
            plot.place(
                    whole(arg(a, 0), "x"),
                    whole(arg(a, 1), "y"),
                    whole(arg(a, 2), "z"),
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
        out.put("dimension", (Scripting.Function)
                a -> body.dimension().location().toString());
        // moveTo(pos) in the overworld, or moveTo(pos, dimension).
        out.put("moveTo", (Scripting.Function) a -> {
            body.moveTo(a.length > 1 ? dimension(a[1]) : Level.OVERWORLD, blockPos(arg(a, 0)));
            return null;
        });
        out.put("give", (Scripting.Function) a -> {
            body.give(lookup(BuiltInRegistries.ITEM, arg(a, 0), "item"), a.length > 1 ? whole(a[1], "count") : 1);
            return null;
        });
        out.put("count", (Scripting.Function) a -> body.count(lookup(BuiltInRegistries.ITEM, arg(a, 0), "item")));
        return out;
    }

    /**
     * The {@link Driven} verbs; {@code press} takes {@code {forward, left, jump, sneak, sprint, attack, use, aimAt,
     * toolFor, hit, release}}.
     */
    private static void driven(Map<String, Object> out, Driven body) {
        out.put("park", (Scripting.Function) a -> {
            body.park();
            return null;
        });
        out.put("unpark", (Scripting.Function) a -> {
            body.unpark();
            return null;
        });
        out.put("tick", (Scripting.Function) a -> {
            body.tick(a.length > 0 ? whole(a[0], "ticks") : 1);
            return null;
        });
        out.put("tickUntilIdle", (Scripting.Function) a -> body.tickUntilIdle(whole(arg(a, 0), "maxTicks")));
        out.put("step", (Scripting.Function) a -> {
            body.step(a.length > 0 ? whole(a[0], "ticks") : 1);
            return null;
        });
        out.put("think", (Scripting.Function) a -> {
            body.think();
            return null;
        });
        out.put("press", (Scripting.Function) a -> {
            if (!(arg(a, 0) instanceof Map<?, ?> keys)) {
                throw new IllegalArgumentException(
                        "press takes {forward, left, jump, sneak, sprint, attack, use, aimAt,"
                                + " toolFor, hit, release}");
            }
            HeldKeys held = body.press(p -> press(p, keys));
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("up", held.up());
            answer.put("down", held.down());
            answer.put("left", held.left());
            answer.put("right", held.right());
            answer.put("jump", held.jump());
            answer.put("sprint", held.sprint());
            answer.put("attack", held.attack());
            answer.put("shift", held.shift());
            return answer;
        });
    }

    static Press press(Press p, Map<?, ?> keys) {
        for (Object k : keys.keySet()) {
            if (!PRESS_KEYS.contains(k)) {
                throw new IllegalArgumentException("press has no key '" + k + "'; it takes " + PRESS_KEYS);
            }
        }
        if (Expectations.truthy(keys.get("release"))) p = p.release();
        // Each axis alone, so a press naming only forward leaves left as it was held.
        if (keys.containsKey("forward") || keys.containsKey("left")) {
            p = new Press(
                    p.bot(),
                    keys.containsKey("forward") ? Optional.of(number(keys.get("forward"), "forward")) : p.forward(),
                    keys.containsKey("left") ? Optional.of(number(keys.get("left"), "left")) : p.left(),
                    p.jump(),
                    p.sneak(),
                    p.sprint(),
                    p.attack(),
                    p.use(),
                    p.aimAt(),
                    p.toolFor(),
                    p.hit());
        }
        if (keys.containsKey("jump")) p = p.jump(Expectations.truthy(keys.get("jump")));
        if (keys.containsKey("sneak")) p = p.sneak(Expectations.truthy(keys.get("sneak")));
        if (keys.containsKey("sprint")) p = p.sprint(Expectations.truthy(keys.get("sprint")));
        if (keys.containsKey("attack")) p = p.attack(Expectations.truthy(keys.get("attack")));
        if (keys.containsKey("use")) p = p.use(Expectations.truthy(keys.get("use")));
        if (keys.containsKey("aimAt")) p = p.aimAt(cell(keys.get("aimAt")));
        if (keys.containsKey("toolFor")) p = p.toolFor(cell(keys.get("toolFor")));
        if (keys.containsKey("hit")) p = p.hit(whole(keys.get("hit"), "hit"));
        return p;
    }

    private static final List<String> PRESS_KEYS = List.of(
            "forward", "left", "jump", "sneak", "sprint", "attack", "use", "aimAt", "toolFor", "hit", "release");

    private static double number(Object v, String what) {
        if (v instanceof Number n) return n.doubleValue();
        throw new IllegalArgumentException(what + " is a number from -1 to 1, but was " + Expectations.show(v));
    }

    private static BlockPosition cell(Object v) {
        BlockPos pos = blockPos(v);
        return new BlockPosition(pos.getX(), pos.getY(), pos.getZ());
    }

    static Map<String, Object> position(BlockPos pos) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("x", pos.getX());
        out.put("y", pos.getY());
        out.put("z", pos.getZ());
        return out;
    }

    static ResourceKey<Level> dimension(Object id) {
        ResourceLocation location = id instanceof String s ? ResourceLocation.tryParse(s) : null;
        if (location == null)
            throw new IllegalArgumentException("a dimension is its id, but was " + Expectations.show(id));
        return ResourceKey.create(Registries.DIMENSION, location);
    }

    private static BlockPos blockPos(Object v) {
        if (v instanceof Map<?, ?> m)
            return new BlockPos(whole(m.get("x"), "x"), whole(m.get("y"), "y"), whole(m.get("z"), "z"));
        throw new IllegalArgumentException("a position is {x, y, z}, but was " + Expectations.show(v));
    }

    /** Loud on a miss: the registry's own answer for an unknown id is its default, air or nothing. */
    private static <T> T lookup(Registry<T> registry, Object id, String what) {
        ResourceLocation location = id instanceof String s ? ResourceLocation.tryParse(s) : null;
        if (location == null)
            throw new IllegalArgumentException("a " + what + " is its id, but was " + Expectations.show(id));
        return registry.getOptional(location)
                .orElseThrow(() -> new IllegalArgumentException("no " + what + " '" + id + "' in this game"));
    }

    /** A game rule by id: one of the two keys is set, by whether it holds a boolean or a whole number. */
    private record Rule(GameRules.Key<GameRules.BooleanValue> bool, GameRules.Key<GameRules.IntegerValue> integer) {}

    private static Rule rule(String id) {
        Rule[] found = {null};
        GameRules.visitGameRuleTypes(new GameRules.GameRuleTypeVisitor() {
            @Override
            public void visitBoolean(
                    GameRules.Key<GameRules.BooleanValue> key, GameRules.Type<GameRules.BooleanValue> type) {
                if (key.getId().equals(id)) found[0] = new Rule(key, null);
            }

            @Override
            public void visitInteger(
                    GameRules.Key<GameRules.IntegerValue> key, GameRules.Type<GameRules.IntegerValue> type) {
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
