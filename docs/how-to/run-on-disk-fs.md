# Run a module on disk with the `fs` cap

**Goal:** run a command module that reads and writes real files on the computer's
disk, without blocking the server tick.

This uses **mode B** (`wasm.run`), which runs the module off-thread on a worker
pool and yields the Lua coroutine until it exits. The `fs` capability mounts a
sub-directory of the computer's own save directory at the guest's `/`.

## Steps

1. Put your command module in `<config>/wasm-modules/<name>.wasm` (see
   [Add your own wasm module](add-your-own-module.md)).

2. Put the input file in a sub-folder of the computer's disk — say `media/`. With
   `fs = "media"`, that folder becomes the guest's root `/`.

3. Call `wasm.run` with the `fs` cap and an `args` (argv) array:

```lua
local ok, res = wasm.run("mp3dec", {
  args    = { "mp3dec", "/song.mp3", "/song.wav" }, -- argv; "/" is the media mount
  fs      = "media",                                -- mount <computer>/media at "/"
  timeout = 30,                                     -- seconds
})
if not ok then error("decode failed: " .. tostring(res)) end
print(("exit %d  %s"):format(res.exit, res.stdout))
```

4. Read the output back with ComputerCraft's normal `fs` API — the file the module
   wrote to `/song.wav` lands at `media/song.wav` on the computer:

```lua
local f = fs.open("media/song.wav", "rb")
local wav = f.readAll(); f.close()
print(("wrote %d bytes of WAV"):format(#wav))
```

## What you get back

`wasm.run` returns `ok, result`:

- On success, `ok` is `true` and `result` is a table `{ exit, stdout, stderr }`.
- On failure, `ok` is `false` and `result` is an error string (for example
  `"busy: ..."`, `"timeout"`, or a module error).

## Notes

- The `fs` path is confined to the computer's own save directory. Absolute paths,
  `..` traversal, and command (admin) computers are rejected.
- The directory you name is created automatically if it does not exist.
- `timeout` is in seconds and is clamped by the server's `maxTimeoutSeconds`
  ([config](../reference/configuration.md)).

## See also

- [`decode.lua`](https://github.com/r33drichards/wasm-cc/blob/master/examples/decode.lua)
  — the runnable version of this guide.
- [Two execution modes](../explanation/execution-modes.md) — why disk/network work
  belongs in mode B.
- [Convert MP3 to MIDI](mp3-to-midi.md) — chaining two `fs`-cap modules.
