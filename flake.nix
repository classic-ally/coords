{
  description = "Coords - private location sharing";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-25.11";
    fenix = {
      url = "github:nix-community/fenix";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs = { self, nixpkgs, fenix }:
  let
    systems = [ "x86_64-linux" "aarch64-linux" "x86_64-darwin" "aarch64-darwin" ];
    forAllSystems = f: nixpkgs.lib.genAttrs systems (system: f system);
  in {
    packages = forAllSystems (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config.allowUnfree = true;
          config.android_sdk.accept_license = true;
        };

        # Rust toolchain with Android targets via fenix
        rustToolchain = fenix.packages.${system}.combine [
          fenix.packages.${system}.stable.cargo
          fenix.packages.${system}.stable.rustc
          fenix.packages.${system}.stable.rust-src
          fenix.packages.${system}.targets.aarch64-linux-android.stable.rust-std
          fenix.packages.${system}.targets.armv7-linux-androideabi.stable.rust-std
          fenix.packages.${system}.targets.x86_64-linux-android.stable.rust-std
          fenix.packages.${system}.targets.i686-linux-android.stable.rust-std
        ];

        # Android SDK with NDK for cross-compilation
        androidComposition = pkgs.androidenv.composeAndroidPackages {
          platformVersions = [ "36" ];
          buildToolsVersions = [ "35.0.0" "36.0.0" ];  # AGP 8.x needs 35
          includeNDK = true;
          ndkVersions = [ "26.3.11579264" ];
        };
        androidSdk = androidComposition.androidsdk;
      in {
        default = self.packages.${system}.coords-server;

        coords-server = pkgs.rustPlatform.buildRustPackage {
          pname = "coords-server";
          version = "2026.1.6";
          src = ./server;
          cargoLock.lockFile = ./server/Cargo.lock;

          meta = with pkgs.lib; {
            description = "Coords location relay server";
            homepage = "https://coord.is";
            license = licenses.agpl3Only;
            mainProgram = "coords-server";
          };
        };

        coords-website = pkgs.buildNpmPackage {
          pname = "coords-website";
          version = "0.0.1";
          src = ./website;
          npmDepsHash = "sha256-wzLEqWsKMmQmkYKO5M5tQbz7qsS2L1GV/1LRGw6IU48=";

          buildPhase = ''
            npm run build
          '';

          installPhase = ''
            mkdir -p $out
            cp -r dist/* $out/
          '';

          meta = with pkgs.lib; {
            description = "Coords marketing website";
            homepage = "https://coord.is";
            license = licenses.agpl3Only;
          };
        };
        # Android build toolchain (for CI)
        android-toolchain = pkgs.buildEnv {
          name = "android-toolchain";
          paths = [
            rustToolchain
            androidSdk
            pkgs.cargo-ndk
            pkgs.jdk17
            pkgs.gradle
          ];
        };

      } // pkgs.lib.optionalAttrs pkgs.stdenv.isLinux {
        # Docker image (Linux only)
        coords-docker = pkgs.dockerTools.buildLayeredImage {
          name = "coords-server";
          tag = "latest";
          contents = [ self.packages.${system}.coords-server ];
          config = {
            Cmd = [ "${self.packages.${system}.coords-server}/bin/coords-server" ];
            Env = [ "COORDS_PORT=3000" ];
            ExposedPorts = { "3000/tcp" = {}; };
          };
        };
      }
    );

    nixosModules.default = { config, lib, pkgs, ... }:
      let
        cfg = config.services.coords;
      in {
        options.services.coords = {
          enable = lib.mkEnableOption "Coords location relay server";

          port = lib.mkOption {
            type = lib.types.port;
            default = 3000;
            description = "Port for the server to listen on";
          };

          package = lib.mkOption {
            type = lib.types.package;
            default = self.packages.${pkgs.system}.coords-server;
            description = "The coords-server package to use";
          };
        };

        config = lib.mkIf cfg.enable {
          systemd.services.coords = {
            description = "Coords location relay";
            after = [ "network.target" ];
            wantedBy = [ "multi-user.target" ];

            environment.COORDS_PORT = toString cfg.port;

            serviceConfig = {
              ExecStart = "${cfg.package}/bin/coords-server";
              Restart = "on-failure";
              RestartSec = "10s";
              DynamicUser = true;

              # Hardening
              NoNewPrivileges = true;
              ProtectSystem = "strict";
              ProtectHome = true;
              PrivateTmp = true;
              PrivateDevices = true;
              ProtectKernelTunables = true;
              ProtectKernelModules = true;
              ProtectControlGroups = true;
              RestrictAddressFamilies = [ "AF_INET" "AF_INET6" ];
              RestrictNamespaces = true;
              LockPersonality = true;
              MemoryDenyWriteExecute = true;
              RestrictRealtime = true;
            };
          };

          networking.firewall.allowedTCPPorts = [ cfg.port ];
        };
      };

    devShells = forAllSystems (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config.allowUnfree = true;
          config.android_sdk.accept_license = true;
        };
        inherit (self.packages.${system}) android-toolchain;
        androidSdk = (pkgs.androidenv.composeAndroidPackages {
          platformVersions = [ "36" ];
          buildToolsVersions = [ "35.0.0" "36.0.0" ];  # AGP 8.x needs 35
          includeNDK = true;
          ndkVersions = [ "26.3.11579264" ];
        }).androidsdk;
      in {
        default = pkgs.mkShell {
          buildInputs = [
            android-toolchain
            pkgs.rust-analyzer
            pkgs.nodejs_24
            pkgs.git
            pkgs.jq
          ];

          shellHook = ''
            export ANDROID_HOME="${androidSdk}/libexec/android-sdk"
            export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/26.3.11579264"
            export JAVA_HOME="${pkgs.jdk17}"
          '';
        };
      }
    );
  };
}
