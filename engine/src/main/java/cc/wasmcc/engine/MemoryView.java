package cc.wasmcc.engine;

import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.Memory;

import java.nio.charset.StandardCharsets;

/**
 * Thin, host-language-neutral wrapper over a guest module's linear {@link Memory},
 * plus the marshalling helpers every caller of the raw WebAssembly API needs
 * (the same primitives the mcp-v8 JS example open-codes: {@code allocString},
 * {@code readCString}, byte/int read-write).
 *
 * <p>It deliberately mirrors the JS {@code DataView}/{@code Uint8Array} surface so
 * a CC Lua script (or a JUnit test) drives a module identically to the V8 host:
 * {@code mem:writeString(p, sql)}, {@code mem:readCString(ptr)}, {@code mem:readU32(p)}.
 *
 * <p>{@link #allocString(String)}/{@link #alloc(int)} require the module to export
 * {@code malloc} (the common Emscripten/WASI-libc case). If it does not, those
 * methods throw {@link IllegalStateException}; the raw read/write methods work
 * regardless.
 */
public final class MemoryView {

    private final Memory memory;
    private final ExportFunction malloc; // nullable
    private final ExportFunction free;   // nullable

    MemoryView(Memory memory, ExportFunction malloc, ExportFunction free) {
        this.memory = memory;
        this.malloc = malloc;
        this.free = free;
    }

    /** The underlying Chicory memory, for callers that need the raw surface. */
    public Memory raw() {
        return memory;
    }

    // --- reads -------------------------------------------------------------

    public byte readByte(int addr) {
        return memory.read(addr);
    }

    public byte[] readBytes(int addr, int len) {
        return memory.readBytes(addr, len);
    }

    /** Signed 32-bit little-endian. */
    public int readI32(int addr) {
        return memory.readInt(addr);
    }

    /** Unsigned 32-bit little-endian, widened to a long (pointers/sizes). */
    public long readU32(int addr) {
        return memory.readU32(addr);
    }

    public long readI64(int addr) {
        return memory.readLong(addr);
    }

    public float readF32(int addr) {
        return memory.readFloat(addr);
    }

    public double readF64(int addr) {
        return memory.readDouble(addr);
    }

    /** Reads {@code len} bytes as UTF-8. */
    public String readString(int addr, int len) {
        return memory.readString(addr, len);
    }

    /** Reads a NUL-terminated UTF-8 C string. */
    public String readCString(int addr) {
        return memory.readCString(addr);
    }

    // --- writes ------------------------------------------------------------

    public void writeByte(int addr, byte b) {
        memory.writeByte(addr, b);
    }

    public void writeBytes(int addr, byte[] data) {
        memory.write(addr, data);
    }

    public void writeI32(int addr, int v) {
        memory.writeI32(addr, v);
    }

    public void writeI64(int addr, long v) {
        memory.writeLong(addr, v);
    }

    public void writeF32(int addr, float v) {
        memory.writeF32(addr, v);
    }

    public void writeF64(int addr, double v) {
        memory.writeF64(addr, v);
    }

    /** Writes UTF-8 bytes (no NUL terminator). */
    public void writeString(int addr, String s) {
        memory.writeString(addr, s);
    }

    /** Writes UTF-8 bytes followed by a NUL terminator. */
    public void writeCString(int addr, String s) {
        memory.writeCString(addr, s);
    }

    // --- allocation (requires exported malloc/free) ------------------------

    /** Allocates {@code n} bytes via the module's exported {@code malloc}. */
    public int alloc(int n) {
        if (malloc == null) {
            throw new IllegalStateException(
                "module does not export 'malloc'; cannot allocate guest memory");
        }
        int ptr = (int) malloc.apply(n)[0];
        if (ptr == 0) {
            throw new IllegalStateException("malloc(" + n + ") returned NULL");
        }
        return ptr;
    }

    /** Frees a pointer via the module's exported {@code free} (no-op if absent). */
    public void free(int ptr) {
        if (free != null && ptr != 0) {
            free.apply(ptr);
        }
    }

    /**
     * Allocates and writes {@code s} as a NUL-terminated UTF-8 C string, returning
     * the pointer. Caller owns the allocation (use {@link #free(int)}).
     */
    public int allocCString(String s) {
        byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
        int ptr = alloc(utf8.length + 1);
        memory.write(ptr, utf8);
        memory.writeByte(ptr + utf8.length, (byte) 0);
        return ptr;
    }

    /** Allocates a pointer-sized (4-byte) output slot, zero-initialised. */
    public int allocPtr() {
        int ptr = alloc(4);
        memory.writeI32(ptr, 0);
        return ptr;
    }
}
