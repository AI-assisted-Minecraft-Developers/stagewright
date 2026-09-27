package net.magicterra.stagewright.cli.install;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Fetches files into an install directory, verifying each one and never leaving half of one behind.
 *
 * <p>Order of preference for every file: already in place and correct, then HMCL's cache, then the
 * network — each candidate URL the mirror offers, retried, the original last. A file is written beside
 * its target and renamed over it only once its sha1 and size check out, so a killed run or a second
 * launcher reading the same directory never sees a truncated jar.
 */
public final class Downloader implements AutoCloseable {

    /**
     * One file to put in place.
     *
     * @param sha1       expected checksum, or null when the source publishes none
     * @param size       expected size, or -1 when unknown
     * @param trustSize  accept a file already in place on its size alone — for content-addressed
     *                   assets, whose name is their hash, where rehashing 800 MB each run buys nothing
     * @param cacheable  offer the file to HMCL's cache once verified
     */
    public record Artifact(URI url, Path target, String sha1, long size, boolean trustSize,
                           boolean cacheable) {}

    private static final int ATTEMPTS = 3;
    private static final int PARALLELISM = 16;

    private final HttpClient http;
    private final Function<URI, List<URI>> candidates;
    private final HmclCache cache;
    private final ExecutorService pool = Executors.newFixedThreadPool(PARALLELISM, r -> {
        Thread t = new Thread(r, "stagewright-download");
        t.setDaemon(true);
        return t;
    });

    public Downloader(Mirror mirror, Path installDir) {
        this(mirror::candidates, new HmclCache(installDir), proxy(System.getenv()));
    }

    Downloader(Function<URI, List<URI>> candidates, HmclCache cache, ProxySelector proxy) {
        this.candidates = candidates;
        this.cache = cache;
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20))
                .proxy(proxy)
                .build();
    }

    /** Put one file in place; true when it had to be fetched from the network. */
    public boolean fetch(Artifact a) throws IOException {
        if (inPlace(a)) return false;
        if (a.sha1() != null && cache.placeInto(a.sha1(), a.size(), a.target())) return false;
        Files.createDirectories(a.target().getParent());
        IOException last = null;
        for (URI uri : candidates.apply(a.url())) {
            for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
                try {
                    downloadOnce(uri, a);
                    if (a.cacheable() && a.sha1() != null) cache.store(a.target(), a.sha1());
                    return true;
                } catch (FileNotFoundException e) {
                    last = e;
                    break;              // absent at this source; retrying it will not change that
                } catch (IOException e) {
                    last = e;
                }
            }
        }
        throw new IOException("could not download " + a.url() + " to " + a.target() + " — "
                + (last == null ? "no source" : last.getMessage()), last);
    }

    /** Put every file in place, in parallel; returns how many came from the network. */
    public int fetchAll(List<Artifact> all) throws IOException {
        List<Future<Boolean>> futures = new ArrayList<>();
        for (Artifact a : all) futures.add(pool.submit(() -> fetch(a)));
        int fetched = 0;
        IOException first = null;
        int failed = 0;
        for (Future<Boolean> f : futures) {
            try {
                if (f.get()) fetched++;
            } catch (ExecutionException e) {
                failed++;
                if (first == null) {
                    first = e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while downloading", e);
            }
        }
        if (first != null) {
            throw new IOException(failed + " of " + all.size() + " downloads failed; the first: "
                    + first.getMessage(), first);
        }
        return fetched;
    }

    /** A small text resource — a manifest, a profile, a checksum sidecar. */
    public String text(URI url) throws IOException {
        IOException last = null;
        for (URI uri : candidates.apply(url)) {
            for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
                try {
                    HttpResponse<String> response = http.send(request(uri),
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    check(uri, response.statusCode());
                    return response.body();
                } catch (FileNotFoundException e) {
                    last = e;
                    break;
                } catch (IOException e) {
                    last = e;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted fetching " + uri, e);
                }
            }
        }
        if (last instanceof FileNotFoundException notFound) throw notFound;
        throw new IOException("could not fetch " + url + " — "
                + (last == null ? "no source" : last.getMessage()), last);
    }

    private boolean inPlace(Artifact a) throws IOException {
        if (!Files.isRegularFile(a.target())) return false;
        if (a.trustSize()) return a.size() < 0 || Files.size(a.target()) == a.size();
        return Sha1.matches(a.target(), a.sha1(), a.size());
    }

    private void downloadOnce(URI uri, Artifact a) throws IOException {
        Path temp = a.target().resolveSibling(a.target().getFileName() + "." + UUID.randomUUID() + ".part");
        try {
            HttpResponse<Path> response = http.send(request(uri), HttpResponse.BodyHandlers.ofFile(temp));
            check(uri, response.statusCode());
            if (!Sha1.matches(temp, a.sha1(), a.size())) {
                throw new IOException(uri + " served a file that does not match its checksum"
                        + " (expected sha1 " + a.sha1() + ", size " + a.size() + ")");
            }
            HmclCache.moveOver(temp, a.target());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted downloading " + uri, e);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static HttpRequest request(URI uri) {
        return HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(5))
                .header("User-Agent", "stagewright-cli")
                .GET()
                .build();
    }

    private static void check(URI uri, int status) throws IOException {
        // 400 included: Fabric's meta answers it for a loader version that does not exist.
        if (status == 400 || status == 404 || status == 410) {
            throw new FileNotFoundException(uri + " answered " + status);
        }
        if (status != 200) throw new IOException(uri + " answered " + status);
    }

    /**
     * The proxy the environment names. Java does not read {@code HTTPS_PROXY} by itself, so on a box
     * where every other tool goes through one, a plain {@code HttpClient} alone goes direct and times
     * out. An explicit {@code -Dhttps.proxyHost} still wins.
     */
    static ProxySelector proxy(Map<String, String> env) {
        InetSocketAddress address = proxyAddress(env);
        if (System.getProperty("https.proxyHost") != null || address == null) {
            return ProxySelector.getDefault();
        }
        return ProxySelector.of(address);
    }

    /** The same proxy as JVM arguments, for a child process such as NeoForge's installer. */
    static List<String> proxyJvmArgs(Map<String, String> env) {
        InetSocketAddress address = proxyAddress(env);
        if (address == null) return List.of();
        String host = address.getHostString();
        String port = String.valueOf(address.getPort());
        return List.of("-Dhttps.proxyHost=" + host, "-Dhttps.proxyPort=" + port,
                "-Dhttp.proxyHost=" + host, "-Dhttp.proxyPort=" + port);
    }

    static InetSocketAddress proxyAddress(Map<String, String> env) {
        for (String name : List.of("HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy")) {
            String value = env.get(name);
            if (value == null || value.isBlank()) continue;
            URI uri = URI.create(value.contains("://") ? value.trim() : "http://" + value.trim());
            if (uri.getHost() == null) continue;
            int port = uri.getPort() != -1 ? uri.getPort() : "https".equals(uri.getScheme()) ? 443 : 80;
            return InetSocketAddress.createUnresolved(uri.getHost(), port);
        }
        return null;
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }
}
