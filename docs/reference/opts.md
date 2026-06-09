# The `opts` table

Both `wasm.instantiate(ref, opts)` and `wasm.run(ref, opts)` accept an optional
options table. All fields are optional. Derived from `decodeOpts` /
`buildConfig` in `WasmLuaAPI.java`.

| Field | Type | Applies to | Description |
|-------|------|-----------|-------------|
| `caps` | array of strings | both | Capabilities to grant: any of `"wasi"`, `"fs"`, `"http"`. If omitted, the server's `defaultCaps` is used (default `{"wasi"}`). See [Capabilities](capabilities.md). |
| `args` | array of strings | both | The argv passed to the module. By convention the first element is the program name. |
| `env` | table (string → string) | both | Environment variables for the module. |
| `fs` | string | both | A sub-directory of the computer's own disk to mount at the guest's `/`. Requires the `fs` cap. Created if absent; traversal/absolute paths and command computers are rejected. |
| `timeout` | number | `run` only | Maximum run time in **seconds** for a mode-B run. Clamped by the server's `maxTimeoutSeconds`. |

## Examples

Mode A — just capabilities:

```lua
local h = wasm.instantiate("sqlite3", { caps = { "wasi" } })
```

Mode B — argv, a disk mount, and a timeout:

```lua
local ok, res = wasm.run("mp3dec", {
  args    = { "mp3dec", "/song.mp3", "/song.wav" },
  fs      = "media",
  timeout = 30,
})
```

Mode B — networking with environment variables:

```lua
local ok, res = wasm.run("fetcher", {
  caps = { "wasi", "http" },
  args = { "fetcher", "https://api.example.com/status.json" },
  env  = { TOKEN = "abc123" },
})
```

## Validation

`opts` fields are type-checked when decoded; a wrong type raises a Lua error:

- `caps` and `args` must be arrays of strings.
- `env` must be a table.
- `fs` must be a string.
- `timeout` must be a number.

!!! note
    A passing `fs` value only takes effect if the `fs` capability is in `caps`,
    and an `httpAllow` allowlist is only applied when `http` is in `caps`.
