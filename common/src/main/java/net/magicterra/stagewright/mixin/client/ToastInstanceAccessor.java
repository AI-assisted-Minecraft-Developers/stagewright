package net.magicterra.stagewright.mixin.client;

import net.minecraft.client.gui.components.toasts.Toast;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A shown toast's slot, private to its package, so named by its binary name. */
@Mixin(targets = "net.minecraft.client.gui.components.toasts.ToastComponent$ToastInstance")
public interface ToastInstanceAccessor {

    /** The toast in the slot. */
    @Accessor("toast")
    Toast stagewright$toast();
}
