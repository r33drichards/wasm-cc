package cc.wasmcc.engine;

import cc.wasmcc.engine.caps.HttpPack;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls a wasm module straight from a public <b>OCI registry</b> (anonymous reads
 * only), verifying the bytes against their content digest. Host-neutral: pure
 * {@link java.net.http.HttpClient} + Gson, no Minecraft types, so it is unit
 * testable with an in-process {@code com.sun.net.httpserver.HttpServer}.
 *
 * <p>Flow (per the design doc):
 * <ol>
 *   <li>{@code GET /v2/<repo>/manifests/<ref>}; on {@code 401} fetch an anonymous
 *       bearer token from the {@code WWW-Authenticate} realm and retry once.</li>
 *   <li>Pick the wasm layer from the manifest.</li>
 *   <li>{@code GET /v2/<repo>/blobs/<digest>}, following the CDN redirect but
 *       <b>stripping {@code Authorization} across a cross-origin redirect</b> (the
 *       blob URL is presigned).</li>
 *   <li>Verify {@code sha256(bytes) == layer digest} (hard fail on mismatch) and
 *       cap the size while streaming.</li>
 * </ol>
 * Blob bytes are cached on disk keyed by their (immutable) content digest, so a
 * tag re-resolves the manifest cheaply but the bytes are fetched once.
 *
 * <p>Scheme is {@code https} except for {@code localhost}/loopback registries,
 * which use plain {@code http} (mirrors container tooling's {@code --plain-http}
 * and lets tests drive the full flow against a local stub server).
 */
final class OciResolver {

    private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Pattern AUTH_PARAM = Pattern.compile("(\\w+)=\"([^\"]*)\"");
    private static final String ACCEPT =
        "application/vnd.oci.image.manifest.v1+json, "
        + "application/vnd.docker.distribution.manifest.v2+json";

    private final Path cacheDir;            // nullable (blob cache disabled if null)
    private final long maxBytes;
    private final boolean allowOci;
    private final List<String> registryAllow;
    private final HttpClient http;          // NORMAL redirects (manifest + token)
    private final HttpClient noRedirect;    // NEVER (manual blob redirect, auth-stripping)

    OciResolver(Path cacheDir, long maxBytes, boolean allowOci, List<String> registryAllow) {
        this.cacheDir = cacheDir;
        this.maxBytes = maxBytes;
        this.allowOci = allowOci;
        this.registryAllow = registryAllow == null ? List.of() : registryAllow;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        this.noRedirect = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    /** A parsed OCI reference: {@code registry/repo} pinned by tag or digest. */
    static final class Ref {
        final String registry;   // host[:port]
        final String repo;       // e.g. "owner/name"
        final String reference;  // tag, or "sha256:<hex>" manifest digest
        Ref(String registry, String repo, String reference) {
            this.registry = registry;
            this.repo = repo;
            this.reference = reference;
        }
    }

    /**
     * Parse a no-scheme/oci ref body ({@code <registry>/<repo>[:tag|@sha256:..]})
     * into a {@link Ref}, or return {@code null} if it is not a plausible OCI ref
     * (so the caller can fall through to a clearer error for bare names).
     */
    static Ref tryParse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        String repoPart;
        String reference;
        int at = body.indexOf('@');
        if (at >= 0) {
            reference = body.substring(at + 1);
            repoPart = body.substring(0, at);
            if (!DIGEST.matcher(reference).matches()) {
                return null;
            }
        } else {
            int slash = body.lastIndexOf('/');
            int colon = body.lastIndexOf(':');
            if (colon > slash) {           // a tag (the colon is after the last '/')
                reference = body.substring(colon + 1);
                repoPart = body.substring(0, colon);
            } else {                       // no tag → default to "latest"
                reference = "latest";
                repoPart = body;
            }
        }
        int firstSlash = repoPart.indexOf('/');
        if (firstSlash <= 0 || firstSlash == repoPart.length() - 1) {
            return null;                   // need both a registry and a repo
        }
        String registry = repoPart.substring(0, firstSlash);
        String repo = repoPart.substring(firstSlash + 1);
        if (repo.isBlank() || reference.isBlank()) {
            return null;
        }
        // The registry must look like a host: a dot, an explicit port, or localhost.
        String regHost = registry.contains(":") ? registry.substring(0, registry.indexOf(':')) : registry;
        if (!(registry.contains(".") || registry.contains(":")
                || regHost.equalsIgnoreCase("localhost"))) {
            return null;
        }
        return new Ref(registry, repo, reference);
    }

    byte[] resolve(Ref ref) throws IOException {
        if (!allowOci) {
            throw new IOException("OCI modules are disabled (set allowOciModules)");
        }
        if (!registryAllowed(ref.registry)) {
            throw new IOException("OCI registry not allowlisted: " + ref.registry
                + " (add it to ociRegistryAllow)");
        }
        String base = scheme(ref.registry) + "://" + ref.registry;

        // 1. Manifest (with one anonymous-token retry on 401).
        String manifestUrl = base + "/v2/" + ref.repo + "/manifests/" + ref.reference;
        Manifest mf = fetchManifest(manifestUrl, ref);

        // 2. Select the wasm layer → its content digest.
        String layerDigest = selectWasmLayer(mf.json, ref);
        String hex = layerDigest.substring(layerDigest.indexOf(':') + 1).toLowerCase(Locale.ROOT);

        // Cache is keyed by the immutable layer digest.
        Path cached = cacheDir == null ? null : cacheDir.resolve("oci-" + hex + ".wasm");
        if (cached != null && Files.isRegularFile(cached)) {
            return Files.readAllBytes(cached);
        }

        // 3. Blob (manual redirect, dropping auth cross-origin) + verify digest.
        String blobUrl = base + "/v2/" + ref.repo + "/blobs/" + layerDigest;
        byte[] bytes = fetchBlob(blobUrl, base, mf.token);

        String actual = ModuleCache.sha256(bytes);
        if (!actual.equalsIgnoreCase(hex)) {
            throw new IOException("OCI blob digest mismatch: expected sha256:" + hex
                + " but got sha256:" + actual);
        }

        if (cached != null) {
            Files.createDirectories(cacheDir);
            Path tmp = Files.createTempFile(cacheDir, "oci", ".part");
            Files.write(tmp, bytes);
            Files.move(tmp, cached, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        return bytes;
    }

    // --- manifest + token --------------------------------------------------

    private static final class Manifest {
        final JsonObject json;
        final String token;   // nullable; carried so the blob fetch can reuse it
        Manifest(JsonObject json, String token) {
            this.json = json;
            this.token = token;
        }
    }

    private Manifest fetchManifest(String url, Ref ref) throws IOException {
        HttpResponse<String> resp = send(http, manifestRequest(url, null));
        String token = null;
        if (resp.statusCode() == 401) {
            token = anonymousToken(resp, ref);
            resp = send(http, manifestRequest(url, token));
        }
        if (resp.statusCode() == 404) {
            throw new IOException("OCI manifest not found: " + url);
        }
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("OCI manifest fetch failed (HTTP " + resp.statusCode()
                + "): " + url);
        }
        JsonObject json;
        try {
            json = JsonParser.parseString(resp.body()).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("OCI manifest is not valid JSON: " + url, e);
        }
        if (json.has("manifests")) {
            throw new IOException("OCI manifest index / multi-platform images are not "
                + "supported; pin a single-platform manifest digest");
        }
        return new Manifest(json, token);
    }

    private static HttpRequest manifestRequest(String url, String token) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", ACCEPT)
            .GET();
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return b.build();
    }

    private String anonymousToken(HttpResponse<String> challenge, Ref ref) throws IOException {
        String header = challenge.headers().firstValue("WWW-Authenticate")
            .orElseThrow(() -> new IOException("OCI registry returned 401 without a "
                + "WWW-Authenticate challenge"));
        if (!header.regionMatches(true, 0, "Bearer", 0, 6)) {
            throw new IOException("unsupported OCI auth scheme (only anonymous Bearer "
                + "is supported): " + header);
        }
        String realm = null, service = null, scope = null;
        Matcher m = AUTH_PARAM.matcher(header);
        while (m.find()) {
            switch (m.group(1).toLowerCase(Locale.ROOT)) {
                case "realm" -> realm = m.group(2);
                case "service" -> service = m.group(2);
                case "scope" -> scope = m.group(2);
                default -> { }
            }
        }
        if (realm == null) {
            throw new IOException("OCI Bearer challenge had no realm: " + header);
        }
        if (scope == null) {
            scope = "repository:" + ref.repo + ":pull";
        }
        StringBuilder q = new StringBuilder(realm);
        q.append(realm.contains("?") ? '&' : '?');
        if (service != null) {
            q.append("service=").append(enc(service)).append('&');
        }
        q.append("scope=").append(enc(scope));

        HttpRequest req = HttpRequest.newBuilder(URI.create(q.toString()))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json")
            .GET()
            .build();
        HttpResponse<String> resp = send(http, req);
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("OCI anonymous token fetch failed (HTTP "
                + resp.statusCode() + ")");
        }
        JsonObject tok;
        try {
            tok = JsonParser.parseString(resp.body()).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("OCI token response is not valid JSON", e);
        }
        JsonElement t = tok.has("token") ? tok.get("token") : tok.get("access_token");
        if (t == null || t.isJsonNull()) {
            throw new IOException("OCI token response had no token");
        }
        return t.getAsString();
    }

    // --- layer selection ---------------------------------------------------

    private static String selectWasmLayer(JsonObject manifest, Ref ref) throws IOException {
        JsonArray layers = manifest.getAsJsonArray("layers");
        if (layers == null || layers.isEmpty()) {
            throw new IOException("OCI manifest has no layers: " + ref.registry + "/" + ref.repo);
        }
        List<JsonObject> wasmish = new ArrayList<>();
        for (JsonElement el : layers) {
            JsonObject layer = el.getAsJsonObject();
            JsonElement mt = layer.get("mediaType");
            if (mt != null && isWasm(mt.getAsString())) {
                wasmish.add(layer);
            }
        }
        JsonObject chosen;
        if (wasmish.size() == 1) {
            chosen = wasmish.get(0);
        } else if (wasmish.isEmpty() && layers.size() == 1) {
            chosen = layers.get(0).getAsJsonObject();   // single layer, take it
        } else if (wasmish.size() > 1) {
            throw new IOException("OCI manifest has multiple wasm layers; ambiguous");
        } else {
            throw new IOException("OCI manifest has no wasm layer");
        }
        JsonElement digest = chosen.get("digest");
        if (digest == null || !DIGEST.matcher(digest.getAsString()).matches()) {
            throw new IOException("OCI wasm layer has no valid sha256 digest");
        }
        return digest.getAsString();
    }

    private static boolean isWasm(String mediaType) {
        String mt = mediaType.toLowerCase(Locale.ROOT);
        return mt.equals("application/wasm") || mt.endsWith("+wasm") || mt.contains("wasm");
    }

    // --- blob fetch (manual redirect, auth-stripping) ----------------------

    private byte[] fetchBlob(String blobUrl, String registryBase, String token) throws IOException {
        String registryOrigin = originOf(URI.create(registryBase));
        String url = blobUrl;
        for (int hop = 0; hop < 6; hop++) {
            URI uri = URI.create(url);
            boolean sameOrigin = originOf(uri).equals(registryOrigin);
            HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(60))
                .GET();
            // Only attach the registry bearer token on the registry's own origin;
            // a cross-origin (CDN) redirect target is presigned, so auth is dropped.
            if (token != null && sameOrigin) {
                b.header("Authorization", "Bearer " + token);
            }
            HttpResponse<InputStream> resp;
            try {
                resp = noRedirect.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("OCI blob fetch interrupted: " + url, e);
            }
            int sc = resp.statusCode();
            if (sc / 100 == 2) {
                try (InputStream in = resp.body()) {
                    return ModuleResolver.readCapped(in, maxBytes);
                }
            }
            if (sc == 301 || sc == 302 || sc == 303 || sc == 307 || sc == 308) {
                String loc = resp.headers().firstValue("Location")
                    .orElseThrow(() -> new IOException("OCI blob redirect without Location"));
                resp.body().close();
                url = uri.resolve(loc).toString();
                continue;
            }
            throw new IOException("OCI blob fetch failed (HTTP " + sc + "): " + url);
        }
        throw new IOException("OCI blob fetch exceeded redirect limit: " + blobUrl);
    }

    // --- helpers -----------------------------------------------------------

    private boolean registryAllowed(String registry) {
        // Reuse the http cap's host-pattern matcher (host[:port], *.suffix, *).
        return HttpPack.allowed("https://" + registry + "/", registryAllow);
    }

    private static String scheme(String registry) {
        String host = registry.contains(":") ? registry.substring(0, registry.indexOf(':')) : registry;
        host = host.toLowerCase(Locale.ROOT);
        boolean loopback = host.equals("localhost") || host.equals("127.0.0.1")
            || host.equals("::1") || host.equals("[::1]");
        return loopback ? "http" : "https";
    }

    private static String originOf(URI uri) {
        int port = uri.getPort();
        return (uri.getScheme() + "://" + uri.getHost() + ":" + port).toLowerCase(Locale.ROOT);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static HttpResponse<String> send(HttpClient client, HttpRequest req) throws IOException {
        try {
            return client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("OCI request interrupted: " + req.uri(), e);
        }
    }
}
