package net.magicterra.stagewright.gradle;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import net.magicterra.stagewright.engine.Display;
import org.gradle.api.GradleException;
import org.gradle.api.file.FileCollection;
import org.gradle.api.logging.Logger;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.tasks.JavaExec;

/**
 * What a scene run sometimes needs beside the game, and the questions a run task is asked about what
 * its loader has not finished binding.
 *
 * <p>The process itself is {@link CompanionLaunch}: a second game, started from values taken from its
 * run task when the build was configured.
 */
final class SideProcesses {

    private SideProcesses() {}

    /** How long to give a companion to die politely before killing it. */
    private static final long COMPANION_STOP_SECONDS = 20;

    /**
     * The classpath the run task would execute with, including the part it only merges in once it is
     * already running.
     *
     * <p>A run task is allowed to finish assembling itself inside its own task action, and
     * ModDevGradle does exactly that — {@code RunGameTask.exec()} calls
     * {@code classpath(getClasspathProvider())} as its second statement, so until the task runs,
     * {@code getClasspath()} is empty and the devlaunch jar holding the main class is nowhere on it.
     * Read from the outside without this, the spec is not merely incomplete, it is convincingly
     * wrong: every argument is present, the command starts, and the JVM dies on
     * {@code ClassNotFoundException: net.neoforged.devlaunch.Main} as though the install were broken.
     */
    static FileCollection resolveClasspath(JavaExec spec) {
        FileCollection classpath = spec.getClasspath();
        for (Toolchain toolchain : Toolchain.of(spec)) {
            FileCollection staged = toolchain.lateClasspath(spec);
            if (staged != null) classpath = classpath.plus(staged);
        }
        return classpath;
    }

    /**
     * Environment entries staged the same late way as the classpath above, from either build system,
     * unread so a caller can hold them until the run.
     *
     * <p>Both stage them and neither exposes them through {@code getEnvironment()}; each is merged
     * into the real environment by the first line of that task's own action — which a companion
     * never reaches, because it is launched FROM the spec rather than by running the task.
     *
     * <p>Missing loom's cost a topology. Loom stages {@code MOD_CLASSES} there, and that is how
     * NeoForge in dev learns which classes belong to which mod. Without it FML still finds the mod
     * file — the {@code neoforge.mods.toml} is in the resources output, which IS on the classpath —
     * so it reads the manifest, prints <i>"WorldDriver 0.1.0+1.21.1 (worlddriver)"</i> in the mod
     * list, attaches no classes to it, finds no {@code @Mod} to construct, and carries on. A mod
     * with no code is a legal mod. Nothing warns. The driver was simply absent from a JVM that
     * listed it, its client probe found no API to probe, and
     * {@code stagewrightDedicatedServerWithClientNeoforge} reported ENV with nothing to point at.
     * The Fabric twin was unaffected throughout: fabric-loom passes the same information as a
     * {@code -D} system property, which is on the command line and survives being copied.
     *
     * <p>Merged rather than picking one, so a task that somehow has both is not silently
     * half-configured.
     */
    static List<MapProperty<?, ?>> lateBoundEnvironmentProperties(JavaExec spec) {
        List<MapProperty<?, ?>> found = new ArrayList<>();
        for (Toolchain toolchain : Toolchain.of(spec)) found.addAll(toolchain.lateEnvironment(spec));
        return found;
    }

    static Map<String, String> merged(List<MapProperty<?, ?>> properties) {
        Map<String, String> result = new LinkedHashMap<>();
        for (MapProperty<?, ?> p : properties) {
            p.get().forEach((k, v) -> result.put(String.valueOf(k), String.valueOf(v)));
        }
        return result;
    }

    /**
     * The class directories {@code MOD_CLASSES} hands a NeoForge game in dev, as
     * {@code <mod>%%<path>} entries separated by the path separator. A run loads them without their
     * being on its classpath: worlddriver keeps its scene classes off the classpath this way.
     */
    static List<File> modClasses(Map<String, String> environment) {
        String value = environment.get("MOD_CLASSES");
        List<File> dirs = new ArrayList<>();
        if (value == null || value.isBlank()) return dirs;
        for (String entry : value.split(Pattern.quote(File.pathSeparator))) {
            int mod = entry.indexOf("%%");
            String path = mod < 0 ? entry : entry.substring(mod + 2);
            if (!path.isBlank()) dirs.add(new File(path));
        }
        return dirs;
    }

    /** The environment a run task's game gets: the task's own, plus what its loader binds late. */
    static Map<String, String> environmentOf(JavaExec spec) {
        Map<String, String> env = new LinkedHashMap<>();
        spec.getEnvironment().forEach((k, v) -> env.put(k, String.valueOf(v)));
        env.putAll(merged(lateBoundEnvironmentProperties(spec)));
        return env;
    }

    /**
     * Stop a companion, politely then not.
     *
     * <p>Called from a finally, so it must not throw: the verdict of the run that just finished is
     * more informative than anything that can go wrong while tidying up after it.
     */
    static void stopCompanion(Process process, Logger logger) {
        if (process == null || !process.isAlive()) return;
        process.destroy();
        try {
            if (!process.waitFor(COMPANION_STOP_SECONDS, TimeUnit.SECONDS)) {
                logger.warn("[stagewright] companion (pid {}) ignored the stop request — killing it",
                        process.pid());
                process.destroyForcibly();
                process.waitFor(COMPANION_STOP_SECONDS, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    /**
     * Refuse to start a client that has no display to open, before it spends a boot finding out.
     *
     * <p>The display itself is the environment's to provide; see {@link Display} for why the check
     * still belongs here.
     *
     * @param env the environment the client will be started with
     */
    static void requireDisplay(String what, Map<String, ?> env) {
        String missing = Display.missing(System.getProperty("os.name", ""), env);
        if (missing != null) {
            throw new GradleException("[stagewright] ENV — " + what + ": " + missing);
        }
    }
}
