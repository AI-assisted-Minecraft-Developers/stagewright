package net.magicterra.stagewright.neoforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.toml.TomlParser;
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
}
