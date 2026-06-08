package cc.wasmcc.engine;

import cc.wasmcc.engine.caps.Caps;
import cc.wasmcc.engine.caps.ImportBundle;
import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.wasi.WasiExitException;

/**
 * Entry point to the raw WebAssembly API: {@link #compile} a module (cached) and
 * {@link #instantiate} it with a chosen set of Java-backed import capabilities,
 * yielding a {@link WasmInstance} whose exports/memory the caller drives directly.
 *
 * <p>This is the host-neutral engine: no ComputerCraft or Minecraft types. The
 * mod layer wraps it for Lua (mode A, synchronous on the computer thread); the
 * off-thread mode-B runner is built on the same {@link #compile} + a command-style
 * instantiate.
 */
public final class WasmHost {

    private final ModuleCache cache;

    public WasmHost() {
        this(new ModuleCache(64));
    }

    public WasmHost(ModuleCache cache) {
        this.cache = cache;
    }

    /** Parse + AOT-compile (or reuse the cached compilation of these bytes). */
    public CompiledModule compile(byte[] wasm) {
        return cache.compile(wasm);
    }

    /**
     * Instantiate a module for the raw API (reactor style): the start/{@code _start}
     * export is NOT auto-run; instead, if the module exports {@code _initialize}
     * (a wasi-sdk/Emscripten reactor's ctor entry) it is invoked so globals are set
     * up before the caller touches exports.
     */
    public WasmInstance instantiate(CompiledModule module, InstanceConfig cfg) {
        ImportBundle imports = Caps.assemble(module.module(), cfg);
        Instance instance;
        try {
            instance = Instance.builder(module.module())
                .withMachineFactory(module.machineFactory())
                .withImportValues(imports.importValues())
                .withStart(false) // raw API: do not auto-run _start
                .build();
        } catch (RuntimeException e) {
            imports.close();
            throw e;
        }
        // Reactor ctor entry, if present (separate the absent-lookup from a real
        // call failure: only swallow the former).
        ExportFunction init;
        try {
            init = instance.export("_initialize");
        } catch (RuntimeException absent) {
            init = null;
        }
        if (init != null) {
            init.apply();
        }
        return new WasmInstance(instance, imports);
    }

    /** Convenience: compile + instantiate in one call. */
    public WasmInstance instantiate(byte[] wasm, InstanceConfig cfg) {
        return instantiate(compile(wasm), cfg);
    }

    /**
     * Mode-B command run: instantiate with {@code _start} auto-run to completion,
     * capturing the WASI exit code and stdout/stderr. Used by the off-thread
     * service for long/heavy/networked modules; the guest reads and writes files
     * on the mounted host directory ({@link InstanceConfig#fsDir}).
     *
     * <p>A normal C {@code main} that returns triggers wasi-libc's {@code exit()},
     * surfaced by Chicory as {@link WasiExitException} — caught here as the exit
     * code (0 on clean return). Other traps propagate after imports are released.
     */
    public RunResult run(CompiledModule module, InstanceConfig cfg) {
        ImportBundle imports = Caps.assemble(module.module(), cfg);
        int exit = 0;
        RuntimeException trap = null;
        try {
            Instance.builder(module.module())
                .withMachineFactory(module.machineFactory())
                .withImportValues(imports.importValues())
                .withStart(true) // command module: _start runs on build
                .build();
        } catch (WasiExitException e) {
            exit = e.exitCode();
        } catch (RuntimeException e) {
            trap = e; // re-thrown after streams are captured + imports closed
        }
        // Read the captured streams BEFORE close (close only flushes WASI).
        String out = imports.stdout();
        String err = imports.stderr();
        imports.close();
        if (trap != null) {
            throw trap;
        }
        return new RunResult(exit, out, err);
    }

    /** Convenience: compile + run in one call. */
    public RunResult run(byte[] wasm, InstanceConfig cfg) {
        return run(compile(wasm), cfg);
    }
}
