package net.magicterra.stagewright.mixin;

import java.util.function.BooleanSupplier;
import net.minecraft.Util;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets {@code MinecraftServer.stopServer} finish a stop with chunks still generating.
 *
 * <p>The stop drains the chunk maps in a loop: set the next tick a millisecond away, tick every level's chunks with an
 * always-true deadline, then {@code waitUntilNextTick}, which runs the main thread's queued chunk tasks only while
 * there is time before that next tick. A generation cancelled after its last step was queued for the main thread keeps
 * its claim on its chunks until that step runs, and the unload of a claimed chunk sends itself back to the queue it is
 * polled from. So the chunk tick never returns, and even a chunk tick that did return would have used up the
 * millisecond: either way the step never runs, and the server spins until it is forced down. That happened in 12 runs
 * of 17 on a third-party server whose bots joined and left while the chunks around them loaded.
 *
 * <p>Two changes are needed, and either one alone still hangs. The chunk tick gets a deadline, so it returns. The wait
 * then gets time of its own, measured from when it starts, so the queued steps run, the claims are released and the
 * unloads finish.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerStopMixin {

    /** How long one level's chunk tick of the stop may take, in nanoseconds. */
    private static final long STAGEWRIGHT$CHUNK_TICK_NANOS = 2_000_000L;

    /** How long each round of the stop leaves the main thread's queued tasks, in nanoseconds. */
    private static final long STAGEWRIGHT$TASK_NANOS = 10_000_000L;

    @Shadow
    private long nextTickTimeNanos;

    @ModifyArg(
            method = "stopServer",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/server/level/ServerChunkCache;tick(Ljava/util/function/BooleanSupplier;Z)V"),
            index = 0)
    @SuppressWarnings({"PMD.UnusedPrivateMethod", "PMD.UnusedFormalParameter"}) // Mixin calls it; vanilla's is replaced
    private BooleanSupplier stagewright$deadline(BooleanSupplier always) {
        long until = Util.getNanos() + STAGEWRIGHT$CHUNK_TICK_NANOS;
        return () -> Util.getNanos() < until;
    }

    @Inject(
            method = "stopServer",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;waitUntilNextTick()V"))
    @SuppressWarnings({"PMD.UnusedPrivateMethod", "PMD.UnusedFormalParameter"}) // Mixin calls it
    private void stagewright$timeForTasks(CallbackInfo ci) {
        nextTickTimeNanos = Util.getNanos() + STAGEWRIGHT$TASK_NANOS;
    }
}
