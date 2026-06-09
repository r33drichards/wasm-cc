# Builds the WASM test fixtures consumed by :engine:test, into the engine test
# resources. Requires the nix devshell (WASI_SDK_PATH, curl, unzip):
#
#   nix develop -c make resources
#
# `resources` builds the tiny C spike fixture and the SQLite shell/library; the
# heavy ffmpeg module is NOT built here (it is built once and fetched by URL in
# the integration test — see the ffmpeg task).

WASI_SDK ?= $(WASI_SDK_PATH)
CLANG     = $(WASI_SDK)/bin/clang
TESTRES   = engine/src/test/resources/modules
THIRD     = third_party
SQLITE_VER = 3460100
SQLITE_URL = https://www.sqlite.org/2024/sqlite-amalgamation-$(SQLITE_VER).zip

DRMP3_URL = https://raw.githubusercontent.com/mackron/dr_libs/da35f9d6c7374a95353fd1df1d394d44ab66cf01/dr_mp3.h

.PHONY: resources fixtures sqlite mp3 clean e2e-setup publish-sqlite

resources: fixtures sqlite mp3

# --- publish the sqlite wasm module to an OCI registry --------------------
# Push the built sqlite3.wasm to ghcr.io as a single application/wasm layer (the
# media type OciResolver selects), so `wasm.instantiate("oci://ghcr.io/...")`
# resolves. Requires `oras` and a prior `oras login ghcr.io`. The release
# workflow runs this automatically on a `v*` tag.
#
#   nix develop -c make publish-sqlite                 # -> :0.1.0
#   nix develop -c make publish-sqlite OCI_TAG=0.2.0   # custom tag
OCI_REPO ?= ghcr.io/r33drichards/sqlite
OCI_TAG  ?= 0.1.0
publish-sqlite: $(TESTRES)/sqlite3.wasm
	cd $(TESTRES) && oras push $(OCI_REPO):$(OCI_TAG) sqlite3.wasm:application/wasm

# --- e2e harness ----------------------------------------------------------
# Stage the built mod jar + sqlite3.wasm + config into e2e/data so the docker
# compose server (see e2e/README.md) can load them. Build the inputs first:
#   nix develop -c ./gradlew :mod:build   &&   nix develop -c make resources
e2e-setup:
	bash e2e/setup.sh

# --- tiny fixtures (raw-API spike + fs-cap command + auto-stub + http) ----
fixtures: $(TESTRES)/spike.wasm $(TESTRES)/copy.wasm $(TESTRES)/stub.wasm $(TESTRES)/httpget.wasm

$(TESTRES)/spike.wasm: fixtures/spike.c | $(TESTRES)
	$(CLANG) --target=wasm32-wasi -mexec-model=reactor -O2 \
	  -Wl,--export=add -Wl,--export=sum_bytes -Wl,--export=make_ramp \
	  -Wl,--export=malloc -Wl,--export=free \
	  -o $@ $<

$(TESTRES)/copy.wasm: fixtures/copy.c | $(TESTRES)
	$(CLANG) --target=wasm32-wasi -O2 -o $@ $<

# Imports unprovided env.* functions; --allow-undefined keeps them as imports
# (instead of a link error) so the engine's auto-stub can satisfy them.
$(TESTRES)/stub.wasm: fixtures/stub.c | $(TESTRES)
	$(CLANG) --target=wasm32-wasi -mexec-model=reactor -O2 \
	  -Wl,--allow-undefined \
	  -Wl,--export=trigger -Wl,--export=call_answer \
	  -o $@ $<

$(TESTRES)/httpget.wasm: fixtures/httpget.c | $(TESTRES)
	$(CLANG) --target=wasm32-wasi -mexec-model=reactor -O2 \
	  -Wl,--allow-undefined \
	  -Wl,--export=do_get -Wl,--export=malloc -Wl,--export=free \
	  -o $@ $<

# --- sqlite as a wasm32-wasi reactor library ------------------------------
sqlite: $(TESTRES)/sqlite3.wasm

$(THIRD)/sqlite/sqlite3.c:
	mkdir -p $(THIRD)
	curl -fsSL -o $(THIRD)/sqlite.zip $(SQLITE_URL)
	cd $(THIRD) && unzip -oq sqlite.zip && rm -rf sqlite && mv sqlite-amalgamation-$(SQLITE_VER) sqlite

$(TESTRES)/sqlite3.wasm: $(THIRD)/sqlite/sqlite3.c | $(TESTRES)
	$(CLANG) --target=wasm32-wasi -mexec-model=reactor -O2 \
	  -D_WASI_EMULATED_PROCESS_CLOCKS -D_WASI_EMULATED_SIGNAL -D_WASI_EMULATED_MMAN \
	  -DSQLITE_THREADSAFE=0 -DSQLITE_OMIT_LOAD_EXTENSION -DSQLITE_DISABLE_LFS \
	  -DSQLITE_OMIT_WAL -DSQLITE_OMIT_DEPRECATED \
	  -I$(THIRD)/sqlite \
	  -Wl,--export=malloc -Wl,--export=free \
	  -Wl,--export=sqlite3_open -Wl,--export=sqlite3_close \
	  -Wl,--export=sqlite3_errmsg -Wl,--export=sqlite3_exec \
	  -Wl,--export=sqlite3_prepare_v2 -Wl,--export=sqlite3_step \
	  -Wl,--export=sqlite3_finalize -Wl,--export=sqlite3_column_count \
	  -Wl,--export=sqlite3_column_name -Wl,--export=sqlite3_column_type \
	  -Wl,--export=sqlite3_column_int -Wl,--export=sqlite3_column_double \
	  -Wl,--export=sqlite3_column_text -Wl,--export=sqlite3_changes \
	  -Wl,--export=sqlite3_last_insert_rowid \
	  -lwasi-emulated-process-clocks -lwasi-emulated-signal -lwasi-emulated-mman \
	  -o $@ $(THIRD)/sqlite/sqlite3.c

# --- mp3 decoder (dr_mp3) + a synthesized sample.mp3 ----------------------
# The sanctioned "exercise ffmpeg" fallback: a real MP3 decoder, plus a sample
# MP3 synthesized with sox (sine -> WAV) + lame (WAV -> MP3) so the IT is
# self-contained (no committed binary, no network).
mp3: $(TESTRES)/mp3dec.wasm $(TESTRES)/sample.mp3

$(THIRD)/dr_mp3.h:
	mkdir -p $(THIRD)
	curl -fsSL -o $@ $(DRMP3_URL)

$(TESTRES)/mp3dec.wasm: fixtures/mp3dec.c $(THIRD)/dr_mp3.h | $(TESTRES)
	$(CLANG) --target=wasm32-wasi -O2 -I$(THIRD) -o $@ fixtures/mp3dec.c -lm

$(TESTRES)/sample.mp3: | $(TESTRES)
	mkdir -p $(THIRD)
	sox -n -r 16000 -c 1 $(THIRD)/sample.wav synth 0.30 sine 440
	lame --quiet -b 64 $(THIRD)/sample.wav $@

$(TESTRES):
	mkdir -p $(TESTRES)

clean:
	rm -f $(TESTRES)/*.wasm $(TESTRES)/*.mp3
	rm -rf $(THIRD)
