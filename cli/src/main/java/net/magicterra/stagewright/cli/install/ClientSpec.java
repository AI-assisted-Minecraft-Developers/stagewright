package net.magicterra.stagewright.cli.install;

/**
 * What {@code --client} asks for: {@code neoforge:<mc>:<version>}, {@code fabric:<mc>:<version>} or
 * {@code vanilla:<mc>}.
 *
 * <p>Always an exact loader version. A game directory that has had several loaders installed into it
 * is the normal case, and picking "the newest" there has already chosen a {@code 21.5.34-beta} over
 * the {@code 21.1.248} a pack actually boots.
 */
public record ClientSpec(String loader, String minecraft, String loaderVersion) {

    private static final String USAGE = "--client takes neoforge:<mc>:<version>,"
            + " fabric:<mc>:<version> or vanilla:<mc> — e.g. neoforge:1.21.1:21.1.248";

    public static ClientSpec parse(String spec) {
        String[] parts = spec.trim().split(":", -1);
        for (String part : parts) {
            if (part.isBlank()) throw new IllegalArgumentException(USAGE + ", not '" + spec + "'");
        }
        return switch (parts[0]) {
            case "vanilla" -> {
                if (parts.length != 2) throw new IllegalArgumentException(USAGE + ", not '" + spec + "'");
                yield new ClientSpec("vanilla", parts[1], null);
            }
            case "neoforge", "fabric" -> {
                if (parts.length != 3) throw new IllegalArgumentException(USAGE + ", not '" + spec + "'");
                yield new ClientSpec(parts[0], parts[1], parts[2]);
            }
            default -> throw new IllegalArgumentException(USAGE + ", not '" + spec + "'");
        };
    }

    /** The directory name under {@code versions/} this install produces — each loader's own convention. */
    public String versionId() {
        return switch (loader) {
            case "neoforge" -> "neoforge-" + loaderVersion;
            case "fabric" -> "fabric-loader-" + loaderVersion + "-" + minecraft;
            default -> minecraft;
        };
    }

    /** {@code neoforge} or {@code fabric}, the name StageWright's own jars are built under; null for vanilla. */
    public String modLoader() {
        return "vanilla".equals(loader) ? null : loader;
    }

    @Override
    public String toString() {
        return loaderVersion == null ? loader + ":" + minecraft
                : loader + ":" + minecraft + ":" + loaderVersion;
    }
}
