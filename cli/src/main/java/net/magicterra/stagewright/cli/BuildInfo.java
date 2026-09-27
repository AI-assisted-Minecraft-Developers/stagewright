package net.magicterra.stagewright.cli;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.TreeMap;

/**
 * What this jar was built from: when, and which engine and framework builds it carries.
 *
 * <p>Printed at the start of every run. The engine and the framework are published to mavenLocal by
 * separate builds, and a CLI built after forgetting one of them carries the old code without any
 * sign of it — the publish times printed here are that sign.
 */
final class BuildInfo {

    private static final String RESOURCE = "/stagewright-cli-build.properties";

    private BuildInfo() {}

    /** One line per part, the first saying when the jar was built. */
    static List<String> describe() {
        Properties p = new Properties();
        try (InputStream in = BuildInfo.class.getResourceAsStream(RESOURCE)) {
            if (in == null) return List.of("build info missing — this jar was not built by cli/build.gradle");
            p.load(in);
        } catch (IOException e) {
            return List.of("build info unreadable: " + e);
        }
        List<String> out = new ArrayList<>();
        out.add("stagewright CLI built " + p.getProperty("built", "?"));
        TreeMap<String, String> parts = new TreeMap<>();
        p.stringPropertyNames().stream().filter(k -> !k.equals("built"))
                .forEach(k -> parts.put(k, p.getProperty(k)));
        // Each value is "<published instant> <sha1 prefix>".
        parts.forEach((jar, value) -> out.add("  " + jar + " published " + value.replace(" ", ", sha1 ")));
        return out;
    }
}
