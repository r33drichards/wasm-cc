-- sqlite ON DISK via the raw WebAssembly API, on a ComputerCraft computer.
-- Sibling of examples/sqlite.lua, but instead of ":memory:" this opens a real
-- file through the `fs` cap, so the database survives the wasm instance and lands
-- on the computer's save dir as an inspectable .sqlite file.
--
-- How the path maps (verified against the engine source):
--   * wasm.instantiate(..., { fs = "sql" }) resolves to this computer's own save
--     dir + "sql": <world>/computercraft/computer/<id>/sql   (WasmLuaAPI.resolveFs
--     + computerRoot).
--   * The `fs` cap mounts that host directory at the guest root "/"
--     (Caps.assemble -> WasiOptions.withDirectory("/", fsDir)).
--   * So sqlite3_open("/app.sqlite") writes <id>/sql/app.sqlite on the host disk.
--
-- The module is pulled from an OCI registry (digest-verified); enable it in
-- wasm-cc.json with "allowOciModules": true and "ociRegistryAllow": ["ghcr.io"].
-- The artifact is published by the release workflow (or `make publish-sqlite`);
-- before the first `v*` tag exists, use the local form instead: drop sqlite3.wasm
-- in <config>/wasm-modules/ and call wasm.instantiate("file://sqlite3", ...).

local SQLITE_OK, SQLITE_ROW, SQLITE_DONE = 0, 100, 101
local COL_INT, COL_FLOAT, COL_NULL = 1, 2, 5

-- The database file, addressed inside the mounted dir (guest "/" == <id>/sql).
local DB_PATH = "/app.sqlite"
local FS_SUBDIR = "sql"

-- Open a fresh sqlite instance with the fs cap, returning {h=handle, db=ptr}.
local function open()
  local h = wasm.instantiate("oci://ghcr.io/r33drichards/sqlite:0.1.1", { caps = { "wasi", "fs" }, fs = FS_SUBDIR })
  local namePtr = wasm.allocCString(h, DB_PATH)
  local ppDb = wasm.allocPtr(h)
  assert(wasm.call(h, "sqlite3_open", namePtr, ppDb) == SQLITE_OK, "open failed")
  local db = wasm.readI32(h, ppDb)
  wasm.free(h, namePtr); wasm.free(h, ppDb)
  return { h = h, db = db }
end

local function errmsg(c) return wasm.readCString(c.h, wasm.call(c.h, "sqlite3_errmsg", c.db)) end

local function exec(c, sql)
  local p = wasm.allocCString(c.h, sql)
  local rc = wasm.call(c.h, "sqlite3_exec", c.db, p, 0, 0, 0)
  wasm.free(c.h, p)
  assert(rc == SQLITE_OK, "exec: " .. errmsg(c))
end

local function query(c, sql)
  local h = c.h
  local p = wasm.allocCString(h, sql)
  local pp = wasm.allocPtr(h)
  assert(wasm.call(h, "sqlite3_prepare_v2", c.db, p, -1, pp, 0) == SQLITE_OK, "prepare: " .. errmsg(c))
  wasm.free(h, p)
  local stmt = wasm.readI32(h, pp); wasm.free(h, pp)
  local n = wasm.call(h, "sqlite3_column_count", stmt)
  local cols = {}
  for col = 0, n - 1 do cols[col] = wasm.readCString(h, wasm.call(h, "sqlite3_column_name", stmt, col)) end
  local rows = {}
  while wasm.call(h, "sqlite3_step", stmt) == SQLITE_ROW do
    local row = {}
    for col = 0, n - 1 do
      local t = wasm.call(h, "sqlite3_column_type", stmt, col)
      if t == COL_NULL then row[cols[col]] = nil
      elseif t == COL_INT then row[cols[col]] = wasm.call(h, "sqlite3_column_int", stmt, col)
      elseif t == COL_FLOAT then row[cols[col]] = wasm.callf(h, "sqlite3_column_double", stmt, col)
      else row[cols[col]] = wasm.readCString(h, wasm.call(h, "sqlite3_column_text", stmt, col)) end
    end
    rows[#rows + 1] = row
  end
  wasm.call(h, "sqlite3_finalize", stmt)
  return rows
end

local function close(c)
  wasm.call(c.h, "sqlite3_close", c.db)
  wasm.close(c.h)
end

-- ---- pass 1: write, then fully tear down the wasm instance ------------------
print("opening " .. FS_SUBDIR .. DB_PATH .. " (on disk) ...")
local c = open()
exec(c, "CREATE TABLE IF NOT EXISTS users (id INTEGER PRIMARY KEY, name TEXT, age INTEGER)")
exec(c, "DELETE FROM users")
exec(c, "INSERT INTO users (name, age) VALUES ('Alice', 30), ('Bob', 25), ('Charlie', 35)")
print("wrote " .. #query(c, "SELECT id FROM users") .. " rows; closing instance.")
close(c)  -- tears down the whole wasm instance: nothing left in memory

-- ---- pass 2: re-open a BRAND NEW instance and read it back ------------------
-- If this returns the rows, the data round-tripped through the host disk file.
print("re-opening a fresh instance to prove persistence ...")
local c2 = open()
local rows = query(c2, "SELECT name, age FROM users ORDER BY age")
assert(#rows == 3, "expected 3 persisted rows, got " .. #rows)
for _, r in ipairs(rows) do
  print(r.name, r.age)
end
close(c2)

print("ok: data persisted to " .. FS_SUBDIR .. DB_PATH .. " on the computer's disk.")
print("inspect it on the host at:")
print("  <world>/computercraft/computer/<id>/" .. FS_SUBDIR .. "/app.sqlite")
