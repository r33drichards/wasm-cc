package cc.wasmcc.mod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code wasm-cc.json} in the Fabric config dir; created with defaults on first
 * run. On any read/parse error the defaults are returned (and logged) so a
 * corrupt config never blocks startup. Public plain fields so Gson round-trips
 * with no custom adapters.
 */
public final class WasmCcConfig {
    private static final Logger LOG = LoggerFactory.getLogger("wasm-cc");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // --- mode-B off-thread runner (ported from picat-cc) ---
    /** Worker pool size (concurrent in-flight mode-B runs, process-wide). */
    public int workerThreads = 6;
    /** Reject new work once this many timed-out zombie runs are outstanding. */
    public int maxAbandonedJobs = 32;
    /** Hard cap (seconds) on any single mode-B run's timeout. */
    public int maxTimeoutSeconds = 300;
    /** How many mode-B runs a single computer may have in flight at once. */
    public int maxJobsPerComputer = 8;

    // --- mode-A raw API ---
    /** How many live instances a single computer may hold open at once. */
    public int maxInstancesPerComputer = 16;

    // --- module sourcing / limits ---
    /** Directory (under the Fabric config dir) of named modules: {@code <name>.wasm}. */
    public String modulesDir = "wasm-modules";
    /** Allow {@code http(s)://} module references (downloaded + cached). */
    public boolean allowUrlModules = false;
    /** Max module size in bytes (name lookups + URL downloads). */
    public long maxModuleBytes = 64L * 1024 * 1024;

    // --- capabilities ---
    /** Capabilities granted by default when a caller omits {@code caps}. */
    public List<String> defaultCaps = List.of("wasi");
    /** Allowlist (host[:port] patterns) for the http cap; empty = deny all. */
    public List<String> httpAllow = List.of();

    public static WasmCcConfig load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("wasm-cc.json");
        if (Files.isRegularFile(path)) {
            try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                WasmCcConfig cfg = GSON.fromJson(r, WasmCcConfig.class);
                if (cfg == null) {
                    LOG.warn("wasm-cc config {} was empty; using defaults", path);
                    return new WasmCcConfig();
                }
                return cfg.sanitised();
            } catch (Exception e) {
                LOG.warn("Failed to read wasm-cc config {}; using defaults", path, e);
                return new WasmCcConfig();
            }
        }
        WasmCcConfig cfg = new WasmCcConfig();
        try {
            Files.createDirectories(path.getParent());
            try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(cfg, w);
            }
        } catch (IOException e) {
            LOG.warn("Failed to write default wasm-cc config {}; continuing", path, e);
        }
        return cfg;
    }

    /** Clamp deserialized values into sane ranges (mirrors picat-cc's floors so a
     *  hand-edited config can't wedge the runner). */
    private WasmCcConfig sanitised() {
        if (workerThreads < 4) workerThreads = 4;
        if (maxAbandonedJobs < 16) maxAbandonedJobs = 16;
        if (maxTimeoutSeconds < 1) maxTimeoutSeconds = 1;
        if (maxJobsPerComputer < 2) maxJobsPerComputer = 2;
        if (maxInstancesPerComputer < 1) maxInstancesPerComputer = 1;
        if (maxModuleBytes < 1024) maxModuleBytes = 1024;
        if (modulesDir == null || modulesDir.isBlank()) modulesDir = "wasm-modules";
        if (defaultCaps == null) defaultCaps = List.of("wasi");
        if (httpAllow == null) httpAllow = List.of();
        return this;
    }
}
