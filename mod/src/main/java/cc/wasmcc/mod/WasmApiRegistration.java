package cc.wasmcc.mod;

import dan200.computercraft.api.ComputerCraftAPI;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wires the wasm engine into ComputerCraft once at mod init and binds the heavy
 * {@link WasmEngine} (thread pool + module cache) to the Minecraft server
 * lifecycle, exactly as picat-cc does: one process-wide {@code registerAPIFactory}
 * whose lambda reads the {@code volatile} engine for the currently-running server,
 * (re)created on SERVER_STARTING and shut down on SERVER_STOPPING.
 */
public final class WasmApiRegistration {
    private static final Logger LOG = LoggerFactory.getLogger("wasm-cc");

    private static volatile WasmEngine engine;

    private WasmApiRegistration() {}

    static WasmEngine engine() {
        return engine;
    }

    public static void register() {
        ComputerCraftAPI.registerAPIFactory(
            computer -> new WasmLuaAPI(computer, WasmApiRegistration::engine));

        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            WasmCcConfig cfg = WasmCcConfig.load();
            engine = WasmEngine.create(cfg, FabricLoader.getInstance().getConfigDir());
            LOG.info("wasm-cc engine started (threads={}, maxTimeout={}s, maxJobsPerComputer={}, "
                    + "maxInstancesPerComputer={}, allowUrl={})",
                cfg.workerThreads, cfg.maxTimeoutSeconds, cfg.maxJobsPerComputer,
                cfg.maxInstancesPerComputer, cfg.allowUrlModules);
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            WasmEngine e = engine;
            engine = null;
            if (e != null) {
                e.shutdown();
                LOG.info("wasm-cc engine stopped");
            }
        });
    }
}
