// Tiny wasm32-wasi *reactor* fixture for validating the raw WebAssembly API
// (export calls + linear-memory marshalling + malloc/free), independent of any
// large real module. Built by `make fixtures` into the engine test resources.
//
// Reactor model: no main(); wasi-sdk emits `_initialize` for ctors. We export
// `add`, `sum_bytes`, and `store` so a test can exercise i32 args, reading guest
// memory we hand it, and writing into a guest buffer it later reads back.
#include <stdint.h>
#include <stdlib.h>

// a + b — exercises i32 args and an i32 return.
int add(int a, int b) { return a + b; }

// Sum `len` bytes starting at guest pointer `ptr` — exercises the host writing
// bytes into guest memory and the guest reading them.
int sum_bytes(const uint8_t *ptr, int len) {
    int s = 0;
    for (int i = 0; i < len; i++) s += ptr[i];
    return s;
}

// malloc `n`, fill with 0,1,2,... and return the pointer — exercises the host
// calling the guest allocator and reading back what the guest wrote.
uint8_t *make_ramp(int n) {
    uint8_t *p = (uint8_t *)malloc(n);
    for (int i = 0; i < n; i++) p[i] = (uint8_t)i;
    return p;
}
