package net.magicterra.stagewright.contract.remote;

/**
 * Names both ends of the attached-scene protocol agree on.
 *
 * <p>Here rather than in the game-side module because the out-of-process home has no Minecraft and
 * still has to spell every method and marker the same way. The game-side implementation is
 * {@code mc.test.attached.*} in {@code :stagewright-common}; this class is its only specification
 * that a compiler can check from both sides.
 */
public final class Wire {
    private Wire() {}

    /**
     * Bumped when a message changes shape. {@link #BEGIN} carries it, so a runner and a game that
     * disagree stop at the first call instead of at a field one of them does not know.
     */
    public static final int PROTOCOL = 1;

    /** Pin the world for the out-of-process phase. */
    public static final String BEGIN = "mc.test.attached.begin";
    /** Hand the world back; must precede {@code mc.test.run}. */
    public static final String END = "mc.test.attached.end";
    /** Ask for an arena for one scene. Answers at once; the arena arrives over the ticks that follow. */
    public static final String OPEN = "mc.test.attached.open";
    /** Whether the arena a session asked for has arrived. */
    public static final String STATUS = "mc.test.attached.status";
    /** Call one method on the scene's game-side context, or on an object it handed out. */
    public static final String CALL = "mc.test.attached.call";
    /** Call a driver method on the server thread, the way an in-process scene does. */
    public static final String ROUTE = "mc.test.attached.route";
    /** Let the arena go and answer for what the scene left behind. */
    public static final String CLOSE = "mc.test.attached.close";

    /** The target of a {@link #CALL} that means the scene's own context rather than an object it handed out. */
    public static final int CONTEXT = 0;

    /** A value that is a reference to an object living in the game: {@code {"$handle": 7, "type": "Closure"}}. */
    public static final String HANDLE = "$handle";
    /** A value that names something in a registry: {@code {"$id": "block", "id": "minecraft:stone"}}. */
    public static final String ID = "$id";
    /** A map whose own keys begin with {@code $}, so it cannot be mistaken for either marker above. */
    public static final String MAP = "$map";
    /** An argument that is null: {@code {"$null": true}}, since a call's argument list cannot hold a bare JSON null. */
    public static final String NULL = "$null";

    /** Session states a {@link #STATUS} answers with. */
    public static final String PREPARING = "preparing";
    public static final String READY = "ready";
    public static final String FAILED = "failed";
    public static final String SKIPPED = "skipped";

    /** Why a {@link #CALL} did not return a value: the exception it ended in, by kind. */
    public static final String KIND_SKIPPED = "skipped";
    public static final String KIND_FAILED = "failed";
    public static final String KIND_ILLEGAL_ARGUMENT = "illegalArgument";
    public static final String KIND_UNSUPPORTED = "unsupported";
    public static final String KIND_ERROR = "error";
}
