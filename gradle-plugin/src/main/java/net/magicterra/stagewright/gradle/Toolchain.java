package net.magicterra.stagewright.gradle;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileCollection;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.JavaExec;

/**
 * What a loader plugin keeps in its run task outside the places Gradle looks.
 *
 * <p>A run task is allowed to finish assembling itself inside its own action, and both loaders do: the classpath, the
 * environment and the working directory are each bound there. A launch built by reading the task from outside is then
 * convincingly wrong rather than visibly incomplete — every argument present, the command starts, and the game dies on
 * a class that was never on the classpath. These members are the answer to "where did it keep them", one adapter per
 * loader, and each hands back the property object itself rather than its value: the value is read when the game starts,
 * and nothing here holds the task.
 *
 * <p>The members are not published API of either loader, so each adapter finds them by name and answers nothing when
 * one is missing. That is why a run task that has members of this kind this file does not know is refused for a
 * companion instead of launched without them.
 */
interface Toolchain {

    /** Both loaders, and nothing for a run task that is neither. */
    List<Toolchain> KNOWN = List.of(new ModDevGradle(), new Loom());

    String name();

    /** Whether {@code run} is this loader's. */
    boolean owns(JavaExec run);

    /** What the loader adds to the classpath once it is running, or null. */
    FileCollection lateClasspath(JavaExec run);

    /** The environment properties the loader merges into the real one when its action starts. */
    List<MapProperty<?, ?>> lateEnvironment(JavaExec run);

    /** Where the loader runs the game, or null when it does not say. */
    WorkingDirectory workingDirectory(JavaExec run);

    /** ModDevGradle: a staged classpath, an environment property, a game directory. */
    final class ModDevGradle implements Toolchain {
        @Override
        public String name() {
            return "ModDevGradle";
        }

        @Override
        public boolean owns(JavaExec run) {
            return Members.has(run, "getClasspathProvider")
                    || Members.has(run, "getEnvironmentProperty")
                    || Members.has(run, "getGameDirectory");
        }

        @Override
        public FileCollection lateClasspath(JavaExec run) {
            return Members.read(run, "getClasspathProvider") instanceof FileCollection files ? files : null;
        }

        @Override
        public List<MapProperty<?, ?>> lateEnvironment(JavaExec run) {
            return Members.read(run, "getEnvironmentProperty") instanceof MapProperty<?, ?> p ? List.of(p) : List.of();
        }

        @Override
        public WorkingDirectory workingDirectory(JavaExec run) {
            return Members.read(run, "getGameDirectory") instanceof DirectoryProperty p
                    ? new WorkingDirectory(p, null, null)
                    : null;
        }
    }

    /**
     * Loom, fabric's and architectury's alike: the environment in internal variables, and the run directory as a string
     * relative to a project directory it keeps beside it. Both are behind protected getters.
     */
    final class Loom implements Toolchain {
        @Override
        public String name() {
            return "Loom";
        }

        @Override
        public boolean owns(JavaExec run) {
            return Members.has(run, "getInternalEnvironmentVars") || Members.has(run, "getInternalRunDir");
        }

        @Override
        public FileCollection lateClasspath(JavaExec run) {
            return null;
        }

        @Override
        public List<MapProperty<?, ?>> lateEnvironment(JavaExec run) {
            return Members.read(run, "getInternalEnvironmentVars") instanceof MapProperty<?, ?> p
                    ? List.of(p)
                    : List.of();
        }

        @Override
        public WorkingDirectory workingDirectory(JavaExec run) {
            return Members.read(run, "getInternalRunDir") instanceof Property<?> runDir
                            && Members.read(run, "getProjectDir") instanceof Property<?> projectDir
                    ? new WorkingDirectory(null, runDir, projectDir)
                    : null;
        }
    }

    /** The adapters whose loader {@code run} belongs to; empty for a task that is neither. */
    static List<Toolchain> of(JavaExec run) {
        return KNOWN.stream().filter(t -> t.owns(run)).toList();
    }

    /**
     * Whether {@code run} carries members of its own that hold values the game is started with and that no adapter here
     * reads: a subclass, not one Gradle generated, that declares a getter returning a property, a provider or a file
     * collection.
     *
     * <p>A subclass of its own with only plain members cannot hide a late binding from a launch built from outside; one
     * with those can, and there is no telling which.
     */
    static String unreadMembers(JavaExec run) {
        List<String> found = new ArrayList<>();
        for (Class<?> type = run.getClass(); type != null && type != JavaExec.class; type = type.getSuperclass()) {
            if (type.getName().contains("_Decorated")) {
                continue;
            }
            for (Method m : type.getDeclaredMethods()) {
                if (m.getParameterCount() != 0 || m.isSynthetic() || Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                Class<?> returned = m.getReturnType();
                if (Provider.class.isAssignableFrom(returned) || FileCollection.class.isAssignableFrom(returned)) {
                    found.add(type.getSimpleName() + "." + m.getName());
                }
            }
        }
        return found.isEmpty() ? null : String.join(", ", found);
    }

    /** Reflection by name: there is no interface to ask, and the names belong to the loaders. */
    final class Members {
        private Members() {}

        static boolean has(JavaExec run, String getter) {
            return find(run, getter) != null;
        }

        /**
         * A zero-argument getter's answer, or null if the task has none.
         *
         * <p>Walks the hierarchy rather than asking for public methods: loom keeps its run directory behind a
         * {@code protected abstract} getter, which {@code getMethod} cannot see at all.
         */
        // Opening the getter is the point: loom's run directory getter is protected, and no public API reaches it.
        @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
        static Object read(JavaExec run, String getter) {
            Method method = find(run, getter);
            if (method == null) {
                return null;
            }
            try {
                method.setAccessible(true);
                return method.invoke(run);
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }

        private static Method find(JavaExec run, String getter) {
            for (Class<?> type = run.getClass(); type != null; type = type.getSuperclass()) {
                try {
                    return type.getDeclaredMethod(getter);
                } catch (NoSuchMethodException e) {
                    // Keep walking — the getter may be declared further up.
                }
            }
            return null;
        }
    }

    /**
     * The directory a run executes in, as the property objects that say it and not as a path: the answer is read when
     * the game starts, or null if the loader has not set one.
     */
    final class WorkingDirectory {
        private final DirectoryProperty gameDirectory;
        private final Property<?> runDir;
        private final Property<?> projectDir;

        WorkingDirectory(DirectoryProperty gameDirectory, Property<?> runDir, Property<?> projectDir) {
            this.gameDirectory = gameDirectory;
            this.runDir = runDir;
            this.projectDir = projectDir;
        }

        File resolve() {
            if (gameDirectory != null && gameDirectory.isPresent()) {
                return gameDirectory.get().getAsFile();
            }
            if (runDir != null && projectDir != null && runDir.isPresent() && projectDir.isPresent()) {
                return new File(String.valueOf(projectDir.get()), String.valueOf(runDir.get()));
            }
            return null;
        }
    }
}
