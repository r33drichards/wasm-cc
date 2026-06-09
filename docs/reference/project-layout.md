# Project layout

What lives where in the repository.

## `engine/`

The host-neutral runtime — **no Minecraft types** — so it can be tested in plain
JUnit and reused.

| Component | Role |
|-----------|------|
| `WasmHost` | compile / instantiate / mode-B `run`. |
| `ModuleCache` | sha-256 → compiled module, LRU. |
| `MemoryView` | the linear-memory read/write/alloc helpers behind the `wasm.read*`/`write*`/`alloc*` Lua functions. |
| `ModuleResolver` | resolve a `ref` to bytes (named dir + optional URL). |
| `caps/` | the capability packs: WASI, the `fs` mount, the `http` import, and the auto-stub. |
| `run/WasmService` | the off-thread worker pool + timeout for mode B. |

## `mod/`

The Fabric mod — the Minecraft-facing layer.

| Component | Role |
|-----------|------|
| `WasmLuaAPI` | the `wasm` global (modes A + B). The authoritative source for the [Lua API reference](lua-api.md). |
| `WasmEngine` | the per-server engine (shared host, resolver, service). |
| `WasmCcConfig` | the `wasm-cc.json` config ([reference](configuration.md)). |
| `WasmApiRegistration` / `WasmCcMod` | mod entry points and CC API registration. |

## `fixtures/`

C sources for the wasm test fixtures, built by the [`Makefile`](build.md)
(`spike.c`, `copy.c`, `stub.c`, `httpget.c`, `mp3dec.c`).

## `examples/`

Runnable Lua scripts for a CC computer:

- `sqlite.lua` — the [SQLite walkthrough](../tutorials/sqlite-walkthrough.md) (mode A).
- `decode.lua` — [decode an MP3 on disk](../how-to/run-on-disk-fs.md) (mode B, `fs`).
- `fetch.lua` — [networking via the http cap](../how-to/allowlist-http-host.md) (mode B, `http`).

## Top-level

| Path | Role |
|------|------|
| `flake.nix` | the Nix dev shells (`default` for the mod toolchain, `docs` for this site). |
| `Makefile` | builds the wasm fixtures. |
| `mkdocs.yml` | this documentation site. |
| `docs/` | the documentation sources. |
| `.github/workflows/` | CI (`ci.yml`), releases (`release.yml`), and docs deploy (`docs.yml`). |

## Lineage

wasm-cc was seeded from
[picat-cc](https://github.com/r33drichards/picat-cc); the Chicory integration,
host-disk mount, concurrency model, and Lua yield/event plumbing are its lineage.
See [Lineage](../explanation/lineage.md).
