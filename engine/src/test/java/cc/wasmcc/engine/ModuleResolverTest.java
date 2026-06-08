package cc.wasmcc.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ModuleResolverTest {

    @Test
    void resolvesNameFromDir(@TempDir Path dir) throws Exception {
        byte[] wasm = {0x00, 0x61, 0x73, 0x6d}; // "\0asm"
        Files.write(dir.resolve("foo.wasm"), wasm);
        ModuleResolver r = new ModuleResolver(dir, null, 1_000_000, false);
        assertArrayEquals(wasm, r.resolve("foo"));
        assertArrayEquals(wasm, r.resolve("foo.wasm"));
    }

    @Test
    void rejectsTraversalAndMissing(@TempDir Path dir) {
        ModuleResolver r = new ModuleResolver(dir, null, 1_000_000, false);
        assertThrows(IOException.class, () -> r.resolve("../etc/passwd"));
        assertThrows(IOException.class, () -> r.resolve("sub/foo"));
        assertThrows(IOException.class, () -> r.resolve("nope"));
    }

    @Test
    void urlDisabledByDefault(@TempDir Path dir) {
        ModuleResolver r = new ModuleResolver(dir, dir, 1_000_000, false);
        IOException e = assertThrows(IOException.class,
            () -> r.resolve("https://example.com/x.wasm"));
        assertTrue(e.getMessage().contains("disabled"), e.getMessage());
    }

    @Test
    void enforcesSizeCapOnName(@TempDir Path dir) throws Exception {
        Files.write(dir.resolve("big.wasm"), new byte[2048]);
        ModuleResolver r = new ModuleResolver(dir, null, 1024, false);
        assertThrows(IOException.class, () -> r.resolve("big"));
    }
}
