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

The `<name>` (without `.wasm`) is what you pass to `wasm.instantiate` /
`wasm.run`. The directory name is configurable via `modulesDir` in
[`wasm-cc.json`](../reference/configuration.md).

## 4. Use it

Mode A:

```lua
local h = wasm.instantiate("mymod", { caps = { "wasi" } })
print(wasm.call(h, "my_func", 21))
wasm.close(h)
```

Mode B:

```lua
local ok, res = wasm.run("mytool", { args = { "mytool", "--flag" }, timeout = 30 })
print(ok, res and res.stdout)
```

## Optional: load by URL

If the server enables `allowUrlModules` in
[`wasm-cc.json`](../reference/configuration.md), you can pass an `http(s)://` URL
as the `ref` instead of a name; the module is downloaded and cached. This is off
by default.

## See also

- [Capabilities reference](../reference/capabilities.md) — what `wasi`/`fs`/`http`
  give a module.
- [Build & Make targets](../reference/build.md) — how the shipped fixtures are
  compiled (good clang examples).
