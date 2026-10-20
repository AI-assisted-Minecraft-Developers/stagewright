package net.magicterra.stagewright.contract.remote;

import dev.latvian.mods.rhino.BaseFunction;
import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.NativeJavaObject;
import dev.latvian.mods.rhino.Scriptable;
import dev.latvian.mods.rhino.type.TypeInfo;

/**
 * What a script holds for something that lives in the game: the Java object that stands for it here,
 * with every name that object does not answer sent to the game.
 *
 * <p>The object's own methods win, so {@code toString} and the few things a runner defines locally
 * behave as on any Java object. Everything else — the whole of what the game-side class offers — is
 * looked up there when it is called, which is why nothing in this module lists it. A name the game
 * does not know comes back as the game's refusal, naming the class and the arity it tried.
 */
public final class RemoteFace extends NativeJavaObject {
    private final RemoteSession session;
    private final int target;

    /**
     * @param javaObject the local stand-in the script is holding
     * @param session    the game to ask; null for a context that has none, whose calls fail by saying so
     * @param target     {@link Wire#CONTEXT} or the handle of the object this stands for
     */
    public RemoteFace(Scriptable scope, Object javaObject, Context cx, RemoteSession session, int target) {
        super(scope, javaObject, TypeInfo.of(javaObject.getClass()), cx);
        this.session = session;
        this.target = target;
    }

    @Override
    public Object get(Context cx, String name, Scriptable start) {
        Object own = super.get(cx, name, start);
        if (own != Scriptable.NOT_FOUND && own != null) return own;
        return new RemoteMethod(name, getParentScope());
    }

    /** One name on a remote object, callable. */
    private final class RemoteMethod extends BaseFunction {
        private final String name;

        RemoteMethod(String name, Scriptable scope) {
            this.name = name;
            setParentScope(scope);
        }

        @Override
        public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
            if (session == null) {
                throw new net.magicterra.stagewright.contract.SceneFailure("'" + name + "' needs the game, and"
                        + " this context has no session in it");
            }
            return cx.javaToJS(session.call(target, name, args), scope);
        }

        @Override
        public String getFunctionName() {
            return name;
        }
    }
}
