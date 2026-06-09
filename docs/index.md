# wasm-cc

Run **arbitrary WebAssembly modules from ComputerCraft**. `wasm-cc` is a
[Fabric](https://fabricmc.net/) mod that gives every CC computer a `wasm` global
exposing the **raw WebAssembly API** — compile a module, instantiate it, then call
its exports and read/write its linear memory directly — over the
[Chicory](https://chicory.dev) pure-JVM runtime (no native libraries, no JNI).

It is the in-game sibling of the V8-based
[mcp-v8](https://github.com/r33drichards/mcp-v8): **the same `wasm32` module runs
in both**, so you write a module once and drive it from JavaScript (mcp-v8) or Lua
(here).

A module's imports are served by **Java capability packs**, backed by the same
subsystems CC itself uses:

| Need | Served by |
|------|-----------|
| in-memory compute (sqlite, parsers, codecs) | WASI Preview 1 stubs |
| read/write a file on the computer's disk | WASI `fs`, mounted on the computer's real save dir |
| networking | an `http` import, gated by an allowlist (CC's http policy) |

## How this documentation is organized

These docs follow the [Diátaxis](https://diataxis.fr/) framework, which splits
documentation into four kinds, each serving a distinct need. Pick the quadrant
that matches what you are trying to do right now:

<div class="grid cards" markdown>

-   :material-school: **[Tutorials](tutorials/index.md)**

    ---

    *Learning-oriented.* Start here if you are new. Hands-on lessons that take you
    from zero to a running module: build the mod, drop it into a server, run your
    first `wasm` snippet, and walk through a SQLite example end to end.

-   :material-wrench: **[How-to guides](how-to/index.md)**

    ---

    *Task-oriented.* Recipes for specific goals: run a module against a computer's
    disk with the `fs` cap, allowlist an `http` host, chain MP3→MIDI, or add your
    own `.wasm` module.

-   :material-book-open-variant: **[Reference](reference/index.md)**

    ---

    *Information-oriented.* The complete, authoritative `wasm.*` Lua API, the
    `opts` table, capabilities, the `wasm-cc.json` config, build/Make targets, and
    the project layout.

-   :material-lightbulb-on: **[Explanation](explanation/index.md)**

    ---

    *Understanding-oriented.* The why behind the design: the two execution modes
    (Chicory has no mid-call yield), capability packs and resilient auto-stub
    imports, and the lineage from mcp-v8 and picat-cc.

</div>

## Where to start

- Never used wasm-cc before? → **[Getting started](tutorials/getting-started.md)**
- Want the exact signature of a `wasm.*` function? → **[Lua API reference](reference/lua-api.md)**
- Trying to accomplish one specific thing? → **[How-to guides](how-to/index.md)**
- Want to understand *why* there are two execution modes? → **[Two execution modes](explanation/execution-modes.md)**
