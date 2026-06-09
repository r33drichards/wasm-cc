-- sqlite via the raw WebAssembly API, on a ComputerCraft computer.
-- The CC analogue of the mcp-v8 sqlite example: same sqlite3.wasm module, driven
-- through `wasm` (mode A, synchronous).
--
-- This loads the module straight from an OCI registry (digest-verified). For that
-- to work, wasm-cc.json must enable it:
--     "allowOciModules": true,
--     "ociRegistryAllow": ["ghcr.io"]
-- The host fetches the module itself, so only the "wasi" cap is needed here (no
-- "http" cap). The ghcr.io/r33drichards/sqlite artifact is published by the
-- release workflow (or manually via `make publish-sqlite`); until the first `v*`
-- tag is pushed this ref will not resolve yet -- meanwhile you can use the local
-- form instead: wasm.instantiate("file://sqlite3", ...) with sqlite3.wasm in
-- <config>/wasm-modules/.

local SQLITE_OK, SQLITE_ROW, SQLITE_DONE = 0, 100, 101
local COL_INT, COL_FLOAT, COL_NULL = 1, 2, 5

local h = wasm.instantiate("oci://ghcr.io/r33drichards/sqlite:0.1.0", { caps = { "wasi" } })

local function open()
  local namePtr = wasm.allocCString(h, ":memory:")
  local ppDb = wasm.allocPtr(h)
  assert(wasm.call(h, "sqlite3_open", namePtr, ppDb) == SQLITE_OK, "open failed")
  local db = wasm.readI32(h, ppDb)
  wasm.free(h, namePtr); wasm.free(h, ppDb)
  return db
end

local function errmsg(db) return wasm.readCString(h, wasm.call(h, "sqlite3_errmsg", db)) end

local function exec(db, sql)
  local p = wasm.allocCString(h, sql)
  local rc = wasm.call(h, "sqlite3_exec", db, p, 0, 0, 0)
  wasm.free(h, p)
  assert(rc == SQLITE_OK, "exec: " .. errmsg(db))
end

local function query(db, sql)
  local p = wasm.allocCString(h, sql)
  local pp = wasm.allocPtr(h)
  assert(wasm.call(h, "sqlite3_prepare_v2", db, p, -1, pp, 0) == SQLITE_OK, "prepare: " .. errmsg(db))
  wasm.free(h, p)
  local stmt = wasm.readI32(h, pp); wasm.free(h, pp)
  local n = wasm.call(h, "sqlite3_column_count", stmt)
  local cols = {}
  for c = 0, n - 1 do cols[c] = wasm.readCString(h, wasm.call(h, "sqlite3_column_name", stmt, c)) end
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

local db = open()
exec(db, "CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT, age INTEGER)")
exec(db, "INSERT INTO users (name, age) VALUES ('Alice', 30), ('Bob', 25), ('Charlie', 35)")
for _, r in ipairs(query(db, "SELECT name, age FROM users ORDER BY age")) do
  print(r.name, r.age)
end
wasm.call(h, "sqlite3_close", db)
wasm.close(h)
