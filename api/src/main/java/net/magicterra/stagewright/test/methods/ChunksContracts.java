package net.magicterra.stagewright.test.methods;

import java.util.Optional;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.ResourceId;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.MethodSpec;
import net.magicterra.worlddriver.api.schema.UnionType;
import net.magicterra.worlddriver.api.schema.annotation.Default;
import net.magicterra.worlddriver.api.schema.annotation.Doc;
import net.magicterra.worlddriver.api.schema.annotation.Length;
import net.magicterra.worlddriver.api.schema.annotation.Named;
import net.magicterra.worlddriver.api.schema.annotation.Range;
import net.magicterra.worlddriver.api.schema.annotation.Tag;
import net.magicterra.worlddriver.api.schema.annotation.Variant;

/** {@code mc.test.chunks}: take a chunk out of memory and bring it back, as a player walking away and back would. */
public final class ChunksContracts {
    private ChunksContracts() {}

    /** The requests {@code mc.test.chunks} takes, told apart by {@code op}. */
    @Tag("op")
    @Named("TestChunksParams")
    public sealed interface ChunksOp permits Unload, Reload {}

    /** Drops a chunk from memory and waits until it and its entities are gone. */
    @Variant("unload")
    public record Unload(
            @Doc("Any block of the chunk.") BlockPosition pos,
            @Doc("Omitted: minecraft:overworld.") Optional<@ResourceId String> dimension,

            @Doc("Server ticks to wait for the chunk and its entities to leave memory.")
            @Default("200")
            @Range(min = 1, max = 6000)
            int withinTicks)
            implements ChunksOp {}

    /** Forces a chunk loaded and waits until its entities are read back. */
    @Variant("reload")
    public record Reload(
            @Doc("Any block of the chunk.") BlockPosition pos,
            @Doc("Omitted: minecraft:overworld.") Optional<@ResourceId String> dimension,

            @Doc("Server ticks to wait for the chunk and its entities to be read back.")
            @Default("200")
            @Range(min = 1, max = 6000)
            int withinTicks)
            implements ChunksOp {}

    /** The answers {@code mc.test.chunks} gives, one per {@link ChunksOp} variant. */
    @Tag("op")
    @Named("TestChunksReply")
    public sealed interface ChunksReply permits Unloaded, Reloaded {}

    /** Whether the chunk left memory, how long that took, and what held it when it did not. */
    @Variant("unload")
    public record Unloaded(
            int chunkX,
            int chunkZ,

            @Doc("False when withinTicks ran out with the chunk still in memory.")
            boolean done,

            @Doc("Server ticks waited.") @Range(min = 0) int ticks,
            @Doc("When not done, what still held it.") Optional<@Length(max = 256) String> heldBy)
            implements ChunksReply {}

    /** Whether the chunk and its entities are back, how long that took, and how many entities it holds. */
    @Variant("reload")
    public record Reloaded(
            int chunkX,
            int chunkZ,

            @Doc("False when withinTicks ran out before the chunk and its entities were back.")
            boolean done,

            @Doc("Server ticks waited.") @Range(min = 0) int ticks,

            @Doc("Entities in the chunk once done; 0 when not.") @Range(min = 0)
            int entities)
            implements ChunksReply {}

    public static final UnionType<ChunksOp> CHUNKS_PARAMS = ApiTypes.union(ChunksOp.class);

    public static final UnionType<ChunksReply> CHUNKS_REPLY = ApiTypes.union(ChunksReply.class);

    public static final MethodSpec<ChunksOp, ChunksReply> CHUNKS = MethodSpec.of(
                    "mc.test.chunks",
                    CHUNKS_PARAMS,
                    CHUNKS_REPLY,
                    "unload drops the chunk's forced loading and waits until neither it nor its entities are in memory, "
                            + "saved as the game saves a chunk it unloads; reload forces it loaded and waits until its "
                            + "entities are back. done false says the wait ran out, and for unload what held the chunk.")
            .hidden()
            .effects(MethodSpec.Effect.WORLD_WRITE)
            .mayWait();
}
