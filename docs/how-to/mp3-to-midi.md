# Convert an MP3 to MIDI, then play it on a speaker

**Goal:** chain two WebAssembly modules over the computer's disk to turn an MP3
into a MIDI file, then play its notes on an attached CC speaker.

This is pure compute on the wasm side (mode B, `fs` cap): the shipped `mp3dec`
decodes MP3 → WAV with a real codec (`dr_mp3`), then a `wav2midi` module you
provide transcribes WAV → MIDI. What you do with the `.mid` afterward — speaker,
disk drive, rednet — is ordinary ComputerCraft.

## 1. Set up the files

- Put `song.mp3` in the computer's `media/` folder.
- Put both modules in `<config>/wasm-modules/`: `mp3dec.wasm` (shipped — build it
  with `nix develop -c make resources`) and `wav2midi.wasm` (your own; see
  [Add your own wasm module](add-your-own-module.md)).
- Attach a speaker to the computer.

## 2. Chain the two modules

Both runs mount `media/` at `/`, so they read and write through the computer's
real disk:

```lua
local function run(mod, ...)
  local ok, res = wasm.run(mod, { args = { mod, ... }, fs = "media", timeout = 120 })
  assert(ok and res.exit == 0, mod .. " failed: " .. tostring(ok and res.stderr or res))
end

run("mp3dec",   "/song.mp3", "/song.wav")   -- decode (real codec, dr_mp3)
run("wav2midi", "/song.wav", "/song.mid")   -- transcribe WAV -> MIDI
```

## 3. Play the MIDI on the speaker

Read the `.mid` back with the normal CC `fs` API and feed its note events to the
speaker:

```lua
local f = fs.open("media/song.mid", "rb"); local midi = f.readAll(); f.close()
local speaker = peripheral.find("speaker")
for _, ev in ipairs(parseMidiNotes(midi)) do      -- your tiny MIDI reader
  speaker.playNote(ev.instrument, ev.volume, ev.pitch)
  sleep(ev.dt)
end
```

(`parseMidiNotes` is a small MIDI reader you write in Lua — the conversion modules
just produce the file; turning notes into `speaker.playNote` calls is plain CC.)

## Why mode B

Decoding and transcribing audio is heavy work. Running it off-thread with
`wasm.run` means the Lua coroutine yields until each module exits, so even a long
file never stalls the server tick. See
[Two execution modes](../explanation/execution-modes.md).

## See also

- [Run a module on disk (fs cap)](run-on-disk-fs.md)
- [Add your own wasm module](add-your-own-module.md)
