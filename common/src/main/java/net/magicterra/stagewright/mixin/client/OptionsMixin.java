package net.magicterra.stagewright.mixin.client;

import java.io.File;

import net.magicterra.stagewright.client.RunOptions;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps a scene run's in-memory settings out of the player's {@code options.txt}; see {@link RunOptions}. */
@Mixin(Options.class)
public abstract class OptionsMixin {

    @Shadow
    @Final
    private File optionsFile;

    /**
     * As the file is read — before the narrator, the window and the first screen are built from it.
     * At every return, not the tail: with no options.txt, a fresh game directory, {@code load} returns
     * early, and a tail injection never runs.
     */
    @Inject(method = "load", at = @At("RETURN"))
    private void stagewright$applyRunSettings(CallbackInfo ci) {
        RunOptions.apply((Options) (Object) this, optionsFile);
    }

    @Inject(method = "save", at = @At("RETURN"))
    private void stagewright$restorePlayersSettings(CallbackInfo ci) {
        RunOptions.restoreFile(optionsFile);
    }
}
