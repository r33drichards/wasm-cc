{
  description = "wasm-cc: run arbitrary WASM modules from ComputerCraft (raw WebAssembly API over Chicory)";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-25.05";

  outputs = { self, nixpkgs }:
    let
      systems = [ "aarch64-darwin" "x86_64-linux" ];
      forAll = f: nixpkgs.lib.genAttrs systems (s: f s);
      wasiArch = {
        "aarch64-darwin" = "arm64-macos";
        "x86_64-linux" = "x86_64-linux";
      };
      # Same wasi-sdk 25 pin as picat-cc. Fill the linux hash on first CI run.
      wasiHash = {
        "aarch64-darwin" = "sha256-4eUp6iJrHbC0MDJ4Cd6ukka1gPo8rjLTHILf53AjNYc=";
        "x86_64-linux" = "sha256-UmQN3hNZm/EnqVSZ5h1tZAJWEZRW0a+Il6tnJbzz2Jw=";
      };
    in {
      devShells = forAll (system:
        let
          pkgs = import nixpkgs { inherit system; };
          wasiSdk = pkgs.stdenvNoCC.mkDerivation rec {
            pname = "wasi-sdk";
            version = "25.0";
            src = pkgs.fetchurl {
              url = "https://github.com/WebAssembly/wasi-sdk/releases/download/wasi-sdk-25/wasi-sdk-25.0-${wasiArch.${system}}.tar.gz";
              hash = wasiHash.${system};
            };
            dontStrip = true;
            dontPatchShebangs = true;
            nativeBuildInputs = pkgs.lib.optionals pkgs.stdenv.isLinux
              [ pkgs.autoPatchelfHook ];
            buildInputs = pkgs.lib.optionals pkgs.stdenv.isLinux
              [ pkgs.stdenv.cc.cc.lib pkgs.zlib ];
            installPhase = ''
              mkdir -p $out
              cp -r . $out/
            '';
          };
          # python3 + mkdocs-material build the documentation site (docs/, mkdocs.yml).
          docsEnv = pkgs.python3.withPackages (ps: [ ps.mkdocs-material ]);
        in {
          default = pkgs.mkShell {
            # jdk21 + gradle build the engine/mod; make/curl/unzip/wabt build the
            # wasm test fixtures (tiny C fixtures, sqlite shell, dr_mp3 decoder) via
            # wasi-sdk; sox+lame synthesize the sample.mp3 the decode IT consumes.
            packages = [ pkgs.jdk21 pkgs.gradle pkgs.gnumake pkgs.curl pkgs.unzip
                         pkgs.wabt pkgs.sox pkgs.lame ];
            WASI_SDK_PATH = "${wasiSdk}";
            JAVA_HOME = "${pkgs.jdk21}";
          };

          # The MkDocs (Material) documentation toolchain. Build/preview the site:
          #   nix develop .#docs -c mkdocs serve
          #   nix develop .#docs -c mkdocs build --strict
          docs = pkgs.mkShell {
            packages = [ docsEnv ];
          };
        });
    };
}
