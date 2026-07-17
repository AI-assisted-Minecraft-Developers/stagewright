package net.magicterra.testkit.junit.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Small pure-JSON helpers shared by the live UI scene classes. No transport, no
 * game state of its own — every method reads a snapshot the caller already fetched
 * over the {@link net.magicterra.testkit.junit.Testkit} facade. Kept package-private
 * so the ui scenes read declaratively (`hasScreen(info)`, `slotCount(tree)`) instead
 * of open-coding gson field digs at every assertion.
 */
final class UiSupport {

    private UiSupport() {}

    /** {@code mc.client.screen.info} → is a Screen currently open? */
    static boolean hasScreen(JsonObject info) {
        return info != null && info.has("hasScreen")
                && info.get("hasScreen").getAsBoolean();
    }

    /** The open screen's class simpleName (e.g. {@code InventoryScreen}), or "" when none. */
    static String screenType(JsonObject info) {
        if (info == null || !info.has("type") || info.get("type").isJsonNull()) {
            return "";
        }
        return info.get("type").getAsString();
    }

    /**
     * Count the slot nodes under the synthetic {@code ContainerSlots} child of a
     * {@code mc.client.screen.tree} snapshot. Returns 0 when the tree has no such node
     * (no screen, or a non-container screen). See {@code ScreenIntrospection.containerSlotsNode}.
     */
    static int slotCount(JsonObject tree) {
        if (tree == null || !tree.has("children") || !tree.get("children").isJsonArray()) {
            return 0;
        }
        for (JsonElement e : tree.getAsJsonArray("children")) {
            if (!e.isJsonObject()) continue;
            JsonObject node = e.getAsJsonObject();
            if (node.has("type") && "ContainerSlots".equals(strOf(node, "type"))) {
                JsonElement kids = node.get("children");
                return kids != null && kids.isJsonArray() ? kids.getAsJsonArray().size() : 0;
            }
        }
        return 0;
    }

    /**
     * True if any EditBox node anywhere in the tree carries a {@code value} containing
     * {@code needle}. Used to prove {@code typeText} landed in the focused chat box.
     */
    static boolean anyEditBoxValueContains(JsonObject tree, String needle) {
        if (tree == null) return false;
        return walkForValue(tree, needle);
    }

    private static boolean walkForValue(JsonObject node, String needle) {
        JsonElement value = node.get("value");
        if (value != null && value.isJsonPrimitive() && value.getAsString().contains(needle)) {
            return true;
        }
        JsonElement kids = node.get("children");
        if (kids != null && kids.isJsonArray()) {
            JsonArray arr = kids.getAsJsonArray();
            for (JsonElement e : arr) {
                if (e.isJsonObject() && walkForValue(e.getAsJsonObject(), needle)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String strOf(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }
}
