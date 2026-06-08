package cc.wasmcc.engine.caps;

import cc.wasmcc.engine.InstanceConfig;
import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.Store;
import com.dylibso.chicory.wasi.WasiOptions;
import com.dylibso.chicory.wasi.WasiPreview1;
import com.dylibso.chicory.wasm.WasmModule;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Assembles the Java host imports for an instance from the requested capabilities.
 *
 * <p>Today every instance gets the full WASI Preview 1 surface (via Chicory's
 * {@link WasiPreview1}) with stdout/stderr captured; the {@code fs} cap mounts a
 * host directory at the guest root (the CC computer's real disk in production),
 * and the {@code http} cap adds an allowlisted net import. Anything the module
 * imports beyond these is auto-stubbed by {@link Stubs} so it still instantiates.
 */
public final class Caps {

    private Caps() {}

    /** Module name of the WASI Preview 1 imports Chicory's WasiPreview1 serves. */
    private static final String WASI_MODULE = "wasi_snapshot_preview1";

    public static ImportBundle assemble(WasmModule module, InstanceConfig cfg) {
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();

        WasiOptions.Builder ob = WasiOptions.builder()
            .withStdout(stdout)
            .withStderr(stderr)
            .withStdin(new ByteArrayInputStream(new byte[0]));
        if (!cfg.args().isEmpty()) {
            ob.withArguments(cfg.args());
        }
        cfg.env().forEach(ob::withEnvironment);
        if (cfg.has("fs") && cfg.fsDir() != null) {
            // Mount the host directory at the guest root. In production fsDir is
            // the CC computer's own save dir, validated/confined by the mod layer.
            ob.withDirectory("/", cfg.fsDir());
        }

        WasiPreview1 wasi = WasiPreview1.builder().withOptions(ob.build()).build();
        Store store = new Store().addFunction(wasi.toHostFunctions());

        // Modules served natively (not auto-stubbed). WASI always; http when on.
        Set<String> nativeModules = new HashSet<>();
        nativeModules.add(WASI_MODULE);
        if (cfg.has("http")) {
            HostFunction[] httpFns = HttpPack.hostFunctions(cfg.httpAllow());
            if (httpFns.length > 0) {
                store.addFunction(httpFns);
                nativeModules.add(HttpPack.MODULE);
            }
        }

        // Stub any other function import (e.g. Emscripten env.* no-ops) so the
        // module links even though we don't natively serve it.
        List<HostFunction> stubs = Stubs.forUnprovided(module, nativeModules);
        if (!stubs.isEmpty()) {
            store.addFunction(stubs.toArray(new HostFunction[0]));
        }

        return new ImportBundle(store.toImportValues(), wasi, stdout, stderr);
    }
}
