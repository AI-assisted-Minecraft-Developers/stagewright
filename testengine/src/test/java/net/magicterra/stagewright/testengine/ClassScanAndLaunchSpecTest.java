package net.magicterra.stagewright.testengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ClassScanAndLaunchSpecTest {

    @Test
    void parametersAreSpelledAsClassGetName() {
        assertEquals(
                "int, [Ljava.lang.String;, java.util.List",
                ClassScan.parameters("(I[Ljava/lang/String;Ljava/util/List;)V"));
        assertEquals("", ClassScan.parameters("()V"));
        assertEquals("[I, long", ClassScan.parameters("([IJ)V"));
    }

    @Test
    void scanFindsInheritedTestsAndContainersWithoutLoadingTheClass() throws Exception {
        ClassLoader loader = getClass().getClassLoader();
        ClassScan.Scanned pass = ClassScan.scan("net.magicterra.stagewright.testengine.fixtures.PassFixture", loader)
                .orElseThrow();
        assertEquals(List.of("pass"), pass.scenes());
        assertEquals(
                List.of("a()", "b()"),
                pass.methods().stream().map(ClassScan.Method::key).toList());

        ClassScan.Scanned dynamic = ClassScan.scan(
                        "net.magicterra.stagewright.testengine.fixtures.DynamicFixture", loader)
                .orElseThrow();
        assertEquals(1, dynamic.methods().size());
        assertEquals("rows(java.lang.String)", dynamic.methods().get(0).key());
        assertTrue(dynamic.methods().get(0).container());
    }

    @Test
    void scanIgnoresClassesWithoutAScene() throws Exception {
        assertEquals(
                Optional.empty(),
                ClassScan.scan(getClass().getName(), getClass().getClassLoader()));
        assertEquals(
                Optional.empty(), ClassScan.scan("no.such.Clazz", getClass().getClassLoader()));
        // Abstract: only ever read as a parent.
        assertEquals(
                Optional.empty(),
                ClassScan.scan(
                        "net.magicterra.stagewright.testengine.fixtures.BaseFixture",
                        getClass().getClassLoader()));
    }

    private static LaunchSpec.Process process(List<String> jvmArgs) {
        return new LaunchSpec.Process("java", jvmArgs, "Main", List.of("arg"), Path.of("."), Map.of());
    }

    @Test
    void extraArgumentsLandBeforeTheFirstClassPath() {
        assertEquals(
                List.of("java", "-Dfoo=1", "-javaagent:a", "-cp", "x", "Main", "arg"),
                process(List.of("-Dfoo=1", "-cp", "x")).command(List.of("-javaagent:a")));
        assertEquals(
                List.of("java", "-javaagent:a", "--class-path", "x", "-Dlate=1", "Main", "arg"),
                process(List.of("--class-path", "x", "-Dlate=1")).command(List.of("-javaagent:a")));
    }

    @Test
    void extraArgumentsLandBeforeAnArgumentFile() {
        assertEquals(
                List.of("java", "-Da", "-javaagent:a", "@args.txt", "Main", "arg"),
                process(List.of("-Da", "@args.txt")).command(List.of("-javaagent:a")));
    }

    @Test
    void leaksFailUnlessTheSpecSaysReport() {
        Map<String, Object> primary = Map.of("java", "java", "workingDirectory", ".");
        assertEquals(
                LaunchSpec.Leaks.FAIL,
                LaunchSpec.parse("t", Map.of("gameDirectory", ".", "primary", primary))
                        .leaks());
        assertEquals(
                LaunchSpec.Leaks.REPORT,
                LaunchSpec.parse("t", Map.of("gameDirectory", ".", "leaks", "report", "primary", primary))
                        .leaks());
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class,
                () -> LaunchSpec.parse("t", Map.of("gameDirectory", ".", "leaks", "ignore", "primary", primary)));
        assertTrue(e.getMessage().contains("'fail' or 'report'"), e.getMessage());
    }

    @Test
    void extraArgumentsGoLastWhenThereIsNoClassPath() {
        assertEquals(
                List.of("java", "-Da", "-javaagent:a", "Main", "arg"),
                process(List.of("-Da")).command(List.of("-javaagent:a")));
    }
}
