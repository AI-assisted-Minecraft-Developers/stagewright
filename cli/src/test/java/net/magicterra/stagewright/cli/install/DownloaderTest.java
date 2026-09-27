package net.magicterra.stagewright.cli.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Against a local HTTP server: what reaches the network, and what is refused on arrival. */
class DownloaderTest {

    private static final byte[] BODY = "the real library".getBytes(StandardCharsets.UTF_8);

    private HttpServer server;
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
    private URI base;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            hits.computeIfAbsent(path, k -> new AtomicInteger()).incrementAndGet();
            byte[] body = switch (path) {
                case "/good.jar" -> BODY;
                case "/corrupt.jar" -> "not the library".getBytes(StandardCharsets.UTF_8);
                case "/broken-mirror/good.jar" -> null;
                default -> new byte[0];
            };
            int status = body == null ? 500 : body.length == 0 ? 404 : 200;
            exchange.sendResponseHeaders(status, body == null || body.length == 0 ? -1 : body.length);
            if (body != null && body.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
            exchange.close();
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void aFileAlreadyInPlaceIsNotFetchedAgain(@TempDir Path install) throws IOException {
        try (Downloader d = downloader(install, List::of)) {
            Downloader.Artifact a = artifact(install, "/good.jar");
            assertTrue(d.fetch(a));
            assertFalse(d.fetch(a));
            assertEquals(1, hits.get("/good.jar").get());
            assertTrue(Files.isRegularFile(install.resolve("cache/SHA-1/" + a.sha1().substring(0, 2)
                    + "/" + a.sha1())), "a verified download is offered to HMCL's cache");
        }
    }

    @Test
    void theCacheIsConsultedBeforeTheNetwork(@TempDir Path install) throws IOException {
        try (Downloader d = downloader(install, List::of)) {
            d.fetch(artifact(install, "/good.jar"));
            Downloader.Artifact elsewhere = new Downloader.Artifact(base.resolve("/good.jar"),
                    install.resolve("libraries/other/place.jar"), Sha1.of(BODY), BODY.length, false, true);
            assertFalse(d.fetch(elsewhere));
            assertEquals(1, hits.get("/good.jar").get());
        }
    }

    @Test
    void aFileThatDoesNotMatchItsChecksumNeverLands(@TempDir Path install) throws IOException {
        try (Downloader d = downloader(install, List::of)) {
            Downloader.Artifact a = new Downloader.Artifact(base.resolve("/corrupt.jar"),
                    install.resolve("libraries/corrupt.jar"), Sha1.of(BODY), -1, false, true);
            assertThrows(IOException.class, () -> d.fetch(a));
            assertFalse(Files.exists(a.target()));
            try (var left = Files.list(a.target().getParent())) {
                assertEquals(0, left.count(), "no .part file is left behind");
            }
        }
    }

    @Test
    void aFailingMirrorFallsBackToTheOriginal(@TempDir Path install) throws IOException {
        try (Downloader d = downloader(install,
                uri -> List.of(base.resolve("/broken-mirror" + uri.getPath()), uri))) {
            assertTrue(d.fetch(artifact(install, "/good.jar")));
            assertEquals(3, hits.get("/broken-mirror/good.jar").get(), "a 500 is retried");
            assertEquals(1, hits.get("/good.jar").get());
        }
    }

    @Test
    void aMissingTextResourceSaysSo(@TempDir Path install) throws IOException {
        try (Downloader d = downloader(install, List::of)) {
            assertThrows(java.io.FileNotFoundException.class, () -> d.text(base.resolve("/absent.sha1")));
            assertEquals(1, hits.get("/absent.sha1").get(), "a 404 is not retried");
        }
    }

    private Downloader downloader(Path install, java.util.function.Function<URI, List<URI>> mirror) {
        java.util.function.Function<URI, List<URI>> candidates = uri -> {
            List<URI> out = new java.util.ArrayList<>(mirror.apply(uri));
            if (!out.contains(uri)) out.add(uri);
            return out;
        };
        return new Downloader(candidates, new HmclCache(install), ProxySelector.getDefault());
    }

    private Downloader.Artifact artifact(Path install, String path) {
        return new Downloader.Artifact(base.resolve(path), install.resolve("libraries" + path),
                Sha1.of(BODY), BODY.length, false, true);
    }
}
