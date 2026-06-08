# wasm-cc

Run **arbitrary WebAssembly modules from ComputerCraft**. `wasm-cc` is a Fabric mod
that gives every CC computer a `wasm` global exposing the **raw WebAssembly API** —
compile a module, instantiate it, then call its exports and read/write its linear
memory directly — over the [Chicory](https://chicory.dev) pure-JVM runtime (no
native libraries, no JNI). It is the in-game sibling of the V8-based
[mcp-v8](https://github.com/r33drichards/mcp-v8): **the same `wasm32` module runs
in both**, so you write a module once and drive it from JavaScript (mcp-v8) or Lua
(here).

A module's imports are served by **Java capability packs**, backed by the same
subsystems CC itself uses:

| Need | Served by |
|------|-----------|
| in-memory compute (sqlite, parsers, codecs) | WASI Preview 1 stubs |
| **read/write a file on the computer's disk** | WASI `fs`, mounted on the computer's real save dir |
| **networking** | an `http` import, gated by an allowlist (CC's http policy) |

Imports a module declares that no pack serves are auto-stubbed (zero-returning
no-ops), so Emscripten `STANDALONE_WASM` builds (which import `env.*` helpers) load
unmodified — the same resilient-import trick mcp-v8 uses.

## Two execution modes

WASM calls run to completion on their thread (Chicory has no mid-call yield), so:

- **Mode A — raw API (synchronous, on the computer thread).** Best for chatty,
  fast work (sqlite, file munging). `wasm.instantiate` returns an integer handle;
  you call exports and poke memory through it. A *slow* call blocks the computer,
  so heavy/networked work belongs in mode B.
- **Mode B — `wasm.run` (off-thread, yields).** A command (run-to-completion)
  module runs on a worker pool and the Lua coroutine yields until it exits,
  returning `{exit, stdout, stderr}`. Output files land on the computer's disk via
  the `fs` cap. Ported from picat-cc's timeout-by-abandonment + saturation guard.

## Lua API

```lua
-- mode A: raw WebAssembly API ------------------------------------------------
local h = wasm.instantiate("sqlite", { caps = {"wasi"} })  -- name in wasm-modules/, or https:// URL
local ptr = wasm.alloc(h, 16)
local namePtr = wasm.allocCString(h, ":memory:")
local ppDb = wasm.allocPtr(h)
wasm.call(h, "sqlite3_open", namePtr, ppDb)                -- call any export by name
local db = wasm.readI32(h, ppDb)                           -- read linear memory
-- ... sqlite3_exec / prepare / step / column_* ...
local avg = wasm.callf(h, "sqlite3_column_double", stmt, 1) -- f64-returning export
local text = wasm.readCString(h, wasm.call(h, "sqlite3_column_text", stmt, 0))
wasm.close(h)

-- mode B: off-thread command run ---------------------------------------------
local ok, res = wasm.run("ffmpeg", {                       -- yields until done
  args = { "-i", "song.mp3", "song.wav" },                 -- argv
  fs   = "media",                                          -- mount <computer>/media at "/"
  timeout = 60,
})
if ok then print(res.exit, res.stdout) end                 -- then read song.wav with fs.open
```

**Functions:** `wasm.version()`, `wasm.instantiate(ref, opts) -> handle`,
`wasm.call(h, name, ...) -> int`, `wasm.callf(h, name, ...) -> double`,
`wasm.readI32/readU32/writeI32`, `wasm.readBytes/writeBytes` (binary strings),
`wasm.readString/readCString/writeString`, `wasm.alloc/allocCString/allocPtr/free`,
`wasm.close(h)`, `wasm.run(ref, opts) -> ok, {exit,stdout,stderr}|err`.

**`opts`:** `caps` (array of `"wasi"`/`"fs"`/`"http"`), `fs` (sub-dir of the
computer's disk to mount at `/`), `args` (argv), `env` (table), `timeout` (seconds,
mode B).

## Building

Uses Nix for the toolchain (JDK 21, Gradle, wasi-sdk 25):

```sh
nix develop -c make resources            # build wasm test fixtures + sqlite
nix develop -c ./gradlew :engine:test    # engine tests (fast; --configure-on-demand skips loom)
nix develop -c ./gradlew :mod:build      # the Fabric mod jar (loom downloads Minecraft on first run)
```

Drop the resulting `mod/build/libs/*.jar` (and CC:Tweaked) into a 1.21.8 Fabric
server's `mods/`. Put named modules in `<config>/wasm-modules/<name>.wasm`.

### Releases

Pushing a `v*` tag builds the mod jar in CI and publishes it as a **GitHub Release**
asset (`.github/workflows/release.yml`). The asset is a direct `.jar` URL:

```
https://github.com/<owner>/wasm-cc/releases/download/<tag>/wasm-cc-<tag>.jar
```

so [itzg/docker-minecraft-server](https://github.com/itzg/docker-minecraft-server)
can fetch it straight from `MODS=`:

```
MODS=https://github.com/<owner>/wasm-cc/releases/download/v0.1.0/wasm-cc-v0.1.0.jar
```

## Layout

- `engine/` — host-neutral runtime (no Minecraft types): `WasmHost` (compile /
  instantiate / mode-B `run`), `ModuleCache` (sha-256 → compiled, LRU),
  `MemoryView`, `ModuleResolver` (dir + URL), `caps/` (WASI + `fs` mount + `http`
  + auto-stub), `run/WasmService` (off-thread pool + timeout).
- `mod/` — the Fabric mod: `WasmLuaAPI` (the `wasm` global, modes A+B),
  `WasmEngine`/`WasmCcConfig`/`WasmApiRegistration`.
- `fixtures/` — C sources for the wasm test fixtures (built by `make`).

Seeded from [picat-cc](https://github.com/r33drichards/picat-cc); the Chicory
integration, host-disk mount, concurrency model, and Lua yield/event plumbing are
its lineage.
