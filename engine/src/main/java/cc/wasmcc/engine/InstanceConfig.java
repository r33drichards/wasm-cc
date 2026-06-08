package cc.wasmcc.engine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a guest instance is allowed to see: which Java capability packs back its
 * imports, plus the WASI surface (args/env/stdin and an optional host directory
 * mounted at {@code /} for the {@code fs} cap).
 *
 * <p>Capabilities are opt-in by name — {@code "wasi"} (stdio/clock/proc_exit/env
 * stubs + fd ops), {@code "fs"} (WASI fs backed by {@link #fsDir}, i.e. the CC
 * computer's real disk in production), {@code "http"} (an allowlisted net import).
 * Unknown imports a module declares are stubbed with a zero-returning no-op, so a
 * module that only needs a subset still instantiates (mirrors the resilient import
 * loop in the mcp-v8 example).
 */
public final class InstanceConfig {

    private final Set<String> caps;
    private final Path fsDir;          // nullable; mounted at "/" when fs cap on
    private final List<String> args;   // argv (argv[0] is the module name by convention)
    private final Map<String, String> env;
    private final List<String> httpAllow; // host[:port] globs for the http cap
    private final boolean autoStart;   // run _start on build (mode B command modules)

    private InstanceConfig(Builder b) {
        this.caps = Set.copyOf(b.caps);
        this.fsDir = b.fsDir;
        this.args = List.copyOf(b.args);
        this.env = Map.copyOf(b.env);
        this.httpAllow = List.copyOf(b.httpAllow);
        this.autoStart = b.autoStart;
    }

    public Set<String> caps() { return caps; }
    public boolean has(String cap) { return caps.contains(cap); }
    public Path fsDir() { return fsDir; }
    public List<String> args() { return args; }
    public Map<String, String> env() { return env; }
    public List<String> httpAllow() { return httpAllow; }
    public boolean autoStart() { return autoStart; }

    public static Builder builder() { return new Builder(); }

    /** A reactor instance with only WASI stubs — enough for in-memory modules
     *  like the sqlite example. */
    public static InstanceConfig wasiOnly() {
        return builder().cap("wasi").build();
    }

    public static final class Builder {
        private final Set<String> caps = new LinkedHashSet<>();
        private Path fsDir;
        private final List<String> args = new ArrayList<>();
        private final Map<String, String> env = new LinkedHashMap<>();
        private final List<String> httpAllow = new ArrayList<>();
        private boolean autoStart = false;

        public Builder cap(String c) { caps.add(c); return this; }
        public Builder caps(Iterable<String> cs) { cs.forEach(caps::add); return this; }
        public Builder fsDir(Path d) { fsDir = d; if (d != null) caps.add("fs"); return this; }
        public Builder arg(String a) { args.add(a); return this; }
        public Builder args(List<String> as) { args.addAll(as); return this; }
        public Builder env(String k, String v) { env.put(k, v); return this; }
        public Builder httpAllow(String pattern) { httpAllow.add(pattern); caps.add("http"); return this; }
        public Builder autoStart(boolean s) { autoStart = s; return this; }

        public InstanceConfig build() { return new InstanceConfig(this); }
    }
}
