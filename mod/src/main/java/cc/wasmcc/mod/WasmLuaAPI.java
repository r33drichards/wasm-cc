package cc.wasmcc.mod;

import cc.wasmcc.engine.CompiledModule;
import cc.wasmcc.engine.InstanceConfig;
import cc.wasmcc.engine.MemoryView;
import cc.wasmcc.engine.WasmInstance;
import cc.wasmcc.engine.run.WasmService;
import dan200.computercraft.api.component.ComputerComponents;
import dan200.computercraft.api.lua.IArguments;
import dan200.computercraft.api.lua.IComputerSystem;
import dan200.computercraft.api.lua.ILuaAPI;
import dan200.computercraft.api.lua.ILuaCallback;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.lua.MethodResult;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * The {@code wasm} Lua global, one per CC computer. Exposes the raw WebAssembly
 * API: instantiate a module and drive its exports + linear memory directly (the
 * Lua analogue of the mcp-v8 JS example), plus an off-thread {@code run} for
 * heavy/networked command modules.
 *
 * <h2>Two execution modes</h2>
 * <ul>
 *   <li><b>Mode A (raw API):</b> {@link #instantiate} compiles + instantiates
 *       synchronously and returns an integer <em>handle</em>; {@code call}/memory
 *       ops run on the computer thread. Fast and chatty (sqlite, parsing). A slow
 *       call blocks the computer — use mode B for heavy/networked work.</li>
 *   <li><b>Mode B (command):</b> {@link #run} submits to the off-thread engine and
 *       yields the Lua coroutine until the module exits, returning
 *       {@code {exit, stdout, stderr}} (files land on the computer's disk via the
 *       fs cap). Ported from picat-cc's yield/token plumbing.</li>
 * </ul>
 *
 * <p>Instances are tracked by integer handle in a per-computer map (CC marshals
 * only plain values across the Lua boundary, so a handle is simpler and safer than
 * returning a Java object).
 */
public final class WasmLuaAPI implements ILuaAPI {
    private static final Logger LOG = LoggerFactory.getLogger("wasm-cc");
    private static final String VERSION = "0.1.0";

    private final IComputerSystem computer;
    private final Supplier<WasmEngine> engineSupplier;

    /** Live mode-A instances by handle. Accessed only on the computer thread. */
    private final Map<Integer, WasmInstance> instances = new ConcurrentHashMap<>();
    private final AtomicInteger nextHandle = new AtomicInteger(0);

    /** Mode-B in-flight cap + token (as picat-cc). */
    private final AtomicInteger inFlight = new AtomicInteger(0);
    private final AtomicLong nextToken = new AtomicLong(0);

    public WasmLuaAPI(IComputerSystem computer, Supplier<WasmEngine> engineSupplier) {
        this.computer = computer;
        this.engineSupplier = engineSupplier;
    }

    @Override
    public String[] getNames() {
        return new String[]{"wasm"};
    }

    @Override
    public void shutdown() {
        // Computer unloaded/rebooted: free any live instances.
        instances.values().forEach(WasmInstance::close);
        instances.clear();
    }

    // --- common ------------------------------------------------------------

    @LuaFunction
    public final String version() {
        return VERSION;
    }

    private WasmEngine engine() throws LuaException {
        WasmEngine e = engineSupplier.get();
        if (e == null) throw new LuaException("internal: wasm engine not available");
        return e;
    }

    // --- mode A: raw API ---------------------------------------------------

    /**
     * {@code wasm.instantiate(ref [, opts])} → handle. {@code ref} is a module
     * name (in the modules dir) or, if enabled, an {@code http(s)://} URL.
     * {@code opts}: {@code caps} (array of "wasi"/"fs"/"http"), {@code fs} (sub-dir
     * mounted at "/"), {@code args} (argv array), {@code env} (string→string).
     */
    @LuaFunction
    public final int instantiate(IArguments args) throws LuaException {
        WasmEngine engine = engine();
        String ref = args.getString(0);
        Map<String, Object> opts = decodeOpts(args, 1);

        if (instances.size() >= engine.maxInstancesPerComputer()) {
            throw new LuaException("busy: too many open wasm instances on this computer");
        }
        byte[] bytes;
        try {
            bytes = engine.resolver().resolve(ref);
        } catch (Exception e) {
            throw new LuaException("module: " + e.getMessage());
        }
        InstanceConfig cfg = buildConfig(engine, opts);
        WasmInstance inst;
        try {
            CompiledModule mod = engine.host().compile(bytes);
            inst = engine.host().instantiate(mod, cfg);
        } catch (RuntimeException e) {
            throw new LuaException("instantiate: " + msg(e));
        }
        int handle = nextHandle.incrementAndGet();
        instances.put(handle, inst);
        return handle;
    }

    /** {@code wasm.call(handle, name [, args...])} → first result as an integer. */
    @LuaFunction
    public final long call(IArguments args) throws LuaException {
        WasmInstance inst = lookup(args, 0);
        String name = args.getString(1);
        long[] wargs = numericArgs(args, 2);
        try {
            return inst.callI64(name, wargs);
        } catch (RuntimeException e) {
            throw new LuaException("call " + name + ": " + msg(e));
        }
    }

    /** {@code wasm.callf(handle, name [, args...])} → first result as a double. */
    @LuaFunction
    public final double callf(IArguments args) throws LuaException {
        WasmInstance inst = lookup(args, 0);
        String name = args.getString(1);
        long[] wargs = numericArgs(args, 2);
        try {
            return inst.callF64(name, wargs);
        } catch (RuntimeException e) {
            throw new LuaException("callf " + name + ": " + msg(e));
        }
    }

    // --- mode A: memory ops ------------------------------------------------

    @LuaFunction
    public final int readI32(IArguments a) throws LuaException {
        return mem(a).readI32(addr(a, 1));
    }

    @LuaFunction
    public final long readU32(IArguments a) throws LuaException {
        return mem(a).readU32(addr(a, 1));
    }

    @LuaFunction
    public final void writeI32(IArguments a) throws LuaException {
        mem(a).writeI32(addr(a, 1), (int) a.getLong(2));
    }

    /** {@code wasm.readBytes(handle, ptr, len)} → binary string. */
    @LuaFunction
    public final java.nio.ByteBuffer readBytes(IArguments a) throws LuaException {
        return java.nio.ByteBuffer.wrap(mem(a).readBytes(addr(a, 1), (int) a.getLong(2)));
    }

    /** {@code wasm.writeBytes(handle, ptr, data)} where data is a (binary) string. */
    @LuaFunction
    public final void writeBytes(IArguments a) throws LuaException {
        java.nio.ByteBuffer bb = a.getBytes(2);
        byte[] data = new byte[bb.remaining()];
        bb.get(data);
        mem(a).writeBytes(addr(a, 1), data);
    }

    /** {@code wasm.readString(handle, ptr, len)} → UTF-8 string. */
    @LuaFunction
    public final String readString(IArguments a) throws LuaException {
        return mem(a).readString(addr(a, 1), (int) a.getLong(2));
    }

    /** {@code wasm.readCString(handle, ptr)} → NUL-terminated UTF-8 string. */
    @LuaFunction
    public final String readCString(IArguments a) throws LuaException {
        return mem(a).readCString(addr(a, 1));
    }

    @LuaFunction
    public final void writeString(IArguments a) throws LuaException {
        mem(a).writeString(addr(a, 1), a.getString(2));
    }

    /** {@code wasm.alloc(handle, n)} → pointer (requires exported malloc). */
    @LuaFunction
    public final int alloc(IArguments a) throws LuaException {
        try {
            return mem(a).alloc((int) a.getLong(1));
        } catch (RuntimeException e) {
            throw new LuaException("alloc: " + msg(e));
        }
    }

    /** {@code wasm.allocCString(handle, s)} → pointer to a NUL-terminated copy. */
    @LuaFunction
    public final int allocCString(IArguments a) throws LuaException {
        try {
            return mem(a).allocCString(a.getString(1));
        } catch (RuntimeException e) {
            throw new LuaException("allocCString: " + msg(e));
        }
    }

    /** {@code wasm.allocPtr(handle)} → 4-byte zeroed slot (for out-params). */
    @LuaFunction
    public final int allocPtr(IArguments a) throws LuaException {
        try {
            return mem(a).allocPtr();
        } catch (RuntimeException e) {
            throw new LuaException("allocPtr: " + msg(e));
        }
    }

    @LuaFunction
    public final void free(IArguments a) throws LuaException {
        mem(a).free((int) a.getLong(1));
    }

    /** {@code wasm.close(handle)} — free a mode-A instance. */
    @LuaFunction
    public final void close(IArguments a) throws LuaException {
        Integer h = (int) a.getLong(0);
        WasmInstance inst = instances.remove(h);
        if (inst != null) inst.close();
    }

    // --- mode B: off-thread command run ------------------------------------

    /**
     * {@code wasm.run(ref [, opts])} → {@code ok, {exit,stdout,stderr}|err}. Runs a
     * command (run-to-completion) module off-thread, yielding until it exits.
     * {@code opts}: {@code caps}, {@code fs}, {@code args}, {@code env},
     * {@code timeout} (seconds).
     */
    @LuaFunction
    public final MethodResult run(IArguments args) throws LuaException {
        WasmEngine engine = engine();
        String ref = args.getString(0);
        Map<String, Object> opts = decodeOpts(args, 1);

        byte[] bytes;
        try {
            bytes = engine.resolver().resolve(ref);
        } catch (Exception e) {
            return MethodResult.of(false, "module: " + e.getMessage());
        }
        InstanceConfig cfg = buildConfig(engine, opts);
        CompiledModule mod;
        try {
            mod = engine.host().compile(bytes);
        } catch (RuntimeException e) {
            return MethodResult.of(false, "compile: " + msg(e));
        }
        WasmService svc = engine.service();
        if (inFlight.incrementAndGet() > svc.maxJobsPerComputer()) {
            inFlight.decrementAndGet();
            return MethodResult.of(false, "busy: too many wasm runs on this computer");
        }
        long timeoutMs = svc.resolveTimeoutMs(numberOpt(opts, "timeout"));
        return dispatch(svc.run(mod, cfg, timeoutMs));
    }

    private MethodResult dispatch(java.util.concurrent.CompletableFuture<WasmService.Outcome> future) {
        long token = nextToken.incrementAndGet();
        future.whenComplete((res, ex) -> {
            inFlight.decrementAndGet();
            Object[] event;
            if (ex != null) {
                LOG.warn("wasm run failed on computer {}", safeId(), ex);
                event = new Object[]{token, false, "internal: " + msg(ex)};
            } else if (res.ok()) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("exit", res.result().exit());
                r.put("stdout", res.result().stdout());
                r.put("stderr", res.result().stderr());
                event = new Object[]{token, true, r};
            } else {
                event = new Object[]{token, false, res.error()};
            }
            try {
                computer.queueEvent("wasm_done", event);
            } catch (RuntimeException qe) {
                LOG.debug("queueEvent wasm_done dropped for computer {}", safeId(), qe);
            }
        });
        return MethodResult.pullEvent("wasm_done", new TokenCallback(token));
    }

    private final class TokenCallback implements ILuaCallback {
        private final long token;
        TokenCallback(long token) { this.token = token; }
        @Override
        public MethodResult resume(Object[] event) {
            if (event.length >= 4 && event[1] instanceof Number n && n.longValue() == token) {
                return MethodResult.of(event[2], event[3]);
            }
            return MethodResult.pullEvent("wasm_done", this);
        }
    }

    // --- helpers -----------------------------------------------------------

    private WasmInstance lookup(IArguments a, int handleIdx) throws LuaException {
        int h = (int) a.getLong(handleIdx);
        WasmInstance inst = instances.get(h);
        if (inst == null) throw new LuaException("invalid wasm handle: " + h);
        return inst;
    }

    private MemoryView mem(IArguments a) throws LuaException {
        MemoryView m = lookup(a, 0).memory();
        if (m == null) throw new LuaException("module exports no memory");
        return m;
    }

    private static int addr(IArguments a, int idx) throws LuaException {
        return (int) a.getLong(idx);
    }

    /** Collect wasm call args (longs) from {@code index} onward. */
    private static long[] numericArgs(IArguments a, int index) throws LuaException {
        int n = Math.max(0, a.count() - index);
        long[] out = new long[n];
        for (int i = 0; i < n; i++) {
            out[i] = a.getLong(index + i);
        }
        return out;
    }

    private InstanceConfig buildConfig(WasmEngine engine, Map<String, Object> opts)
            throws LuaException {
        InstanceConfig.Builder b = InstanceConfig.builder();
        @SuppressWarnings("unchecked")
        List<String> caps = (List<String>) opts.getOrDefault("caps", engine.defaultCaps());
        b.caps(caps);
        @SuppressWarnings("unchecked")
        List<String> argv = (List<String>) opts.get("args");
        if (argv != null) b.args(argv);
        @SuppressWarnings("unchecked")
        Map<String, String> env = (Map<String, String>) opts.get("env");
        if (env != null) env.forEach(b::env);
        if (caps.contains("http")) {
            for (String pat : engine.httpAllow()) b.httpAllow(pat);
        }
        Object fs = opts.get("fs");
        if (fs instanceof String sub) {
            b.fsDir(resolveFs(sub));
        }
        return b.build();
    }

    // --- option decoding (caps/args/env/fs/timeout) ------------------------

    private static Map<String, Object> decodeOpts(IArguments args, int index) throws LuaException {
        Map<String, Object> opts = new LinkedHashMap<>();
        if (index >= args.count() || args.get(index) == null) return opts;
        Map<?, ?> t = args.getTable(index);
        List<String> caps = stringArray(t.get("caps"), "caps");
        if (caps != null) opts.put("caps", caps);
        List<String> argv = stringArray(t.get("args"), "args");
        if (argv != null) opts.put("args", argv);
        Object env = t.get("env");
        if (env instanceof Map<?, ?> em) {
            Map<String, String> m = new LinkedHashMap<>();
            em.forEach((k, v) -> m.put(String.valueOf(k), String.valueOf(v)));
            opts.put("env", m);
        } else if (env != null) {
            throw new LuaException("bad argument: opts.env must be a table");
        }
        Object fs = t.get("fs");
        if (fs instanceof String s) opts.put("fs", s);
        else if (fs != null) throw new LuaException("bad argument: opts.fs must be a string");
        Object timeout = t.get("timeout");
        if (timeout instanceof Number nn) opts.put("timeout", nn);
        else if (timeout != null) throw new LuaException("bad argument: opts.timeout must be a number");
        return opts;
    }

    /** Decode a Lua array of strings (CC delivers arrays as Map with 1..n keys). */
    private static List<String> stringArray(Object v, String what) throws LuaException {
        if (v == null) return null;
        if (!(v instanceof Map<?, ?> table)) {
            throw new LuaException("bad argument: opts." + what + " must be an array");
        }
        SortedMap<Double, String> ordered = new TreeMap<>();
        for (Map.Entry<?, ?> e : table.entrySet()) {
            if (!(e.getKey() instanceof Number k) || !(e.getValue() instanceof String s)) {
                throw new LuaException("bad argument: opts." + what + " must be an array of strings");
            }
            ordered.put(k.doubleValue(), s);
        }
        return new ArrayList<>(ordered.values());
    }

    private static double numberOpt(Map<String, Object> opts, String key) {
        Object v = opts.get(key);
        return v instanceof Number n ? n.doubleValue() : 0.0;
    }

    // --- fs path resolution (ported from picat-cc) -------------------------

    /** Resolve {@code sub} to a directory under this computer's own save dir,
     *  rejecting traversal/absolute paths; creates it. */
    private Path resolveFs(String sub) throws LuaException {
        if (sub.isBlank()) throw new LuaException("fs: invalid path");
        Path root = computerRoot();
        if (root == null) throw new LuaException("fs: storage unavailable");
        Path resolved;
        try {
            Path rel = Path.of(sub);
            if (rel.isAbsolute()) throw new LuaException("fs: invalid path");
            for (Path part : rel) {
                if ("..".equals(part.toString())) throw new LuaException("fs: invalid path");
            }
            resolved = root.resolve(rel).normalize();
        } catch (java.nio.file.InvalidPathException bad) {
            throw new LuaException("fs: invalid path");
        }
        if (!resolved.startsWith(root.normalize())) throw new LuaException("fs: invalid path");
        try {
            Files.createDirectories(resolved);
        } catch (Exception e) {
            throw new LuaException("fs: cannot create directory");
        }
        return resolved;
    }

    /** {@code <worldPath(ROOT)>/computercraft/computer/<id>} (see picat-cc for the
     *  getSaveFolder coupling; command computers are rejected). */
    private Path computerRoot() throws LuaException {
        if (computer.getComponent(ComputerComponents.ADMIN_COMPUTER) != null) {
            throw new LuaException("fs: not supported on command computers");
        }
        try {
            Path world = computer.getLevel().getServer().getWorldPath(LevelResource.ROOT);
            return world.resolve("computercraft").resolve("computer")
                .resolve(Integer.toString(computer.getID()));
        } catch (RuntimeException e) {
            LOG.warn("Could not resolve computer storage root", e);
            return null;
        }
    }

    private static String msg(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private int safeId() {
        try {
            return computer.getID();
        } catch (RuntimeException e) {
            return -1;
        }
    }
}
