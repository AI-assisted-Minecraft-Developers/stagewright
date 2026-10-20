package net.magicterra.stagewright.contract.remote;

import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.Scriptable;
import dev.latvian.mods.rhino.type.TypeInfo;
import dev.latvian.mods.rhino.util.CustomJavaToJsWrapper;

/**
 * An object that lives in the game, as the out-of-process home holds it: a number the game gave it,
 * and the session that gave it.
 *
 * <p>Deliberately empty of methods. What a script can call on one is decided by the object it stands
 * for, not by this class, so the list is whatever the game-side class offers and nobody here has to
 * keep it in step.
 */
public final class RemoteRef implements CustomJavaToJsWrapper {
    private final RemoteSession session;
    private final int handle;
    private final String type;

    RemoteRef(RemoteSession session, int handle, String type) {
        this.session = session;
        this.handle = handle;
        this.type = type;
    }

    RemoteSession session() {
        return session;
    }

    int handle() {
        return handle;
    }

    /** The simple name of the class the game holds, for the messages that name it. */
    String type() {
        return type;
    }

    /** Wherever Rhino would wrap this as a plain Java object — a return value, a list's element — it is a face. */
    @Override
    public Scriptable convertJavaToJs(Context cx, Scriptable scope, TypeInfo staticType) {
        return new RemoteFace(scope, this, cx, session, handle);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof RemoteRef r && r.session == session && r.handle == handle;
    }

    @Override
    public int hashCode() {
        return 31 * System.identityHashCode(session) + handle;
    }

    @Override
    public String toString() {
        return type + "#" + handle;
    }
}
