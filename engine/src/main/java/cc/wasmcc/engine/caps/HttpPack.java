package cc.wasmcc.engine.caps;

import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.ValType;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * The {@code http} capability pack: a tiny net import a guest module links against,
 * implemented in Java and gated by an allowlist (the mod feeds it CC's http
 * policy). A coarse two-call ABI (the body length is unknown up front), so the
 * guest learns the size then copies the bytes:
 *
 * <pre>
 *   (import "http" "get"  (func (param i32 i32) (result i32)))  ; (url_ptr,url_len) -> len | -1 denied | -2 error
 *   (import "http" "read" (func (param i32 i32) (result i32)))  ; (dst_ptr,dst_cap) -> bytes copied
 * </pre>
 *
 * <p>The GET is synchronous: harmless off-thread (mode B), but it blocks the
 * computer thread in mode A — so networking belongs in {@code wasm.run}. State
 * (the last response body) is per-instance: {@link #hostFunctions} is called once
 * per instance and the two functions close over the same holder.
 */
public final class HttpPack {

    /** The import module name a guest links against for the http cap. */
    public static final String MODULE = "http";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    private HttpPack() {}

    public static HostFunction[] hostFunctions(List<String> allow) {
        final byte[][] last = new byte[1][]; // per-instance last response body

        HostFunction get = new HostFunction(MODULE, "get",
            FunctionType.of(List.of(ValType.I32, ValType.I32), List.of(ValType.I32)),
            (Instance inst, long... args) -> {
                int urlPtr = (int) args[0], urlLen = (int) args[1];
                String url = inst.memory().readString(urlPtr, urlLen);
                if (!allowed(url, allow)) {
                    last[0] = null;
                    return new long[]{-1};
                }
                try {
                    byte[] body = fetch(url);
                    last[0] = body;
                    return new long[]{body.length};
                } catch (Exception e) {
                    last[0] = null;
                    return new long[]{-2};
                }
            });

        HostFunction read = new HostFunction(MODULE, "read",
            FunctionType.of(List.of(ValType.I32, ValType.I32), List.of(ValType.I32)),
            (Instance inst, long... args) -> {
                int dst = (int) args[0], cap = (int) args[1];
                byte[] b = last[0];
                if (b == null || cap <= 0) {
                    return new long[]{0};
                }
                int n = Math.min(cap, b.length);
                inst.memory().write(dst, b, 0, n);
                return new long[]{n};
            });

        return new HostFunction[]{get, read};
    }

    private static byte[] fetch(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build();
        HttpResponse<byte[]> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() / 100 != 2) {
            throw new java.io.IOException("HTTP " + resp.statusCode());
        }
        return resp.body();
    }

    /**
     * Allowlist check: each pattern matches the request host (and optional
     * {@code :port}); a leading {@code *.} matches subdomains; {@code *} matches
     * anything. Empty allowlist denies everything.
     */
    public static boolean allowed(String url, List<String> allow) {
        if (allow == null || allow.isEmpty()) {
            return false;
        }
        final URI uri;
        try {
            uri = URI.create(url);
        } catch (RuntimeException e) {
            return false;
        }
        String host = uri.getHost();
        if (host == null) {
            return false;
        }
        int port = uri.getPort();
        String hostPort = port < 0 ? host : host + ":" + port;
        for (String patRaw : allow) {
            String pat = patRaw.trim().toLowerCase();
            if (pat.equals("*")) {
                return true;
            }
            String h = host.toLowerCase();
            String hp = hostPort.toLowerCase();
            if (pat.equals(h) || pat.equals(hp)) {
                return true;
            }
            if (pat.startsWith("*.")) {
                String suffix = pat.substring(1); // ".example.com"
                if (h.endsWith(suffix)) {
                    return true;
                }
            }
        }
        return false;
    }
}
