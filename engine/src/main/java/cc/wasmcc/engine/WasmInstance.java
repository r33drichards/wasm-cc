package cc.wasmcc.engine;

import cc.wasmcc.engine.caps.ImportBundle;
import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Memory;

/**
 * A live guest instance exposed via the raw WebAssembly API: call any exported
 * function by name ({@link #call}) and read/write its linear memory
 * ({@link #memory}). This is the Java surface a CC Lua script (or a JUnit test)
 * drives exactly like the mcp-v8 JS example drives {@code instance.exports}.
 *
 * <p>Closing releases the WASI backing. The instance itself is single-threaded:
 * a Chicory call runs to completion on the calling thread (no mid-call yield).
 */
public final class WasmInstance implements AutoCloseable {

    private final Instance instance;
    private final ImportBundle imports;
    private final MemoryView memory; // nullable if the module exports no memory

    WasmInstance(Instance instance, ImportBundle imports) {
        this.instance = instance;
        this.imports = imports;
        Memory mem = tryMemory(instance);
        this.memory = mem == null ? null
            : new MemoryView(mem, tryExport("malloc"), tryExport("free"));
    }

    /** Calls exported {@code name} with i32/i64/f32/f64 args (passed as longs),
     *  returning the raw result word(s) (empty for void). */
    public long[] call(String name, long... args) {
        ExportFunction fn = instance.export(name);
        long[] r = fn.apply(args);
        return r == null ? new long[0] : r;
    }

    /** Calls {@code name} and returns the first result truncated to i32. */
    public int callI32(String name, long... args) {
        long[] r = call(name, args);
        return r.length == 0 ? 0 : (int) r[0];
    }

    /** Calls {@code name} and returns the first result as a long. */
    public long callI64(String name, long... args) {
        long[] r = call(name, args);
        return r.length == 0 ? 0 : r[0];
    }

    /** Calls {@code name} and decodes the first result word as an f64 (Chicory
     *  returns floats as their raw IEEE-754 bits in the result word). */
    public double callF64(String name, long... args) {
        long[] r = call(name, args);
        return r.length == 0 ? 0.0 : Double.longBitsToDouble(r[0]);
    }

    /** Calls {@code name} and decodes the first result word as an f32. */
    public float callF32(String name, long... args) {
        long[] r = call(name, args);
        return r.length == 0 ? 0f : Float.intBitsToFloat((int) r[0]);
    }

    /** Linear-memory view, or null if the module exports no memory. */
    public MemoryView memory() {
        return memory;
    }

    public String stdout() { return imports.stdout(); }
    public String stderr() { return imports.stderr(); }

    /** Whether an export of this name exists (a callable function). */
    public boolean hasExport(String name) {
        return tryExport(name) != null;
    }

    @Override
    public void close() {
        imports.close();
    }

    private ExportFunction tryExport(String name) {
        try {
            return instance.export(name);
        } catch (RuntimeException absent) {
            return null;
        }
    }

    private static Memory tryMemory(Instance instance) {
        try {
            return instance.memory();
        } catch (RuntimeException none) {
            return null;
        }
    }
}
