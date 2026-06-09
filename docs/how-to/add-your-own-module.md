# Add your own wasm module

**Goal:** compile your own code to WebAssembly and make it callable from CC
computers.

wasm-cc runs **`wasm32`** modules — the same target mcp-v8 runs, so a module you
build works in both. You decide whether it is driven through the raw API (mode A)
or run as a command (mode B).

## 1. Choose the module shape

| You want to… | Build as | Driven by |
|--------------|----------|-----------|
| call individual exported functions and poke memory | a `wasm32-wasi` **reactor** (`-mexec-model=reactor`) that exports your functions (and usually `malloc`/`free`) | mode A — `wasm.instantiate` + `wasm.call` |
| run a whole program to completion (reads/writes files, does I/O, exits) | a `wasm32-wasi` **command** (default exec model, has a `_start`) | mode B — `wasm.run` |

## 2. Compile with wasi-sdk

The Nix dev shell provides wasi-sdk 25 as `$WASI_SDK_PATH`. A reactor that exports
two functions plus the allocator:

```sh
nix develop -c bash -c '
  "$WASI_SDK_PATH/bin/clang" --target=wasm32-wasi -mexec-model=reactor -O2 \
    -Wl,--export=my_func -Wl,--export=malloc -Wl,--export=free \
    -o mymod.wasm mymod.c
'
```

A command module (run with `wasm.run`) is just the default model:

```sh
nix develop -c bash -c '
  "$WASI_SDK_PATH/bin/clang" --target=wasm32-wasi -O2 -o mytool.wasm mytool.c
'
```

!!! tip "Imports you don't provide are auto-stubbed"
    Functions your module imports that no capability pack serves (for example
    Emscripten `env.*` helpers) are auto-stubbed as zero-returning no-ops, so the
    module still instantiates. Pass `-Wl,--allow-undefined` if the linker would
    otherwise reject them. See
    [Capability packs & auto-stub](../explanation/capability-packs.md).

## 3. Install it

Copy the `.wasm` into the server's modules directory:

```
<config>/wasm-modules/<name>.wasm
```

You reference it as `file://<name>` (the `.wasm` is optional) in
`wasm.instantiate` / `wasm.run`. The directory name is configurable via
`modulesDir` in [`wasm-cc.json`](../reference/configuration.md).

## 4. Use it

Mode A:

```lua
local h = wasm.instantiate("file://mymod", { caps = { "wasi" } })
print(wasm.call(h, "my_func", 21))
wasm.close(h)
```

Mode B:

```lua
local ok, res = wasm.run("file://mytool", { args = { "mytool", "--flag" }, timeout = 30 })
print(ok, res and res.stdout)
```

## Optional: load by URL or from an OCI registry

Besides `file://<name>`, the `ref` can name a remote source (both off by default):

- **`http(s)://…`** — enable `allowUrlModules` in
  [`wasm-cc.json`](../reference/configuration.md); the module is downloaded and
  cached.
- **`oci://<registry>/<repo>:<tag>`** (or the no-scheme sugar
  `<registry>/<repo>:<tag>`, or an `@sha256:<digest>` pin) — enable
  `allowOciModules` and list the registry in `ociRegistryAllow` (e.g.
  `["ghcr.io"]`). The bytes are pulled anonymously and **verified against the
  layer's content digest**, so the ref can't silently serve different code. To
  publish your own module, push it as a single `application/wasm` layer, e.g.
  with `oras push ghcr.io/you/mod:1.0 mod.wasm:application/wasm`.

See [Module references](../reference/lua-api.md#module-references).

## See also

- [Capabilities reference](../reference/capabilities.md) — what `wasi`/`fs`/`http`
  give a module.
- [Build & Make targets](../reference/build.md) — how the shipped fixtures are
  compiled (good clang examples).
