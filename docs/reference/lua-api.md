# Lua API (`wasm.*`)

Every CC computer has a `wasm` global. This page documents every function it
exposes, derived from `mod/src/main/java/cc/wasmcc/mod/WasmLuaAPI.java`.

Functions split into two execution modes:

- **Mode A — raw API** (synchronous, on the computer thread): `instantiate`, the
  `call`/`callf` export callers, the memory helpers, and `close`.
- **Mode B — command run** (off-thread, yields the coroutine): `run`.

See [Two execution modes](../explanation/execution-modes.md) for the rationale, and
the [`opts` table](opts.md) for the options shared by `instantiate` and `run`.

In the signatures below, `h` (or `handle`) is the integer returned by
`wasm.instantiate`, and `ptr` is a byte offset into the module's linear memory.

---

## Common

### `wasm.version()`

Returns the mod version as a string (currently `"0.1.0"`). Needs no module.

```lua
print(wasm.version())  --> 0.1.0
```

---

## Mode A — instantiate & call

### `wasm.instantiate(ref [, opts])` → `handle`

Compiles and instantiates a module **synchronously** and returns an integer
handle. `ref` is a module **name** (looked up as `<name>.wasm` in the modules
directory) or, if the server enables `allowUrlModules`, an `http(s)://` URL.
`opts` is the optional [options table](opts.md) (`caps`, `args`, `env`, `fs`).

```lua
local h = wasm.instantiate("sqlite3", { caps = { "wasi" } })
```

Raises a Lua error if the module can't be resolved, fails to compile/instantiate,
or the computer already holds the maximum number of open instances
(`maxInstancesPerComputer`).

### `wasm.call(h, name [, args...])` → `integer`

Calls the export `name` with the given numeric arguments and returns its first
result as an **integer** (64-bit). Use this for exports returning `i32`/`i64`
(including pointers).

```lua
local rc = wasm.call(h, "sqlite3_open", namePtr, ppDb)
```

### `wasm.callf(h, name [, args...])` → `number`

Like `call`, but returns the first result as a **double**. Use this for exports
returning `f32`/`f64`.

```lua
local x = wasm.callf(h, "sqlite3_column_double", stmt, 1)
```

---

## Mode A — linear memory

These operate on the handle's exported linear memory. They raise
`module exports no memory` if the module exports none.

### Reads

| Function | Returns | Description |
|----------|---------|-------------|
| `wasm.readI32(h, ptr)` | `integer` | Signed 32-bit int at `ptr`. |
| `wasm.readU32(h, ptr)` | `integer` | Unsigned 32-bit int at `ptr`. |
| `wasm.readBytes(h, ptr, len)` | `string` | `len` raw bytes as a binary string. |
| `wasm.readString(h, ptr, len)` | `string` | `len` bytes decoded as UTF-8. |
| `wasm.readCString(h, ptr)` | `string` | Bytes up to the first NUL, as UTF-8. |

### Writes

| Function | Description |
|----------|-------------|
| `wasm.writeI32(h, ptr, value)` | Write a 32-bit int at `ptr`. |
| `wasm.writeBytes(h, ptr, data)` | Write the bytes of binary string `data` at `ptr`. |
| `wasm.writeString(h, ptr, s)` | Write string `s` at `ptr`. |

---

## Mode A — allocation

These require the module to export `malloc` (and `free`); they raise a Lua error
otherwise.

### `wasm.alloc(h, n)` → `ptr`

Allocate `n` bytes in the module's memory and return a pointer.

### `wasm.allocCString(h, s)` → `ptr`

Copy `s` into the module's memory as a NUL-terminated C string and return a
pointer to it.

### `wasm.allocPtr(h)` → `ptr`

Allocate a 4-byte zeroed slot (for an out-parameter, e.g. a `sqlite3 **`) and
return a pointer to it.

### `wasm.free(h, ptr)`

Free a pointer previously returned by one of the `alloc*` helpers.

---

## Mode A — lifecycle

### `wasm.close(h)`

Free a mode-A instance. Always call this when you are done with a handle. (All of a
computer's instances are also freed automatically when the computer is unloaded or
rebooted.)

---

## Mode B — command run

### `wasm.run(ref [, opts])` → `ok, result`

Runs a command (run-to-completion) module **off-thread** on a worker pool and
**yields** the Lua coroutine until the module exits. `ref` is a module name or URL
(as for `instantiate`). `opts` is the [options table](opts.md), and for mode B may
include `timeout` (seconds).

Returns two values:

- **On success:** `true`, and a result table:

    | Field | Type | Description |
    |-------|------|-------------|
    | `exit` | `integer` | The module's exit code. |
    | `stdout` | `string` | Captured standard output. |
    | `stderr` | `string` | Captured standard error. |

- **On failure:** `false`, and an error **string** — for example `"module: ..."`
  (couldn't resolve), `"compile: ..."`, `"busy: too many wasm runs on this
  computer"`, `"timeout"`, or an internal error message.

```lua
local ok, res = wasm.run("mp3dec", {
  args    = { "mp3dec", "/song.mp3", "/song.wav" },
  fs      = "media",
  timeout = 30,
})
if ok then
  print(res.exit, res.stdout)
else
  printError(res)
end
```

!!! note "How yielding works"
    Internally `run` submits the job and uses CC's event plumbing: it queues a
    `wasm_done` event when the job completes and resumes your coroutine with the
    result. You do not handle the event yourself — `wasm.run` looks like a normal
    blocking call that returns when the module finishes.
