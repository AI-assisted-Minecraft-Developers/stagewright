package net.magicterra.stagewright.agent;

import java.util.List;

/**
 * Builds one JSON object on one line. The agent has no JSON library to lean on, and the objects it sends are flat
 * enough that a builder is shorter than the question of which library to carry.
 */
final class Lines {

    private final StringBuilder out = new StringBuilder("{");
    private boolean first = true;

    static Lines event(String name) {
        return new Lines().text("event", name);
    }

    Lines text(String key, String value) {
        key(key);
        quote(value);
        return this;
    }

    Lines number(String key, long value) {
        key(key);
        out.append(value);
        return this;
    }

    Lines bool(String key, boolean value) {
        key(key);
        out.append(value);
        return this;
    }

    Lines texts(String key, List<String> values) {
        key(key);
        out.append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) out.append(',');
            quote(values.get(i));
        }
        out.append(']');
        return this;
    }

    /** An array whose elements are already complete objects. */
    Lines objects(String key, List<Lines> values) {
        key(key);
        out.append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) out.append(',');
            out.append(values.get(i).build());
        }
        out.append(']');
        return this;
    }

    /** A value that is already one complete JSON value, written as it is. */
    Lines raw(String key, String json) {
        key(key);
        out.append(json);
        return this;
    }

    String build() {
        return out + "}";
    }

    private void key(String key) {
        if (!first) out.append(',');
        first = false;
        quote(key);
        out.append(':');
    }

    private void quote(String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }
}
