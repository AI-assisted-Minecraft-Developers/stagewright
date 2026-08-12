package net.magicterra.stagewright.gradle;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.gradle.api.GradleException;
import org.gradle.api.logging.Logger;
import org.gradle.api.tasks.JavaExec;

/**
 * The two processes a scene run sometimes needs beside the game: a second game, and a display for
 * it to draw into.
 *
 * <p>Both used to live in the python orchestrators, and both are the reason people believed the
 * orchestration had to be a separate program. Neither is large. What made them look large was that
 * the python had to reconstruct, from the outside, information Gradle already had — where the java
 * binary is, what the classpath is, which run directory to use — and then manage the process tree
 * differently on each platform.
 */
final class SideProcesses {

    private SideProcesses() {}

    /** How long to give a companion to die politely before killing it. */
    private static final long COMPANION_STOP_SECONDS = 20;

    /** Displays to try when probing. Starts high to stay clear of a developer's own {@code :0}/{@code :1}. */
    private static final int DISPLAY_PROBE_FROM = 90;
    private static final int DISPLAY_PROBE_TO = 120;

    /**
     * Start a companion run as a detached process, built from the run task's own resolved
     * {@link JavaExec} spec.
     *
     * <p>Reading the spec rather than rebuilding it is the whole point: the loader plugin — loom,
     * ModDevGradle, whatever comes next — stays the only thing that knows what a dev run looks like
     * on its loader. This copies the answer; it does not compute one.
     *
     * <p>The caller must already have run the companion's own task dependencies, or the argfiles
     * those tasks generate will not exist yet. {@code StageWrightPlugin} wires that.
     */
    static Process startCompanion(JavaExec spec, File logFile, String display, Logger logger) {
        List<String> command = new ArrayList<>();
        command.add(javaBinary(spec));
        List<String> jvmArgs = new ArrayList<>();
        jvmArgs(spec).forEach(a -> jvmArgs.add(unescapeArgFileReference(a)));
        command.addAll(jvmArgs);
        if (!alreadyHasClasspath(jvmArgs)) {
            String classpathArgFile = writeClasspathArgFile(resolveClasspath(spec), logFile);
            if (classpathArgFile != null) {
                command.add(classpathArgFile);
            }
        }
        command.add(spec.getMainClass().get());
        spec.getArgs().forEach(a -> command.add(unescapeArgFileReference(a)));
        // ProcessBuilder answers a null element with a bare NullPointerException carrying no message
        // and no index — which is a terrible way to learn that a loader plugin left one of these
        // unset. Name it instead.
        for (int i = 0; i < command.size(); i++) {
            if (command.get(i) == null) {
                throw new GradleException("the companion run '" + spec.getName()
                        + "' produced a null argument at position " + i
                        + " — its loader plugin builds the command line differently than expected");
            }
        }

        // Created here, because the run task that would normally create it is not going to run. Its
        // absence surfaces as a bare "cannot start the companion" from ProcessBuilder, which says
        // nothing about a missing directory.
        File workingDir = resolveWorkingDir(spec);
        if (!workingDir.isDirectory() && !workingDir.mkdirs()) {
            throw new GradleException("cannot create the companion's game directory " + workingDir);
        }
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(workingDir)
                // Merged and redirected to a file rather than inherited: the companion is a whole
                // second game, and interleaving its log with the one being judged makes the run
                // that matters unreadable at exactly the moment someone is reading it.
                .redirectErrorStream(true)
                // Appended, not truncated: writeHeader put the command line in first.
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));
        spec.getEnvironment().forEach((k, v) -> builder.environment().put(k, String.valueOf(v)));
        builder.environment().putAll(lateBoundEnvironment(spec));
        if (display != null) {
            builder.environment().put("DISPLAY", display);
        }

        try {
            // The command that produced a log is the first thing anyone debugging one wants, and it
            // is otherwise unrecoverable — the process is detached and Gradle never printed it.
            writeHeader(logFile, command);
            Process process = builder.start();
            logger.lifecycle("[stagewright] companion '{}' started (pid {}), log: {}",
                    spec.getName(), process.pid(), logFile.getAbsolutePath());
            return process;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot start the companion run " + spec.getName(), e);
        }
    }

    /** Start the log with the command that produced it, one argument per line. */
    private static void writeHeader(File logFile, List<String> command) {
        StringBuilder header = new StringBuilder("# stagewright companion command\n");
        command.forEach(a -> header.append("#   ").append(a).append('\n'));
        try {
            logFile.getParentFile().mkdirs();
            java.nio.file.Files.writeString(logFile.toPath(), header,
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write the companion log " + logFile, e);
        }
    }

    /**
     * Undo the backslash escaping a loader plugin applies to an {@code @argfile} reference.
     *
     * <p>ModDevGradle hands its run task {@code @C:\\Users\\...\\RunVmArgs.txt} — doubled, because
     * Gradle writes long command lines into a java argument file of its own and a backslash is an
     * escape character in there. Handed straight to {@code ProcessBuilder}, which does no such
     * rewriting, that names a file which does not exist. The JVM then starts with none of the vm
     * args — no module path, no launch target — and dies on
     * {@code ClassNotFoundException: net.neoforged.devlaunch.Main}, an error that says nothing
     * whatsoever about the actual cause.
     *
     * <p>Only after the first character, so a UNC reference ({@code @\\server\share\...}) keeps its
     * leading pair.
     */
    private static String unescapeArgFileReference(String arg) {
        if (arg == null || arg.length() < 2 || arg.charAt(0) != '@') return arg;
        return "@" + arg.substring(1).replace("\\\\", "\\");
    }

    /**
     * Every JVM argument the run would start with, from all three places Gradle keeps them.
     *
     * <p>No single source is sufficient, and each loader plugin uses a different one.
     *
     * <ul>
     *   <li>{@code getAllJvmArgs()} — the eager list. ModDevGradle's argfile reference is here.</li>
     *   <li>{@code getJvmArguments()} — the lazy {@code ListProperty} that superseded it, and NOT
     *       included in the eager list. architectury-loom puts everything here, so reading only the
     *       eager list produced a four-argument command line carrying nothing but the default
     *       encoding and locale — the client died in {@code TransformerRuntime.applyProperties} on a
     *       null path, which is the transformer asking for settings nobody had given it.</li>
     *   <li>{@code jvmArgumentProviders} — argument providers, whose output a plugin may or may not
     *       also place in one of the above. ModDevGradle places its {@code -Dfml.modFolders} in
     *       both, and expanding it a second time put two copies on the line.</li>
     * </ul>
     *
     * <p>Union, then, de-duplicated a whole source at a time — never token by token. A JVM argument
     * list is not a set of independent strings: {@code --add-opens} and {@code --add-exports} appear
     * many times over, each followed by its own value, and dropping the second occurrence of the
     * flag as a "duplicate" leaves its value stranded on the command line. The JVM then reads that
     * value as the main class and reports
     * {@code Could not find or load main class java.base.java.lang.invoke=cpw.mods.securejarhandler},
     * which names the symptom and hides the cause completely.
     */
    private static List<String> jvmArgs(JavaExec spec) {
        List<String> args = new ArrayList<>(spec.getAllJvmArgs());
        appendUnlessPresent(args, spec.getJvmArguments().getOrElse(List.of()));
        for (org.gradle.process.CommandLineArgumentProvider provider : spec.getJvmArgumentProviders()) {
            List<String> provided = new ArrayList<>();
            provider.asArguments().forEach(provided::add);
            appendUnlessPresent(args, provided);
        }
        return args;
    }

    /** Append a source's arguments unless the target already carries all of them. */
    private static void appendUnlessPresent(List<String> target, List<String> extra) {
        if (extra.isEmpty() || target.containsAll(extra)) return;
        target.addAll(extra);
    }

    /**
     * Whether the JVM arguments already put a classpath on the command line, directly or through an
     * argument file.
     *
     * <p>Asked because appending a second one is not harmless. A later {@code -classpath} wins, so
     * the run would silently execute against this plugin's idea of the classpath instead of the
     * loader's — and loom's differs deliberately, having filtered out libraries its run must not
     * see.
     */
    private static boolean alreadyHasClasspath(List<String> args) {
        for (String arg : args) {
            if (arg.equals("-cp") || arg.equals("-classpath") || arg.equals("--class-path")) {
                return true;
            }
            if (arg.startsWith("@")) {
                try {
                    String body = java.nio.file.Files.readString(
                            java.nio.file.Path.of(arg.substring(1)));
                    if (body.startsWith("-classpath") || body.startsWith("-cp")
                            || body.contains("\n-classpath") || body.contains("\n-cp")) {
                        return true;
                    }
                } catch (IOException | RuntimeException e) {
                    // Unreadable argfile: not this method's problem. The JVM will report it far more
                    // precisely than a guess here could.
                }
            }
        }
        return false;
    }

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
     *
     * <p>Reflective and by name, because there is no interface to ask. The name belongs to a loader
     * plugin, not to Gradle, so this is a list of known stashes rather than a general rule — and an
     * unknown loader loses nothing, since a plugin that assembles its classpath the ordinary way is
     * already covered by {@code getClasspath()}.
     */
    private static org.gradle.api.file.FileCollection resolveClasspath(JavaExec spec) {
        org.gradle.api.file.FileCollection classpath = spec.getClasspath();
        Object staged = readProperty(spec, "getClasspathProvider");
        if (staged instanceof org.gradle.api.file.FileCollection extra) {
            classpath = classpath.plus(extra);
        }
        return classpath;
    }

    /**
     * Environment entries staged the same late way as the classpath above, from either build system.
     *
     * <p>Both stage them and neither exposes them through {@code getEnvironment()}: ModDevGradle in
     * {@code getEnvironmentProperty()}, loom in {@code getInternalEnvironmentVars()}. Each is merged
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
     * <p>Read both and merge rather than picking one, so a task that somehow has both is not
     * silently half-configured.
     */
    @SuppressWarnings("unchecked")
    private static java.util.Map<String, String> lateBoundEnvironment(JavaExec spec) {
        java.util.Map<String, String> result = new java.util.LinkedHashMap<>();
        for (String getter : new String[] {"getEnvironmentProperty", "getInternalEnvironmentVars"}) {
            if (readProperty(spec, getter) instanceof org.gradle.api.provider.MapProperty<?, ?> p) {
                ((java.util.Map<Object, Object>) p.get())
                        .forEach((k, v) -> result.put(String.valueOf(k), String.valueOf(v)));
            }
        }
        return result;
    }

    /**
     * The directory the run would execute in — the game directory, not the project directory.
     *
     * <p>Staged late like the classpath, and by both loaders: ModDevGradle and loom each call
     * {@code setWorkingDir} as a statement inside their own task action. Read from outside without
     * this, {@code getWorkingDir()} answers the project directory, and a companion started there
     * does not fail — it runs perfectly well and writes {@code config/}, {@code downloads/},
     * {@code options.txt} and {@code servers.dat} into the repository. The run is green and the
     * working tree is dirty, which is the kind of wrong that only shows up in {@code git status}.
     */
    /** {@link #resolveWorkingDir} for callers outside the launch path — the provision task needs the
     *  companion's run directory before anything is started, to install into it. */
    static File runDirectoryOf(JavaExec spec) {
        return resolveWorkingDir(spec);
    }

    private static File resolveWorkingDir(JavaExec spec) {
        // ModDevGradle: a DirectoryProperty naming the game directory.
        Object gameDirectory = readProperty(spec, "getGameDirectory");
        if (gameDirectory instanceof org.gradle.api.file.DirectoryProperty property
                && property.isPresent()) {
            return property.get().getAsFile();
        }
        // loom: the run directory as a string, relative to a separately-stashed project directory.
        Object runDir = readProperty(spec, "getInternalRunDir");
        Object projectDir = readProperty(spec, "getProjectDir");
        if (runDir instanceof org.gradle.api.provider.Property<?> runProperty
                && projectDir instanceof org.gradle.api.provider.Property<?> projectProperty
                && runProperty.isPresent() && projectProperty.isPresent()) {
            return new File(String.valueOf(projectProperty.get()), String.valueOf(runProperty.get()));
        }
        return spec.getWorkingDir();
    }

    /**
     * Invoke a zero-argument getter if the task happens to have one, else null.
     *
     * <p>Walks the hierarchy rather than asking only for public methods: loom stashes its run
     * directory behind a {@code protected abstract} getter, which {@code getMethod} cannot see at
     * all.
     */
    private static Object readProperty(JavaExec spec, String getter) {
        for (Class<?> type = spec.getClass(); type != null; type = type.getSuperclass()) {
            try {
                java.lang.reflect.Method method = type.getDeclaredMethod(getter);
                method.setAccessible(true);
                return method.invoke(spec);
            } catch (NoSuchMethodException e) {
                // Keep walking — the getter may be declared further up.
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Put the run's classpath in a java argument file and return the {@code @file} argument, or null
     * when the run carries no classpath at all.
     *
     * <p>A file rather than command-line arguments because a modded dev classpath runs to hundreds of
     * entries, well past the ~32k Windows allows in a command line.
     *
     * <p>Separators are normalised to {@code /}: a java argument file treats backslash as an escape
     * character, so a Windows path written verbatim silently loses its separators. The JVM accepts
     * forward slashes in classpath entries on every platform, which makes normalising simpler and
     * safer than escaping.
     */
    private static String writeClasspathArgFile(org.gradle.api.file.FileCollection cp, File logFile) {
        String classpath = cp.getAsPath();
        if (classpath == null || classpath.isBlank()) return null;

        File argFile = new File(logFile.getParentFile(), logFile.getName() + ".classpath.args");
        String body = "-cp \"" + classpath.replace('\\', '/') + "\"\n";
        try {
            argFile.getParentFile().mkdirs();
            java.nio.file.Files.writeString(argFile.toPath(), body,
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write the companion classpath argfile " + argFile, e);
        }
        return "@" + argFile.getAbsolutePath();
    }

    /**
     * The java binary a run task would use.
     *
     * <p>Three sources, in the order they win. {@code javaLauncher} is what a modern loader plugin
     * sets — ModDevGradle configures its runs through the toolchain service and leaves
     * {@code executable} null, which is how the first attempt at this got a
     * {@code NullPointerException} out of {@code ProcessBuilder} with nothing in it to say why.
     * {@code executable} covers a build that pinned one by hand. The JVM running Gradle is the last
     * resort, and it is a real answer rather than a guess: the daemon is already on a JDK this build
     * chose.
     */
    private static String javaBinary(JavaExec spec) {
        if (spec.getJavaLauncher().isPresent()) {
            return spec.getJavaLauncher().get().getExecutablePath().getAsFile().getAbsolutePath();
        }
        String executable = spec.getExecutable();
        if (executable != null && !executable.isBlank()) {
            return executable;
        }
        return new File(new File(System.getProperty("java.home"), "bin"),
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")
                        ? "java.exe" : "java").getAbsolutePath();
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

    /** A started Xvfb and the display it owns. */
    record VirtualDisplay(Process process, String display) {}

    /**
     * Start an X virtual framebuffer on a probed-free display, or return null when one is neither
     * needed nor possible.
     *
     * <p>Null rather than an exception for every "not applicable" case — a developer on a real
     * desktop asking for the client topology should get their own screen, not a failure telling them
     * their machine is not a CI box.
     */
    static VirtualDisplay startVirtualDisplay(Logger logger) {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) {
            return null;
        }
        String existing = System.getenv("DISPLAY");
        if (existing != null && !existing.isBlank()) {
            logger.lifecycle("[stagewright] DISPLAY={} is already set — using it rather than"
                    + " starting an Xvfb", existing);
            return null;
        }

        int number = probeFreeDisplay();
        if (number < 0) {
            throw new GradleException("virtualDisplay was requested but every display between :"
                    + DISPLAY_PROBE_FROM + " and :" + DISPLAY_PROBE_TO + " is taken");
        }
        String display = ":" + number;
        try {
            Process process = new ProcessBuilder(
                    "Xvfb", display, "-screen", "0", "1280x720x24", "-nolisten", "tcp")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            // Xvfb exits immediately if the display is taken by something that left no socket
            // behind, and a client pointed at a dead display hangs rather than failing — so the
            // cheap check here saves a timeout later.
            if (process.waitFor(1, TimeUnit.SECONDS)) {
                throw new GradleException("Xvfb exited immediately on " + display
                        + " (code " + process.exitValue() + ")");
            }
            logger.lifecycle("[stagewright] Xvfb on {} (pid {})", display, process.pid());
            return new VirtualDisplay(process, display);
        } catch (IOException e) {
            throw new GradleException("virtualDisplay was requested but Xvfb could not be started"
                    + " — install it, or drop the flag and provide a DISPLAY", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GradleException("interrupted while starting Xvfb", e);
        }
    }

    static void stopVirtualDisplay(VirtualDisplay virtualDisplay, Logger logger) {
        if (virtualDisplay == null) return;
        virtualDisplay.process().destroy();
        try {
            if (!virtualDisplay.process().waitFor(5, TimeUnit.SECONDS)) {
                virtualDisplay.process().destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            virtualDisplay.process().destroyForcibly();
        }
        logger.lifecycle("[stagewright] Xvfb on {} stopped", virtualDisplay.display());
    }

    /**
     * The lowest display with no X socket under {@code /tmp/.X11-unix}.
     *
     * <p>Probed, never pinned. The orchestrators this replaces originally hardcoded {@code :99} and
     * the note explaining why that was wrong — "the live dev client may own it" — outlived several
     * rewrites of everything around it.
     */
    private static int probeFreeDisplay() {
        for (int n = DISPLAY_PROBE_FROM; n <= DISPLAY_PROBE_TO; n++) {
            if (!new File("/tmp/.X11-unix/X" + n).exists()) return n;
        }
        return -1;
    }
}
