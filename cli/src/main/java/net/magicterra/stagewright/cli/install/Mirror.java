package net.magicterra.stagewright.cli.install;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Where else a download may be fetched from, tried before the original.
 *
 * <p>The original always stays in the list, last. BMCLAPI does not carry everything — measured, it
 * answers 500 for NeoForge's installer jar and 404 for Fabric's {@code .sha1} sidecars while serving
 * the jars beside them — so a mirror is a faster first try, never a replacement.
 */
public enum Mirror {
    NONE(Map.of()),
    BMCLAPI(Map.of(
            "piston-meta.mojang.com", "https://bmclapi2.bangbang93.com",
            "launchermeta.mojang.com", "https://bmclapi2.bangbang93.com",
            "piston-data.mojang.com", "https://bmclapi2.bangbang93.com",
            "launcher.mojang.com", "https://bmclapi2.bangbang93.com",
            "libraries.minecraft.net", "https://bmclapi2.bangbang93.com/maven",
            "resources.download.minecraft.net", "https://bmclapi2.bangbang93.com/assets",
            "maven.fabricmc.net", "https://bmclapi2.bangbang93.com/maven",
            "meta.fabricmc.net", "https://bmclapi2.bangbang93.com/fabric-meta"));

    /** NeoForge's maven serves releases under a path prefix the mirror does not repeat. */
    private static final String NEOFORGED_HOST = "maven.neoforged.net";
    private static final String NEOFORGED_PREFIX = "/releases";

    private final Map<String, String> hosts;

    Mirror(Map<String, String> hosts) {
        this.hosts = hosts;
    }

    public static Mirror parse(String name) {
        return switch (name) {
            case "bmclapi" -> BMCLAPI;
            case "none" -> NONE;
            default -> throw new IllegalArgumentException("--mirror takes bmclapi or none, not '" + name + "'");
        };
    }

    /** The URLs to try for {@code original}, in order; the original is always the last. */
    public List<URI> candidates(URI original) {
        String host = original.getHost();
        String path = original.getRawPath();
        String base = hosts.get(host);
        if (this == BMCLAPI && NEOFORGED_HOST.equals(host) && path.startsWith(NEOFORGED_PREFIX + "/")) {
            base = "https://bmclapi2.bangbang93.com/maven";
            path = path.substring(NEOFORGED_PREFIX.length());
        }
        if (base == null) return List.of(original);
        return List.of(URI.create(base + path), original);
    }
}
