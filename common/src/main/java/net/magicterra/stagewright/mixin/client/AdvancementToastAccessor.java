package net.magicterra.stagewright.mixin.client;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.client.gui.components.toasts.AdvancementToast;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The advancement an advancement toast announces, for {@code mc.test.toasts}. */
@Mixin(AdvancementToast.class)
public interface AdvancementToastAccessor {

    /** The advancement. */
    @Accessor("advancement")
    AdvancementHolder stagewright$advancement();
}
