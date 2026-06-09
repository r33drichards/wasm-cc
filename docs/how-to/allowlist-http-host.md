# Allowlist an `http` host

**Goal:** let a WebAssembly module make outbound network requests through the
gated `http` capability.

A module's networking "comes from" the host: its `http` import is implemented in
Java and gated by the server's allowlist (`wasm-cc.json` → `httpAllow`, mirroring
CC's http policy). The GET is synchronous, so run networked modules **off-thread**
(mode B) so a slow request never stalls the tick.

## 1. Allow the host in `wasm-cc.json`

Edit `<config>/wasm-cc.json` and add hosts to `httpAllow`:

```json
{
  "httpAllow": ["api.example.com", "*.example.com"]
}
```

Allowlist matching rules (see the [capabilities reference](../reference/capabilities.md)):

| Pattern | Matches |
|---------|---------|
| `api.example.com` | exactly that host (optionally with `:port`) |
| `*.example.com` | any subdomain of `example.com` |
| `*` | anything |
| *(empty list)* | nothing — denies all requests |

## 2. Build a module against the `http` import

The capability exposes a coarse two-call ABI (the body length is unknown up front,
so the guest learns the size, then copies the bytes):

```c
// guest side — compile to wasm32-wasi, place in <config>/wasm-modules/fetcher.wasm
__attribute__((import_module("http"), import_name("get")))
extern int  http_get(const char *url, int len);   // -> body len | -1 denied | -2 error
__attribute__((import_module("http"), import_name("read")))
extern int  http_read(char *dst, int cap);        // copies body into dst
```

So `http.get(url_ptr, url_len)` returns the body length (or `-1` if the host denied
it, `-2` on a fetch error), and `http.read(dst_ptr, dst_cap)` copies that body into
guest memory.

## 3. Run it off-thread with the `http` cap

```lua
local ok, res = wasm.run("fetcher", {
  caps    = { "wasi", "http" },
  args    = { "fetcher", "https://api.example.com/status.json" },
  timeout = 30,
})
if ok then
  print(res.stdout)              -- the fetcher prints the body it received
else
  printError(res)                -- "busy: ...", "timeout", or a module error (-1 = host denied)
end
```

## Notes

- You must include `"http"` in `caps` **and** allow the host in `httpAllow`. If
  the host isn't allowed, the guest's `http.get` returns `-1`.
- An empty `httpAllow` denies everything — this is the safe default.
- Requests have a 10s connect timeout and a 30s request timeout, and normal
  redirects are followed.

## See also

- [`fetch.lua`](https://github.com/r33drichards/wasm-cc/blob/master/examples/fetch.lua)
  — the runnable version of this guide.
- [Capabilities reference](../reference/capabilities.md)
- [Configuration reference](../reference/configuration.md) — `httpAllow` details.
