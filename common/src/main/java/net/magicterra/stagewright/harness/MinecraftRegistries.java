package net.magicterra.stagewright.harness;

import net.magicterra.stagewright.harness.remote.Marshal;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

/**
 * The registries a runner can name things in: blocks and items, the two a scene writes as
 * {@code block('minecraft:stone')}. An id that names nothing fails here, loudly, for the reason
 * {@code JsBridge.block} fails loudly in process: a registry answers its default for an unknown id,
 * and a typo would place nothing, assert nothing and pass.
 */
final class MinecraftRegistries implements Marshal.Registries {
    static final String BLOCK = "block";
    static final String ITEM = "item";

    @Override
    public String[] nameOf(Object o) {
        if (o instanceof Block b) return new String[] {BLOCK, BuiltInRegistries.BLOCK.getKey(b).toString()};
        if (o instanceof Item i) return new String[] {ITEM, BuiltInRegistries.ITEM.getKey(i).toString()};
        return null;
    }

    @Override
    public Object find(String kind, String id) {
        return switch (kind) {
            case BLOCK -> lookup(BuiltInRegistries.BLOCK, id, "block");
            case ITEM -> lookup(BuiltInRegistries.ITEM, id, "item");
            default -> throw new IllegalArgumentException("'" + kind + "' is not a kind of registry entry this game"
                    + " can look up (block, item)");
        };
    }

    private static <T> T lookup(Registry<T> registry, String id, String what) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null) {
            throw new IllegalArgumentException("'" + id + "' is not a " + what + " id (expected namespace:path)");
        }
        if (!registry.containsKey(key)) {
            throw new IllegalArgumentException("no " + what + " registered as '" + id
                    + "' — check the mod is loaded and the id is spelled as the registry has it");
        }
        return registry.get(key);
    }
}
