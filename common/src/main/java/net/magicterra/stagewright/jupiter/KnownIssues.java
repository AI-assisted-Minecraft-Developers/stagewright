package net.magicterra.stagewright.jupiter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A YAML step's {@code knownIssues}: entries taken out of every list in the step's result before its {@code expect} is
 * checked, each with the reason it is there. An issue is a map: its keys other than {@code reason} must all equal an
 * item's fields for the item to go, or, for an item that is a plain value, its one key's value must equal the item. An
 * issue that took nothing out is no longer seen, and fails the step, so the list shrinks as things get fixed.
 */
final class KnownIssues {

    private final List<Map<String, Object>> issues = new ArrayList<>();
    private final boolean[] used;

    KnownIssues(Object spec) {
        if (!(spec instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("knownIssues is a list of {field: value, reason: why}");
        }
        for (Object each : list) {
            if (!(each instanceof Map<?, ?> m) || !(m.get("reason") instanceof String) || m.size() < 2) {
                throw new IllegalArgumentException(
                        "a known issue is {field: value, reason: why}, not " + Expectations.show(each));
            }
            Map<String, Object> issue = new LinkedHashMap<>();
            m.forEach((k, v) -> issue.put(String.valueOf(k), v));
            issues.add(issue);
        }
        used = new boolean[issues.size()];
    }

    /** {@code result} with every item an issue matches taken out of every list in it, at any depth. */
    Object subtract(Object result) {
        if (result instanceof List<?> list) {
            List<Object> kept = new ArrayList<>();
            for (Object item : list) {
                if (!matchedAny(item)) {
                    kept.add(subtract(item));
                }
            }
            return kept;
        }
        if (result instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), subtract(v)));
            return out;
        }
        return result;
    }

    /** Fails naming every issue that took nothing out: it is fixed, or the step no longer reports it. */
    void checkAllSeen() {
        List<String> stale = new ArrayList<>();
        for (int i = 0; i < issues.size(); i++) {
            if (!used[i]) {
                stale.add(Expectations.show(issues.get(i)));
            }
        }
        if (!stale.isEmpty()) {
            throw new AssertionError("known issues no longer seen, so remove them: " + String.join("; ", stale));
        }
    }

    int count() {
        return issues.size();
    }

    private boolean matchedAny(Object item) {
        boolean any = false;
        for (int i = 0; i < issues.size(); i++) {
            if (matches(issues.get(i), item)) {
                used[i] = true;
                any = true;
            }
        }
        return any;
    }

    private static boolean matches(Map<String, Object> issue, Object item) {
        Map<String, Object> fields = new LinkedHashMap<>(issue);
        fields.remove("reason");
        if (item instanceof Map<?, ?> map) {
            for (Map.Entry<String, Object> f : fields.entrySet()) {
                if (!map.containsKey(f.getKey()) || !Expectations.same(map.get(f.getKey()), f.getValue())) {
                    return false;
                }
            }
            return true;
        }
        return fields.size() == 1
                && Expectations.same(fields.values().iterator().next(), item);
    }
}
