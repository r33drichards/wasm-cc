-- Networking from WASM via the http capability.
--
-- The module's `http` import is implemented in Java and gated by the server's
-- allowlist (wasm-cc.json -> httpAllow, mirroring CC's http policy). Build a
-- module against the import below and place it in <config>/wasm-modules/:
--
--   __attribute__((import_module("http"), import_name("get")))
--   extern int http_get(const char *url, int len);            // -> body len | -1 denied | -2 error
--   __attribute__((import_module("http"), import_name("read")))
--   extern int http_read(char *dst, int cap);                  // copies body into dst
--
-- Run it off-thread (mode B) so a slow request never stalls the tick:

local ok, res = wasm.run("file://fetcher", {
  caps    = { "wasi", "http" },
  args    = { "fetcher", "https://example.com/data.json" },
  timeout = 30,
})
if ok then
  print("exit " .. res.exit)
  print(res.stdout)            -- e.g. the fetcher prints the body it received
else
  print("error: " .. tostring(res))   -- "busy: ...", "timeout", "trap: ...", or a module error
end
