# Capability packs & resilient auto-stub imports

A WebAssembly module is not self-contained: it declares **imports** — functions it
expects the host to provide. wasm-cc serves those imports with **Java capability
packs**, and handles imports no pack provides with an **auto-stub**. This page
explains how that assembly works and why it is designed to be forgiving.

## Imports are served by the host

When you instantiate a module, wasm-cc inspects which capabilities you requested
([`opts.caps`](../reference/opts.md)) and assembles a matching set of host
functions:

- **`wasi`** wires up Chicory's WASI Preview 1 implementation (captured
  stdout/stderr, the module's args and env).
- **`fs`** mounts a host directory at the guest's `/` — in production a sub-folder
  of the CC computer's real save directory.
- **`http`** adds a small, allowlist-gated networking import.

Each pack is "backed by the same subsystems CC itself uses": the disk mount is the
computer's actual save directory, and the `http` allowlist mirrors CC's own http
policy. So a module's powers are exactly the host's powers, gated the same way.

The full `wasm32` module is portable across hosts: the **same** module runs in
mcp-v8 (driven from JavaScript) and here (driven from Lua). Only the host bindings
differ.

## Why auto-stub unprovided imports

Real-world toolchains emit modules that import more than they strictly use.
Emscripten `STANDALONE_WASM` builds, for instance, import a pile of `env.*` helper
functions. If the host refused to instantiate a module with any unbound import,
those modules simply wouldn't load — and you'd be stuck patching each one.

wasm-cc takes the opposite stance: any function import that **no requested pack
serves** is replaced with a **zero-returning no-op stub**. The module links and
instantiates even though the host doesn't truly implement that function. If the
guest never calls it (the common case for over-declared imports), everything works;
if it does call it, it gets a harmless zero.

This is the same **resilient-import** trick mcp-v8 uses, and it is what lets
Emscripten and other off-the-shelf `STANDALONE_WASM` builds load unmodified. When
compiling your own module you may need `-Wl,--allow-undefined` so the linker keeps
unresolved symbols as imports (rather than erroring) for the stubber to satisfy —
see [Add your own wasm module](../how-to/add-your-own-module.md).

## How assembly fits together

For one instance, the host:

1. Builds the WASI options (stdout/stderr capture, args, env) and, if the `fs` cap
   is on, mounts the host directory at `/`.
2. Marks the modules it serves *natively* — always WASI, plus `http` when that cap
   is on.
3. Scans the module's declared imports and, for every function import **not** in a
   native module, adds a no-op stub.

The result is a complete import set: native packs where you asked for them, stubs
everywhere else, so instantiation always succeeds.

## The trade-off

Auto-stubbing favors **"it loads and runs"** over **"fail loudly on a missing
import."** A module that genuinely depends on an unprovided import will get silent
zeros rather than a link error, which can be surprising. In exchange you get broad
compatibility with real toolchain output and a uniform capability model where the
host — not the module — decides what powers are actually granted.

See the [capabilities reference](../reference/capabilities.md) for the concrete
surface of each pack.
