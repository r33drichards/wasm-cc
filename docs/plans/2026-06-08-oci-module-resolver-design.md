# OCI module resolution for wasm-cc

**Date:** 2026-06-08
**Status:** Approved design, pending implementation

## Summary

Let `wasm.instantiate` / `wasm.run` load a module straight from an **OCI
registry** (e.g. `ghcr.io`, Docker Hub) by reference, in addition to the
existing local and `http(s)://` sources. OCI references are **content-addressed**,
so the bytes are verified against their digest — a real integrity upgrade over
trusting a plain URL. The whole client is pure `java.net.http` + Gson (already on
the engine classpath), in keeping with the project's "no native libraries" ethos.

Scope is deliberately small: **anonymous public pulls only**. No credential
storage, no private repositories, no manifest indexes / signature verification /
push. Those can come later behind the same config flag.

## Reference grammar & dispatch (greenfield — breaks the old bare-name contract)

`ModuleResolver.resolve(ref)` dispatches purely on scheme. The previous rule
("anything without a scheme is a name in `modulesDir`") is replaced:

| Ref form | Resolves to |
|----------|-------------|
| `oci://<registry>/<repo>:<tag>` or `…@sha256:<digest>` | OCI pull (explicit) |
| `http://…` / `https://…` | URL download (unchanged) |
| `file://<name>` | local module `<modulesDir>/<name>.wasm`, path-traversal guarded |
| no scheme, e.g. `ghcr.io/owner/repo:tag` | **OCI pull** (sugar for `oci://`) |

So `oci://ghcr.io/r33drichards/sqlite:0.1.0` and the bare
`ghcr.io/r33drichards/sqlite:0.1.0` are equivalent. **Local files are always
explicit via `file://`.** A no-scheme ref that doesn't parse as a valid
`registry/repo[:tag|@digest]` fails with an error pointing at `file://`.

### Breaking-change blast radius (intentional)

Every existing bare-name call site migrates to `file://`:

- `examples/sqlite.lua`, `examples/sqlite_disk.lua`
- README Lua snippets
- the merged MkDocs pages (tutorials/reference/how-to) using bare names
- the `e2e/` guide and any staged Lua
- `WasmLuaAPI` / docs that describe the `ref` contract

## Fetch flow (`OciResolver`, in the `engine` module)

Pure HTTPS + JSON; lives next to `ModuleResolver` so it is unit-testable with no
Minecraft types.

1. `GET https://<registry>/v2/<repo>/manifests/<ref>` with
   `Accept: application/vnd.oci.image.manifest.v1+json,
   application/vnd.docker.distribution.manifest.v2+json`.
2. On `401`, parse `WWW-Authenticate: Bearer realm=…,service=…,scope=…`, `GET`
   the realm for an **anonymous** bearer token, retry the manifest once with
   `Authorization: Bearer <token>`.
3. Parse the manifest (Gson). Select the **wasm layer**: the layer whose
   `mediaType` is wasm-ish (`application/wasm`, `*+wasm`, contains `wasm`); if
   exactly one layer, take it.
4. `GET https://<registry>/v2/<repo>/blobs/<digest>` for that layer. Follow the
   CDN redirect, **stripping `Authorization` on a cross-origin redirect** (the
   redirect target is presigned).
5. **Verify** `sha256(bytes) == layer digest`. Mismatch → hard `IOException`.

Reuses the existing `maxBytes` cap (applied while streaming the blob) and the
on-disk cache, now keyed by the resolved **content digest** (immutable, so a tag
re-resolves cheaply but bytes are fetched once).

## Config (`WasmCcConfig`)

Mirrors the existing `httpAllow` / `allowUrlModules` pattern:

```jsonc
{
  "allowOciModules": false,                 // master switch, default off
  "ociRegistryAllow": ["ghcr.io", "*.ghcr.io"] // host patterns; empty = deny all
}
```

- `file://` local lookups stay governed by the existing `modulesDir`.
- `http(s)://` downloads stay gated by `allowUrlModules`.
- The registry-host allowlist uses the same matcher as `httpAllow`.

## Errors

All surface as `IOException` → Lua error string:

- registry host not in `ociRegistryAllow`
- `allowOciModules` is false
- manifest `404` / `MANIFEST_UNKNOWN`
- no wasm layer in the manifest
- **digest mismatch** (hard fail)
- blob exceeds `maxModuleBytes`
- anonymous token fetch failed

## Testing

`engine` is host-neutral, so the OCI path is tested in-process:

- Unit tests spin a tiny JDK `com.sun.net.httpserver.HttpServer` stubbing the
  `/v2/` manifest + blob + token endpoints. Cases: happy path (tag pin and
  `@sha256` digest pin), anonymous token (`401` → token → retry), digest-mismatch
  rejection, wasm-layer selection among multiple layers, allowlist denial,
  `allowOciModules` off, size-cap exceeded, cross-origin redirect drops auth.
- One **opt-in integration test** against real public `ghcr.io` (env-gated, like
  the existing ffmpeg IT) pulling a known small wasm artifact.

## Out of scope (YAGNI for now)

Private-registry credentials, docker `config.json` discovery, manifest indexes /
multi-platform selection, cosign/referrers signature verification, push/publish.
All can be added later behind `allowOciModules` without changing the ref grammar.
