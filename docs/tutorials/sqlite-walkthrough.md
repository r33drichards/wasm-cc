# SQLite walkthrough

In this tutorial you will drive a real **SQLite** engine — compiled to WebAssembly
— from a ComputerCraft computer, using the raw WebAssembly API (mode A). You will
open an in-memory database, create a table, insert rows, and read results back.

This is the CC analogue of the mcp-v8 SQLite example: it is the **same**
`sqlite3.wasm` module, driven through Lua instead of JavaScript.

!!! note "Prerequisites"
    Work through **[Getting started](getting-started.md)** first so you have the
    mod installed and know how `wasm.instantiate` / `wasm.call` / `wasm.close`
    work. The full runnable script is
    [`examples/sqlite.lua`](https://github.com/r33drichards/wasm-cc/blob/master/examples/sqlite.lua).

## 1. Build and install the SQLite module

`sqlite3.wasm` is built from the SQLite amalgamation by the Makefile (it is large,
so it is not committed):

```sh
nix develop -c make resources
# copy engine/src/test/resources/modules/sqlite3.wasm
# into <config>/wasm-modules/sqlite3.wasm
```

The module is compiled as a `wasm32-wasi` **reactor** that exports the SQLite C
API plus `malloc`/`free`, so we can drive it directly.

## 2. Instantiate it

```lua
local h = wasm.instantiate("file://sqlite3", { caps = { "wasi" } })
```

SQLite is pure in-memory compute here, so the `wasi` capability is all it needs.

!!! tip "Or pull it from a registry"
    Instead of building and copying `sqlite3.wasm` yourself, you can let the host
    pull a published, digest-verified build straight from an OCI registry:

    ```lua
    local h = wasm.instantiate("oci://ghcr.io/r33drichards/sqlite:0.1.1", { caps = { "wasi" } })
    ```

    This needs `"allowOciModules": true` and `"ociRegistryAllow": ["ghcr.io"]` in
    `wasm-cc.json`. The artifact is published by the release workflow (or
    `make publish-sqlite`); the runnable [`examples/sqlite.lua`] uses this form.
    See [Module references](../reference/lua-api.md#module-references).

    [`examples/sqlite.lua`]: https://github.com/r33drichards/wasm-cc/blob/master/examples/sqlite.lua

## 3. The memory pattern

The SQLite C API works with pointers: you pass it C strings and out-parameter
slots, and it hands back pointers you then read. With the raw API you manage that
linear memory yourself using `wasm.alloc*`, `wasm.read*`, and `wasm.free`.

A few constants from SQLite's headers we will use:

```lua
local SQLITE_OK, SQLITE_ROW, SQLITE_DONE = 0, 100, 101
local COL_INT, COL_FLOAT, COL_NULL = 1, 2, 5
```

## 4. Open a database

`sqlite3_open(filename, ppDb)` takes a C-string filename and a pointer-to-pointer
out-parameter; on success it writes the database handle into that slot.

```lua
local function open()
  local namePtr = wasm.allocCString(h, ":memory:")  -- C string in guest memory
  local ppDb = wasm.allocPtr(h)                      -- 4-byte out-param slot
  assert(wasm.call(h, "sqlite3_open", namePtr, ppDb) == SQLITE_OK, "open failed")
  local db = wasm.readI32(h, ppDb)                   -- read the handle back out
  wasm.free(h, namePtr); wasm.free(h, ppDb)
  return db
end
```

Note the three building blocks:

- `wasm.allocCString(h, s)` copies a NUL-terminated string into the module's memory
  and returns a pointer.
- `wasm.allocPtr(h)` reserves a zeroed 4-byte slot for an out-parameter.
- `wasm.readI32(h, ptr)` reads a 32-bit integer back out.

## 5. A helper for error messages

`sqlite3_errmsg(db)` returns a pointer to a C string. `wasm.readCString` reads a
NUL-terminated string starting at a pointer:

```lua
local function errmsg(db)
  return wasm.readCString(h, wasm.call(h, "sqlite3_errmsg", db))
end
```

## 6. Execute statements

`sqlite3_exec` runs SQL that returns no rows (DDL, inserts). We pass the SQL as a C
string and zero for the callback arguments we don't use:

```lua
local function exec(db, sql)
  local p = wasm.allocCString(h, sql)
  local rc = wasm.call(h, "sqlite3_exec", db, p, 0, 0, 0)
  wasm.free(h, p)
  assert(rc == SQLITE_OK, "exec: " .. errmsg(db))
end
```

## 7. Query rows

For queries we prepare a statement, step through rows, and read each column by its
declared type. Note `wasm.callf` for the floating-point column accessor — it
returns a `double` where `wasm.call` returns an integer:

```lua
local function query(db, sql)
  local p = wasm.allocCString(h, sql)
  local pp = wasm.allocPtr(h)
  assert(wasm.call(h, "sqlite3_prepare_v2", db, p, -1, pp, 0) == SQLITE_OK,
         "prepare: " .. errmsg(db))
  wasm.free(h, p)
  local stmt = wasm.readI32(h, pp); wasm.free(h, pp)

  local n = wasm.call(h, "sqlite3_column_count", stmt)
  local cols = {}
  for c = 0, n - 1 do
    cols[c] = wasm.readCString(h, wasm.call(h, "sqlite3_column_name", stmt, c))
  end

  local rows = {}
  while wasm.call(h, "sqlite3_step", stmt) == SQLITE_ROW do
    local row = {}
    for c = 0, n - 1 do
      local t = wasm.call(h, "sqlite3_column_type", stmt, c)
      if t == COL_NULL then row[cols[c]] = nil
      elseif t == COL_INT then row[cols[c]] = wasm.call(h, "sqlite3_column_int", stmt, c)
      elseif t == COL_FLOAT then row[cols[c]] = wasm.callf(h, "sqlite3_column_double", stmt, c)
      else row[cols[c]] = wasm.readCString(h, wasm.call(h, "sqlite3_column_text", stmt, c)) end
    end
    rows[#rows + 1] = row
  end
  wasm.call(h, "sqlite3_finalize", stmt)
  return rows
end
```

## 8. Put it together

```lua
local db = open()
exec(db, "CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT, age INTEGER)")
exec(db, "INSERT INTO users (name, age) VALUES ('Alice', 30), ('Bob', 25), ('Charlie', 35)")
for _, r in ipairs(query(db, "SELECT name, age FROM users ORDER BY age")) do
  print(r.name, r.age)
end
wasm.call(h, "sqlite3_close", db)
wasm.close(h)
```

Running it prints:

```
Bob     25
Alice   30
Charlie 35
```

## What you learned

- Mode A lets you drive a complex C library's exports directly from Lua.
- The memory helpers — `allocCString`, `allocPtr`, `readI32`, `readCString`,
  `free` — are how you marshal arguments and out-parameters across the boundary.
- `wasm.call` returns integers; `wasm.callf` returns doubles. Use the one that
  matches the export's return type.
- Always `wasm.close(h)` the instance when finished.

## Next steps

- **[Run a module on disk (fs cap)](../how-to/run-on-disk-fs.md)** — mode B, for
  modules that read and write real files.
- **[Lua API reference](../reference/lua-api.md)** — every memory and call helper.
