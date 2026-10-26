package net.magicterra.stagewright.testengine;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * A scene's declaration, {@code stagewright/scenes/<name>.yaml} on the test class path. Only {@code topology} is
 * applied so far; a declaration naming anything else is refused rather than run without it.
 */
record SceneFile(String name, String topology) {

    static final String DIRECTORY = "stagewright/scenes/";
    private static final Set<String> APPLIED = Set.of("topology");

    /** The declaration of scene {@code name}; refuses a missing or malformed one, saying what is wrong. */
    static SceneFile load(String name, ClassLoader loader) {
        String path = DIRECTORY + name + ".yaml";
        Object root;
        try (InputStream in = loader.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("scene '" + name + "' has no declaration: expected " + path
                        + " on the test class path");
            }
            root = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
        } catch (IOException e) {
            throw new IllegalStateException("scene '" + name + "': " + path + " could not be read", e);
        }
        if (!(root instanceof Map<?, ?> map)) {
            throw new IllegalStateException("scene '" + name + "': " + path + " is not a mapping");
        }
        Set<String> unapplied = new TreeSet<>();
        for (Object key : map.keySet()) {
            if (!APPLIED.contains(String.valueOf(key))) unapplied.add(String.valueOf(key));
        }
        if (!unapplied.isEmpty()) {
            throw new IllegalStateException("scene '" + name + "' declares " + unapplied + ", which this StageWright"
                    + " does not apply yet; only " + APPLIED + " is");
        }
        if (!(map.get("topology") instanceof String topology) || topology.isBlank()) {
            throw new IllegalStateException("scene '" + name + "': " + path + " names no topology");
        }
        return new SceneFile(name, topology);
    }
}
