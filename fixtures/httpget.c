// Reactor fixture for the http capability. Links against the engine's http import
// (a guest module's networking "comes from" the host, gated by the allowlist) and
// exposes do_get so a test can drive it: fetch a URL, copy the body into a buffer,
// return the byte count (or a negative code: -1 denied, -2 error).
#include <stdint.h>

__attribute__((import_module("http"), import_name("get")))
extern int http_get(const char *url, int url_len);

__attribute__((import_module("http"), import_name("read")))
extern int http_read(char *dst, int cap);

int do_get(const char *url, int url_len, char *out, int out_cap) {
    int n = http_get(url, url_len);
    if (n < 0) return n;            // -1 denied / -2 error
    return http_read(out, out_cap); // bytes copied into out
}
