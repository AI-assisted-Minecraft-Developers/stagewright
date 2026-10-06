package net.magicterra.stagewright.junit.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Small pure-JSON helpers shared by the live UI scene classes. No transport, no
 * game state of its own — every method reads a snapshot the caller already fetched
 * over the {@link net.magicterra.stagewright.junit.StageWright} facade. Kept package-private
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
     * Count the nodes held by the synthetic {@code ContainerSlots} node of a
     * {@code mc.client.screen.tree} snapshot — the flat {@code nodes} list names a node's holder
     * in {@code parent}. Returns 0 when the tree has no such node (no screen, or a non-container
     * screen). See {@code ScreenIntrospection.containerSlotsNode}.
     */
    static int slotCount(JsonObject tree) {
        JsonArray nodes = nodesOf(tree);
        String holder = null;
        for (JsonElement e : nodes) {
            if (e.isJsonObject() && "ContainerSlots".equals(strOf(e.getAsJsonObject(), "type"))) {
                holder = strOf(e.getAsJsonObject(), "id");
                break;
            }
        }
        if (holder == null) return 0;
        int count = 0;
        for (JsonElement e : nodes) {
            if (e.isJsonObject() && holder.equals(strOf(e.getAsJsonObject(), "parent"))) count++;
        }
        return count;
    }

    /**
     * True if any node in the tree carries a {@code value} containing {@code needle}. Used to
     * prove {@code typeText} landed in the focused chat box.
     */
    static boolean anyEditBoxValueContains(JsonObject tree, String needle) {
        for (JsonElement e : nodesOf(tree)) {
            if (!e.isJsonObject()) continue;
            JsonElement value = e.getAsJsonObject().get("value");
            if (value != null && value.isJsonPrimitive() && value.getAsString().contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /** The tree's flat, depth-first {@code nodes}; empty when there are none. */
    static JsonArray nodesOf(JsonObject tree) {
        JsonElement nodes = tree == null ? null : tree.get("nodes");
        return nodes != null && nodes.isJsonArray() ? nodes.getAsJsonArray() : new JsonArray();
    }

    private static String strOf(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }
}
