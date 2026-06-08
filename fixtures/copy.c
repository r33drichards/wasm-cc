// wasm32-wasi *command* fixture for the fs capability: copies argv[1] -> argv[2]
// using ordinary stdio, which wasi-libc maps onto WASI fd ops against the
// preopened directory the host mounts at "/" (the CC computer's disk in prod).
#include <stdio.h>

int main(int argc, char **argv) {
    if (argc < 3) {
        fprintf(stderr, "usage: copy IN OUT\n");
        return 2;
    }
    FILE *in = fopen(argv[1], "rb");
    if (!in) { perror("open in"); return 3; }
    FILE *out = fopen(argv[2], "wb");
    if (!out) { perror("open out"); fclose(in); return 4; }
    char buf[4096];
    size_t n;
    while ((n = fread(buf, 1, sizeof buf, in)) > 0) {
        if (fwrite(buf, 1, n, out) != n) { perror("write"); return 5; }
    }
    fclose(in);
    fclose(out);
    printf("copied %s -> %s\n", argv[1], argv[2]);
    return 0;
}
