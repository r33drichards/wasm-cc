# Capabilities

A module's imports are served by **Java capability packs**. You request packs per
instance via the [`opts.caps`](opts.md) array; if you omit it, the server's
`defaultCaps` (default `{"wasi"}`) is used. Anything the module imports that no
requested pack serves is [auto-stubbed](../explanation/capability-packs.md).

Derived from `engine/src/main/java/cc/wasmcc/engine/caps/`.

## `wasi`

The WASI Preview 1 surface, served by Chicory's `WasiPreview1`. Standard output
and standard error are captured (and returned by `wasm.run` as `stdout` /
`stderr`); standard input is empty. The module's `args` and `env` from
[`opts`](opts.md) are passed through.

This is what pure in-memory compute (sqlite, parsers, codecs) needs.

## `fs`

Mounts a host directory at the guest's root `/`. In production that directory is a
sub-folder of the **CC computer's own save directory**, named by
[`opts.fs`](opts.md).

- The path is confined to the computer's save directory: absolute paths and `..`
  traversal are rejected, and the directory is created if missing.
- Not supported on command (admin) computers.
- Files the module writes appear on the computer's disk and can be read back with
  ComputerCraft's normal `fs` API.

See [Run a module on disk](../how-to/run-on-disk-fs.md).

## `http`

Adds an allowlisted networking import the guest links against. The GET is
synchronous — harmless off-thread (mode B), but it would block the computer thread
in mode A, so **networking belongs in `wasm.run`**.

### Guest ABI

A coarse two-call ABI (the body length is unknown up front):

```wat
(import "http" "get"  (func (param i32 i32) (result i32)))  ; (url_ptr,url_len) -> len | -1 denied | -2 error
(import "http" "read" (func (param i32 i32) (result i32)))  ; (dst_ptr,dst_cap)  -> bytes copied
```

`get` performs the request and returns the body length (or `-1` if the host was
denied, `-2` on a fetch error); `read` then copies that body into guest memory.
The last response body is held per-instance. Requests use a 10s connect timeout
and a 30s request timeout and follow normal redirects; non-2xx responses are
treated as errors.

### Allowlist

The `http` cap is gated by the server's `httpAllow` list
([config](configuration.md)). Matching (from `HttpPack.allowed`):

| Pattern | Matches |
|---------|---------|
| `host` or `host:port` | that exact host (and port, if given) |
| `*.example.com` | any subdomain of `example.com` |
| `*` | anything |
| *(empty list)* | nothing — denies all requests |

Matching is case-insensitive. An empty allowlist (the default) denies everything.

See [Allowlist an http host](../how-to/allowlist-http-host.md).

## Auto-stub (not a cap you request)

Any function import a module declares that no requested pack natively serves —
classically Emscripten `env.*` helpers — is replaced with a zero-returning no-op so
the module still instantiates. This is the same resilient-import trick mcp-v8 uses;
see [Capability packs & auto-stub](../explanation/capability-packs.md).
