package net.magicterra.stagewright.contract;

import java.util.regex.Pattern;

/**
 * How what a scene's body or cleanup threw reads as its reason, one rule for both homes and for body
 * and cleanup alike.
 *
 * <p>Rhino-free, as {@link Cleanups} is: the in-process API calls this from packs with no Rhino on
 * the classpath, so a script's error is told apart by its class's package rather than by type.
 */
public final class Reasons {
    private Reasons() {}

    /** The package of every exception Rhino raises out of a script. */
    private static final String RHINO_PACKAGE = "dev.latvian.mods.rhino.";

    /** Intermediary names ({@code class_2338}, {@code method_10263}) leaking into an error message. */
    private static final Pattern INTERMEDIARY = Pattern.compile("\\b(class|method|field)_\\d+\\b");

    /**
     * A failure that is not a {@link SceneFailure} or {@link SceneSkipped}. A script's error reads as
     * the script said it, as a body's does; anything else is {@code unexpected <class>: <message>},
     * from the throwable itself rather than its innermost cause, which would drop the words of
     * whoever wrapped it.
     */
    public static String unexpected(Throwable t) {
        if (t.getClass().getName().startsWith(RHINO_PACKAGE)) return explainIntermediary(message(t));
        return "unexpected " + t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + message(t));
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
}
