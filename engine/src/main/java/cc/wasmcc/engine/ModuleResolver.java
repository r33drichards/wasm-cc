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
import java.util.List;

/**
 * Turns a module reference into raw wasm bytes. Dispatch is purely by scheme:
 * <ul>
 *   <li>{@code oci://<registry>/<repo>:<tag>} (or {@code …@sha256:<hex>}), and the
 *       no-scheme sugar {@code <registry>/<repo>:<tag>}, pull from an OCI registry
 *       with content-digest verification (see {@link OciResolver});</li>
 *   <li>an {@code http(s)://} URL is downloaded once and cached on disk (keyed by
 *       the SHA-256 of the URL), subject to a size cap;</li>
 *   <li>{@code file://<name>} resolves to the local module
 *       {@code <modulesDir>/<name>.wasm} (path-traversal rejected; {@code .wasm}
 *       optional).</li>
 * </ul>
 * URL downloads are opt-in ({@code allowUrl}) and OCI pulls are opt-in
 * ({@code allowOci} + an allowlist) so a locked-down server can restrict modules
 * to a curated directory. Bytes are handed to {@link WasmHost#compile} (which
 * de-dups by content hash), so re-resolving the same module is cheap.
 */
public final class ModuleResolver {

    private final Path modulesDir;   // nullable (file:// lookups disabled if null)
    private final long maxBytes;
    private final boolean allowUrl;
    private final HttpClient http;
    private final Path cacheDir;     // nullable (URL caching disabled if null)
    private final OciResolver oci;

    public ModuleResolver(Path modulesDir, Path cacheDir, long maxBytes, boolean allowUrl,
            boolean allowOci, List<String> ociRegistryAllow) {
        this.modulesDir = modulesDir;
        this.cacheDir = cacheDir;
        this.maxBytes = maxBytes;
        this.allowUrl = allowUrl;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        this.oci = new OciResolver(cacheDir, maxBytes, allowOci, ociRegistryAllow);
    }

    public byte[] resolve(String ref) throws IOException {
        if (ref == null || ref.isBlank()) {
            throw new IOException("module ref is empty");
        }
        String lower = ref.toLowerCase();
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return resolveUrl(ref);
        }
        if (lower.startsWith("file://")) {
            return resolveName(ref.substring("file://".length()));
        }
        boolean explicitOci = lower.startsWith("oci://");
        String body = explicitOci ? ref.substring("oci://".length()) : ref;
        OciResolver.Ref parsed = OciResolver.tryParse(body);
        if (parsed != null) {
            return oci.resolve(parsed);
        }
        if (explicitOci) {
            throw new IOException("invalid oci ref: " + ref
                + " (expected oci://<registry>/<repo>:<tag> or @sha256:<digest>)");
        }
        throw new IOException("unknown module ref '" + ref
            + "'; use file://<name> for a local module, http(s):// for a URL, "
            + "or oci://<registry>/<repo>:<tag> for a registry pull");
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
    static byte[] readCapped(InputStream in, long cap) throws IOException {
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
