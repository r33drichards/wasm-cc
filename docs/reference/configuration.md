# Configuration (`wasm-cc.json`)

On first run the mod writes `wasm-cc.json` to the Fabric **config directory** with
default values. If the file is missing, empty, or unparseable, defaults are used
(and a warning is logged) so a corrupt config never blocks startup. Deserialized
values are clamped into sane ranges.

Derived from `mod/src/main/java/cc/wasmcc/mod/WasmCcConfig.java`.

## Fields

### Mode-B off-thread runner

| Field | Type | Default | Minimum | Description |
|-------|------|---------|---------|-------------|
| `workerThreads` | int | `6` | `4` | Worker pool size — concurrent in-flight mode-B runs, process-wide. |
| `maxAbandonedJobs` | int | `32` | `16` | Reject new work once this many timed-out "zombie" runs are outstanding. |
| `maxTimeoutSeconds` | int | `300` | `1` | Hard cap (seconds) on any single mode-B run's `timeout`. |
| `maxJobsPerComputer` | int | `8` | `2` | How many mode-B runs a single computer may have in flight at once. |

### Mode-A raw API

| Field | Type | Default | Minimum | Description |
|-------|------|---------|---------|-------------|
| `maxInstancesPerComputer` | int | `16` | `1` | How many live mode-A instances a single computer may hold open at once. |

### Module sourcing / limits

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `modulesDir` | string | `"wasm-modules"` | Directory (under the config dir) of local modules, looked up as `<name>.wasm` for `file://<name>` refs. |
| `allowUrlModules` | boolean | `false` | Allow `http(s)://` module references (downloaded and cached). |
| `allowOciModules` | boolean | `false` | Allow `oci://` (and no-scheme `<registry>/<repo>:<tag>`) module references — anonymous, digest-verified pulls from an OCI registry. |
| `ociRegistryAllow` | array of strings | `[]` | Allowlist (host / `host:port` patterns, same matcher as `httpAllow`) of OCI registries. Empty denies all. e.g. `["ghcr.io", "*.ghcr.io"]`. |
| `maxModuleBytes` | long | `67108864` (64 MiB) | Max module size in bytes — local lookups, URL downloads, and OCI blobs (minimum `1024`). |

### Capabilities

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `defaultCaps` | array of strings | `["wasi"]` | Capabilities granted when a caller omits [`opts.caps`](opts.md). |
| `httpAllow` | array of strings | `[]` | Allowlist (host / `host:port` patterns) for the `http` cap. Empty denies all. See [Capabilities](capabilities.md). |

## Example

```json
{
  "workerThreads": 6,
  "maxAbandonedJobs": 32,
  "maxTimeoutSeconds": 300,
  "maxJobsPerComputer": 8,
  "maxInstancesPerComputer": 16,
  "modulesDir": "wasm-modules",
  "allowUrlModules": false,
  "allowOciModules": false,
  "ociRegistryAllow": ["ghcr.io"],
  "maxModuleBytes": 67108864,
  "defaultCaps": ["wasi"],
  "httpAllow": ["api.example.com", "*.example.com"]
}
```

Module `ref`s dispatch by scheme — `file://<name>` (local), `http(s)://…`
(needs `allowUrlModules`), and `oci://<registry>/<repo>:<tag>` (needs
`allowOciModules` + the registry in `ociRegistryAllow`). See
[Module references](lua-api.md#module-references).

## Related directories

Alongside `wasm-cc.json`, under the config dir:

- `wasm-modules/` (configurable via `modulesDir`) — your named `.wasm` modules.
- `wasm-cache/` — the on-disk cache for URL downloads and OCI blobs (the latter
  keyed by content digest; `oci-<digest>.wasm`).
