package net.magicterra.stagewright.jupiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Step;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaStepsTest {

    public record Pair(String left, int right) {}

    public static final class Good {
        @Step("pair")
        public static Pair pair(Map<String, Object> args, Game game) {
            return new Pair((String) args.get("left"), game == null ? -1 : 1);
        }

        @Step("echo")
        public static Object echo(Object args) {
            return args;
        }
    }

    public static final class Clash {
        @Step("echo")
        public static Object again() {
            return null;
        }
    }

    public static final class Builtin {
        @Step("place")
        public static Object place() {
            return null;
        }
    }

    public static final class Instance {
        @Step("instance")
        public Object run() {
            return null;
        }
    }

    public static final class WrongParameter {
        @Step("wrong")
        public static Object run(String s) {
            return s;
        }
    }

    @TempDir
    Path dir;

    private JavaSteps load(Class<?>... classes) throws Exception {
        return load(java.util.Arrays.stream(classes).map(Class::getName).toArray(String[]::new));
    }

    private JavaSteps load(String... classNames) throws Exception {
        Path index = dir.resolve(JavaSteps.INDEX);
        Files.createDirectories(index.getParent());
        StringBuilder lines = new StringBuilder("# step classes\n");
        for (String c : classNames) lines.append(c).append('\n');
        Files.writeString(index, lines);
        try (URLClassLoader loader = new URLClassLoader(new URL[] {dir.toUri().toURL()}, getClass().getClassLoader())) {
            return JavaSteps.load(loader);
        }
    }

    @Test
    void aStepIsFoundByNameAndGivenWhatItsParametersAskFor() throws Exception {
        JavaSteps steps = load(Good.class);
        assertEquals(java.util.Set.of("pair", "echo"), steps.names());
        // An object comes back as JSON would read it; plain values come back as they are.
        assertEquals(Map.of("left", "a", "right", -1.0), steps.run("pair", null, Map.of("left", "a")));
        assertEquals("text", steps.run("echo", null, "text"));
        assertThrows(IllegalArgumentException.class, () -> steps.run("pair", null, "not a map"));
    }

    @Test
    void mistakesAreRefusedWhenTheStepsAreLoaded() {
        for (Class<?> bad : new Class<?>[] {Builtin.class, Instance.class, WrongParameter.class}) {
            assertThrows(IllegalArgumentException.class, () -> load(bad), bad.getSimpleName());
        }
        IllegalArgumentException clash = assertThrows(IllegalArgumentException.class, () -> load(Good.class, Clash.class));
        assertTrue(clash.getMessage().contains("also taken"), clash.getMessage());
        assertThrows(IllegalArgumentException.class, () -> load(Good.class.getName() + "Missing"));
    }
}
