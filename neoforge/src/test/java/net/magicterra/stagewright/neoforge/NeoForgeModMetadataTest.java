package net.magicterra.stagewright.neoforge;

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

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.toml.TomlParser;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.artifact.versioning.VersionRange;
import org.junit.jupiter.api.Test;

/**
 * The neoforge.mods.toml processResources writes into the jar, against gradle.properties. Every
 * value both state must come from the property: a literal drifts from the Fabric jar the next time
 * the property changes.
 */
class NeoForgeModMetadataTest {

    private static final Properties PROPS = properties();
    private static final Config META = metadata();

    private static Properties properties() {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(Path.of("..", "gradle.properties"))) {
            p.load(r);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return p;
    }

    private static Config metadata() {
        try (Reader r = Files.newBufferedReader(Path.of("build/resources/main/META-INF/neoforge.mods.toml"))) {
            return new TomlParser().parse(r);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String prop(String key) {
        String v = PROPS.getProperty(key);
        assertNotNull(v, "gradle.properties has no " + key);
        return v.trim();
    }

    private static Config mod() {
        List<Config> mods = META.get("mods");
        assertEquals(1, mods.size());
        return mods.get(0);
    }

    private static Config dependency(String modId) {
        List<Config> deps = META.get(List.of("dependencies", prop("mod_id")));
        assertNotNull(deps, "no [[dependencies." + prop("mod_id") + "]]");
        return deps.stream().filter(d -> modId.equals(d.get("modId"))).findFirst().orElse(null);
    }

    @Test
    void identityAndLicenseComeFromGradleProperties() {
        assertEquals(prop("mod_license"), META.get("license"));
        assertEquals(prop("mod_id"), mod().get("modId"));
        assertEquals(prop("mod_name"), mod().get("displayName"));
        assertEquals(prop("mod_authors"), mod().get("authors"));
        assertEquals(prop("mod_description"), mod().<String>get("description").trim());
    }

    @Test
    void rangesAreTheOnesGradlePropertiesStates() {
        assertEquals(prop("loader_version_range"), META.get("loaderVersion"));
        assertEquals(prop("neoforge_version_range"), dependency("neoforge").get("versionRange"));
        assertEquals(prop("minecraft_version_range"), dependency("minecraft").get("versionRange"));
    }

    @Test
    void worlddriverIsOptionalButRefusedOutsideTheLineStageWrightLinksAgainst() throws Exception {
        Config wd = dependency("worlddriver");
        assertNotNull(wd, "no dependency entry for worlddriver");
        // FML refuses an OPTIONAL dependency that is present with a version outside its range, and
        // ignores it when absent — exactly the relationship StageWright has with its driver.
        assertEquals("optional", wd.get("type"));
        assertEquals("BOTH", wd.get("side"));

        VersionRange range = VersionRange.createFromVersionSpec(wd.get("versionRange"));
        String built = prop("worlddriver_version");
        Matcher v = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)").matcher(built);
        assertTrue(v.find(), built);
        int major = Integer.parseInt(v.group(1));
        int minor = Integer.parseInt(v.group(2));
        String base = v.group();
        String nextBreaking = major == 0 ? "0." + (minor + 1) + ".0" : (major + 1) + ".0.0";
        String laterCompatible = major + "." + minor + "." + (Integer.parseInt(v.group(3)) + 1);
        for (String version : List.of(built, base, laterCompatible + "+1.21.1")) {
            assertTrue(range.containsVersion(new DefaultArtifactVersion(version)), "range should accept " + version);
        }
        for (String version : List.of(base + "-alpha", nextBreaking, nextBreaking + "+1.21.1")) {
            assertFalse(range.containsVersion(new DefaultArtifactVersion(version)), "range should refuse " + version);
        }
    }
}
