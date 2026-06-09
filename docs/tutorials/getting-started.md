# Getting started

By the end of this tutorial you will have built the wasm-cc mod, installed it into
a Minecraft Fabric server next to CC:Tweaked, and run your very first WebAssembly
call from a ComputerCraft computer.

This is a *learning* exercise — every step is spelled out. For terse, goal-focused
recipes see the [How-to guides](../how-to/index.md).

## What you need

- A working [Nix](https://nixos.org/) install (the project uses Nix to provide the
  whole toolchain — JDK 21, Gradle, and wasi-sdk 25 — so you do not install those
  separately).
- A Minecraft **1.21.8** Fabric server.
- The [CC:Tweaked](https://tweaked.cc/) mod for that server.

## 1. Get the source

```sh
git clone https://github.com/r33drichards/wasm-cc
cd wasm-cc
```

## 2. Build the mod jar

The toolchain lives in the Nix dev shell, so prefix build commands with
`nix develop -c`:

```sh
nix develop -c ./gradlew :mod:build
```

The first run is slow: Gradle's Loom plugin downloads Minecraft, the mappings, and
CC:Tweaked. When it finishes you will have a jar under:

```
mod/build/libs/*.jar
```

!!! tip "Prefer a prebuilt jar?"
    Each tagged release publishes the jar as a GitHub Release asset. You can point
    [itzg/docker-minecraft-server](https://github.com/itzg/docker-minecraft-server)
    straight at it:

    ```
    MODS=https://github.com/r33drichards/wasm-cc/releases/download/v0.1.0/wasm-cc-v0.1.0.jar
    ```

## 3. Install into a server

Copy two jars into your server's `mods/` directory:

1. the wasm-cc jar from `mod/build/libs/`, and
2. the CC:Tweaked jar.

Start the server once. wasm-cc writes a default config file to
`<config>/wasm-cc.json` and creates a `<config>/wasm-modules/` directory — this is
where named `.wasm` modules live. (See the
[configuration reference](../reference/configuration.md) for every option.)

## 4. Run your first wasm call

Place a Computer in the world, open it, and create a program:

```lua title="hello.lua"
print("wasm-cc version: " .. wasm.version())
```

Run it:

```
> hello
wasm-cc version: 0.1.0
```

`wasm.version()` is the simplest function in the API — it needs no module and
proves the `wasm` global is present.

## 5. Call into an actual module

Now let's instantiate a real module and call one of its exports. The project ships
tiny test fixtures; the `spike` fixture exports an `add` function. Build the
fixtures and copy `spike.wasm` into your server's `wasm-modules/` directory:

```sh
nix develop -c make resources
# then copy engine/src/test/resources/modules/spike.wasm
# into <config>/wasm-modules/spike.wasm
```

Back on the computer:

```lua title="add.lua"
-- Instantiate the local module (file://<name> looks up wasm-modules/<name>.wasm).
local h = wasm.instantiate("file://spike", { caps = { "wasi" } })

-- Call an export. wasm.call returns the first result as an integer.
local sum = wasm.call(h, "add", 2, 3)
print("2 + 3 = " .. sum)   --> 2 + 3 = 5

-- Always close a mode-A instance when you are done with it.
wasm.close(h)
```

Run it:

```
> add
2 + 3 = 5
```

You just compiled a WebAssembly module, instantiated it on the JVM through
Chicory, called one of its exported functions, and read the result back into Lua —
all from inside ComputerCraft.

## What you learned

- The `wasm` global is available on every CC computer.
- `wasm.instantiate(ref, opts)` returns an integer **handle** (this is **mode A**,
  the synchronous raw API).
- `wasm.call(handle, name, ...)` invokes an export and returns its first result.
- You must `wasm.close(handle)` to free a mode-A instance.

## Next steps

- **[SQLite walkthrough](sqlite-walkthrough.md)** — a richer mode-A example that
  reads and writes the module's linear memory.
- **[Two execution modes](../explanation/execution-modes.md)** — when to use
  mode A (synchronous) vs. mode B (`wasm.run`, off-thread).
- **[Lua API reference](../reference/lua-api.md)** — every `wasm.*` function.
