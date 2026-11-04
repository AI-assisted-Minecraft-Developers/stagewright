package net.magicterra.stagewright.mixin.client;

import java.util.Deque;
import java.util.List;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The toasts a client shows and queues, for {@code mc.test.toasts}. */
@Mixin(ToastComponent.class)
public interface ToastComponentAccessor {

    /**
     * Shown now, as {@code ToastComponent.ToastInstance}s: a class private to its package, so the list's elements are
     * read through {@link ToastInstanceAccessor}.
     */
    @Accessor("visible")
    List<?> stagewright$visible();

    /** Waiting for a free slot. */
    @Accessor("queued")
    Deque<Toast> stagewright$queued();
}
