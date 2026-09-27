package net.magicterra.stagewright.cli.install;

import java.util.Locale;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Mojang's rule blocks, evaluated once for both halves of a client: what the installer downloads and
 * what the launcher puts on the classpath. Two copies of this could disagree, and a library allowed at
 * launch but skipped at install fails as a missing jar that looks like a broken download.
 */
public final class Rules {

    /** The value rule blocks match {@code os.name} against. */
    static final String OS = os(System.getProperty("os.name", ""));

    private Rules() {}

    static String os(String osName) {
        String name = osName.toLowerCase(Locale.ROOT);
        if (name.contains("win")) return "windows";
        if (name.contains("mac") || name.contains("darwin")) return "osx";
        return "linux";
    }

    /**
     * Absent means allowed, otherwise the last matching rule wins.
     *
     * <p>Feature rules ({@code is_demo}, {@code has_custom_resolution}, quick-play) are skipped rather
     * than evaluated, because we ask for none of those features — and a rule we cannot evaluate must
     * not be allowed to turn an argument ON.
     */
    public static boolean allowed(JsonObject entry) {
        return allowed(entry, OS);
    }

    static boolean allowed(JsonObject entry, String os) {
        JsonArray rules = entry.getAsJsonArray("rules");
        if (rules == null || rules.isEmpty()) return true;
        boolean verdict = false;
        for (JsonElement element : rules) {
            JsonObject rule = element.getAsJsonObject();
            JsonObject ruleOs = rule.getAsJsonObject("os");
            if (ruleOs != null && ruleOs.has("name") && !os.equals(ruleOs.get("name").getAsString())) {
                continue;
            }
            if (rule.has("features")) continue;
            verdict = "allow".equals(rule.get("action").getAsString());
        }
        return verdict;
    }
}
