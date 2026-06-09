package cc.wasmcc.engine;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link OciResolver} against an in-process {@code HttpServer}
 * stubbing the {@code /v2/} registry API (manifest + blob + anonymous token).
 * Registries on {@code 127.0.0.1} are pulled over plain {@code http}, so the full
 * flow runs offline.
 */
class OciResolverTest {

    private static final byte[] WASM = "\0asm\1\0\0\0payload".getBytes(StandardCharsets.UTF_8);

    /** A minimal registry stub; tests register the handlers they need. */
    private static final class Registry implements AutoCloseable {
        final HttpServer server;
        Registry() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.start();
        }
        int port() { return server.getAddress().getPort(); }
        String host() { return "127.0.0.1:" + port(); }
        void on(String path, HttpHandler h) { server.createContext(path, h); }
        @Override public void close() { server.stop(0); }
    }

    private static void send(HttpExchange ex, int code, byte[] body) throws IOException {
        ex.sendResponseHeaders(code, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        }
        ex.close();
    }

    private static String manifest(String layerDigest, String mediaType, int size) {
        return "{\"schemaVersion\":2,"
            + "\"mediaType\":\"application/vnd.oci.image.manifest.v1+json\","
            + "\"config\":{\"mediaType\":\"application/vnd.oci.image.config.v1+json\","
            + "\"digest\":\"sha256:" + "0".repeat(64) + "\",\"size\":2},"
            + "\"layers\":[{\"mediaType\":\"" + mediaType + "\","
            + "\"digest\":\"" + layerDigest + "\",\"size\":" + size + "}]}";
    }

    private static String digestOf(byte[] b) {
        return "sha256:" + ModuleCache.sha256(b);
    }

    private OciResolver newResolver(Path cache, boolean allowOci, List<String> allow) {
        return newResolver(cache, allowOci, allow, 1_000_000);
    }

    private OciResolver newResolver(Path cache, boolean allowOci, List<String> allow, long max) {
        return new OciResolver(cache, max, allowOci, allow);
    }

    private byte[] resolve(OciResolver r, String body) throws IOException {
        OciResolver.Ref ref = OciResolver.tryParse(body);
        assertNotNull(ref, () -> "ref did not parse: " + body);
        return r.resolve(ref);
    }

    // --- happy paths -------------------------------------------------------

    @Test
    void happyPathTagPin(@TempDir Path cache) throws Exception {
        try (Registry reg = new Registry()) {
            String dg = digestOf(WASM);
            reg.on("/v2/owner/mod/manifests/", ex ->
                send(ex, 200, manifest(dg, "application/wasm", WASM.length).getBytes()));
            reg.on("/v2/owner/mod/blobs/", ex -> send(ex, 200, WASM));

            OciResolver r = newResolver(cache, true, List.of("127.0.0.1"));
            byte[] got = resolve(r, reg.host() + "/owner/mod:1.0");
            assertArrayEquals(WASM, got);
        }
    }

    @Test
    void happyPathDigestPin(@TempDir Path cache) throws Exception {
        try (Registry reg = new Registry()) {
            String dg = digestOf(WASM);
            // Pin by (arbitrary) manifest digest; the stub serves the same manifest.
            String manifestDigest = "sha256:" + "a".repeat(64);
            reg.on("/v2/owner/mod/manifests/", ex ->
                send(ex, 200, manifest(dg, "application/wasm", WASM.length).getBytes()));
            reg.on("/v2/owner/mod/blobs/", ex -> send(ex, 200, WASM));

            OciResolver r = newResolver(cache, true, List.of("127.0.0.1"));
            byte[] got = resolve(r, reg.host() + "/owner/mod@" + manifestDigest);
            assertArrayEquals(WASM, got);
        }
    }

    @Test
    void cachesBlobByDigest(@TempDir Path cache) throws Exception {
        AtomicReference<Integer> blobHits = new AtomicReference<>(0);
        try (Registry reg = new Registry()) {
            String dg = digestOf(WASM);
            reg.on("/v2/owner/mod/manifests/", ex ->
                send(ex, 200, manifest(dg, "application/wasm", WASM.length).getBytes()));
            reg.on("/v2/owner/mod/blobs/", ex -> {
                blobHits.updateAndGet(n -> n + 1);
                send(ex, 200, WASM);
            });
            OciResolver r = newResolver(cache, true, List.of("127.0.0.1"));
            assertArrayEquals(WASM, resolve(r, reg.host() + "/owner/mod:1.0"));
            assertArrayEquals(WASM, resolve(r, reg.host() + "/owner/mod:1.0"));
            assertEquals(1, blobHits.get(), "blob should be fetched once, then cached");
        }
    }

    // --- anonymous token ---------------------------------------------------

    @Test
    void anonymousTokenFlow(@TempDir Path cache) throws Exception {
        AtomicBoolean tokenUsed = new AtomicBoolean(false);
        try (Registry reg = new Registry()) {
            String dg = digestOf(WASM);
            String realm = "http://" + reg.host() + "/token";
            reg.on("/v2/owner/mod/manifests/", ex -> {
                String auth = ex.getRequestHeaders().getFirst("Authorization");
                if (auth == null) {
                    ex.getResponseHeaders().add("WWW-Authenticate",
                        "Bearer realm=\"" + realm + "\",service=\"registry\","
                        + "scope=\"repository:owner/mod:pull\"");
                    send(ex, 401, new byte[0]);
                    return;
                }
                assertEquals("Bearer tok-123", auth, "retry must carry the token");
                tokenUsed.set(true);
                send(ex, 200, manifest(dg, "application/wasm", WASM.length).getBytes());
            });
            reg.on("/token", ex -> {
                assertTrue(ex.getRequestURI().getQuery().contains("scope="), "scope passed");
                send(ex, 200, "{\"token\":\"tok-123\"}".getBytes());
            });
            reg.on("/v2/owner/mod/blobs/", ex -> send(ex, 200, WASM));

            OciResolver r = newResolver(cache, true, List.of("127.0.0.1"));
            assertArrayEquals(WASM, resolve(r, reg.host() + "/owner/mod:1.0"));
            assertTrue(tokenUsed.get(), "token retry should have happened");
        }
    }

    // --- failure modes -----------------------------------------------------

    @Test
    void digestMismatchRejected(@TempDir Path cache) throws Exception {
        try (Registry reg = new Registry()) {
            // Manifest advertises a digest that the served bytes do NOT hash to.
            String wrong = "sha256:" + "b".repeat(64);
            reg.on("/v2/owner/mod/manifests/", ex ->
                send(ex, 200, manifest(wrong, "application/wasm", WASM.length).getBytes()));
            reg.on("/v2/owner/mod/blobs/", ex -> send(ex, 200, WASM));

            OciResolver r = newResolver(cache, true, List.of("127.0.0.1"));
            IOException e = assertThrows(IOException.class,
                () -> resolve(r, reg.host() + "/owner/mod:1.0"));
            assertTrue(e.getMessage().contains("digest mismatch"), e.getMessage());
        }
    }

    @Test
    void selectsWasmLayerAmongMultiple(@TempDir Path cache) throws Exception {
        try (Registry reg = new Registry()) {
            String dg = digestOf(WASM);
            String json = "{\"schemaVersion\":2,\"layers\":["
                + "{\"mediaType\":\"application/vnd.oci.image.layer.v1.tar\","
                + "\"digest\":\"sha256:" + "c".repeat(64) + "\",\"size\":3},"
                + "{\"mediaType\":\"application/wasm\",\"digest\":\"" + dg + "\","
                + "\"size\":" + WASM.length + "},"
                + "{\"mediaType\":\"text/plain\",\"digest\":\"sha256:" + "d".repeat(64)
                + "\",\"size\":4}]}";
            reg.on("/v2/owner/mod/manifests/", ex -> send(ex, 200, json.getBytes()));
            reg.on("/v2/owner/mod/blobs/", ex -> send(ex, 200, WASM));

            OciResolver r = newResolver(cache, true, List.of("127.0.0.1"));
            assertArrayEquals(WASM, resolve(r, reg.host() + "/owner/mod:1.0"));
        }
    }

    @Test
    void noWasmLayerErrors(@TempDir Path cache) throws Exception {
        try (Registry reg = new Registry()) {
            String json = "{\"schemaVersion\":2,\"layers\":["
                + "{\"mediaType\":\"application/octet-stream\",\"digest\":\"sha256:"
                + "c".repeat(64) + "\",\"size\":3},"
                + "{\"mediaType\":\"text/plain\",\"digest\":\"sha256:" + "d".repeat(64)
                + "\",\"size\":4}]}";
            reg.on("/v2/owner/mod/manifests/", ex -> send(ex, 200, json.getBytes()));
            OciResolver r = newResolver(cache, true, List.of("127.0.0.1"));
            IOException e = assertThrows(IOException.class,
                () -> resolve(r, reg.host() + "/owner/mod:1.0"));
            assertTrue(e.getMessage().contains("no wasm layer"), e.getMessage());
        }
    }

    @Test
    void allowlistDenial(@TempDir Path cache) throws Exception {
        try (Registry reg = new Registry()) {
            OciResolver r = newResolver(cache, true, List.of("ghcr.io"));  // not 127.0.0.1
            IOException e = assertThrows(IOException.class,
                () -> resolve(r, reg.host() + "/owner/mod:1.0"));
            assertTrue(e.getMessage().contains("not allowlisted"), e.getMessage());
        }
    }

    @Test
    void ociDisabled(@TempDir Path cache) throws Exception {
        try (Registry reg = new Registry()) {
            OciResolver r = newResolver(cache, false, List.of("127.0.0.1"));
            IOException e = assertThrows(IOException.class,
                () -> resolve(r, reg.host() + "/owner/mod:1.0"));
            assertTrue(e.getMessage().contains("disabled"), e.getMessage());
        }
    }

    @Test
    void sizeCapExceeded(@TempDir Path cache) throws Exception {
        try (Registry reg = new Registry()) {
            byte[] big = new byte[4096];
            String dg = digestOf(big);
            reg.on("/v2/owner/mod/manifests/", ex ->
                send(ex, 200, manifest(dg, "application/wasm", big.length).getBytes()));
            reg.on("/v2/owner/mod/blobs/", ex -> send(ex, 200, big));

            OciResolver r = newResolver(cache, true, List.of("127.0.0.1"), 1024);
            IOException e = assertThrows(IOException.class,
                () -> resolve(r, reg.host() + "/owner/mod:1.0"));
            assertTrue(e.getMessage().contains("size cap"), e.getMessage());
        }
    }

    @Test
    void manifestNotFound(@TempDir Path cache) throws Exception {
        try (Registry reg = new Registry()) {
            reg.on("/v2/owner/mod/manifests/", ex -> send(ex, 404, new byte[0]));
            OciResolver r = newResolver(cache, true, List.of("127.0.0.1"));
            IOException e = assertThrows(IOException.class,
                () -> resolve(r, reg.host() + "/owner/mod:1.0"));
            assertTrue(e.getMessage().contains("not found"), e.getMessage());
        }
    }

    // --- cross-origin redirect drops Authorization -------------------------

    @Test
    void crossOriginRedirectDropsAuth(@TempDir Path cache) throws Exception {
        // Two distinct origins (different ports): the registry (auth'd) 302-redirects
        // the blob to a presigned CDN that MUST NOT receive the bearer token.
        try (Registry reg = new Registry(); Registry cdn = new Registry()) {
            String dg = digestOf(WASM);
            String realm = "http://" + reg.host() + "/token";
            AtomicBoolean cdnSawAuth = new AtomicBoolean(false);

            reg.on("/v2/owner/mod/manifests/", ex -> {
                if (ex.getRequestHeaders().getFirst("Authorization") == null) {
                    ex.getResponseHeaders().add("WWW-Authenticate",
                        "Bearer realm=\"" + realm + "\",service=\"r\",scope=\"s\"");
                    send(ex, 401, new byte[0]);
                    return;
                }
                send(ex, 200, manifest(dg, "application/wasm", WASM.length).getBytes());
            });
            reg.on("/token", ex -> send(ex, 200, "{\"token\":\"tok-xyz\"}".getBytes()));
            // Registry blob endpoint: requires auth, then redirects cross-origin.
            reg.on("/v2/owner/mod/blobs/", ex -> {
                assertEquals("Bearer tok-xyz", ex.getRequestHeaders().getFirst("Authorization"),
                    "same-origin blob request should carry auth");
                ex.getResponseHeaders().add("Location", "http://" + cdn.host() + "/cdn/blob");
                send(ex, 302, new byte[0]);
            });
            // CDN: must be hit WITHOUT the Authorization header.
            cdn.on("/cdn/blob", ex -> {
                if (ex.getRequestHeaders().getFirst("Authorization") != null) {
                    cdnSawAuth.set(true);
                    send(ex, 403, new byte[0]);
                    return;
                }
                send(ex, 200, WASM);
            });

            OciResolver r = newResolver(cache, true, List.of("127.0.0.1"));
            byte[] got = resolve(r, reg.host() + "/owner/mod:1.0");
            assertArrayEquals(WASM, got);
            assertFalse(cdnSawAuth.get(), "Authorization must be stripped cross-origin");
        }
    }

    // --- parsing -----------------------------------------------------------

    @Test
    void parseRejectsBareName() {
        assertNull(OciResolver.tryParse("sqlite3"));
        assertNull(OciResolver.tryParse("just-a-name"));
    }

    @Test
    void parseAcceptsRegistryForms() {
        assertNotNull(OciResolver.tryParse("ghcr.io/owner/repo:1.0"));
        assertNotNull(OciResolver.tryParse("ghcr.io/owner/repo"));        // default tag
        assertNotNull(OciResolver.tryParse("ghcr.io/owner/repo@sha256:" + "a".repeat(64)));
        assertNotNull(OciResolver.tryParse("127.0.0.1:5000/owner/repo:1.0"));
        assertNotNull(OciResolver.tryParse("localhost:5000/repo:1.0"));
        // bad digest is rejected
        assertNull(OciResolver.tryParse("ghcr.io/owner/repo@sha256:short"));
    }
}
