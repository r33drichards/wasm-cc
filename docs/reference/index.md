# Reference

Information-oriented, authoritative descriptions. These pages are derived from the
mod source (`WasmLuaAPI.java`, `WasmCcConfig.java`, the `caps/` packs) and the
`Makefile` — they describe what exists, precisely, without tutorials or opinions.

- **[Lua API (`wasm.*`)](lua-api.md)** — every function, its arguments, and its
  return value.
- **[`opts` table](opts.md)** — the options table accepted by `instantiate` and
  `run`.
- **[Capabilities](capabilities.md)** — `wasi`, `fs`, `http`, and the auto-stub.
- **[Configuration (`wasm-cc.json`)](configuration.md)** — every server config
  field and its default.
- **[Build & Make targets](build.md)** — the Nix toolchain and Make targets.
- **[Project layout](project-layout.md)** — what lives where in the repo.
