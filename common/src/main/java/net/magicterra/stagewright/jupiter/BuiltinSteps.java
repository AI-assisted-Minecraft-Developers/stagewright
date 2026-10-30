package net.magicterra.stagewright.jupiter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

/**
 * The named steps StageWright itself provides, taken like a script's: {@code name: args} in YAML, {@code t.step(name,
 * args)} in JavaScript. A position is a handle a {@code place} named with {@code as}, {@code [x, y, z]} inside the
 * test's plot, or {@code {x, y, z}} in the world. A script may not register a step under one of these names.
 */
final class BuiltinSteps {

    static final Set<String> NAMES =
            Set.of("place", "insert", "container", "chunk.unload", "chunk.reload", "store.put", "store.get");

    private BuiltinSteps() {}

    static Object run(ScriptContext context, String name, Object args) {
        return switch (name) {
            case "place" -> place(context, map(args, "place: {at, block, as}"));
            case "insert" -> insert(context, map(args, "insert: {into, items}"));
            case "container" -> container(context, args);
            case "chunk.unload" -> chunk(context, "unload", args);
            case "chunk.reload" -> chunk(context, "reload", args);
            case "store.put" -> {
                // {key: value, …}: each kept for the tests after a restart.
                map(args, "store.put: {key: value}")
                        .forEach((k, v) -> context.game().store().put(String.valueOf(k), v));
                yield null;
            }
            case "store.get" -> {
                if (!(args instanceof String key)) throw new IllegalArgumentException("store.get: the key, a string");
                yield context.game().store().get(key);
            }
            default -> throw new IllegalArgumentException("no built-in step '" + name + "'");
        };
    }

    /** {@code place: {at, block, as}}: set one block, and name its position when {@code as} is given. */
    private static Object place(ScriptContext context, Map<?, ?> args) {
        BlockPos at = position(context, args.get("at"));
        if (!(args.get("block") instanceof String block)) throw new IllegalArgumentException("place: block is an id");
        Map<String, Object> pos = ScriptHandles.position(at);
        Map<String, Object> fill = new LinkedHashMap<>();
        fill.put("from", pos);
        fill.put("to", pos);
        fill.put("type", block);
        context.call("mc.action.fill", fill);
        if (args.get("as") instanceof String handle) context.name(handle, at);
        return pos;
    }

    /** {@code insert: {into, items: {id: count}}}: put the items into the container there, all of them or fail. */
    private static Object insert(ScriptContext context, Map<?, ?> args) {
        BlockPos at = position(context, args.get("into"));
        if (!(args.get("items") instanceof Map<?, ?> items) || items.isEmpty()) {
            throw new IllegalArgumentException("insert: items is a map of item id to count");
        }
        context.game().runOnServer(server -> {
            Container container = containerAt(server.overworld().getBlockEntity(at), at);
            for (Map.Entry<?, ?> e : items.entrySet()) {
                Item item = item(e.getKey());
                int left = ScriptContext.ticks(e.getValue(), "insert: the count of " + e.getKey());
                while (left > 0) {
                    int n = Math.min(left, item.getDefaultMaxStackSize());
                    ItemStack rest = HopperBlockEntity.addItem(null, container, new ItemStack(item, n), null);
                    if (!rest.isEmpty()) {
                        throw new AssertionError("insert: the container at " + at.toShortString() + " had no room for "
                                + (left - n + rest.getCount()) + " of " + e.getKey());
                    }
                    left -= n;
                }
            }
            container.setChanged();
        });
        return contents(context, at);
    }

    /**
     * {@code container: {of, equals, contains, …}}: what the container there holds, item id to count; its other keys
     * are an expectation of that, as {@code expect} is.
     */
    private static Object container(ScriptContext context, Object args) {
        Map<?, ?> map = args instanceof Map<?, ?> m && m.containsKey("of") ? m : Map.of("of", args);
        Map<String, Integer> contents = contents(context, position(context, map.get("of")));
        Map<Object, Object> expected = new LinkedHashMap<>(map);
        expected.remove("of");
        if (!expected.isEmpty()) Expectations.expect(contents, expected);
        return contents;
    }

    /** {@code chunk.unload: at} or {@code {at, within}}: the chunk holding that position, out of memory or back. */
    private static Object chunk(ScriptContext context, String op, Object args) {
        Map<?, ?> map = args instanceof Map<?, ?> m && m.containsKey("at") ? m : Map.of("at", args);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("op", op);
        params.put("pos", ScriptHandles.position(position(context, map.get("at"))));
        if (map.containsKey("within")) params.put("withinTicks", map.get("within"));
        Object reply = context.call("mc.test.chunks", params);
        if (!(reply instanceof Map<?, ?> r) || !Boolean.TRUE.equals(r.get("done"))) {
            throw new AssertionError("chunk." + op + ": not done in time"
                    + (reply instanceof Map<?, ?> r2 && r2.get("heldBy") != null ? ", held by " + r2.get("heldBy") : "")
                    + ": " + reply);
        }
        return reply;
    }

    private static Map<String, Integer> contents(ScriptContext context, BlockPos at) {
        return context.game().onServer(server -> {
            Container container = containerAt(server.overworld().getBlockEntity(at), at);
            Map<String, Integer> out = new TreeMap<>();
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                if (!stack.isEmpty())
                    out.merge(
                            BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
            }
            return out;
        });
    }

    private static Container containerAt(BlockEntity entity, BlockPos at) {
        if (entity instanceof Container container) return container;
        throw new AssertionError("no container at " + at.toShortString()
                + (entity == null ? ": no block entity there" : ": " + entity.getType() + " holds no items"));
    }

    /** A handle's name, a position in the plot as {@code [x, y, z]}, or one in the world as {@code {x, y, z}}. */
    static BlockPos position(ScriptContext context, Object ref) {
        if (ref instanceof String handle) return context.named(handle);
        if (ref instanceof List<?> list && list.size() == 3) {
            return context.plotOrFail()
                    .at(
                            ScriptContext.ticks(list.get(0), "x"),
                            ScriptContext.ticks(list.get(1), "y"),
                            ScriptContext.ticks(list.get(2), "z"));
        }
        if (ref instanceof Map<?, ?> m) {
            return new BlockPos(whole(m.get("x"), "x"), whole(m.get("y"), "y"), whole(m.get("z"), "z"));
        }
        throw new IllegalArgumentException(
                "a position is a handle's name, [x, y, z] in the plot, or {x, y, z}, not " + Expectations.show(ref));
    }

    private static Item item(Object id) {
        ResourceLocation location = id instanceof String s ? ResourceLocation.tryParse(s) : null;
        if (location == null) throw new IllegalArgumentException("an item is its id, not " + Expectations.show(id));
        return BuiltInRegistries.ITEM
                .getOptional(location)
                .orElseThrow(() -> new IllegalArgumentException("no item '" + id + "' in this game"));
    }

    private static int whole(Object v, String what) {
        if (v instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) return n.intValue();
        throw new IllegalArgumentException(what + " is a whole number, not " + Expectations.show(v));
    }

    private static Map<?, ?> map(Object args, String usage) {
        if (args instanceof Map<?, ?> m) return m;
        throw new IllegalArgumentException(usage);
    }
}
