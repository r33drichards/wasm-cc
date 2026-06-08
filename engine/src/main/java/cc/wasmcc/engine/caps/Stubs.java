package cc.wasmcc.engine.caps;

import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.ExternalType;
import com.dylibso.chicory.wasm.types.FunctionImport;
import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.Import;
import com.dylibso.chicory.wasm.types.ImportSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Auto-stubs the function imports a module declares that aren't served by a
 * native capability pack, with a zero-returning no-op of the correct signature —
 * so a module still instantiates instead of failing to link. This mirrors the
 * resilient import loop in the mcp-v8 example (Emscripten {@code STANDALONE_WASM}
 * builds import a handful of {@code env.*} functions like
 * {@code emscripten_notify_memory_growth} that an in-memory module never needs).
 *
 * <p>A module whose name is in {@code nativeModules} (e.g. {@code
 * wasi_snapshot_preview1}, served by Chicory's WasiPreview1) is left alone; every
 * other function import gets a stub returning the right number of zero words.
 */
final class Stubs {

    private Stubs() {}

    static List<HostFunction> forUnprovided(WasmModule module, Set<String> nativeModules) {
        List<HostFunction> out = new ArrayList<>();
        ImportSection imports = module.importSection();
        for (int i = 0; i < imports.importCount(); i++) {
            Import imp = imports.getImport(i);
            if (imp.importType() != ExternalType.FUNCTION) {
                continue; // table/memory/global imports are out of scope here
            }
            if (nativeModules.contains(imp.module())) {
                continue; // a native pack provides this whole module's functions
            }
            FunctionType ft = module.typeSection().getType(((FunctionImport) imp).typeIndex());
            int nRet = ft.returns().size();
            out.add(new HostFunction(imp.module(), imp.name(), ft,
                (Instance instance, long... args) -> nRet == 0 ? null : new long[nRet]));
        }
        return out;
    }
}
