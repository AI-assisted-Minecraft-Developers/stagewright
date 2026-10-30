package net.magicterra.stagewright.cli.install;

/**
 * What {@code --client} asks for: {@code neoforge:<mc>:<version>}, {@code fabric:<mc>:<version>} or
 * {@code vanilla:<mc>}.
 *
 * <p>Always an exact loader version. A game directory that has had several loaders installed into it is the normal
 * case, and picking "the newest" there has already chosen a {@code 21.5.34-beta} over the {@code 21.1.248} a pack
 * actually boots.
 */
public record ClientSpec(String loader, String minecraft, String loaderVersion) {

    /** Between the parts of a spec, as in {@code neoforge:1.21.1:21.1.248}. */
    private static final String SEPARATOR = ":";

    private static final String USAGE = "--client takes neoforge:<mc>:<version>,"
            + " fabric:<mc>:<version> or vanilla:<mc> — e.g. neoforge:1.21.1:21.1.248";

    /** The spec {@code --client} names; any other form is refused, with the expected shapes in the message. */
    public static ClientSpec parse(String spec) {
        String[] parts = spec.trim().split(SEPARATOR, -1);
        for (String part : parts) {
            if (part.isBlank()) {
                throw refused(spec);
            }
        }
        return switch (parts[0]) {
            case "vanilla" -> {
                if (parts.length != 2) {
                    throw refused(spec);
                }
                yield new ClientSpec("vanilla", parts[1], null);
            }
            case "neoforge", "fabric" -> {
                if (parts.length != 3) {
                    throw refused(spec);
                }
                yield new ClientSpec(parts[0], parts[1], parts[2]);
            }
            default -> throw refused(spec);
        };
    }

    private static IllegalArgumentException refused(String spec) {
        return new IllegalArgumentException(USAGE + ", not '" + spec + "'");
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
        return loaderVersion == null
                ? loader + SEPARATOR + minecraft
                : loader + SEPARATOR + minecraft + SEPARATOR + loaderVersion;
    }
}
