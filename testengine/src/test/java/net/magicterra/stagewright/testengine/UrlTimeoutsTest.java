package net.magicterra.stagewright.testengine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A joining client gets timeouts for URL connections that set none, unless its launch already sets them. */
class UrlTimeoutsTest {

    private static final String CONNECT = "-Dsun.net.client.defaultConnectTimeout=";

    private static final String READ = "-Dsun.net.client.defaultReadTimeout=";

    private static LaunchSpec.Process client(List<String> jvmArgs, Map<String, String> environment) {
        return new LaunchSpec.Process("java", jvmArgs, "Main", List.of(), Path.of("client"), environment);
    }

    @Test
    void launchWithoutTimeoutsGetsBoth() {
        assertEquals(
                List.of(CONNECT + "15000", READ + "15000"),
                SceneRun.urlTimeouts(client(List.of("-Xmx4G"), Map.of()), Map.of()));
    }

    @Test
    void timeoutOnTheCommandLineIsKept() {
        assertEquals(
                List.of(CONNECT + "15000"), SceneRun.urlTimeouts(client(List.of(READ + "500"), Map.of()), Map.of()));
    }

    @Test
    void timeoutInEitherOptionVariableIsKept() {
        assertEquals(
                List.of(READ + "15000"),
                SceneRun.urlTimeouts(
                        client(List.of(), Map.of()), Map.of("JAVA_TOOL_OPTIONS", "-Xss2m " + CONNECT + "500")));
        assertEquals(
                List.of(CONNECT + "15000"),
                SceneRun.urlTimeouts(client(List.of(), Map.of()), Map.of("JDK_JAVA_OPTIONS", READ + "500")));
    }

    @Test
    void clientsOwnEnvironmentOverridesTheEngines() {
        assertEquals(
                List.of(CONNECT + "15000", READ + "15000"),
                SceneRun.urlTimeouts(
                        client(List.of(), Map.of("JAVA_TOOL_OPTIONS", "-Xss2m")),
                        Map.of("JAVA_TOOL_OPTIONS", READ + "500")));
        assertEquals(
                List.of(CONNECT + "15000"),
                SceneRun.urlTimeouts(client(List.of(), Map.of("JDK_JAVA_OPTIONS", READ + "500")), Map.of()));
    }
}
