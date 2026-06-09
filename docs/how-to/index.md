# How-to guides

Task-oriented recipes. Each one assumes you already know the basics (work through
the [Tutorials](../tutorials/index.md) first if not) and gets you to a specific
goal with minimal ceremony.

- **[Run a module on disk (fs cap)](run-on-disk-fs.md)** — give a command module
  access to the computer's real files and run it off-thread.
- **[Allowlist an http host](allowlist-http-host.md)** — let a module make network
  requests through the gated `http` capability.
- **[Convert MP3 to MIDI](mp3-to-midi.md)** — chain two modules over the
  computer's disk and play the result on a speaker.
- **[Add your own wasm module](add-your-own-module.md)** — compile your own
  `.wasm` and make it available to computers.

For exact signatures and option semantics, see the
[Reference](../reference/index.md).
