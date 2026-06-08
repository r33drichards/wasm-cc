// Reactor fixture validating auto-stubbing of unprovided imports: it imports two
// functions from a module ("env") that no capability pack serves, the way an
// Emscripten STANDALONE_WASM build imports env.emscripten_notify_memory_growth.
// The engine must supply zero-returning no-op stubs so the module still links.
#include <stdint.h>

__attribute__((import_module("env"), import_name("custom_noop")))
extern void custom_noop(void);

__attribute__((import_module("env"), import_name("answer")))
extern int answer(void);

// Calls the void import (stub = no-op) and returns a constant.
int trigger(void) {
    custom_noop();
    return 7;
}

// Calls the i32-returning import (stub returns 0) and adds 1 -> expect 1.
int call_answer(void) {
    return answer() + 1;
}
