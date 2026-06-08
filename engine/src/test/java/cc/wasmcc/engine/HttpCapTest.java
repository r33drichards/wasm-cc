package cc.wasmcc.engine;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code http} capability: a guest module fetches a URL through the Java http
 * import, gated by an allowlist — the bridge test for "networking" without the
 * game (a localhost stub server stands in; the mod feeds CC's http policy as the
 * allowlist in production). Allowed hosts fetch; others are denied.
 */
class HttpCapTest {

    static byte[] resource(String path) throws Exception {
        try (InputStream in = HttpCapTest.class.getResourceAsStream(path)) {
            assertNotNull(in, () -> "missing test resource " + path
                + " — run `nix develop -c make fixtures`");
            return in.readAllBytes();
        }
    }

    private interface WithPort { void run(int port) throws Exception; }

    /** Serve {@code body} at /x on a fresh localhost port for the duration of the test. */
    private void withStub(byte[] body, WithPort test) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/x", ex -> {
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            test.run(server.getAddress().getPort());
        } finally {
            server.stop(0);
        }
    }

    /** Call do_get(url) into a fresh out buffer; returns {rc, outPtr}. */
    private int[] doGet(WasmInstance inst, String url, int cap) {
        MemoryView m = inst.memory();
        byte[] u = url.getBytes(StandardCharsets.UTF_8);
        int urlPtr = m.alloc(u.length);
        m.writeBytes(urlPtr, u);
        int outPtr = m.alloc(cap);
        int rc = inst.callI32("do_get", urlPtr, u.length, outPtr, cap);
        return new int[]{rc, outPtr};
    }

    @Test
    void fetchesAllowedHost() throws Exception {
        byte[] body = "hello from http".getBytes(StandardCharsets.UTF_8);
        withStub(body, port -> {
            WasmHost host = new WasmHost();
            InstanceConfig cfg = InstanceConfig.builder()
                .httpAllow("127.0.0.1:" + port)
                .build();
            try (WasmInstance inst = host.instantiate(resource("/modules/httpget.wasm"), cfg)) {
                int[] r = doGet(inst, "http://127.0.0.1:" + port + "/x", 256);
                assertEquals(body.length, r[0], () -> "do_get returned " + r[0]);
                assertArrayEquals(body, inst.memory().readBytes(r[1], r[0]));
            }
        });
    }

    @Test
    void deniedHostReturnsNegative() throws Exception {
        byte[] body = "secret".getBytes(StandardCharsets.UTF_8);
        withStub(body, port -> {
            WasmHost host = new WasmHost();
            InstanceConfig cfg = InstanceConfig.builder()
                .httpAllow("example.com") // does NOT match 127.0.0.1
                .build();
            try (WasmInstance inst = host.instantiate(resource("/modules/httpget.wasm"), cfg)) {
                int[] r = doGet(inst, "http://127.0.0.1:" + port + "/x", 256);
                assertEquals(-1, r[0], "expected denied (-1)");
            }
        });
    }
}
