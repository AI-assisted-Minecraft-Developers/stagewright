package net.magicterra.stagewright.gradle;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The repository's own gradle/version.gradle and gradle/nexus.gradle, applied to a throwaway
 * project whose Nexus repository is a local server recording each upload, so no case here can
 * reach the real one.
 */
class ReleaseVersionTest {

    private static final Path SCRIPTS = Path.of("..", "gradle").toAbsolutePath().normalize();

    @TempDir
    Path dir;

    private HttpServer nexus;
    private final List<String> uploads = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startNexus() throws IOException {
        nexus = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        nexus.createContext("/", exchange -> {
            if ("PUT".equals(exchange.getRequestMethod())) uploads.add(exchange.getRequestURI().getPath());
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders("PUT".equals(exchange.getRequestMethod()) ? 201 : 404, -1);
            exchange.close();
        });
        nexus.start();
    }

    @AfterEach
    void stopNexus() {
        nexus.stop(0);
    }

    private GradleRunner project(String buildNumber) throws IOException {
        assertTrue(Files.isRegularFile(SCRIPTS.resolve("version.gradle")), SCRIPTS.toString());
        Files.writeString(dir.resolve("settings.gradle"), "rootProject.name = 'consumer'\n");
        Files.writeString(dir.resolve("build.gradle"), """
                plugins { id 'java'; id 'maven-publish' }
                group = 'net.magicterra.test'
                apply from: '%s'
                version = stagewrightVersion
                apply from: '%s'
                publishing {
                    publications { lib(MavenPublication) { from components.java } }
                    repositories.getByName('gardelNexus') {
                        url = uri('%s')
                        allowInsecureProtocol = true
                    }
                }
                tasks.register('printVersion') {
                    def v = version
                    doLast { println "VERSION=" + v }
                }
                """.formatted(SCRIPTS.resolve("version.gradle"), SCRIPTS.resolve("nexus.gradle"),
                "http://127.0.0.1:" + nexus.getAddress().getPort() + "/"));
        Map<String, String> env = new HashMap<>(System.getenv());
        env.remove("BUILD_NUMBER");
        if (buildNumber != null) env.put("BUILD_NUMBER", buildNumber);
        env.put("MAVEN_USERNAME", "u");
        env.put("MAVEN_PASSWORD", "p");
        return GradleRunner.create().withProjectDir(dir.toFile()).withEnvironment(env);
    }

    @Test
    void aLocalBuildIsVersionedLocalAndNeverReachesTheNexus() throws IOException {
        BuildResult version = project(null).withArguments("printVersion").build();
        assertTrue(version.getOutput().contains("VERSION=0.1.0-build.local+1.21.1"), version.getOutput());

        BuildResult publish = project(null)
                .withArguments("publishAllPublicationsToGardelNexusRepository").buildAndFail();
        assertTrue(publish.getOutput().contains("not published to the Nexus"), publish.getOutput());
        assertEquals(List.of(), uploads);
    }

    @Test
    void aCiBuildIsNumberedAndPublishes() throws IOException {
        // The first build published by hand is numbered 0.
        BuildResult first = project("0").withArguments("printVersion").build();
        assertTrue(first.getOutput().contains("VERSION=0.1.0-build.0+1.21.1"), first.getOutput());

        BuildResult publish = project("42")
                .withArguments("printVersion", "publishAllPublicationsToGardelNexusRepository").build();
        assertTrue(publish.getOutput().contains("VERSION=0.1.0-build.42+1.21.1"), publish.getOutput());
        assertTrue(uploads.stream().anyMatch(u -> u.contains("/0.1.0-build.42+1.21.1/")), uploads.toString());
    }

    @Test
    void aBuildNumberThatIsNotARunNumberIsRefused() throws IOException {
        for (String bad : new String[] {"", "abc", "1.2", "007"}) {
            BuildResult result = project(bad).withArguments("printVersion").buildAndFail();
            assertTrue(result.getOutput().contains("BUILD_NUMBER"), bad + ": " + result.getOutput());
        }
    }
}
