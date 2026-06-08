package cc.wasmcc.engine;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/**
 * Turns a module reference into raw wasm bytes:
 * <ul>
 *   <li>a bare <b>name</b> ({@code "sqlite"} / {@code "sqlite.wasm"}) resolves to
 *       {@code <modulesDir>/<name>.wasm}, with path-traversal rejected; and</li>
 *   <li>an <b>{@code http(s)://} URL</b> is downloaded once and cached on disk
 *       (keyed by the SHA-256 of the URL), subject to a size cap.</li>
 * </ul>
 * Downloading is opt-in ({@code allowUrl}) so a locked-down server can restrict
 * modules to a curated directory. Bytes are handed to {@link WasmHost#compile}
 * (which de-dups by content hash), so re-resolving the same module is cheap.
 */
public final class ModuleResolver {

    private final Path modulesDir;   // nullable (name lookups disabled if null)
    private final Path cacheDir;     // nullable (URL caching disabled if null)
    private final long maxBytes;
    private final boolean allowUrl;
    private final HttpClient http;

    public ModuleResolver(Path modulesDir, Path cacheDir, long maxBytes, boolean allowUrl) {
        this.modulesDir = modulesDir;
        this.cacheDir = cacheDir;
        this.maxBytes = maxBytes;
        this.allowUrl = allowUrl;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    public byte[] resolve(String ref) throws IOException {
        if (ref == null || ref.isBlank()) {
            throw new IOException("module ref is empty");
        }
        String lower = ref.toLowerCase();
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return resolveUrl(ref);
        }
        return resolveName(ref);
    }

    private byte[] resolveName(String name) throws IOException {
        if (modulesDir == null) {
            throw new IOException("module directory not configured; cannot resolve name '" + name + "'");
        }
        String file = name.endsWith(".wasm") ? name : name + ".wasm";
        // Reject anything but a simple file name (no separators, no traversal).
        if (file.contains("/") || file.contains("\\") || file.contains("..")) {
            throw new IOException("invalid module name: " + name);
        }
        Path p = modulesDir.resolve(file).normalize();
        if (!p.startsWith(modulesDir.normalize()) || !Files.isRegularFile(p)) {
            throw new IOException("module not found: " + name);
        }
        byte[] bytes = Files.readAllBytes(p);
        if (bytes.length > maxBytes) {
            throw new IOException("module '" + name + "' exceeds size cap (" + maxBytes + " bytes)");
        }
        return bytes;
    }

    private byte[] resolveUrl(String url) throws IOException {
        if (!allowUrl) {
            throw new IOException("URL modules are disabled");
        }
        Path cached = cacheDir == null ? null
            : cacheDir.resolve(ModuleCache.sha256(url.getBytes()) + ".wasm");
        if (cached != null && Files.isRegularFile(cached)) {
            return Files.readAllBytes(cached);
        }
        byte[] bytes = download(url);
        if (cached != null) {
            Files.createDirectories(cacheDir);
            Path tmp = Files.createTempFile(cacheDir, "dl", ".part");
            Files.write(tmp, bytes);
            Files.move(tmp, cached, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        return bytes;
    }

    private byte[] download(String url) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(60))
            .GET()
            .build();
        try {
            HttpResponse<InputStream> resp =
                http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() / 100 != 2) {
                throw new IOException("download failed (HTTP " + resp.statusCode() + "): " + url);
            }
            try (InputStream in = resp.body()) {
                byte[] bytes = readCapped(in, maxBytes);
                return bytes;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("download interrupted: " + url, e);
        }
    }

    /** Reads up to {@code cap} bytes; throws if the stream exceeds it. */
    private static byte[] readCapped(InputStream in, long cap) throws IOException {
        var out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > cap) {
                throw new IOException("module exceeds size cap (" + cap + " bytes)");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
