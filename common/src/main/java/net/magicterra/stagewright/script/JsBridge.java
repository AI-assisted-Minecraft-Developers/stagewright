package net.magicterra.stagewright.script;

import net.magicterra.stagewright.StageWrightCommon;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * The handful of things a scene written in JavaScript cannot reach through plain Rhino interop.
 *
 * <p>Everything else a scene body touches — {@code expect}, {@code await}, {@code setBlock},
 * {@code player}, {@code perf} — is the same {@link net.magicterra.stagewright.scene.SceneContext}
 * a Java scene uses, called directly. That is the whole design: the JS surface is a different way to
 * WRITE a scene, not a different framework to run one, so it must not accumulate a parallel API.
 * Anything added here should be something JS genuinely cannot express, not something that would
 * merely read a little nicer.
 */
public final class JsBridge {

    private final String sceneFile;

    JsBridge(String sceneFile) {
        this.sceneFile = sceneFile;
    }

    /**
     * Resolve a block by registry id.
     *
     * <p>Loud on a miss, because the quiet alternative is worse: {@code BuiltInRegistries.BLOCK.get}
     * answers AIR for an unknown id, so a typo would place nothing, assert nothing, and pass. A pack
     * author working from a wiki page gets ids wrong regularly — that has to be an error at the line
     * that wrote it, not a green run.
     */
    public Block block(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null) {
            throw new IllegalArgumentException("'" + id + "' is not a block id (expected namespace:path)");
        }
        Block resolved = BuiltInRegistries.BLOCK.get(key);
        if (resolved == Blocks.AIR && !key.equals(BuiltInRegistries.BLOCK.getKey(Blocks.AIR))) {
            throw new IllegalArgumentException("no block registered as '" + id
                    + "' — check the mod is loaded and the id is spelled as the registry has it");
        }
        return resolved;
    }

    /** Write to the server log, tagged with the file the scene came from. */
    public void log(String message) {
        StageWrightCommon.LOG.info("[{}] {}: {}", StageWrightCommon.MOD_ID, sceneFile, message);
    }
}
