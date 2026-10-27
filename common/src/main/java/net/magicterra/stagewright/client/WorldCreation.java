package net.magicterra.stagewright.client;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.function.Function;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Difficulty;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.DataPackConfig;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPreset;

/**
 * How a client creates the singleplayer world an integrated-server topology runs on: from the creation keys of
 * {@code server.properties} in its game directory, read as a dedicated server reads them, so a scene's declared world
 * is the same world on both. The test engine writes those keys there before each scene; a key not written keeps the
 * default a dedicated server boots with.
 *
 * <p>Survival, easy, cheats on is that default, and deliberately not the quieter creative/peaceful pair a sandbox
 * would pick. The point of running on an integrated server as well as a dedicated one is that the two agree; under
 * creative/peaceful no hostile mob spawns and the player takes no damage, so every scene about being attacked timed
 * out and looked like a bot bug instead of reporting that the world was wrong.
 */
record WorldCreation(LevelSettings settings, WorldOptions options, Function<RegistryAccess, WorldDimensions> dimensions) {

    /**
     * The seed when none is declared, fixed so a one-off failure stays reproducible. Matched by
     * {@code RunDirectory.FIXED_SEED}, which pins the same value into a dedicated server's {@code server.properties}:
     * a scene that declares {@code Terrain.GENERATED} asserts about ground this seed decides, so the two disagreeing
     * would hand it a different landscape per topology.
     */
    static final long DEFAULT_SEED = 5471L;

    static WorldCreation of(String levelName, Path gameDirectory) {
        Properties p = read(gameDirectory.resolve("server.properties"));
        GameType mode = GameType.byName(p.getProperty("gamemode", "survival"), GameType.SURVIVAL);
        Difficulty difficulty = Difficulty.byName(p.getProperty("difficulty", "easy"));
        if (difficulty == null) difficulty = Difficulty.EASY;
        DataPackConfig packs = new DataPackConfig(list(p.getProperty("initial-enabled-packs", "vanilla")),
                list(p.getProperty("initial-disabled-packs", "")));
        LevelSettings settings = new LevelSettings(levelName, mode, false, difficulty, true, new GameRules(),
                new WorldDataConfiguration(packs, FeatureFlags.DEFAULT_FLAGS));
        String seed = p.getProperty("level-seed", "");
        WorldOptions options = new WorldOptions(seed.isBlank() ? DEFAULT_SEED : WorldOptions.parseSeed(seed)
                .orElse(WorldOptions.randomSeed()), Boolean.parseBoolean(p.getProperty("generate-structures", "true")),
                false);
        ResourceKey<WorldPreset> preset = ResourceKey.create(Registries.WORLD_PRESET,
                ResourceLocation.parse(presetId(p.getProperty("level-type", "minecraft:normal"))));
        return new WorldCreation(settings, options,
                registries -> registries.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(preset).value()
                        .createWorldDimensions());
    }

    /** {@code level-type} as a dedicated server reads it: the pre-1.19 names map to today's presets. */
    private static String presetId(String type) {
        String id = type.trim().toLowerCase(Locale.ROOT);
        return switch (id) {
            case "default" -> "minecraft:normal";
            case "largebiomes" -> "minecraft:large_biomes";
            default -> id;
        };
    }

    private static List<String> list(String commaSeparated) {
        return Arrays.stream(commaSeparated.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static Properties read(Path file) {
        Properties properties = new Properties();
        if (!Files.isRegularFile(file)) return properties;
        try (Reader in = Files.newBufferedReader(file)) {
            properties.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
        return properties;
    }
}
