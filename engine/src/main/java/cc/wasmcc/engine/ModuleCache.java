package cc.wasmcc.engine;

import com.dylibso.chicory.compiler.MachineFactoryCompiler;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Caches parsed + AOT-compiled modules keyed by content SHA-256, so a given
 * {@code .wasm} is parsed and compiled to JVM bytecode at most once. (picat-cc
 * compiled its single static module at class load; arbitrary modules need
 * compile-on-first-use plus reuse.) LRU-evicted; access is synchronized because
 * mode-A (computer thread) and mode-B (worker pool) may both compile.
 */
public final class ModuleCache {

    private final int maxEntries;
    private final LinkedHashMap<String, CompiledModule> lru;

    public ModuleCache(int maxEntries) {
        this.maxEntries = Math.max(1, maxEntries);
        this.lru = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CompiledModule> e) {
                return size() > ModuleCache.this.maxEntries;
            }
        };
    }

    /** Parse + compile {@code wasm} (or return the cached result for these bytes). */
    public synchronized CompiledModule compile(byte[] wasm) {
        String sha = sha256(wasm);
        CompiledModule cached = lru.get(sha);
        if (cached != null) {
            return cached;
        }
        WasmModule module = Parser.parse(wasm);
        // AOT-compile to JVM bytecode once; the factory is reused per instance.
        var factory = MachineFactoryCompiler.compile(module);
        CompiledModule compiled = new CompiledModule(sha, module, factory);
        lru.put(sha, compiled);
        return compiled;
    }

    public synchronized int size() {
        return lru.size();
    }

    static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
