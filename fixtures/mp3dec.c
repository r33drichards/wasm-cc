// wasm32-wasi *command* fixture: decode an MP3 to a 16-bit PCM WAV using the
// public-domain dr_mp3 single-header codec. This is the approved plan's sanctioned
// fallback for "exercise ffmpeg" — a real MP3 decoder run end-to-end through the
// engine's mode-B runner + fs cap (a full WASI ffmpeg build is deferred). Usage:
//   mp3dec IN.mp3 OUT.wav
#define DR_MP3_IMPLEMENTATION
#include "dr_mp3.h"

#include <stdint.h>
#include <stdio.h>

static void w32(FILE *f, uint32_t v) {
    fputc(v & 0xff, f); fputc((v >> 8) & 0xff, f);
    fputc((v >> 16) & 0xff, f); fputc((v >> 24) & 0xff, f);
}
static void w16(FILE *f, uint16_t v) {
    fputc(v & 0xff, f); fputc((v >> 8) & 0xff, f);
}

int main(int argc, char **argv) {
    if (argc < 3) {
        fprintf(stderr, "usage: mp3dec IN.mp3 OUT.wav\n");
        return 2;
    }
    drmp3_config cfg;
    drmp3_uint64 frames;
    float *pcm = drmp3_open_file_and_read_pcm_frames_f32(argv[1], &cfg, &frames, NULL);
    if (!pcm) {
        fprintf(stderr, "mp3 decode failed: %s\n", argv[1]);
        return 3;
    }
    uint32_t ch = cfg.channels, sr = cfg.sampleRate;
    uint64_t nsamp = frames * ch;
    uint32_t dataBytes = (uint32_t)(nsamp * 2);

    FILE *out = fopen(argv[2], "wb");
    if (!out) {
        fprintf(stderr, "open out failed: %s\n", argv[2]);
        drmp3_free(pcm, NULL);
        return 4;
    }
    // Canonical 44-byte RIFF/WAVE header, 16-bit PCM.
    fwrite("RIFF", 1, 4, out); w32(out, 36 + dataBytes); fwrite("WAVE", 1, 4, out);
    fwrite("fmt ", 1, 4, out); w32(out, 16); w16(out, 1); w16(out, (uint16_t)ch);
    w32(out, sr); w32(out, sr * ch * 2); w16(out, (uint16_t)(ch * 2)); w16(out, 16);
    fwrite("data", 1, 4, out); w32(out, dataBytes);
    for (uint64_t i = 0; i < nsamp; i++) {
        float s = pcm[i];
        if (s > 1.0f) s = 1.0f;
        if (s < -1.0f) s = -1.0f;
        w16(out, (uint16_t)(int16_t)(s * 32767.0f));
    }
    fclose(out);
    drmp3_free(pcm, NULL);
    printf("decoded %llu frames @ %u Hz x%u -> %s\n",
           (unsigned long long)frames, sr, ch, argv[2]);
    return 0;
}
