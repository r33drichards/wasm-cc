package cc.wasmcc.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Opt-in integration test: an anonymous pull of a real, small, public wasm
 * artifact from {@code ghcr.io}. Skipped unless {@code WASMCC_OCI_IT=1} so the
 * default {@code :engine:test} run stays offline (mirrors the ffmpeg IT pattern).
 *
 * <p>The artifact ref is overridable via {@code WASMCC_OCI_IT_REF}; it must be a
 * public OCI ref whose manifest carries a single {@code application/wasm} (or
 * {@code *+wasm}) layer.
 */
@EnabledIfEnvironmentVariable(named = "WASMCC_OCI_IT", matches = "1")
class OciPullIT {

    @Test
    void pullsRealWasmFromGhcr(@TempDir Path cache) throws Exception {
        String ref = System.getenv().getOrDefault("WASMCC_OCI_IT_REF",
            "ghcr.io/r33drichards/sqlite:0.1.1");
        OciResolver.Ref parsed = OciResolver.tryParse(ref);
        assertNotNull(parsed, () -> "WASMCC_OCI_IT_REF did not parse as an OCI ref: " + ref);

        OciResolver r = new OciResolver(cache, 64L * 1024 * 1024, true, List.of("ghcr.io"));
        byte[] bytes = r.resolve(parsed);

        assertTrue(bytes.length > 4, "expected non-trivial wasm bytes");
        // wasm magic: 0x00 0x61 0x73 0x6d ("\0asm")
        assertEquals(0x00, bytes[0] & 0xFF);
        assertEquals(0x61, bytes[1] & 0xFF);
        assertEquals(0x73, bytes[2] & 0xFF);
        assertEquals(0x6d, bytes[3] & 0xFF);
    }
}
