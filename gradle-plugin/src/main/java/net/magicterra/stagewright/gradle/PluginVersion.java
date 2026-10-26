package net.magicterra.stagewright.gradle;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/** The version this plugin was built as, which is also the version of the artifacts it pairs with. */
final class PluginVersion {

    private static final String RESOURCE = "/stagewright-plugin.properties";

    private PluginVersion() {}

    static String get() {
        return property("version");
    }

    /** The JUnit Jupiter version StageWright's in-game runner is built with. */
    static String junit() {
        return property("junit");
    }

    private static String property(String key) {
        try (InputStream in = PluginVersion.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException(RESOURCE + " is missing from the plugin jar");
            Properties properties = new Properties();
            properties.load(in);
            String value = properties.getProperty(key);
            if (value == null) throw new IllegalStateException(RESOURCE + " has no " + key);
            return value;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
