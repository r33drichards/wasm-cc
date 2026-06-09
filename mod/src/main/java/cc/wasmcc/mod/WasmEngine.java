package cc.wasmcc.mod;

import cc.wasmcc.engine.ModuleResolver;
import cc.wasmcc.engine.ModuleCache;
import cc.wasmcc.engine.WasmHost;
import cc.wasmcc.engine.run.WasmService;

import java.nio.file.Path;
import java.util.List;

/**
 * The per-server engine: a shared {@link WasmHost} (one module cache, so a module
 * is compiled once across all computers), a {@link ModuleResolver} (named dir +
 * optional URL), and the off-thread {@link WasmService} for mode-B runs. Built on
 * SERVER_STARTING from {@link WasmCcConfig}; shut down on SERVER_STOPPING.
 */
public final class WasmEngine {

    private final WasmHost host;
    private final ModuleResolver resolver;
    private final WasmService service;
    private final int maxInstancesPerComputer;
    private final List<String> defaultCaps;
    private final List<String> httpAllow;

    private WasmEngine(WasmHost host, ModuleResolver resolver, WasmService service,
            int maxInstancesPerComputer, List<String> defaultCaps, List<String> httpAllow) {
        this.host = host;
        this.resolver = resolver;
        this.service = service;
        this.maxInstancesPerComputer = maxInstancesPerComputer;
        this.defaultCaps = defaultCaps;
        this.httpAllow = httpAllow;
    }

    /**
     * @param configDir the Fabric config dir, under which the modules dir and the
     *                  URL-download cache live
     */
    public static WasmEngine create(WasmCcConfig cfg, Path configDir) {
        WasmHost host = new WasmHost(new ModuleCache(64));
        Path modulesDir = configDir.resolve(cfg.modulesDir);
        Path cacheDir = configDir.resolve("wasm-cache");
        ModuleResolver resolver = new ModuleResolver(
            modulesDir, cacheDir, cfg.maxModuleBytes, cfg.allowUrlModules,
            cfg.allowOciModules, cfg.ociRegistryAllow);
        WasmService service = new WasmService(host, cfg.workerThreads,
            cfg.maxAbandonedJobs, cfg.maxTimeoutSeconds * 1000L, cfg.maxJobsPerComputer);
        return new WasmEngine(host, resolver, service,
            cfg.maxInstancesPerComputer, cfg.defaultCaps, cfg.httpAllow);
    }

    public WasmHost host() { return host; }
    public ModuleResolver resolver() { return resolver; }
    public WasmService service() { return service; }
    public int maxInstancesPerComputer() { return maxInstancesPerComputer; }
    public List<String> defaultCaps() { return defaultCaps; }
    public List<String> httpAllow() { return httpAllow; }

    public void shutdown() {
        service.shutdown();
    }
}
