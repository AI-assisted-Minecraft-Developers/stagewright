package net.magicterra.stagewright.harness.remote;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The objects one out-of-process scene has been handed, by the number it was handed them under.
 *
 * <p>Held for the scene only: the table goes with the session, so a number from one scene means
 * nothing in the next. The same object always gets the same number, because a script that compares
 * two answers expects "the same block entity" to stay the same one. Capped, because a loop that asks
 * for a fresh object a million times would otherwise grow the server's heap for as long as the scene
 * runs, and nothing else here would notice.
 */
public final class Handles {
    /** Number 0 is the scene's own context; objects start after it. */
    public static final int CONTEXT = 0;

    /** More than a scene needs, and few enough that the table cannot hurt the server. */
    static final int LIMIT = 10_000;

    private final List<Object> byNumber = new ArrayList<>();
    private final Map<Object, Integer> byObject = new IdentityHashMap<>();

    public Handles(Object context) {
        byNumber.add(context);
        byObject.put(context, CONTEXT);
    }

    /** The number {@code object} goes by, the same one every time. */
    public int numberFor(Object object) {
        Integer known = byObject.get(object);
        if (known != null) return known;
        if (byNumber.size() >= LIMIT) {
            throw new IllegalStateException("this scene has been handed " + LIMIT + " objects by the game and"
                    + " cannot hold more; ask for values (numbers, strings, lists) instead of objects, or"
                    + " split the scene");
        }
        int number = byNumber.size();
        byNumber.add(object);
        byObject.put(object, number);
        return number;
    }

    /** The object handed out as {@code number}. */
    public Object get(int number) {
        if (number < 0 || number >= byNumber.size()) {
            throw new IllegalArgumentException("no object #" + number + " in this scene: the game handed out "
                    + (byNumber.size() - 1) + ". A number from another scene's session means nothing here.");
        }
        return byNumber.get(number);
    }

    /** Forget every object; the scene is over. */
    public void clear() {
        Object context = byNumber.get(CONTEXT);
        byNumber.clear();
        byObject.clear();
        byNumber.add(context);
        byObject.put(context, CONTEXT);
    }

    public int size() {
        return byNumber.size() - 1;
    }
}
