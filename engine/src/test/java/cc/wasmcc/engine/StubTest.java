package cc.wasmcc.engine;

import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Auto-stubbing: a module that imports unprovided {@code env.*} functions (the
 * Emscripten {@code STANDALONE_WASM} pattern) still instantiates, with the
 * engine supplying zero-returning no-op stubs of the right signature.
 */
class StubTest {

    static byte[] resource(String path) throws Exception {
        try (InputStream in = StubTest.class.getResourceAsStream(path)) {
            assertNotNull(in, () -> "missing test resource " + path
                + " — run `nix develop -c make fixtures`");
            return in.readAllBytes();
        }
    }

    @Test
    void unprovidedImportsAreStubbed() throws Exception {
        WasmHost host = new WasmHost();
        try (WasmInstance inst =
                 host.instantiate(resource("/modules/stub.wasm"), InstanceConfig.wasiOnly())) {
            // env.custom_noop (void) stubbed as no-op -> trigger() returns 7
            assertEquals(7, inst.callI32("trigger"));
            // env.answer (i32) stubbed returning 0 -> call_answer() returns 1
            assertEquals(1, inst.callI32("call_answer"));
        }
    }
}
