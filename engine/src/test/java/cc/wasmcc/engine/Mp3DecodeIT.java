package cc.wasmcc.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The "exercise ffmpeg" integration test, via the approved plan's sanctioned
 * fallback: a real MP3 decoder ({@code dr_mp3}, built to wasm32-wasi) decodes a
 * synthesized {@code sample.mp3} to a WAV through the engine's mode-B runner + fs
 * cap — proving the heavy/file-based compute path end-to-end. (A full WASI ffmpeg
 * build is deferred; the codec swap keeps the intent: decode an mp3 through a real
 * codec.)
 */
class Mp3DecodeIT {

    static byte[] resource(String path) throws Exception {
        try (InputStream in = Mp3DecodeIT.class.getResourceAsStream(path)) {
            assertNotNull(in, () -> "missing test resource " + path
                + " — run `nix develop -c make mp3`");
            return in.readAllBytes();
        }
    }

    @Test
    void decodesMp3ToWav(@TempDir Path disk) throws Exception {
        // Stage the sample mp3 on the mounted "disk" (a CC computer's disk in prod).
        Files.write(disk.resolve("sample.mp3"), resource("/modules/sample.mp3"));

        WasmHost host = new WasmHost();
        InstanceConfig cfg = InstanceConfig.builder()
            .fsDir(disk)
            .args(List.of("mp3dec", "/sample.mp3", "/out.wav"))
            .build();

        RunResult r = host.run(resource("/modules/mp3dec.wasm"), cfg);
        assertEquals(0, r.exit(), () -> "decode failed; stderr: " + r.stderr());

        byte[] wav = Files.readAllBytes(disk.resolve("out.wav"));
        assertTrue(wav.length > 44, "WAV smaller than its header");
        assertEquals("RIFF", new String(wav, 0, 4, StandardCharsets.US_ASCII));
        assertEquals("WAVE", new String(wav, 8, 4, StandardCharsets.US_ASCII));
        // 16 kHz mono * 0.30 s ≈ 4800 samples * 2 bytes ≈ a few KB of PCM data.
        assertTrue(wav.length > 2000, () -> "decoded PCM unexpectedly small: " + wav.length);
    }
}
