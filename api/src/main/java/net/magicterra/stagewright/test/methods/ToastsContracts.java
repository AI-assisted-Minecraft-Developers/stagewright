package net.magicterra.stagewright.test.methods;

import java.util.List;
import java.util.Optional;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.MethodSpec;
import net.magicterra.worlddriver.api.schema.UnionType;
import net.magicterra.worlddriver.api.schema.annotation.Doc;
import net.magicterra.worlddriver.api.schema.annotation.Length;
import net.magicterra.worlddriver.api.schema.annotation.Named;
import net.magicterra.worlddriver.api.schema.annotation.Range;
import net.magicterra.worlddriver.api.schema.annotation.Tag;
import net.magicterra.worlddriver.api.schema.annotation.Variant;

/** {@code mc.test.toasts}: the toasts a client shows in its corner, and the ones waiting for a free slot. */
public final class ToastsContracts {

    public static final UnionType<ToastsOp> TOASTS_PARAMS = ApiTypes.union(ToastsOp.class);

    public static final UnionType<ToastsReply> TOASTS_REPLY = ApiTypes.union(ToastsReply.class);

    public static final MethodSpec<ToastsOp, ToastsReply> TOASTS = MethodSpec.of(
                    "mc.test.toasts",
                    TOASTS_PARAMS,
                    TOASTS_REPLY,
                    "list reads the toasts this client shows and the ones queued behind them, read on the client "
                            + "thread. Client only.")
            .hidden()
            .mayWait();

    private ToastsContracts() {}

    /** The requests {@code mc.test.toasts} takes, told apart by {@code op}. */
    @Tag("op")
    @Named("TestToastsParams")
    public sealed interface ToastsOp permits ListToasts {}

    /** Asks for every toast shown and queued. */
    @Variant("list")
    public record ListToasts() implements ToastsOp {}

    /** One toast, as far as its type lets it be read. */
    @Named("TestToast")
    public record Toast(
            @Doc("The toast's class, e.g. net.minecraft.client.gui.components.toasts.SystemToast; a mod's own toast "
                    + "is its own class.")
            @Length(max = 256)
            String type,

            @Doc("Its title as plain text; absent for a type whose title cannot be read: any but vanilla's system, "
                    + "tutorial and advancement toasts.")
            Optional<@Length(max = 4096) String> title,

            @Doc("Its message as plain text, one line per line it is drawn on; absent as the title is, or when it has "
                    + "none.")
            Optional<@Length(max = 4096) String> message) {}

    /** The answers {@code mc.test.toasts} gives, one per {@link ToastsOp} variant. */
    @Tag("op")
    @Named("TestToastsReply")
    public sealed interface ToastsReply permits ToastList {}

    /** What the client shows now, and what waits for a free slot, each oldest first. */
    @Variant("list")
    public record ToastList(
            @Doc("Shown now, oldest first, a toast still sliding out included.") @Length(max = 64)
            List<Toast> visible,

            @Doc("Waiting for a free slot, the next to show first.") @Length(max = 1024)
            List<Toast> queued,

            @Doc("Toasts left out of queued for its length.") @Range(min = 0)
            int queuedOmitted)
            implements ToastsReply {}
}
