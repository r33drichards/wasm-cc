package cc.wasmcc.engine;

import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Machine;
import com.dylibso.chicory.wasm.WasmModule;

import java.util.function.Function;

/**
 * A parsed + AOT-compiled module, keyed by the SHA-256 of its bytes. The
 * expensive {@link com.dylibso.chicory.compiler.MachineFactoryCompiler#compile
 * machine factory} is built once and reused across every {@link Instance}, so
 * repeated instantiation of the same module is cheap (the per-call cost is a
 * fresh instance, not a recompile).
 */
public final class CompiledModule {

    private final String sha256;
    private final WasmModule module;
    private final Function<Instance, Machine> machineFactory;

    CompiledModule(String sha256, WasmModule module,
            Function<Instance, Machine> machineFactory) {
        this.sha256 = sha256;
        this.module = module;
        this.machineFactory = machineFactory;
    }

    public String sha256() { return sha256; }
    public WasmModule module() { return module; }
    public Function<Instance, Machine> machineFactory() { return machineFactory; }
}
