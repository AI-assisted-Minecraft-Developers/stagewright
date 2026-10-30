package net.magicterra.stagewright.test.methods;

import java.util.List;
import java.util.Optional;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.ResourceId;
import net.magicterra.worlddriver.api.methods.shared.Responses.BotRef;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.MethodSpec;
import net.magicterra.worlddriver.api.schema.UnionType;
import net.magicterra.worlddriver.api.schema.annotation.Doc;
import net.magicterra.worlddriver.api.schema.annotation.Length;
import net.magicterra.worlddriver.api.schema.annotation.Named;
import net.magicterra.worlddriver.api.schema.annotation.Range;
import net.magicterra.worlddriver.api.schema.annotation.Tag;
import net.magicterra.worlddriver.api.schema.annotation.Variant;

/** {@code mc.test.advancements}: a server player's advancement progress, read, granted and revoked. */
public final class AdvancementsContracts {

    public static final UnionType<AdvancementsOp> ADVANCEMENTS_PARAMS = ApiTypes.union(AdvancementsOp.class);

    public static final UnionType<AdvancementsReply> ADVANCEMENTS_REPLY = ApiTypes.union(AdvancementsReply.class);

    public static final MethodSpec<AdvancementsOp, AdvancementsReply> ADVANCEMENTS = MethodSpec.of(
                    "mc.test.advancements",
                    ADVANCEMENTS_PARAMS,
                    ADVANCEMENTS_REPLY,
                    "grant awards every unmet criterion of an advancement to a server player, revoke takes every met "
                            + "one back, progress reads where it stands, list names every advancement loaded. Progress is saved with the player, so a "
                            + "test that grants should revoke what the grant reported. An unknown player is "
                            + "invalid_params at /bot, an unknown advancement at /id.")
            .hidden()
            .effects(MethodSpec.Effect.WORLD_WRITE);
    private AdvancementsContracts() {}

    /** The requests {@code mc.test.advancements} takes, told apart by {@code op}. */
    @Tag("op")
    @Named("TestAdvancementsParams")
    public sealed interface AdvancementsOp permits Grant, Revoke, Progress, ListAdvancements {}

    /** Names every advancement loaded, or those of one namespace. */
    @Variant("list")
    public record ListAdvancements(
            @Doc("Only the advancements of this namespace, e.g. minecraft. Omitted: every advancement.")
            Optional<@Length(min = 1, max = 64) String> namespace)
            implements AdvancementsOp {}

    /** Awards every unmet criterion of an advancement to a server player. */
    @Variant("grant")
    public record Grant(
            @Doc("A server player, e.g. player:steve.") @BotRef
            String bot,

            @Doc("Advancement id.") @ResourceId String id)
            implements AdvancementsOp {}

    /** Takes every met criterion of an advancement back from a server player. */
    @Variant("revoke")
    public record Revoke(
            @Doc("A server player, e.g. player:steve.") @BotRef
            String bot,

            @Doc("Advancement id.") @ResourceId String id)
            implements AdvancementsOp {}

    /** Reads how far a server player has got through an advancement. */
    @Variant("progress")
    public record Progress(
            @Doc("A server player, e.g. player:steve.") @BotRef
            String bot,

            @Doc("Advancement id.") @ResourceId String id)
            implements AdvancementsOp {}

    /** The answers {@code mc.test.advancements} gives, one per {@link AdvancementsOp} variant. */
    @Tag("op")
    @Named("TestAdvancementsReply")
    public sealed interface AdvancementsReply permits Granted, Revoked, Progressed, AdvancementList {}

    /** The advancement ids loaded, or those of one namespace. */
    @Variant("list")
    public record AdvancementList(
            @Doc("Advancements loaded (in the namespace, when one was named), even when the list below is cut.")
            @Range(min = 0)
            int count,

            @Doc("Their ids, sorted; the first 100000.") @Length(max = 100_000)
            List<@ResourceId String> advancements)
            implements AdvancementsReply {}

    /** What a grant changed: whether the advancement is now complete, and the criteria it awarded. */
    @Variant("grant")
    public record Granted(
            @Doc("Whether the advancement is now complete.") boolean done,

            @Doc("Exactly the criteria this call awarded; revoke these to undo it without taking back earlier ones.")
            @Length(max = 1024)
            List<@Length(max = 256) String> awarded)
            implements AdvancementsReply {}

    /** What a revoke took back: whether the advancement is still complete, and the criteria removed. */
    @Variant("revoke")
    public record Revoked(
            @Doc("Whether the advancement is still complete; false after a revoke.")
            boolean done,

            @Doc("The criteria taken back.") @Length(max = 1024)
            List<@Length(max = 256) String> revoked)
            implements AdvancementsReply {}

    /** Where a player stands on an advancement: complete, and which criteria are done and which remain. */
    @Variant("progress")
    public record Progressed(
            boolean done,
            @Length(max = 1024) List<@Length(max = 256) String> completed,
            @Length(max = 1024) List<@Length(max = 256) String> remaining)
            implements AdvancementsReply {}
}
