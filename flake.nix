{
  description = "Coords - private location sharing";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-25.11";
  };

  outputs = { self, nixpkgs }:
  let
    systems = [ "x86_64-linux" "aarch64-linux" "x86_64-darwin" "aarch64-darwin" ];
    forAllSystems = f: nixpkgs.lib.genAttrs systems (system: f system);
  in {
    packages = forAllSystems (system:
      let
        pkgs = nixpkgs.legacyPackages.${system};
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
        pkgs = nixpkgs.legacyPackages.${system};
      in {
        default = pkgs.mkShell {
          buildInputs = with pkgs; [
            rustc
            cargo
            rust-analyzer
            nodejs_24
            git
            jq
          ];
        };
      }
    );
  };
}
