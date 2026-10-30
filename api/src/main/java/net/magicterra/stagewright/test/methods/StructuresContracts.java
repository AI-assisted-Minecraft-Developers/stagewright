package net.magicterra.stagewright.test.methods;

import java.util.List;
import java.util.Optional;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
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

/** {@code mc.test.structures}: worldgen structures, whether one is registered, covers a block, or lies nearby. */
public final class StructuresContracts {
    private StructuresContracts() {}

    @Tag("op")
    @Named("TestStructuresParams")
    public sealed interface StructuresOp permits Registered, At, Locate, ListStructures {}

    @Variant("list")
    public record ListStructures(
            @Doc("Only the structures of this namespace, e.g. minecraft. Omitted: every structure.")
            Optional<@Length(min = 1, max = 64) String> namespace)
            implements StructuresOp {}

    @Variant("registered")
    public record Registered(
            @Doc("Structure id, e.g. minecraft:village_plains.") @ResourceId
            String id) implements StructuresOp {}

    @Variant("at")
    public record At(
            BlockPosition pos,
            @Doc("Omitted: minecraft:overworld.") Optional<@ResourceId String> dimension) implements StructuresOp {}

    @Variant("locate")
    public record Locate(
            @ResourceId String id,
            @Doc("Where the search starts.") BlockPosition pos,

            @Doc("How far to search, in chunks. Explicit because the right value depends on the structure's spacing.")
            @Range(min = 1, max = 200)
            int chunkRadius,

            @Doc("Omitted: minecraft:overworld.") Optional<@ResourceId String> dimension)
            implements StructuresOp {}

    @Tag("op")
    @Named("TestStructuresReply")
    public sealed interface StructuresReply permits IsRegistered, Covering, Located, StructureList {}

    @Variant("list")
    public record StructureList(
            @Doc("Structures registered (in the namespace, when one was named), even when the list below is cut.")
            @Range(min = 0)
            int count,

            @Doc("Their ids, sorted; the first 100000.") @Length(max = 100000)
            List<@ResourceId String> structures)
            implements StructuresReply {}

    @Variant("registered")
    public record IsRegistered(boolean registered) implements StructuresReply {}

    @Variant("at")
    public record Covering(
            @Doc("Structures with a generated piece containing the block, sorted. Only loaded chunks are asked, "
                    + "so empty can mean not generated yet.")
            @Length(max = 1024)
            List<@ResourceId String> structures)
            implements StructuresReply {}

    @Variant("locate")
    public record Located(
            @Doc("False when none lies within chunkRadius.") boolean found,
            Optional<BlockPosition> pos,

            @Doc("Wall-clock milliseconds the search took, found or not: it generates chunks and spends server ticks.")
            @Range(min = 0)
            long ms)
            implements StructuresReply {}

    public static final UnionType<StructuresOp> STRUCTURES_PARAMS = ApiTypes.union(StructuresOp.class);

    public static final UnionType<StructuresReply> STRUCTURES_REPLY = ApiTypes.union(StructuresReply.class);

    public static final MethodSpec<StructuresOp, StructuresReply> STRUCTURES = MethodSpec.of(
                    "mc.test.structures",
                    STRUCTURES_PARAMS,
                    STRUCTURES_REPLY,
                    "registered is a registry lookup; list names every structure registered; at asks the structure manager which generated structures cover a "
                            + "block, without generating anything; locate searches for the nearest one and can be "
                            + "slow, so it reports its milliseconds and answers found false rather than failing "
                            + "when nothing is near.")
            .hidden()
            .effects(MethodSpec.Effect.WORLD_WRITE);
}
