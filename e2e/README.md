# wasm-cc e2e: on-disk SQLite from Lua, end to end

A minimal, locally-runnable loop that proves wasm-cc works in a real game:

1. Build the wasm-cc mod jar and `sqlite3.wasm`.
2. Stage them into a Docker Compose server (Minecraft 1.21.8 + Fabric +
   CC:Tweaked).
3. Join the server, place a Computer, run [`examples/sqlite_disk.lua`](../examples/sqlite_disk.lua).
4. Inspect the resulting `app.sqlite` **file on the host disk** to confirm the Lua
   script read and wrote a real SQLite database through the `fs` cap.

Everything the server reads lives under `e2e/data/` (bind-mounted to `/data`), so
the on-disk database ends up as an ordinary file you can open with `sqlite3`.

## Prerequisites

- Docker + Docker Compose v2 (`docker compose version`).
- Nix (the repo's toolchain: JDK 21, Gradle, wasi-sdk) for the two builds.
- A desktop Minecraft 1.21.8 client with the Fabric loader to actually join (the
  server is headless; see "Connecting" for why we don't automate the client).

## 1. Build the artifacts

From the repo root:

```sh
nix develop -c ./gradlew :mod:build     # -> mod/build/libs/mod.jar
nix develop -c make resources           # -> engine/src/test/resources/modules/sqlite3.wasm
```

(`make resources` also builds the other fixtures; only `sqlite3.wasm` is used
here.)

## 2. Stage them into the compose dirs (one command)

```sh
make e2e-setup        # == bash e2e/setup.sh
```

This copies:

| from | to |
|------|----|
| `mod/build/libs/mod.jar` | `e2e/data/mods/wasm-cc.jar` |
| `engine/src/test/resources/modules/sqlite3.wasm` | `e2e/data/config/wasm-modules/sqlite3.wasm` |
| `e2e/config/wasm-cc.json` | `e2e/data/config/wasm-cc.json` |

`sqlite3.wasm` must live at `<config>/wasm-modules/sqlite3.wasm` because this
offline harness loads it locally via a `file://sqlite3` ref — `modulesDir`
(default `wasm-modules`) under the Fabric config dir, which is `/data/config` on
this server.

### Module ref forms (and the OCI option)

A module `ref` dispatches by scheme:

| Ref | Source | Config flag |
|-----|--------|-------------|
| `file://sqlite3` | local `<config>/wasm-modules/sqlite3.wasm` | — (used by this harness) |
| `http(s)://…` | downloaded + cached | `"allowUrlModules": true` |
| `oci://ghcr.io/r33drichards/sqlite:0.1.1` | anonymous OCI pull, digest-verified | `"allowOciModules": true` + `"ociRegistryAllow": ["ghcr.io"]` |

The staged [`examples/sqlite_disk.lua`](../examples/sqlite_disk.lua) and
[`examples/sqlite.lua`](../examples/sqlite.lua) default to the **`oci://`** form,
which is published by the release workflow (or `make publish-sqlite`). Until the
first `v*` tag is pushed the ghcr ref will not resolve, so for this offline
harness either (a) flip the ref to `file://sqlite3` in the copied script, or
(b) set `"allowOciModules": true` in `wasm-cc.json` once the artifact exists.

## 3. Bring up the server

```sh
./e2e/launch.sh up
```

This runs `docker compose up -d`, waits until the server is healthy (the **first**
run downloads Minecraft, Fabric, CC:Tweaked + Fabric API from Modrinth — give it a
few minutes), then prints connect instructions. CC:Tweaked and Fabric API come
from Modrinth (`MODRINTH_PROJECTS`); the wasm-cc mod is the jar you staged in
`e2e/data/mods/`.

Useful subcommands: `./e2e/launch.sh logs` (follow logs), `status` (health + check
the mod is present), `console` (server console via `rcon-cli`), `down` (stop).

### Using a published release instead of a local jar

If a GitHub Release exists, you can skip the local mod build: delete the
`e2e/data/mods/wasm-cc.jar` you staged and set `MODS` in `docker-compose.yml` to
the release asset URL (commented example is in the file):

```
MODS: "https://github.com/r33drichards/wasm-cc/releases/download/v0.1.0/wasm-cc-v0.1.0.jar"
```

## 4. Connecting (client)

We do **not** auto-launch a Minecraft client: a real client needs a display/GPU
and a Microsoft/Mojang login, so a headless "fully automated join" would be fake.
The pragmatic, genuinely-working path is to join with your own desktop client at
`localhost:25565`:

- **Vanilla launcher:** install a 1.21.8 *Fabric* profile, then *Multiplayer →
  Add Server →* `localhost:25565` *→ Join*.
- **Prism / MultiMC:** create a 1.21.8 instance with the Fabric loader and add a
  server pointing at `localhost:25565`.

The server runs in **offline mode** (`ONLINE_MODE=FALSE`) so any local client can
join without auth friction; a licensed client can still join. Set
`ONLINE_MODE=TRUE` in `docker-compose.yml` to require authentication.

(No client-side mods are needed to connect for this demo — placing and using a
Computer is driven server-side.)

## 5. Run the example in-game

1. Give yourself a Computer and place it: in chat, `/give @s computercraft:computer`
   (creative inventory has it too), place it, right-click to open the terminal.
2. Note the computer's **ID** (shown by the `id` command, or it's the folder that
   appears under `e2e/data/world/computercraft/computer/`).
3. Get `sqlite_disk.lua` onto the computer. Easiest offline way — copy it from the
   host into that computer's save dir, where it becomes a program on the computer:

   ```sh
   # replace 0 with your computer's ID
   cp examples/sqlite_disk.lua e2e/data/world/computercraft/computer/0/sqlite_disk.lua
   # this offline harness ships the module locally, so use the file:// ref:
   sed -i 's#oci://ghcr.io/r33drichards/sqlite:0.1.1#file://sqlite3#' \
     e2e/data/world/computercraft/computer/0/sqlite_disk.lua
   ```

   (Alternatively, if you enable the http cap / pastebin you could `wget`/`pastebin
   get` it, but the host copy needs no network.)
4. In the computer terminal, run it:

   ```
   sqlite_disk
   ```

   Expected output:

   ```
   opening sql/app.sqlite (on disk) ...
   wrote 3 rows; closing instance.
   re-opening a fresh instance to prove persistence ...
   Bob     25
   Alice   30
   Charlie 35
   ok: data persisted to sql/app.sqlite on the computer's disk.
   ```

   The script writes rows, **fully tears down the wasm instance**, then opens a
   brand-new instance and reads the rows back — proving the data round-tripped
   through a file on disk, not just in-memory state.

## 6. Inspect the on-disk database on the host

The database is a real file under the bind-mounted world:

```sh
# replace 0 with your computer's ID
ls -l   e2e/data/world/computercraft/computer/0/sql/app.sqlite
sqlite3 e2e/data/world/computercraft/computer/0/sql/app.sqlite \
  'SELECT name, age FROM users ORDER BY age;'
```

Seeing the three rows from `sqlite3` on the host confirms the full loop: Lua →
`wasm` global → `sqlite3.wasm` → WASI `fs` cap → a file on the computer's real
save dir.

## How the on-disk path maps (engine source of truth)

`examples/sqlite_disk.lua` instantiates with `{ caps = { "wasi", "fs" }, fs = "sql" }`
and opens `"/app.sqlite"`. The mapping from that guest path to a host file is fixed
by the engine:

- `mod/.../WasmLuaAPI.java` — `resolveFs(sub)` + `computerRoot()` resolve the `fs`
  sub-dir to `<world>/computercraft/computer/<id>/<sub>` (here `<id>/sql`), confined
  to the computer's own save dir.
- `engine/.../caps/Caps.java` — `Caps.assemble` mounts that directory at the guest
  root with `WasiOptions.builder().withDirectory("/", cfg.fsDir())`.
- `engine/.../FsCapTest.java` — confirms the mapping: with the dir mounted at `/`,
  guest path `/out.txt` is the host file `disk.resolve("out.txt")`.

So guest `/app.sqlite` is the host file `<id>/sql/app.sqlite`, which on this server
is `e2e/data/world/computercraft/computer/<id>/sql/app.sqlite`.

## Files

```
e2e/
  docker-compose.yml   # MC 1.21.8 + Fabric + CC:Tweaked + wasm-cc, ./data:/data
  setup.sh             # stage mod jar + sqlite3.wasm + config into ./data
  launch.sh            # up / status / logs / console / down
  config/wasm-cc.json  # committed mod config template (copied into ./data/config)
  README.md            # this file
  data/                # runtime (gitignored): world, mods, config, the .sqlite
```
