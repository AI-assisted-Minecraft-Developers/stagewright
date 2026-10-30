package net.magicterra.stagewright.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A suite run end to end on the real test engine, against a stand-in game that reports what a game's JUnit run would:
 * one class in two scenes. Gradle's results must say which scene each test ran in, and the run must say so too.
 */
class SceneResultsTest {

    @TempDir
    Path dir;

    /** The Gradle running this test, and another installation when {@code -PfunctionalTestGradleHome} names one. */
    static Stream<String> gradles() {
        String other = System.getProperty("stagewright.functionalTest.gradleHome");
        return other == null ? Stream.of("current") : Stream.of("current", other);
    }

    private void write(String path, String text) throws Exception {
        Path file = dir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private void consumer() throws Exception {
        String engine = System.getProperty("stagewright.functionalTest.engine");
        StringBuilder files = new StringBuilder();
        for (String f : engine.split(File.pathSeparator)) {
            if (!files.isEmpty()) {
                files.append(", ");
            }
            files.append("'").append(f.replace("\\", "/")).append("'");
        }
        write("settings.gradle", "rootProject.name = 'consumer'\n");
        write("build.gradle", """
                plugins {
                    id 'java'
                    id 'net.magicterra.stagewright'
                }
                tasks.register('runFake', JavaExec) {
                    classpath = sourceSets.main.runtimeClasspath
                    mainClass = 'FakeGame'
                    workingDir = file('run-fake')
                }
                stagewright {
                    topologies { fake {
                        runTask = 'runFake'
                        client = true
                        stallMinutes = 0
                        timeoutMinutes = 2
                    } }
                    tests { fake {
                        classes.from(sourceSets.main.output)
                        topologies.put('fake', 'fake')
                    } }
                }
                // This build's engine, not a published one.
                configurations.stagewrightTestEngine.dependencies.clear()
                dependencies { stagewrightTestEngine files(%s) }
                """.formatted(files));
        // Stand-ins for the annotations, which the engine matches by name without loading them.
        write("src/main/java/org/junit/jupiter/api/Test.java", """
                package org.junit.jupiter.api;
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                public @interface Test {}
                """);
        write("src/main/java/net/magicterra/stagewright/test/Scene.java", """
                package net.magicterra.stagewright.test;
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                public @interface Scene { String[] value(); }
                """);
        write("src/main/java/demo/PortalTest.java", """
                package demo;
                import org.junit.jupiter.api.Test;
                import net.magicterra.stagewright.test.Scene;
                @Scene({"alpha", "beta"})
                public class PortalTest {
                    @Test void lights() {}
                    @Test void holds() {}
                }
                """);
        write("src/main/resources/stagewright/scenes/alpha.yaml", "topology: fake\n");
        write("src/main/resources/stagewright/scenes/beta.yaml", "topology: fake\n");
        // Reports through the supervision agent as the in-game run does; in scene beta it skips "holds".
        write("src/main/java/FakeGame.java", """
                import java.util.ArrayList;
                import java.util.List;
                public final class FakeGame {
                    static final String ENGINE = "[engine:junit-jupiter]";
                    public static void main(String[] args) throws Exception {
                        Thread.sleep(1500);
                        String scene = System.getProperty("stagewright.junit.scene");
                        String c = System.getProperty("stagewright.junit.classes").split(",")[0];
                        String cid = ENGINE + "/[class:" + c + "]";
                        List<String> nodes = new ArrayList<>();
                        nodes.add("{\\"id\\":" + q(ENGINE) + ",\\"name\\":\\"engine\\",\\"test\\":false}");
                        nodes.add("{\\"id\\":" + q(cid) + ",\\"parent\\":" + q(ENGINE)
                                + ",\\"name\\":\\"PortalTest\\",\\"test\\":false,\\"class\\":" + q(c) + "}");
                        for (String m : List.of("lights", "holds")) {
                            nodes.add("{\\"id\\":" + q(cid + "/[method:" + m + "()]") + ",\\"parent\\":" + q(cid)
                                    + ",\\"name\\":" + q(m + "()") + ",\\"test\\":true,\\"class\\":" + q(c)
                                    + ",\\"method\\":" + q(m) + ",\\"parameters\\":\\"\\"}");
                        }
                        test("{\\"type\\":\\"planned\\",\\"nodes\\":[" + String.join(",", nodes) + "]}");
                        test("{\\"type\\":\\"started\\",\\"id\\":" + q(cid) + "}");
                        for (String m : List.of("lights", "holds")) {
                            String id = q(cid + "/[method:" + m + "()]");
                            if (scene.equals("beta") && m.equals("holds")) {
                                test("{\\"type\\":\\"skipped\\",\\"id\\":" + id + ",\\"reason\\":\\"not here\\"}");
                                continue;
                            }
                            test("{\\"type\\":\\"started\\",\\"id\\":" + id + "}");
                            test("{\\"type\\":\\"finished\\",\\"id\\":" + id + ",\\"status\\":\\"SUCCESSFUL\\"}");
                        }
                        test("{\\"type\\":\\"finished\\",\\"id\\":" + q(cid) + ",\\"status\\":\\"SUCCESSFUL\\"}");
                        test("{\\"type\\":\\"ended\\"}");
                        control("done", null, null);
                        System.exit(0);
                    }
                    static String q(String s) { return "\\"" + s + "\\""; }
                    static void test(String json) throws Exception { control("test", String.class, json); }
                    static void control(String name, Class<?> type, Object arg) throws Exception {
                        Class<?> control = ClassLoader.getSystemClassLoader()
                                .loadClass("net.magicterra.stagewright.agent.Control");
                        if (type == null) control.getMethod(name).invoke(null);
                        else control.getMethod(name, type).invoke(null, arg);
                    }
                }
                """);
    }

    @ParameterizedTest(name = "Gradle {0}")
    @MethodSource("gradles")
    void eachSceneHasItsOwnResultsFileAndSummaryLine(String gradle) throws Exception {
        consumer();
        GradleRunner runner = GradleRunner.create()
                .withProjectDir(dir.toFile())
                .withPluginClasspath()
                .withArguments("stagewrightTestFake", "--configuration-cache", "--stacktrace");
        if (!gradle.equals("current")) {
            runner = runner.withGradleInstallation(new File(gradle));
        }
        BuildResult result = runner.build();
        String out = result.getOutput();

        assertTrue(out.contains("[stagewright] alpha: 2 passed, 0 skipped"), out);
        assertTrue(out.contains("[stagewright] beta: 1 passed, 1 skipped"), out);

        Path results = dir.resolve("build/test-results/stagewrightTestFake");
        List<Path> files = new ArrayList<>();
        try (Stream<Path> list = Files.list(results)) {
            list.filter(p -> p.getFileName().toString().endsWith(".xml"))
                    .sorted()
                    .forEach(files::add);
        }
        assertEquals(2, files.size(), "one file per scene and class: " + files);

        List<String> suites = new ArrayList<>();
        for (Path file : files) {
            assertTrue(!file.getFileName().toString().matches(".*-\\d+\\.xml"), "no numbered copies: " + files);
            String xml = Files.readString(file);
            Matcher suite = Pattern.compile("<testsuite name=\"([^\"]*)\"").matcher(xml);
            assertTrue(suite.find(), xml);
            suites.add(suite.group(1));
            List<String> cases = new ArrayList<>();
            Matcher m = Pattern.compile("<testcase name=\"([^\"]*)\" classname=\"([^\"]*)\"")
                    .matcher(xml);
            while (m.find()) {
                assertEquals(suite.group(1), m.group(2), "each test's class names its scene: " + xml);
                cases.add(m.group(1));
            }
            assertEquals(List.of("holds()", "lights()"), cases.stream().sorted().toList(), xml);
        }
        assertEquals(
                List.of("alpha/demo.PortalTest", "beta/demo.PortalTest"),
                suites.stream().sorted().toList());
    }
}
