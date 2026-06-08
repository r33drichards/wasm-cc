package cc.wasmcc.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code fs} capability: a WASI command module reads and writes files on a
 * host directory mounted at the guest root — the bridge test for "access a file
 * on a CC computer" without the game (the temp dir stands in for the computer's
 * disk). Exercises mode-B {@link WasmHost#run} (command, run-to-completion).
 */
class FsCapTest {

    static byte[] resource(String path) throws Exception {
        try (InputStream in = FsCapTest.class.getResourceAsStream(path)) {
            assertNotNull(in, () -> "missing test resource " + path
                + " — run `nix develop -c make fixtures`");
            return in.readAllBytes();
        }
    }

    @Test
    void copiesFileOnMountedDisk(@TempDir Path disk) throws Exception {
        Files.writeString(disk.resolve("in.txt"), "hello wasm fs");

        WasmHost host = new WasmHost();
        InstanceConfig cfg = InstanceConfig.builder()
            .fsDir(disk)                                   // mount disk at "/"
            .args(List.of("copy", "/in.txt", "/out.txt"))  // argv
            .build();

        RunResult r = host.run(resource("/modules/copy.wasm"), cfg);

        assertEquals(0, r.exit(), () -> "exit nonzero; stderr: " + r.stderr());
        assertEquals("hello wasm fs", Files.readString(disk.resolve("out.txt")));
        assertTrue(r.stdout().contains("copied"), () -> "stdout: " + r.stdout());
    }
}
