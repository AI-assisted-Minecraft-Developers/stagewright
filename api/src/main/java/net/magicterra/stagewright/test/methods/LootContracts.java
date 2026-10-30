package net.magicterra.stagewright.test.methods;

import java.util.List;
import java.util.Optional;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.ResourceId;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.MethodSpec;
import net.magicterra.worlddriver.api.schema.UnionType;
import net.magicterra.worlddriver.api.schema.annotation.Doc;
import net.magicterra.worlddriver.api.schema.annotation.Length;
import net.magicterra.worlddriver.api.schema.annotation.Named;
import net.magicterra.worlddriver.api.schema.annotation.Range;
import net.magicterra.worlddriver.api.schema.annotation.Tag;
import net.magicterra.worlddriver.api.schema.annotation.Variant;

/** {@code mc.test.loot}: whether a loot table is registered, and what it gives over many rolls. */
public final class LootContracts {
    private LootContracts() {}

    /** The requests {@code mc.test.loot} takes, told apart by {@code op}. */
    @Tag("op")
    @Named("TestLootParams")
    public sealed interface LootOp permits Exists, Roll, ListTables {}

    /** Names every loot table registered, or those of one namespace. */
    @Variant("list")
    public record ListTables(
            @Doc("Only the tables of this namespace, e.g. minecraft. Omitted: every table.")
            Optional<@Length(min = 1, max = 64) String> namespace)
            implements LootOp {}

    /** Asks whether one loot table is registered. */
    @Variant("exists")
    public record Exists(
            @Doc("Loot table id, e.g. minecraft:chests/simple_dungeon.") @ResourceId
            String table) implements LootOp {}

    /** There is no single-roll form: a pool of 0-2 rolls legitimately gives nothing, so one roll proves nothing. */
    @Variant("roll")
    public record Roll(
            @Doc("A chest-shaped table; one needing a killer, a tool or a block state is invalid_params.") @ResourceId
            String table,

            @Doc("How many times to roll it.") @Range(min = 1, max = 10000)
            int times)
            implements LootOp {}

    /** One item id and how much of it came out. */
    @Named("TestLootItemCount")
    public record ItemCount(
            @ResourceId String item,
            @Doc("Stacks of it produced.") @Range(min = 0) int stacks,

            @Doc("Items of it produced, stack sizes summed.") @Range(min = 0)
            long total) {}

    /** The answers {@code mc.test.loot} gives, one per {@link LootOp} variant. */
    @Tag("op")
    @Named("TestLootReply")
    public sealed interface LootReply permits Existence, Rolled, TableList {}

    /** The loot table ids registered, or those of one namespace. */
    @Variant("list")
    public record TableList(
            @Doc("Tables registered (in the namespace, when one was named), even when the list below is cut.")
            @Range(min = 0)
            int count,

            @Doc("Their ids, sorted; the first 100000.") @Length(max = 100000)
            List<@ResourceId String> tables)
            implements LootReply {}

    /** Whether a loot table is registered. */
    @Variant("exists")
    public record Existence(
            @Doc("False for an unregistered id, which the game answers with an empty stand-in table rather than null.")
            boolean exists)
            implements LootReply {}

    /** What rolling a table gave: how many rolls came up empty, and what the rest produced. */
    @Variant("roll")
    public record Rolled(
            @Doc("Rolls made.") @Range(min = 1) int times,

            @Doc("Rolls that produced no item at all.") @Range(min = 0)
            int emptyRolls,

            @Doc("Per item id, in first-seen order.") @Length(max = 4096)
            List<ItemCount> items)
            implements LootReply {}

    public static final UnionType<LootOp> LOOT_PARAMS = ApiTypes.union(LootOp.class);

    public static final UnionType<LootReply> LOOT_REPLY = ApiTypes.union(LootReply.class);

    public static final MethodSpec<LootOp, LootReply> LOOT = MethodSpec.of(
                    "mc.test.loot",
                    LOOT_PARAMS,
                    LOOT_REPLY,
                    "exists says whether a loot table is registered; list names every one, or a namespace's; roll rolls a chest-shaped table a counted number of times "
                            + "at the overworld origin and counts what came out. An unknown or non-chest table is "
                            + "invalid_params.")
            .hidden();
}
