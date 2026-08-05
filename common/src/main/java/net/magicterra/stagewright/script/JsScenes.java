package net.magicterra.stagewright.script;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.ContextFactory;
import dev.latvian.mods.rhino.Function;
import dev.latvian.mods.rhino.NativeArray;
import dev.latvian.mods.rhino.Scriptable;
import dev.latvian.mods.rhino.ScriptableObject;
import dev.latvian.mods.rhino.Undefined;
import net.magicterra.stagewright.StageWrightCommon;
import net.magicterra.stagewright.scene.Clock;
import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.Terrain;

/**
 * Scenes written in JavaScript, loaded from the run directory rather than compiled into a mod.
 *
 * <p>This exists for people who ship a modpack. They have a {@code mods/} folder and a
 * {@code config/} folder and no build at all — asking them to stand up a Gradle project with a
 * {@code testmod} source set to assert that their pack still works is asking them to become mod
 * developers first. A file of scenes beside their configs is not a lesser version of the Java
 * surface: it registers into the SAME registry, runs on the SAME harness, is caught by the SAME
 * canaries, and is reconciled against the SAME expected-scenes manifest. In the results file a JS
 * scene and a Java scene are indistinguishable, which is the point.
 *
 * <p><b>Requires Rhino</b>, which arrives with worlddriver. Absent it, this class is never loaded
 * and scene files are reported as ignored rather than silently skipped — a pack author who wrote
 * scenes and got a green run that never executed them has been told nothing at all.
 */
public final class JsScenes {

    /** Where a pack keeps its scenes, relative to the game directory. */
    private static final String SCENES_DIR = "config/stagewright/scenes";

    private static final String PRELUDE_RESOURCE = "/data/stagewright/scenes-prelude.js";

    /**
     * Instructions a single scene-body call may execute before it is killed.
     *
     * <p>The one guard this file cannot do without. A Java scene body that loops forever is caught
     * in review by people who know that a body runs inline on the server tick; a pack author writing
     * {@code while (!done) {}} has no reason to know that, and the result is not a failed scene but a
     * dedicated server that stops ticking — no timeout fires, because timeouts are counted in ticks
     * and ticks have stopped. Rhino's instruction observer is the only thing that can interrupt
     * a running script from outside, so it is what stands between a typo and a hung gate.
     *
     * <p>Ten million is far above anything a scene body legitimately does (they register awaits and
     * return) and far below a wall-clock hang.
     */
    private static final int BODY_INSTRUCTION_BUDGET = 10_000_000;

    /** How often Rhino reports progress. Small enough to notice a runaway promptly, large enough
     *  that the check itself is not the cost. */
    private static final int OBSERVER_THRESHOLD = 100_000;

    private JsScenes() {}

    /**
     * Load every {@code .js} file under the scenes directory, in name order.
     *
     * <p>Name order, not directory order, so a pack's scene sequence is the same on every machine —
     * filesystem enumeration order is not, and a suite whose execution order drifts between hosts
     * produces failures that only reproduce on one of them.
     */
    public static List<Scene> load() {
        Path dir = Path.of(SCENES_DIR);
        if (!Files.isDirectory(dir)) return List.of();

        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.list(dir)) {
            walk.filter(p -> p.getFileName().toString().endsWith(".js"))
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .forEach(files::add);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read the scenes directory " + dir.toAbsolutePath(), e);
        }
        if (files.isEmpty()) return List.of();

        List<Scene> out = new ArrayList<>();
        for (Path file : files) out.addAll(loadFile(file));
        StageWrightCommon.LOG.info("[{}] loaded {} scene(s) from {} file(s) under {}",
                StageWrightCommon.MOD_ID, out.size(), files.size(), dir.toAbsolutePath());
        return out;
    }

    private static List<Scene> loadFile(Path file) {
        String name = file.getFileName().toString();
        String source;
        try {
            source = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read scene file " + file, e);
        }

        ContextFactory factory = new ContextFactory();
        Context cx = factory.enter();
        try {
            // No instruction cap while REGISTERING. A scene file's top level is allowed to build
            // tables, read its own data and loop over them — it runs once, at arm time, on a server
            // that is not yet ticking scenes. The cap belongs on the body, which runs on the tick.
            ScriptableObject scope = cx.initStandardObjects();
            ScriptableObject.putProperty(scope, "__bridge",
                    cx.javaToJS(new JsBridge(name), scope), cx);
            // Java, on the same terms KubeJS gives it. See JavaAccess for the trust boundary and for
            // why the fork's own initStandardObjects() does not supply this.
            JavaAccess.install(cx, scope);
            DriverAccess.install(cx, scope);
            cx.evaluateString(scope, prelude(), "<stagewright-prelude>", 1, null);
            cx.evaluateString(scope, source, name, 1, null);
            return harvest(cx, scope, name);
        } catch (RuntimeException e) {
            // Registration failures abort arming rather than dropping the file. A pack author whose
            // scene file has a syntax error must not get a green run over the scenes that happened
            // to parse — that is the same silent-composition hole the expected-scenes manifest
            // exists to close, arriving by a different door.
            throw new IllegalStateException("scene file " + name + " failed to load: " + message(e), e);
        }
    }

    /** Pull the registrations the prelude collected into real {@link Scene}s. */
    private static List<Scene> harvest(Context cx, ScriptableObject scope, String file) {
        Object raw = ScriptableObject.getProperty(scope, "__scenes", cx);
        if (!(raw instanceof NativeArray array)) return List.of();

        List<Scene> out = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            Object entry = array.get(cx, i, array);
            if (!(entry instanceof Scriptable s)) continue;
            String name = String.valueOf(ScriptableObject.getProperty(s, "name", cx));
            int budget = (int) cx.toNumber(ScriptableObject.getProperty(s, "budgetTicks", cx));
            boolean optional = cx.toBoolean(ScriptableObject.getProperty(s, "optional", cx));
            Object body = ScriptableObject.getProperty(s, "body", cx);
            if (!(body instanceof Function fn)) {
                throw new IllegalStateException("scene '" + name + "' in " + file + " has no body");
            }
            Scene scene = Scene.of(name, budget, ctx -> invoke(scope, fn, ctx, name))
                    .withTerrain(terrainOf(cx, s, name, file))
                    .withClock(clockOf(cx, s, name, file));
            out.add(optional ? scene.withRequired(false) : scene);
        }
        return out;
    }

    /** Read {@code options.terrain}, naming the file and scene if it is not a terrain we ship. The
     *  prelude always sets the property, so an absent one means a scene file built its registration
     *  by hand — treat that as the default rather than as an error. */
    private static Terrain terrainOf(Context cx, Scriptable s, String name, String file) {
        Object raw = ScriptableObject.getProperty(s, "terrain", cx);
        if (raw == null || raw == Scriptable.NOT_FOUND || Undefined.isUndefined(raw)) {
            return Terrain.RUN_WORLD;
        }
        try {
            return Terrain.parse(String.valueOf(raw));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("scene '" + name + "' in " + file + ": " + e.getMessage(), e);
        }
    }

    /** Read {@code options.clock}. Same absent-means-default rule as {@link #terrainOf}: the prelude
     *  always sets it, so a missing property is a hand-built registration, not a mistake. */
    private static Clock clockOf(Context cx, Scriptable s, String name, String file) {
        Object raw = ScriptableObject.getProperty(s, "clock", cx);
        if (raw == null || raw == Scriptable.NOT_FOUND || Undefined.isUndefined(raw)) {
            return Clock.MIDNIGHT;
        }
        try {
            return Clock.parse(String.valueOf(raw));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("scene '" + name + "' in " + file + ": " + e.getMessage(), e);
        }
    }

    /**
     * Run one scene body, on the server thread, under the instruction cap.
     *
     * <p>A fresh Context per call rather than one held open across the suite: the cap has to be
     * per-body, and a Context carries its observer state with it. Entering one is cheap next to
     * everything else a tick does.
     */
    private static void invoke(Scriptable scope, Function body, SceneContext ctx, String sceneName) {
        ContextFactory factory = new ContextFactory() {
            @Override
            protected Context createContext() {
                return new CappedContext(this);
            }
        };
        Context cx = factory.enter();
        try {
            cx.setInstructionObserverThreshold(OBSERVER_THRESHOLD);
            body.call(cx, scope, scope, new Object[] { cx.javaToJS(ctx, scope) });
        } catch (BodyTooLong e) {
            ctx.fail("scene '" + sceneName + "' ran " + BODY_INSTRUCTION_BUDGET + " instructions"
                    + " without returning — a scene body registers awaits and returns, it does not"
                    + " loop waiting for the world to change (the world cannot change while it runs)");
        } catch (RuntimeException e) {
            // Anything the body threw, including a SceneFailure raised by ctx.fail(), which must
            // travel on untouched — the harness recognises it and the run reports the assertion the
            // author wrote, not a wrapper around it.
            if (e instanceof net.magicterra.stagewright.scene.SceneFailure
                    || e instanceof net.magicterra.stagewright.scene.SceneSkipped) {
                throw e;
            }
            ctx.fail(explainIntermediary(message(e)));
        }
    }

    /** Marker for a body that blew its instruction budget, distinct from anything a scene throws. */
    private static final class BodyTooLong extends RuntimeException {
        BodyTooLong() { super(null, null, false, false); }
    }

    /** A Context that stops the script once the body's instruction budget is spent. */
    private static final class CappedContext extends Context {
        private long spent;

        CappedContext(ContextFactory factory) {
            super(factory);
        }

        @Override
        protected void observeInstructionCount(int instructionCount) {
            spent += OBSERVER_THRESHOLD;
            if (spent > BODY_INSTRUCTION_BUDGET) throw new BodyTooLong();
        }
    }

    /** Intermediary names ({@code class_2338}, {@code method_10263}) leaking into an error message. */
    private static final java.util.regex.Pattern INTERMEDIARY =
            java.util.regex.Pattern.compile("\\b(class|method|field)_\\d+\\b");

    /**
     * Explain the one failure that only happens in production, and only on one loader.
     *
     * <p>A scene file that calls a method on a Minecraft object works in a dev run and on a
     * production NeoForge server (both mojmap) and fails on a production Fabric server, where the
     * jar is remapped to intermediary and the method is named {@code method_10263}. Rhino's own
     * message — {@code Cannot find function getX in object class_2338} — is accurate and useless: it
     * names neither the cause nor the fix, and the obvious reading (a StageWright bug) is wrong.
     *
     * <p>Detected by the intermediary naming scheme rather than by a list of types, because the point
     * is not which class it was. If a {@code class_1234} reached a scene author's error message at
     * all, they crossed the boundary this explains.
     */
    private static String explainIntermediary(String message) {
        if (message == null || !INTERMEDIARY.matcher(message).find()) return message;
        return message + " — this is a remapped Minecraft name: the scene called a method ON a"
                + " Minecraft object, which only works where the jar is mojmap (a dev run, or a"
                + " NeoForge server). Scene files may hold and pass Minecraft objects but must call"
                + " only StageWright's own methods on them — for a position, use ctx.originX() and"
                + " friends rather than ctx.origin().getX().";
    }

    /** Strip the Java exception class name Rhino prefixes onto wrapped errors. */
    private static String message(Throwable t) {
        String raw = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
        int colon = raw.indexOf(": ");
        if (colon > 0 && raw.substring(0, colon).matches("[\\w.$]*(Exception|Error)")) {
            return raw.substring(colon + 2);
        }
        return raw;
    }

    private static String prelude() {
        try (InputStream in = JsScenes.class.getResourceAsStream(PRELUDE_RESOURCE)) {
            if (in == null) throw new IllegalStateException("missing classpath resource " + PRELUDE_RESOURCE);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("failed to load " + PRELUDE_RESOURCE, e);
        }
    }
}
