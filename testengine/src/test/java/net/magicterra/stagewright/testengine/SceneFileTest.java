package net.magicterra.stagewright.testengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Parsing of the {@code world} block of a scene file, with no game involved. */
class SceneFileTest {

    @TempDir
    Path temp;

    private SceneFile load(String yaml) throws IOException {
        Path dir = Files.createDirectories(temp.resolve("scenes-" + System.nanoTime()));
        Path file = dir.resolve("stagewright/scenes/x.yaml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, yaml, StandardCharsets.UTF_8);
        try (URLClassLoader loader = new URLClassLoader(new URL[] {dir.toUri().toURL()}, null)) {
            return SceneFile.load("x", loader);
        }
    }

    private String refusal(String yaml) {
        return assertThrows(IllegalStateException.class, () -> load(yaml)).getMessage();
    }

    private void refuses(String worldYaml, String... mentions) {
        String message = refusal("topology: t\nworld:\n" + worldYaml);
        for (String m : mentions) assertTrue(message.contains(m), "expected '" + m + "' in: " + message);
    }

    @Test
    void noWorldBlockIsTheQuietWorld() throws IOException {
        SceneFile file = load("topology: t\n");
        assertEquals("t", file.topology());
        assertEquals(SceneFile.World.QUIET, file.world());
        assertFalse(file.world().createsWorld());
        String described = file.world().describe();
        assertTrue(described.contains("time=18000"), described);
        assertTrue(described.contains("(frozen)"), described);
        assertTrue(described.contains("weather=clear"), described);
        assertTrue(described.contains("mobSpawning=false"), described);
    }

    @Test
    void anEmptyWorldBlockIsTheQuietWorldToo() throws IOException {
        assertEquals(SceneFile.World.QUIET, load("topology: t\nworld: {}\n").world());
    }

    @Test
    void theDocumentedExampleIsParsedFieldByField() throws IOException {
        SceneFile.World w = load("""
                topology: dedicatedServerWithClient
                world:
                  preset: minecraft:flat
                  seed: 5471
                  structures: false
                  gamemode: survival
                  difficulty: easy
                  gamerules: { keepInventory: true, randomTickSpeed: 0 }
                  datapacks:
                    enable: [minecraft:trade_rebalance]
                    disable: []
                  time: { at: noon, frozen: true }
                  weather: { clear: false, thunder: false, frozen: true }
                  tickRate: 20
                  mobSpawning: false
                """).world();
        assertEquals("minecraft:flat", w.preset());
        assertEquals("5471", w.seed());
        assertEquals(Boolean.FALSE, w.structures());
        assertEquals("survival", w.gamemode());
        assertEquals("easy", w.difficulty());
        assertEquals(Map.of("keepInventory", true, "randomTickSpeed", 0), w.gamerules());
        assertEquals(List.of("minecraft:trade_rebalance"), w.enablePacks());
        assertEquals(List.of(), w.disablePacks());
        assertEquals(6000L, w.time());
        assertTrue(w.timeFrozen());
        assertTrue(w.raining());
        assertFalse(w.thundering());
        assertTrue(w.weatherFrozen());
        assertEquals(20.0f, w.tickRate());
        assertFalse(w.mobSpawning());
        assertTrue(w.createsWorld());

        assertEquals(Map.of("level-type", "minecraft:flat", "level-seed", "5471", "generate-structures", "false",
                "gamemode", "survival", "difficulty", "easy"), w.serverProperties());

        String json = w.runtimeJson();
        assertTrue(json.contains("\"keepInventory\":true"), json);
        assertTrue(json.contains("\"randomTickSpeed\":0"), json);
        assertTrue(json.contains("\"time\":6000"), json);
        assertTrue(json.contains("\"timeFrozen\":true"), json);
        assertTrue(json.contains("\"raining\":true"), json);
        assertTrue(json.contains("\"tickRate\":20.0"), json);
        assertTrue(json.contains("\"enablePacks\":[\"minecraft:trade_rebalance\"]"), json);
        assertTrue(json.contains("\"disablePacks\":[]"), json);
    }

    @Test
    void thunderIsRainToo() throws IOException {
        SceneFile.World w = load("topology: t\nworld:\n  weather: {thunder: true}\n").world();
        assertTrue(w.raining());
        assertTrue(w.thundering());
    }

    @Test
    void aTickOfTheDayIsTaken() throws IOException {
        assertEquals(1234L, load("topology: t\nworld:\n  time: {at: 1234}\n").world().time());
    }

    @Test
    void namedTimesMapToTheirTicks() throws IOException {
        assertEquals(1000L, load("topology: t\nworld:\n  time: {at: day}\n").world().time());
        assertEquals(13000L, load("topology: t\nworld:\n  time: {at: night}\n").world().time());
    }

    @Test
    void onlyRuntimeKeysDoNotCreateAWorld() throws IOException {
        SceneFile.World w = load("topology: t\nworld:\n  gamerules: { doFireTick: false }\n").world();
        assertFalse(w.createsWorld());
        assertTrue(w.serverProperties().isEmpty());
    }

    @Test
    void datapacksAloneCreateAWorld() throws IOException {
        assertTrue(load("topology: t\nworld:\n  datapacks: { disable: [a] }\n").world().createsWorld());
    }

    @Test
    void anUnknownKeyUnderWorldIsRefused() {
        refuses("  biome: somewhere\n", "biome");
    }

    @Test
    void anUnknownTopLevelKeyIsRefused() {
        String message = refusal("topology: t\nmods: []\n");
        assertTrue(message.contains("mods"), message);
    }

    @Test
    void anUnknownGamemodeIsRefused() {
        refuses("  gamemode: hardcore\n", "gamemode", "hardcore");
    }

    @Test
    void anUnknownDifficultyIsRefused() {
        refuses("  difficulty: insane\n", "difficulty", "insane");
    }

    @Test
    void aGameRuleThatIsNotABooleanOrANumberIsRefused() {
        refuses("  gamerules: { keepInventory: \"yes\" }\n", "keepInventory");
    }

    @Test
    void aTimeThatIsNeitherNamedNorANumberIsRefused() {
        refuses("  time: { at: dusk }\n", "time.at", "dusk");
    }

    @Test
    void structuresMustBeABoolean() {
        refuses("  structures: maybe\n", "structures");
    }

    @Test
    void tickRateMustBeANumber() {
        refuses("  tickRate: fast\n", "tickRate");
    }

    @Test
    void anUnknownKeyUnderDatapacksIsRefused() {
        refuses("  datapacks: { remove: [a] }\n", "datapacks", "remove");
    }

    @Test
    void anUnknownKeyUnderTimeIsRefused() {
        refuses("  time: { until: 5 }\n", "time", "until");
    }

    @Test
    void anUnknownKeyUnderWeatherIsRefused() {
        refuses("  weather: { snow: true }\n", "weather", "snow");
    }

    @Test
    void aWorldThatIsNotAMappingIsRefused() {
        refuses("  - a\n", "world");
    }

    @Test
    void filesAreTextByRelativePath() throws IOException {
        SceneFile file = load("topology: t\nfiles:\n  config/a.toml: |\n    x = 1\n");
        assertEquals(Map.of("config/a.toml", "x = 1\n"), file.files());
    }

    @Test
    void aFileOutsideTheGameDirectoryIsRefused() {
        String message = refusal("topology: t\nfiles:\n  ../escape.txt: x\n");
        assertTrue(message.contains("../escape.txt"), message);
    }

    @Test
    void fromNamesASaveAndKeepsTheRuntimeSettings() throws IOException {
        SceneFile file = load("topology: t\nworld:\n  from: worlds/old.zip\n  time: {at: noon}\n");
        assertEquals("worlds/old.zip", file.from());
        assertEquals(6000L, file.world().time());
        assertFalse(file.world().createsWorld());
    }

    @Test
    void fromWithCreationSettingsIsRefused() {
        refuses("  from: worlds/old.zip\n  seed: 5\n", "world.from", "new one");
    }
}
