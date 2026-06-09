# Build & Make targets

wasm-cc uses **Nix** for the whole toolchain (JDK 21, Gradle, and wasi-sdk 25), so
you do not install those separately. Prefix commands with `nix develop -c` to run
them inside the dev shell.

## Gradle (the mod and engine)

| Command | Builds |
|---------|--------|
| `nix develop -c ./gradlew :engine:test` | Engine tests (fast; the host-neutral runtime). |
| `nix develop -c ./gradlew :mod:build` | The Fabric mod jar (Loom downloads Minecraft + CC:Tweaked on first run). |

The mod jar lands in `mod/build/libs/*.jar`. Drop it (and CC:Tweaked) into a 1.21.8
Fabric server's `mods/`.

## Make targets (wasm test fixtures)

The `Makefile` builds the WebAssembly fixtures consumed by the engine tests, using
wasi-sdk. It needs the Nix dev shell (for `WASI_SDK_PATH`, `curl`, `unzip`, etc.):

```sh
nix develop -c make resources
```

| Target | Builds |
|--------|--------|
| `resources` | Everything below (`fixtures` + `sqlite` + `mp3`). |
| `fixtures` | The tiny C fixtures: `spike.wasm` (raw-API), `copy.wasm` (fs-cap), `stub.wasm` (auto-stub), `httpget.wasm` (http cap). |
| `sqlite` | `sqlite3.wasm` from the SQLite amalgamation (downloaded). |
| `mp3` | `mp3dec.wasm` (the `dr_mp3` decoder) plus a `sample.mp3` synthesized with `sox` + `lame`. |
| `clean` | Remove built `.wasm`/`.mp3` and the `third_party/` source drops. |

Outputs go to `engine/src/test/resources/modules/`. Copy the ones you want
(`sqlite3.wasm`, `mp3dec.wasm`, `spike.wasm`, …) into a server's
`<config>/wasm-modules/` to use them in-game.

These fixtures double as compile examples for
[adding your own module](../how-to/add-your-own-module.md) — they show the exact
`clang --target=wasm32-wasi` flags (reactor model, `--export=`, `--allow-undefined`).

## Releases

Pushing a `v*` tag builds the mod jar in CI and publishes it as a **GitHub
Release** asset (`.github/workflows/release.yml`). The asset is a direct `.jar`
URL:

```
https://github.com/r33drichards/wasm-cc/releases/download/<tag>/wasm-cc-<tag>.jar
```

so [itzg/docker-minecraft-server](https://github.com/itzg/docker-minecraft-server)
can fetch it straight from `MODS=`:

```
MODS=https://github.com/r33drichards/wasm-cc/releases/download/v0.1.0/wasm-cc-v0.1.0.jar
```

## Building these docs

The documentation site is built with MkDocs + Material. A `docs` Nix dev shell
provides Python and the toolchain:

```sh
nix develop .#docs -c mkdocs serve          # live preview at http://127.0.0.1:8000
nix develop .#docs -c mkdocs build --strict # one-shot build (fails on warnings)
```

Without Nix, use the pinned `docs/requirements.txt`:

```sh
python3 -m venv .venv && . .venv/bin/activate
pip install -r docs/requirements.txt
mkdocs serve
```
