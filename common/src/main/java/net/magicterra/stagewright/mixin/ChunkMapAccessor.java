package net.magicterra.stagewright.mixin;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The chunk holders of a level, which the run reads before it stops the server ({@code JupiterRun}). */
@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {

    /** Every chunk holder the level has, loaded or loading. */
    @Invoker("getChunks")
    Iterable<ChunkHolder> stagewright$chunks();
}
