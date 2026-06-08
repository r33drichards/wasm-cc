package cc.wasmcc.engine;

import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates the raw WebAssembly API against a tiny reactor fixture
 * ({@code spike.wasm}, built by {@code make fixtures}): export calls with i32
 * args/returns, host→guest and guest→host memory marshalling, and malloc-backed
 * allocation. Fast first check before the heavier sqlite IT.
 */
class SpikeTest {

    static byte[] resource(String path) throws Exception {
        try (InputStream in = SpikeTest.class.getResourceAsStream(path)) {
            assertNotNull(in, () -> "missing test resource " + path
                + " — run `nix develop -c make fixtures`");
            return in.readAllBytes();
        }
    }

    @Test
    void rawApiExportsAndMemory() throws Exception {
        WasmHost host = new WasmHost();
        try (WasmInstance inst =
                 host.instantiate(resource("/modules/spike.wasm"), InstanceConfig.wasiOnly())) {

            // i32 args + return
            assertEquals(5, inst.callI32("add", 2, 3));

            // guest allocates + fills a ramp; host reads it back
            int ramp = inst.callI32("make_ramp", 5);
            byte[] got = inst.memory().readBytes(ramp, 5);
            assertArrayEquals(new byte[]{0, 1, 2, 3, 4}, got);

            // host allocates + writes bytes; guest sums them
            int buf = inst.memory().alloc(4);
            inst.memory().writeBytes(buf, new byte[]{1, 2, 3, 4});
            assertEquals(10, inst.callI32("sum_bytes", buf, 4));
            inst.memory().free(buf);
        }
    }
}
