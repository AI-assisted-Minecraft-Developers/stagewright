package net.magicterra.stagewright.mixin.client;

import java.util.List;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A system toast's text, for {@code mc.test.toasts}. */
@Mixin(SystemToast.class)
public interface SystemToastAccessor {

    /** Its title. */
    @Accessor("title")
    Component stagewright$title();

    /** Its message, as wrapped to the toast's width. */
    @Accessor("messageLines")
    List<FormattedCharSequence> stagewright$messageLines();
}
