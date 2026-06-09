# Two execution modes

wasm-cc gives you two ways to run a module: a synchronous **raw API** (mode A) and
an off-thread **command run** (mode B). Understanding why both exist — and which to
reach for — comes down to a single fact about the runtime.

## The constraint: WASM calls run to completion

wasm-cc runs modules on [Chicory](https://chicory.dev), a pure-JVM WebAssembly
runtime. A WebAssembly call on Chicory runs **to completion on its thread** — there
is no mechanism to suspend a guest mid-call and hand control back to the host
("mid-call yield"). Whatever thread enters the module owns it until it returns.

ComputerCraft, meanwhile, runs each computer's Lua on a shared computer thread that
the server tick depends on. If you block that thread for a long time, you stall the
game. So the question for any wasm work is: *can it block the computer thread, or
must it run elsewhere?* That split is exactly the two modes.

## Mode A — raw API (synchronous, on the computer thread)

`wasm.instantiate` returns an integer handle, and `wasm.call` / `wasm.callf` plus
the memory helpers (`alloc*`, `read*`, `write*`) drive the module **directly on the
computer thread**. This is the Lua analogue of the mcp-v8 JavaScript example: you
poke the module's exports and linear memory in fine-grained, chatty steps.

Because each call runs to completion inline, mode A is ideal for **fast, chatty
work**: stepping a SQLite cursor, munging a buffer, running a parser or codec over
in-memory data. The round-trip cost is tiny and you keep full control of memory.

The flip side is the constraint above: a *slow* mode-A call blocks the computer for
its entire duration. That's fine for microsecond-scale calls; it's not fine for
decoding an audio file or making a network request. Those belong in mode B.

## Mode B — command run (off-thread, yields)

`wasm.run` is for **run-to-completion command modules** — programs with a `_start`
that do their work and exit. Instead of running inline, `run` submits the job to an
off-thread **worker pool** and **yields the Lua coroutine** until the module
finishes, then resumes it with `{ exit, stdout, stderr }`.

This sidesteps the constraint without violating it: the guest still runs to
completion on *a* thread, just not the computer thread. The computer's Lua is
suspended (yielded), so the server tick is never blocked, even for a long decode or
a slow HTTP request. Output files land on the computer's disk through the `fs`
[capability](../reference/capabilities.md).

Under the hood, `run` uses CC's standard event plumbing: it queues a `wasm_done`
event when the worker completes and resumes the waiting coroutine — so from Lua it
looks like an ordinary blocking call that returns when the module is done. This
yield/token machinery, along with a timeout-by-abandonment guard and a saturation
cap, is ported from picat-cc (see [Lineage](lineage.md)).

## Choosing a mode

| If your work is… | Use | Why |
|------------------|-----|-----|
| fast, fine-grained, in-memory (sqlite, parsing, codecs you drive call-by-call) | **mode A** (`instantiate` + `call`) | minimal overhead, direct memory access |
| a whole program that runs a while, touches files, or does I/O | **mode B** (`run`) | won't block the tick; yields until done |
| **networked** (the `http` cap) | **mode B** | the HTTP GET is synchronous and would block the computer thread in mode A |
| heavy CPU (audio/video transcode, big computations) | **mode B** | a long call must not own the computer thread |

The rule of thumb: **if a call could take more than a tick, run it off-thread.**

## Guard rails

Both modes are bounded so a single computer can't exhaust the server:

- Mode A: `maxInstancesPerComputer` caps live handles.
- Mode B: `maxJobsPerComputer` caps in-flight runs, a global `workerThreads` pool
  bounds total concurrency, `maxTimeoutSeconds` caps any single run, and
  `maxAbandonedJobs` sheds load when too many timed-out runs are outstanding.

See the [configuration reference](../reference/configuration.md) for the knobs.
