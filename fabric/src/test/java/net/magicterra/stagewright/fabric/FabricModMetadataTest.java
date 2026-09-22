package net.magicterra.stagewright.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.version.VersionInterval;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import org.junit.jupiter.api.Test;

/**
 * The fabric.mod.json processResources writes into the jar, against gradle.properties. Every value
 * both state must come from the property: a literal drifts from the NeoForge jar the next time the
 * property changes. Ranges are compared by the interval Fabric Loader itself parses them into.
 */
class FabricModMetadataTest {

    private static final Pattern MAVEN_RANGE = Pattern.compile("^([\\[(])([^,]*),([^\\])]*)([\\])])$");

    private static final Properties PROPS = properties();
    private static final JsonObject META = metadata();

    private static Properties properties() {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(Path.of("..", "gradle.properties"))) {
            p.load(r);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return p;
    }

    private static JsonObject metadata() {
        try {
            return JsonParser.parseString(Files.readString(Path.of("build/resources/main/fabric.mod.json")))
                    .getAsJsonObject();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String prop(String key) {
        String v = PROPS.getProperty(key);
        assertNotNull(v, "gradle.properties has no " + key);
        return v.trim();
    }

    /** The interval Fabric Loader derives from {@code predicate} equals the Maven range in {@code key}. */
    private static void assertSameInterval(String key, JsonElement predicate) throws VersionParsingException {
        assertNotNull(predicate, "no dependency declared for " + key);
        Matcher m = MAVEN_RANGE.matcher(prop(key));
        assertTrue(m.matches(), key + " is not a Maven range: " + prop(key));
        VersionInterval got = VersionPredicate.parse(predicate.getAsString()).getInterval();
        String min = m.group(2).trim();
        String max = m.group(3).trim();
        assertEquals(min.isEmpty() ? null : Version.parse(min), got.getMin(), key + " lower bound");
        assertEquals(max.isEmpty() ? null : Version.parse(max), got.getMax(), key + " upper bound");
        if (!min.isEmpty()) assertEquals(m.group(1).equals("["), got.isMinInclusive(), key + " lower inclusivity");
        if (!max.isEmpty()) assertEquals(m.group(4).equals("]"), got.isMaxInclusive(), key + " upper inclusivity");
    }

    @Test
    void identityAndLicenseComeFromGradleProperties() {
        assertEquals(prop("mod_id"), META.get("id").getAsString());
        assertEquals(prop("mod_name"), META.get("name").getAsString());
        assertEquals(prop("mod_license"), META.get("license").getAsString());
        assertEquals(prop("mod_description"), META.get("description").getAsString());
        assertEquals(List.of(prop("mod_authors")),
                META.getAsJsonArray("authors").asList().stream().map(JsonElement::getAsString).toList());
    }

    @Test
    void minecraftRangeIsTheOneGradlePropertiesStates() throws VersionParsingException {
        assertSameInterval("minecraft_version_range", META.getAsJsonObject("depends").get("minecraft"));
    }

    @Test
    void loaderRangeIsTheOneGradlePropertiesStates() throws VersionParsingException {
        assertSameInterval("fabric_loader_version_range", META.getAsJsonObject("depends").get("fabricloader"));
    }

    /** Any predicate in a string-or-array dependency value matches, as Fabric Loader reads it. */
    private static boolean matches(JsonElement value, String version) throws VersionParsingException {
        List<JsonElement> terms = value.isJsonArray() ? value.getAsJsonArray().asList() : List.of(value);
        for (JsonElement t : terms) {
            if (VersionPredicate.parse(t.getAsString()).test(Version.parse(version))) return true;
        }
        return false;
    }

    @Test
    void worlddriverIsOptionalButRefusedOutsideTheLineStageWrightLinksAgainst() throws VersionParsingException {
        for (String kind : List.of("depends", "recommends")) {
            JsonObject deps = META.getAsJsonObject(kind);
            assertTrue(deps == null || !deps.has("worlddriver"), "worlddriver must stay optional, found in " + kind);
        }
        assertTrue(META.has("suggests") && META.getAsJsonObject("suggests").has("worlddriver"),
                "no suggests entry for worlddriver");
        assertTrue(META.has("breaks") && META.getAsJsonObject("breaks").has("worlddriver"),
                "no breaks entry for worlddriver");
        JsonElement suggests = META.getAsJsonObject("suggests").get("worlddriver");
        JsonElement breaks = META.getAsJsonObject("breaks").get("worlddriver");

        // suggests is advisory only; breaks is what makes Fabric Loader refuse the pair, so the two
        // must be exact complements around the release line of worlddriver_version.
        String built = prop("worlddriver_version");
        Matcher v = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)").matcher(built);
        assertTrue(v.find(), built);
        int major = Integer.parseInt(v.group(1));
        int minor = Integer.parseInt(v.group(2));
        String base = v.group();
        String nextBreaking = major == 0 ? "0." + (minor + 1) + ".0" : (major + 1) + ".0.0";
        String laterCompatible = major + "." + minor + "." + (Integer.parseInt(v.group(3)) + 1);
        for (String version : List.of(built, base, laterCompatible)) {
            assertTrue(matches(suggests, version), "suggests should cover " + version);
            assertFalse(matches(breaks, version), "breaks must not refuse " + version);
        }
        for (String version : List.of(base + "-alpha", nextBreaking, nextBreaking + "+1.21.1")) {
            assertFalse(matches(suggests, version), "suggests should not cover " + version);
            assertTrue(matches(breaks, version), "breaks must refuse " + version);
        }
    }
}
