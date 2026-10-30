package net.magicterra.stagewright.cli;

import java.util.Map;
import net.magicterra.stagewright.engine.Display;

/** What is checked before a client is started, so that a doomed launch fails in a second. */
final class ClientPreflight {

    private ClientPreflight() {}

    /** A client needs a display this tool does not provide, and an X server that lets it in; see {@link Display}. */
    static void requireDisplay(String osName, Map<String, String> env) {
        String missing = Display.missing(osName, env);
        if (missing != null) {
            throw new EnvFailure(missing);
        }
    }
}
