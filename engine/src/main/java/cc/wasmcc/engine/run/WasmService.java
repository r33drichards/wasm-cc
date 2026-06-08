package cc.wasmcc.engine.run;

import cc.wasmcc.engine.CompiledModule;
import cc.wasmcc.engine.InstanceConfig;
import cc.wasmcc.engine.RunResult;
import cc.wasmcc.engine.WasmHost;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Off-thread mode-B runner: executes command (run-to-completion) modules on a
 * fixed worker pool with a wall-clock timeout, so a long/heavy/networked module
 * never blocks the game tick. Directly ports picat-cc's timeout-by-abandonment
 * model (Chicory cannot interrupt running WASM, so a runaway run is abandoned —
 * its daemon worker keeps burning until it finishes — and {@code timeout} is
 * surfaced immediately; a saturation guard rejects new work once too many
 * zombies pile up).
 *
 * @see cc.wasmcc.engine.WasmHost#run
 */
public final class WasmService {

    private static final long DEFAULT_TIMEOUT_MS = 60_000;

    /** Outcome of a run: {@code ok} ⇒ {@code result} is set; else {@code error}
     *  carries a taxonomy string ({@code timeout} / {@code busy: ...} /
     *  {@code trap: ...}). */
    public record Outcome(boolean ok, RunResult result, String error) {
        static Outcome ok(RunResult r) { return new Outcome(true, r, null); }
        static Outcome err(String e) { return new Outcome(false, null, e); }
    }

    private final WasmHost host;
    private final ExecutorService pool;
    private final ScheduledThreadPoolExecutor timer;
    private final int maxAbandoned;
    private final long maxTimeoutMs;
    private final int maxJobsPerComputer;
    private final AtomicInteger abandoned = new AtomicInteger(0);

    public WasmService(WasmHost host, int threads, int maxAbandoned,
            long maxTimeoutMs, int maxJobsPerComputer) {
        this.host = host;
        this.maxAbandoned = maxAbandoned;
        this.maxTimeoutMs = maxTimeoutMs;
        this.maxJobsPerComputer = maxJobsPerComputer;
        ThreadFactory workerFactory = new ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger(0);
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "wasm-worker-" + n.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        };
        this.pool = Executors.newFixedThreadPool(threads, workerFactory);
        this.timer = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "wasm-timeout");
            t.setDaemon(true);
            return t;
        });
        this.timer.setRemoveOnCancelPolicy(true);
    }

    public int maxJobsPerComputer() { return maxJobsPerComputer; }
    public int abandonedCount() { return abandoned.get(); }

    public void shutdown() {
        pool.shutdownNow();
        timer.shutdownNow();
    }

    /** Clamp a requested timeout (seconds) into the configured ceiling. */
    public long resolveTimeoutMs(double requestedSeconds) {
        long def = Math.min(DEFAULT_TIMEOUT_MS, maxTimeoutMs);
        if (requestedSeconds <= 0) return def;
        return Math.min((long) (requestedSeconds * 1000.0), maxTimeoutMs);
    }

    /**
     * Submit a command module run. The returned future may complete on a worker
     * thread (normal path) or the timeout scheduler thread (timeout path); keep
     * {@code whenComplete} bodies cheap.
     */
    public CompletableFuture<Outcome> run(CompiledModule module, InstanceConfig cfg,
            long timeoutMs) {
        if (abandoned.get() >= maxAbandoned) {
            return CompletableFuture.completedFuture(
                Outcome.err("busy: wasm runner saturated by timed-out jobs"));
        }

        CompletableFuture<Outcome> publicFuture = new CompletableFuture<>();
        AtomicBoolean settled = new AtomicBoolean(false);
        final ScheduledFuture<?>[] timeoutHandle = new ScheduledFuture<?>[1];

        CompletableFuture<Outcome> worker = CompletableFuture.supplyAsync(() -> {
            try {
                return Outcome.ok(host.run(module, cfg));
            } catch (RuntimeException e) {
                return Outcome.err(mapRuntime(e));
            }
        }, pool);

        worker.whenComplete((res, ex) -> {
            if (settled.compareAndSet(false, true)) {
                ScheduledFuture<?> h = timeoutHandle[0];
                if (h != null) h.cancel(false);
                if (ex != null) {
                    publicFuture.complete(Outcome.err(mapRuntime(ex)));
                } else {
                    publicFuture.complete(res);
                }
            } else {
                // Timeout already fired and counted this; the zombie finished now.
                abandoned.decrementAndGet();
            }
        });

        timeoutHandle[0] = timer.schedule(() -> {
            if (settled.compareAndSet(false, true)) {
                abandoned.incrementAndGet();
                publicFuture.complete(Outcome.err("timeout"));
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);

        return publicFuture;
    }

    private static String mapRuntime(Throwable e) {
        String m = e.getMessage();
        String lower = m == null ? "" : m.toLowerCase();
        if (lower.contains("memory") || lower.contains("oom")
                || lower.contains("out of memory")) {
            return "memory limit";
        }
        return "trap: " + (m == null ? e.getClass().getSimpleName() : m);
    }
}
