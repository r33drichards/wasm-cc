# Lineage: mcp-v8 & picat-cc

wasm-cc didn't appear in isolation. It sits between two sibling projects and
borrows deliberately from both. Knowing the relationships clarifies why certain
design choices look the way they do.

## mcp-v8 — the same module, a different host

[mcp-v8](https://github.com/r33drichards/mcp-v8) runs `wasm32` modules on the V8
JavaScript engine and drives them from JavaScript. wasm-cc is its **in-game
sibling**: it runs the **same `wasm32` module** on the Chicory JVM runtime and
drives it from Lua.

The point is portability of the *module*: you compile a module once, and it runs in
both hosts. Only the host bindings differ — JavaScript glue in mcp-v8, the `wasm`
Lua global here. Two design elements come straight from this kinship:

- **The raw WebAssembly API (mode A).** `wasm.instantiate` + `call` + the linear
  memory helpers are the Lua analogue of mcp-v8's JavaScript example — the same
  "poke the exports and memory directly" model.
- **Resilient auto-stub imports.** The trick of stubbing unprovided imports so
  off-the-shelf `STANDALONE_WASM` / Emscripten builds load unmodified is the same
  one mcp-v8 uses. See [Capability packs & auto-stub](capability-packs.md).

## picat-cc — the ComputerCraft integration it grew from

wasm-cc was **seeded from**
[picat-cc](https://github.com/r33drichards/picat-cc), an earlier project that ran a
Picat engine from ComputerCraft. The Minecraft-facing machinery is its lineage:

- **The Chicory integration** — running a guest language runtime on the pure-JVM
  Chicory WebAssembly engine, no native libraries or JNI.
- **The host-disk mount** — confining a guest's `fs` access to the CC computer's
  own save directory, with the same path-validation (no absolute paths, no `..`
  traversal, command computers rejected).
- **The concurrency model** — the off-thread worker pool, the per-computer
  in-flight cap, the timeout-by-abandonment guard, and the saturation guard that
  sheds load.
- **The Lua yield/event plumbing** — how mode B yields the coroutine and resumes it
  via a queued completion event ([Two execution modes](execution-modes.md)).

## Where that leaves wasm-cc

The result is a focused combination: mcp-v8's **portable raw-WASM module model**
running inside picat-cc's **proven ComputerCraft host**. You write a module once
(the mcp-v8 lineage), and run it safely on a live server without stalling the tick
(the picat-cc lineage).

For where each piece lives in the code, see the
[project layout](../reference/project-layout.md).
