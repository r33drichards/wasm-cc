package cc.wasmcc.engine.caps;

import com.dylibso.chicory.runtime.ImportValues;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The assembled host imports for one instance, plus the captured WASI stdio and
 * any closeable backing the imports (the {@link com.dylibso.chicory.wasi.WasiPreview1}
 * handle). Closing the bundle releases that backing.
 */
public final class ImportBundle implements AutoCloseable {

    private final ImportValues importValues;
    private final AutoCloseable backing; // nullable (WasiPreview1)
    private final ByteArrayOutputStream stdout;
    private final ByteArrayOutputStream stderr;

    public ImportBundle(ImportValues importValues, AutoCloseable backing,
            ByteArrayOutputStream stdout, ByteArrayOutputStream stderr) {
        this.importValues = importValues;
        this.backing = backing;
        this.stdout = stdout;
        this.stderr = stderr;
    }

    public ImportValues importValues() { return importValues; }

    public String stdout() {
        return stdout == null ? "" : stdout.toString(StandardCharsets.UTF_8);
    }

    public String stderr() {
        return stderr == null ? "" : stderr.toString(StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        if (backing != null) {
            try {
                backing.close();
            } catch (Exception ignored) {
                // WasiPreview1.close() only flushes; nothing actionable on failure.
            }
        }
    }
}
