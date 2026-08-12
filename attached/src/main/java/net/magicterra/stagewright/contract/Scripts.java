package net.magicterra.stagewright.contract;

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
import dev.latvian.mods.rhino.WrappedException;

/**
 * Turning {@code .js} text into scenes, and running one — the whole interpreter half, shared.
 *
 * <p>This class is the reason a scene file can claim to run in two places. Both homes call
 * {@link #load} to parse and {@link #run} to execute, so the prelude, the registration protocol, the
 * instruction cap, the exception unwrapping and the error phrasing are ONE implementation. What each
 * home supplies is only what differs: the globals it installs ({@link ScopeSetup}) and the object a
 * body receives as {@code s}.
 *
 * <p><b>{@link #unwrapOurs} is here for a specific reason, not for tidiness.</b> Rhino wraps any Java
 * exception thrown out of a Java method a script called into a {@link WrappedException}, so an
 * {@code instanceof SceneSkipped} against the raw throwable is false for everything a facet raises.
 * That bug lived for a long time because it is invisible on a failure — a wrapped {@code SceneFailure}
 * still reaches {@code fail} and still reports FAIL. It is only visible on a SKIP, where it inverts
 * the framework's central rule: a scene file asking for a player on a topology without one was
 * reported FAILED, for every absent mod, dimension and player in every pack. A hand-copied second
 * runner would reintroduce it verbatim, and would hide for just as long. Sharing the code is what
 * makes that impossible rather than merely unlikely.
 *
 * <p>MC-free by construction: no Minecraft type appears here, and logging goes through a
 * {@link java.util.function.Consumer} because a Gradle log, a CLI's stdout and the game's logger have
 * nothing in common except that all three accept a line of text.
 */
public final class Scripts {

    private static final String PRELUDE_RESOURCE = "/data/stagewright/scenes-prelude.js";

    /**
     * Instructions a single scene-body call may execute before it is killed.
     *
     * <p>The one guard neither home can do without. A Java scene body that loops forever is caught in
     * review by people who know a body runs inline on the server tick; a pack author writing
     * {@code while (!done) {}} has no reason to know that, and in-process the result is not a failed
     * scene but a dedicated server that stops ticking — no timeout fires, because timeouts are
     * counted in ticks and ticks have stopped. Rhino's instruction observer is the only thing that
     * can interrupt a running script from outside.
     *
     * <p>Ten million is far above anything a body legitimately does (they register awaits and return)
     * and far below a wall-clock hang.
     */
    private static final int BODY_INSTRUCTION_BUDGET = 10_000_000;

    /** How often Rhino reports progress. Small enough to notice a runaway promptly, large enough that
     *  the check itself is not the cost. */
    private static final int OBSERVER_THRESHOLD = 100_000;

    private Scripts() {}

    /** What a home installs into a freshly-created scope: its bridge, its driver access, whatever
     *  else only makes sense where it runs. Called once per file, before the prelude. */
    @FunctionalInterface
    public interface ScopeSetup {
        void install(Context cx, ScriptableObject scope, String fileName);
    }

    /**
     * Load every {@code .js} file under a directory, in name order.
     *
     * <p>Name order, not directory order, so a pack's scene sequence is the same on every machine —
     * filesystem enumeration order is not, and a suite whose execution order drifts between hosts
     * produces failures that only reproduce on one of them.
     */
    public static List<SceneSpec> load(Path dir, ScopeSetup setup, java.util.function.Consumer<String> log) {
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

        List<SceneSpec> out = new ArrayList<>();
        for (Path file : files) out.addAll(loadFile(file, setup));
        log.accept("loaded " + out.size() + " scene(s) from " + files.size() + " file(s) under "
                + dir.toAbsolutePath());
        return out;
    }

    private static List<SceneSpec> loadFile(Path file, ScopeSetup setup) {
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
            // Java access is universal — both homes give scripts the same terms, on the same trust
            // boundary (see JavaAccess). Installed here rather than left to each home's ScopeSetup
            // precisely because "the same terms" is the claim: two homes each remembering to install
            // it would be two places for that claim to stop being true.
            JavaAccess.install(cx, scope);
            setup.install(cx, scope, name);
            cx.evaluateString(scope, prelude(), "<stagewright-prelude>", 1, null);
            cx.evaluateString(scope, source, name, 1, null);
            return harvest(cx, scope, name);
        } catch (RuntimeException e) {
            // Registration failures abort arming rather than dropping the file. A pack author whose
            // scene file has a syntax error must not get a green run over the scenes that happened to
            // parse — that is the same silent-composition hole the expected-scenes manifest exists to
            // close, arriving by a different door.
            throw new IllegalStateException("scene file " + name + " failed to load: " + message(e), e);
        }
    }

    /** Pull the registrations the prelude collected into {@link SceneSpec}s. */
    private static List<SceneSpec> harvest(Context cx, ScriptableObject scope, String file) {
        Object raw = ScriptableObject.getProperty(scope, "__scenes", cx);
        if (!(raw instanceof NativeArray array)) return List.of();

        List<SceneSpec> out = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            Object entry = array.get(cx, i, array);
            if (!(entry instanceof Scriptable s)) continue;
            String name = String.valueOf(ScriptableObject.getProperty(s, "name", cx));
            int budget = (int) cx.toNumber(ScriptableObject.getProperty(s, "budgetTicks", cx));
            boolean optional = cx.toBoolean(ScriptableObject.getProperty(s, "optional", cx));
            Object body = ScriptableObject.getProperty(s, "body", cx);
            if (!(body instanceof Function)) {
                throw new IllegalStateException("scene '" + name + "' in " + file + " has no body");
            }
            out.add(new SceneSpec(name, budget, optional,
                    terrainOf(cx, s, name, file), clockOf(cx, s, name, file), dimensionOf(cx, s),
                    body, scope));
        }
        return out;
    }

    /** Read {@code options.terrain}, naming the file and scene if it is not a terrain we ship. The
     *  prelude always sets the property, so an absent one means a scene file built its registration by
     *  hand — treat that as the default rather than as an error. */
    private static Terrain terrainOf(Context cx, Scriptable s, String name, String file) {
        Object raw = ScriptableObject.getProperty(s, "terrain", cx);
        if (absent(raw)) return Terrain.RUN_WORLD;
        try {
            return Terrain.parse(String.valueOf(raw));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("scene '" + name + "' in " + file + ": " + e.getMessage(), e);
        }
    }

    /** Read {@code options.clock}. Same absent-means-default rule as {@link #terrainOf}. */
    private static Clock clockOf(Context cx, Scriptable s, String name, String file) {
        Object raw = ScriptableObject.getProperty(s, "clock", cx);
        if (absent(raw)) return Clock.MIDNIGHT;
        try {
            return Clock.parse(String.valueOf(raw));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("scene '" + name + "' in " + file + ": " + e.getMessage(), e);
        }
    }

    /**
     * Read {@code options.dimension} — a dimension one of the PACK's mods registers.
     *
     * <p>Unlike terrain and clock this is NOT validated here, and deliberately: those name values
     * StageWright itself defines, so a typo is knowably a typo. A dimension id belongs to some other
     * mod, and nothing here can tell "misspelled" from "that mod is not installed in this particular
     * run" — a legitimate, expected state for a pack scene. Whoever can actually ask the server
     * resolves it and records a skip naming both the wanted dimension and the ones that ARE loaded.
     */
    private static String dimensionOf(Context cx, Scriptable s) {
        Object raw = ScriptableObject.getProperty(s, "dimension", cx);
        if (absent(raw)) return null;
        String id = String.valueOf(raw).trim();
        return id.isEmpty() || "null".equals(id) ? null : id;
    }

    private static boolean absent(Object raw) {
        return raw == null || raw == Scriptable.NOT_FOUND || Undefined.isUndefined(raw);
    }

    /**
     * Run one scene body, handing it {@code ctx} as its {@code s} argument, under the instruction cap.
     *
     * <p>A fresh Context per call rather than one held open across the suite: the cap has to be
     * per-body, and a Context carries its observer state with it. Entering one is cheap next to
     * everything else a tick does.
     */
    public static void run(SceneSpec spec, SceneReport ctx) {
        Scriptable scope = (Scriptable) spec.scope();
        Function body = (Function) spec.body();
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
            ctx.fail("scene '" + spec.name() + "' ran " + BODY_INSTRUCTION_BUDGET + " instructions"
                    + " without returning — a scene body registers awaits and returns, it does not"
                    + " loop waiting for the world to change (the world cannot change while it runs)");
        } catch (RuntimeException e) {
            // Anything the body threw, including a SceneFailure raised by fail(), must travel on
            // untouched — the harness recognises it and the run reports the assertion the author
            // wrote, not a wrapper around it.
            RuntimeException own = unwrapOurs(e);
            if (own != null) throw own;
            ctx.fail(explainIntermediary(message(e)));
        }
    }

    /**
     * The {@link SceneFailure} or {@link SceneSkipped} inside whatever Rhino handed back, or null.
     *
     * <p>See the class comment: testing the raw throwable is a bug that is invisible on a failure and
     * inverts the framework's central rule on a skip.
     */
    public static RuntimeException unwrapOurs(RuntimeException e) {
        Throwable current = e;
        for (int hops = 0; current != null && hops < 8; hops++) {
            if (current instanceof SceneFailure || current instanceof SceneSkipped) {
                return (RuntimeException) current;
            }
            current = current instanceof WrappedException wrapped
                    ? wrapped.getWrappedException()
                    : current.getCause();
        }
        return null;
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
     * <p>A scene file that calls a method on a Minecraft object works in a dev run and on a production
     * NeoForge server (both mojmap) and fails on a production Fabric server, where the jar is remapped
     * to intermediary and the method is named {@code method_10263}. Rhino's own message — {@code Cannot
     * find function getX in object class_2338} — is accurate and useless: it names neither the cause
     * nor the fix, and the obvious reading (a StageWright bug) is wrong.
     *
     * <p>Detected by the intermediary naming scheme rather than by a list of types, because the point
     * is not which class it was. If a {@code class_1234} reached a scene author's error message at all,
     * they crossed the boundary this explains.
     */
    public static String explainIntermediary(String message) {
        if (message == null || !INTERMEDIARY.matcher(message).find()) return message;
        return message + " — this is a remapped Minecraft name: the scene called a method ON a"
                + " Minecraft object, which only works where the jar is mojmap (a dev run, or a"
                + " NeoForge server). Scene files may hold and pass Minecraft objects but must call"
                + " only StageWright's own methods on them — for a position, use ctx.originX() and"
                + " friends rather than ctx.origin().getX().";
    }

    /** Strip the Java exception class name Rhino prefixes onto wrapped errors. */
    public static String message(Throwable t) {
        String raw = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
        int colon = raw.indexOf(": ");
        if (colon > 0 && raw.substring(0, colon).matches("[\\w.$]*(Exception|Error)")) {
            return raw.substring(colon + 2);
        }
        return raw;
    }

    private static String prelude() {
        try (InputStream in = Scripts.class.getResourceAsStream(PRELUDE_RESOURCE)) {
            if (in == null) throw new IllegalStateException("missing classpath resource " + PRELUDE_RESOURCE);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("failed to load " + PRELUDE_RESOURCE, e);
        }
    }
}
