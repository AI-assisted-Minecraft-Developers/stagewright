package net.magicterra.stagewright.cli.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The pure parts of an install: names, paths, rules, where a mirror sends a request. */
class InstallLayoutTest {

    @Test
    void clientSpecsNameTheirVersionTheWayEachLoaderDoes() {
        assertEquals("neoforge-21.1.248", ClientSpec.parse("neoforge:1.21.1:21.1.248").versionId());
        assertEquals("fabric-loader-0.19.5-1.21.1", ClientSpec.parse("fabric:1.21.1:0.19.5").versionId());
        assertEquals("1.21.1", ClientSpec.parse("vanilla:1.21.1").versionId());
        assertNull(ClientSpec.parse("vanilla:1.21.1").modLoader());
    }

    @Test
    void aClientSpecWithoutAnExactLoaderVersionIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> ClientSpec.parse("neoforge:21.1.248"));
        assertThrows(IllegalArgumentException.class, () -> ClientSpec.parse("fabric:1.21.1:"));
        assertThrows(IllegalArgumentException.class, () -> ClientSpec.parse("forge:1.21.1:52.0.1"));
        assertThrows(IllegalArgumentException.class, () -> ClientSpec.parse("vanilla:1.21.1:x"));
    }

    @Test
    void theDefaultInstallIsTheOfficialLaunchersDirectory() {
        assertEquals(Path.of("/home/u", ".minecraft"),
                MinecraftDir.defaultFor("Linux", Map.of(), "/home/u"));
        assertEquals(Path.of("C:/Users/u/AppData/Roaming", ".minecraft"),
                MinecraftDir.defaultFor("Windows 11", Map.of("APPDATA", "C:/Users/u/AppData/Roaming"), "C:/Users/u"));
        assertEquals(Path.of("/Users/u", "Library", "Application Support", "minecraft"),
                MinecraftDir.defaultFor("Mac OS X", Map.of(), "/Users/u"));
    }

    @Test
    void mavenPathsCarryClassifierAndExtension() {
        assertEquals("org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-linux.jar",
                Versions.mavenPath("org.lwjgl:lwjgl:3.3.3:natives-linux"));
        assertEquals("de/oceanlabs/mcp/mcp_config/1.21.1/mcp_config-1.21.1.zip",
                Versions.mavenPath("de.oceanlabs.mcp:mcp_config:1.21.1@zip"));
    }

    @Test
    void aNativesClassifierIsItsOwnLibraryAndTheChildsPinWins() {
        JsonObject child = json("""
                {"libraries":[{"name":"org.ow2.asm:asm:9.10.1","url":"child"}]}""");
        JsonObject parent = json("""
                {"libraries":[{"name":"org.ow2.asm:asm:9.7"},
                              {"name":"org.lwjgl:lwjgl:3.3.3"},
                              {"name":"org.lwjgl:lwjgl:3.3.3:natives-linux",
                               "rules":[{"action":"allow","os":{"name":"linux"}}]}]}""");
        List<JsonObject> libraries = Versions.libraries(List.of(child, parent));
        List<String> names = libraries.stream().map(l -> l.get("name").getAsString()).toList();
        assertTrue(names.contains("org.ow2.asm:asm:9.10.1"), names.toString());
        assertFalse(names.contains("org.ow2.asm:asm:9.7"), names.toString());
        assertTrue(names.contains("org.lwjgl:lwjgl:3.3.3"), names.toString());
    }

    @Test
    void theLastMatchingRuleWinsAndFeatureRulesNeverAllow() {
        JsonObject osx = json("""
                {"rules":[{"action":"allow"},{"action":"disallow","os":{"name":"osx"}}]}""");
        assertTrue(Rules.allowed(osx, "linux"));
        assertFalse(Rules.allowed(osx, "osx"));
        JsonObject demo = json("""
                {"rules":[{"action":"allow","features":{"is_demo_user":true}}]}""");
        assertFalse(Rules.allowed(demo, "linux"));
    }

    @Test
    void bmclapiIsTriedFirstAndTheOriginalLast() {
        URI library = URI.create("https://libraries.minecraft.net/org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3.jar");
        assertEquals(List.of(
                URI.create("https://bmclapi2.bangbang93.com/maven/org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3.jar"),
                library), Mirror.BMCLAPI.candidates(library));
        URI neoforge = URI.create("https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.248/x.jar");
        assertEquals(URI.create("https://bmclapi2.bangbang93.com/maven/net/neoforged/neoforge/21.1.248/x.jar"),
                Mirror.BMCLAPI.candidates(neoforge).get(0));
        assertEquals(List.of(library), Mirror.NONE.candidates(library));
        URI elsewhere = URI.create("https://example.org/a.jar");
        assertEquals(List.of(elsewhere), Mirror.BMCLAPI.candidates(elsewhere));
    }

    @Test
    void theProxyComesFromTheEnvironmentJavaIgnores() {
        var address = Downloader.proxyAddress(Map.of("https_proxy", "http://10.0.0.1:7873"));
        assertEquals("10.0.0.1", address.getHostString());
        assertEquals(7873, address.getPort());
        assertNull(Downloader.proxyAddress(Map.of()));
        assertEquals(List.of("-Dhttps.proxyHost=10.0.0.1", "-Dhttps.proxyPort=7873",
                        "-Dhttp.proxyHost=10.0.0.1", "-Dhttp.proxyPort=7873"),
                Downloader.proxyJvmArgs(Map.of("HTTPS_PROXY", "10.0.0.1:7873")));
    }

    @Test
    void aCachedFileIsVerifiedBeforeItIsUsed(@TempDir Path install) throws IOException {
        HmclCache cache = new HmclCache(install);
        Path source = install.resolve("source.jar");
        Files.writeString(source, "library bytes");
        String sha1 = Sha1.of(source);
        cache.store(source, sha1);
        assertTrue(Files.isRegularFile(install.resolve("cache/SHA-1/" + sha1.substring(0, 2) + "/" + sha1)));

        Path target = install.resolve("libraries/a/b.jar");
        assertTrue(cache.placeInto(sha1, Files.size(source), target));
        assertEquals("library bytes", Files.readString(target));

        // A truncated entry some other launcher left behind must not be linked into an install.
        Files.writeString(cache.file(sha1), "library");
        assertFalse(cache.placeInto(sha1, -1, install.resolve("libraries/a/c.jar")));
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }
}
