package cc.wasmcc.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModuleResolverTest {

    private static ModuleResolver resolver(Path modulesDir, Path cacheDir, long maxBytes,
            boolean allowUrl) {
        return new ModuleResolver(modulesDir, cacheDir, maxBytes, allowUrl, false, List.of());
    }

    @Test
    void resolvesFileRef(@TempDir Path dir) throws Exception {
        byte[] wasm = {0x00, 0x61, 0x73, 0x6d}; // "\0asm"
        Files.write(dir.resolve("foo.wasm"), wasm);
        ModuleResolver r = resolver(dir, null, 1_000_000, false);
        assertArrayEquals(wasm, r.resolve("file://foo"));
        assertArrayEquals(wasm, r.resolve("file://foo.wasm"));
    }

    @Test
    void bareNameNoLongerResolvesLocally(@TempDir Path dir) throws Exception {
        // The old bare-name contract is gone: "foo" is neither a valid OCI ref nor
        // file://, so it errors and points the user at file://.
        Files.write(dir.resolve("foo.wasm"), new byte[]{0});
        ModuleResolver r = resolver(dir, null, 1_000_000, false);
        IOException e = assertThrows(IOException.class, () -> r.resolve("foo"));
        assertTrue(e.getMessage().contains("file://"), e.getMessage());
    }

    @Test
    void rejectsTraversalAndMissing(@TempDir Path dir) {
        ModuleResolver r = resolver(dir, null, 1_000_000, false);
        assertThrows(IOException.class, () -> r.resolve("file://../etc/passwd"));
        assertThrows(IOException.class, () -> r.resolve("file://sub/foo"));
        assertThrows(IOException.class, () -> r.resolve("file://nope"));
    }

    @Test
    void urlDisabledByDefault(@TempDir Path dir) {
        ModuleResolver r = resolver(dir, dir, 1_000_000, false);
        IOException e = assertThrows(IOException.class,
            () -> r.resolve("https://example.com/x.wasm"));
        assertTrue(e.getMessage().contains("disabled"), e.getMessage());
    }

    @Test
    void enforcesSizeCapOnFile(@TempDir Path dir) throws Exception {
        Files.write(dir.resolve("big.wasm"), new byte[2048]);
        ModuleResolver r = resolver(dir, null, 1024, false);
        assertThrows(IOException.class, () -> r.resolve("file://big"));
    }

    @Test
    void noSchemeOciRefDispatchesToOci(@TempDir Path dir) {
        // OCI disabled by default → the dispatch reaches OciResolver, which reports
        // the disabled state (proving it was treated as an OCI ref, not a file).
        ModuleResolver r = resolver(dir, dir, 1_000_000, false);
        IOException e = assertThrows(IOException.class,
            () -> r.resolve("ghcr.io/owner/repo:1.0"));
        assertTrue(e.getMessage().toLowerCase().contains("oci"), e.getMessage());
    }

    @Test
    void explicitOciSchemeDispatchesToOci(@TempDir Path dir) {
        ModuleResolver r = resolver(dir, dir, 1_000_000, false);
        IOException e = assertThrows(IOException.class,
            () -> r.resolve("oci://ghcr.io/owner/repo:1.0"));
        assertTrue(e.getMessage().toLowerCase().contains("oci"), e.getMessage());
    }

    @Test
    void invalidOciSchemeIsAHardError(@TempDir Path dir) {
        ModuleResolver r = resolver(dir, dir, 1_000_000, false);
        // oci:// with a bare name (no registry/repo) is a hard parse error.
        IOException e = assertThrows(IOException.class, () -> r.resolve("oci://sqlite3"));
        assertTrue(e.getMessage().contains("invalid oci ref"), e.getMessage());
    }
}
