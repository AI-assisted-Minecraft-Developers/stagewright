package net.magicterra.stagewright.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The command line, split into options, game system properties and extra mod jars. */
final class Args {

    /** @param opts option name (without the dashes) to its value; a flag maps to {@code "true"} */
    record Parsed(Map<String, String> opts, List<String> systemProps, List<Path> extraMods) {}

    /**
     * Every option that takes a value. A closed set on purpose: an unknown name used to be stored and
     * never read, so {@code --expected} for {@code --expect} ran with reconciliation off and
     * {@code --clean-wrold false} deleted the world, both without a word.
     */
    private static final Set<String> VALUED = Set.of(
            "game-dir", "scenes", "attached", "coverage", "expect", "results", "timeout",
            "clean-world", "headlessmc", "account", "launcher-jvm", "display-client", "mc-version",
            "loader", "world", "with-client", "launch", "java");

    /** Valued options that are really switches, spelled out so a value like "no" cannot read as either. */
    private static final Set<String> BOOLEAN = Set.of("clean-world");

    private Args() {}

    static Parsed parse(String[] args) {
        Map<String, String> opts = new LinkedHashMap<>();
        List<String> systemProps = new ArrayList<>();
        List<Path> extraMods = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("-D")) {
                systemProps.add(a);
            } else if ("--no-install".equals(a)) {
                opts.put("no-install", "true");
            } else if ("--online".equals(a)) {
                opts.put("online", "true");
            } else if ("--mod".equals(a)) {
                if (i + 1 >= args.length) throw new IllegalArgumentException(a + " needs a value");
                extraMods.add(Path.of(args[++i]).toAbsolutePath().normalize());
            } else if (a.startsWith("--")) {
                String name = a.substring(2);
                if (!VALUED.contains(name)) {
                    throw new IllegalArgumentException("unknown option '" + a + "'");
                }
                if (i + 1 >= args.length) throw new IllegalArgumentException(a + " needs a value");
                String value = args[++i];
                if (BOOLEAN.contains(name) && !"true".equals(value) && !"false".equals(value)) {
                    throw new IllegalArgumentException(a + " takes true or false, not '" + value + "'");
                }
                opts.put(name, value);
            } else {
                throw new IllegalArgumentException("unexpected argument '" + a + "'");
            }
        }
        return new Parsed(opts, systemProps, extraMods);
    }
}
