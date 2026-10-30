package net.magicterra.stagewright.test;

/**
 * Values a test keeps for the tests after a restart: what one test records before the game restarts, an
 * {@code afterRestart} test reads after. Values are kept as JSON in the game's directory, so a value is plain (a
 * string, number, boolean, list or map) or a record or class Gson writes; a scene starts with none.
 */
public interface Store {

    /** Keep {@code value} under {@code key}, replacing what was there. */
    void put(String key, Object value);

    /** The plain value under {@code key}: a string, number, boolean, list or map; null when there is none. */
    Object get(String key);

    /** The value under {@code key} read as {@code type}; null when there is none. */
    <T> T get(String key, Class<T> type);
}
