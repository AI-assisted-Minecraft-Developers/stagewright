package net.magicterra.stagewright.mixin.client;

import net.minecraft.client.gui.components.toasts.TutorialToast;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A tutorial toast's text, for {@code mc.test.toasts}. */
@Mixin(TutorialToast.class)
public interface TutorialToastAccessor {

    /** Its title. */
    @Accessor("title")
    Component stagewright$title();

    /** Its message; null when it has none. */
    @Accessor("message")
    Component stagewright$message();
}
