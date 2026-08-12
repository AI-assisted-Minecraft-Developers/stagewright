package net.magicterra.stagewright.scene;

import net.magicterra.stagewright.contract.SceneFailure;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

/**
 * Loot tables — rolled, counted, and checked for existence.
 *
 * <p>Reached as {@link SceneContext#loot()}. The subject is a pack's data rather than its code: a
 * structure whose loot table was renamed, a table that references an item some mod stopped
 * registering, a dungeon chest that can only ever come up empty. None of those throw at load time
 * and none of them show up in a log.
 *
 * <h2>Rolling takes a count, and that is not a convenience</h2>
 *
 * {@link #roll} has no single-roll form. A loot table with a {@code 0-2 rolls} pool legitimately
 * produces nothing at all, so an assertion built on one roll is a test that goes red on a dice
 * throw — and gets "fixed" by being deleted. Requiring a count forces the author to decide which
 * question they are asking:
 *
 * <pre>{@code
 * // existence: does this table EVER give the thing progression depends on?
 * s.expect(s.loot().rollCounts("minecraft:chests/simple_dungeon", 200))
 *     .as("what a dungeon chest can contain").isNotEmpty();
 *
 * // distribution: is it common enough to plan around?
 * var counts = s.loot().rollCounts("minecraft:chests/simple_dungeon", 500);
 * s.record("saddles.per500", counts.getOrDefault("minecraft:saddle", 0));
 * }</pre>
 *
 * <h2>Which loot tables this can roll</h2>
 *
 * Chest-shaped ones — the tables attached to structures, which is what both progression and pack
 * auditing care about. They need only an origin, which this facet supplies from the scene's own.
 * A table wanting a killer, a tool or a block state belongs to a different context set and is
 * refused with a message naming what it wanted, rather than rolled with made-up parameters that
 * would silently change its output.
 */
public final class Loot {

    private final SceneContext ctx;

    Loot(SceneContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Whether this loot table is registered and is not the empty stand-in.
     *
     * <p>The second half matters: {@code getLootTable} answers {@link LootTable#EMPTY} for an id
     * nothing registered rather than null, so a missing table otherwise reads as "a table that
     * happens to drop nothing" — which is a legal thing for a table to be, and therefore
     * indistinguishable from the bug.
     */
    public boolean exists(String tableId) {
        return table(tableId) != LootTable.EMPTY;
    }

    /**
     * Roll a table {@code times} and return every item id produced, in order, with duplicates.
     *
     * <p>Order and duplicates are kept because a scene that wants a set can build one and a scene
     * that wants a distribution cannot recover it from a set.
     */
    public List<String> roll(String tableId, int times) {
        LootTable table = require(tableId);
        LootParams params = chestParams();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            for (ItemStack stack : items(table, params, tableId)) {
                if (!stack.isEmpty()) out.add(Ids.idOf(stack));
            }
        }
        return out;
    }

    /** Roll a table {@code times} and count how many of each item id came out. */
    public Map<String, Integer> rollCounts(String tableId, int times) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String id : roll(tableId, times)) counts.merge(id, 1, Integer::sum);
        return counts;
    }

    /** Roll a table {@code times} and count total ITEMS (stack sizes summed), not stacks — the
     *  number that answers "how much of this does a run of the dungeon actually yield". */
    public Map<String, Integer> rollTotals(String tableId, int times) {
        LootTable table = require(tableId);
        LootParams params = chestParams();
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (int i = 0; i < times; i++) {
            for (ItemStack stack : items(table, params, tableId)) {
                if (stack.isEmpty()) continue;
                totals.merge(Ids.idOf(stack), stack.getCount(), Integer::sum);
            }
        }
        return totals;
    }

    /** Every distinct item id a table produced across {@code times} rolls — the "can this table ever
     *  give X" question, which needs enough rolls to be believed. */
    public List<String> distinct(String tableId, int times) {
        List<String> out = new ArrayList<>();
        for (String id : roll(tableId, times)) {
            if (!out.contains(id)) out.add(id);
        }
        return out;
    }

    /** Every loot table id this run loaded, sorted. The raw material for a pack-wide audit. */
    public List<String> all() {
        List<String> out = new ArrayList<>();
        ctx.server().reloadableRegistries().get().registryOrThrow(Registries.LOOT_TABLE)
                .keySet().forEach(id -> out.add(id.toString()));
        out.sort(String::compareTo);
        return out;
    }

    // ---- internals ----

    private LootTable table(String tableId) {
        ResourceLocation id = Ids.location(tableId, "loot table");
        return ctx.server().reloadableRegistries()
                .getLootTable(ResourceKey.create(Registries.LOOT_TABLE, id));
    }

    private LootTable require(String tableId) {
        LootTable table = table(tableId);
        if (table == LootTable.EMPTY) {
            throw new SceneFailure("no loot table is registered as '" + tableId + "' — the lookup"
                    + " answered the EMPTY stand-in, which is what a missing table looks like."
                    + " This run loaded " + all().size() + " tables.");
        }
        return table;
    }

    /** A chest-shaped context at the scene's origin: the only parameter such a table needs. */
    private LootParams chestParams() {
        return new LootParams.Builder(ctx.level())
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(ctx.origin()))
                .create(LootContextParamSets.CHEST);
    }

    /**
     * One roll, refusing a table whose context set is not the one these params carry.
     *
     * <p><b>Checked here rather than left to the game, because the game does not check.</b> The
     * first version of this facet assumed {@code getRandomItems} would throw on a parameter-set
     * mismatch and translated that exception into a sentence. It does not throw: rolling
     * {@code minecraft:entities/zombie} with chest parameters returns an EMPTY LIST — the table's
     * conditions simply cannot evaluate, so nothing is produced. A scene doing that would conclude
     * "this mob drops nothing", which is a wrong answer that looks exactly like a right one, and is
     * precisely the silent failure this whole facet set is built to refuse.
     *
     * <p>So the set is compared up front and the refusal is ours. Filling in a killer, a tool or a
     * block state with placeholders would be worse than refusing: it would produce plausible loot
     * for a context the scene never described.
     */
    private List<ItemStack> items(LootTable table, LootParams params, String tableId) {
        if (table.getParamSet() != LootContextParamSets.CHEST) {
            throw new SceneFailure("'" + tableId + "' is not a chest-shaped loot table — it declares"
                    + " a different context set, so rolling it needs a killer, a tool or a block"
                    + " state that this facet does not invent. Note the game would NOT have told"
                    + " you: rolling it with chest parameters returns an empty list, which reads as"
                    + " 'this table drops nothing'.");
        }
        return table.getRandomItems(params);
    }
}
