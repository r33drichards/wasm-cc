# Explanation

Understanding-oriented discussion. These pages explain *why* wasm-cc is built the
way it is — background and design rationale rather than steps or signatures.

- **[Two execution modes](execution-modes.md)** — why there are both a synchronous
  raw API and an off-thread command runner, and how each relates to Chicory's lack
  of mid-call yield.
- **[Capability packs & auto-stub](capability-packs.md)** — how a module's imports
  are served, and why unprovided imports are stubbed instead of rejected.
- **[Lineage (mcp-v8 & picat-cc)](lineage.md)** — where wasm-cc comes from and how
  it relates to its siblings.
