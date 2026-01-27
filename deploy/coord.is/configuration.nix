# Deploy with: nixos-rebuild switch --flake .#coord --impure
# (--impure needed because networking.nix is kept out of repo to protect origin IP)
{ config, pkgs, lib, coords, ... }:

{
  imports = [
    ./hardware-configuration.nix
    /etc/nixos/networking.nix  # Contains origin IP - do not commit
  ];

  # Basic system
  system.stateVersion = "23.11";
  networking.hostName = "coord";
  time.timeZone = "UTC";
  boot.tmp.cleanOnBoot = true;
  zramSwap.enable = true;

  # SSH access
  services.openssh = {
    enable = true;
    settings = {
      PermitRootLogin = "prohibit-password";
      PasswordAuthentication = false;
    };
  };
  users.users.root.openssh.authorizedKeys.keys = [
    "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBBW9Lx6k9nJkMpPgs6RkbntH29Z+4hoivXF/FKem2wKqS8MSd8LARENhSr6DLygZFambKzweyqvNHsGku1yxf58= allison@laptop"
    "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBKq95S3UA/S18SvkeHvBMHEK7PKNwrv0iLLpXZmOtRbR0MC91xqDIaFnG+/FcS2DiAg8s8fiG+xLiuJrU2jik2w="
  ];

  # Deploy user for CI uploads
  users.users.deploy = {
    isSystemUser = true;
    group = "deploy";
    home = "/var/lib/coords";
    createHome = true;
    shell = pkgs.bashInteractive;
    openssh.authorizedKeys.keys = [
      "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIIJfw5ZOyGYv8QR7jMVNiBozAMkihgjLpe+a6xiBKSnW coords-ci-deploy"
    ];
  };
  users.groups.deploy = {};

  # Firewall
  networking.firewall = {
    enable = true;
    allowedTCPPorts = [ 22 80 443 ];
    trustedInterfaces = [ "tailscale0" ];
  };

  # Auto-updates from flake (no auto-reboot)
  # Runs before fdroid-update (04:30) so server APIs are ready for new app
  system.autoUpgrade = {
    enable = true;
    flake = "git+https://tangled.org/bentley.sh/coords#coord";
    flags = [ "--impure" ];  # Required for /etc/nixos/networking.nix
    allowReboot = false;
    dates = "03:00";
  };

  # Timeout upgrade by 04:00 so it doesn't block fdroid-update (04:30)
  systemd.services.nixos-upgrade.serviceConfig.TimeoutStartSec = "60m";

  # Tailscale (connects to headscale)
  services.tailscale = {
    useRoutingFeatures = "server";
    enable = true;
  };

  # Coords server (from flake)
  services.coords.enable = true;

  # Secrets
  age.secrets.cf-api-token = {
    file = ./secrets/cf-api-token.age;
    owner = "caddy";
  };

  # Caddy reverse proxy with DNS-01 challenge
  services.caddy = {
    package = pkgs.caddy.withPlugins {
      plugins = [ "github.com/caddy-dns/cloudflare@v0.2.1" ];
      hash = "sha256-Zls+5kWd/JSQsmZC4SRQ/WS+pUcRolNaaI7UQoPzJA0=";
    };
    enable = true;
    virtualHosts."coord.is" = {
      extraConfig = ''
        tls {
          dns cloudflare {env.CF_API_TOKEN}
        }

        # Legacy routes - rewrite to /api prefix
        @legacy path /location /version /privacy
        handle @legacy {
          rewrite * /api{uri}
          reverse_proxy localhost:3000
        }

        # API routes
        handle /api/* {
          reverse_proxy localhost:3000
        }

        # F-Droid repo
        handle /fdroid/* {
          uri strip_prefix /fdroid
          root * /var/lib/coords/fdroid
          file_server browse
        }

        # Website
        handle {
          root * ${coords.packages.x86_64-linux.coords-website}
          file_server
        }
      '';
    };
    virtualHosts."transponder.bentley.sh" = {
      extraConfig = ''
        tls {
          dns cloudflare {env.CF_API_TOKEN}
        }

        # Legacy API routes
        @legacy path /location /version /privacy
        handle @legacy {
          rewrite * /api{uri}
          reverse_proxy localhost:3000
        }

        # API routes
        handle /api/* {
          reverse_proxy localhost:3000
        }

        handle {
          respond "transponder API" 200
        }
      '';
    };
  };

  # Caddy needs the CF API token for DNS-01 challenges
  systemd.services.caddy.serviceConfig.EnvironmentFile = config.age.secrets.cf-api-token.path;

  # F-Droid repo directories
  systemd.tmpfiles.rules = [
    "d /var/lib/coords/fdroid 0755 deploy deploy -"
    "d /var/lib/coords/fdroid/repo 0755 deploy deploy -"
    "d /var/lib/coords/fdroid/metadata 0755 deploy deploy -"
  ];

  # Fix /var/lib/coords permissions (deploy user home is created with 0700)
  system.activationScripts.coordsPermissions.text = ''
    chmod 711 /var/lib/coords
  '';

  # Service to promote staged APKs to F-Droid repo
  systemd.services.fdroid-update = {
    description = "Update F-Droid repo with staged APKs";
    after = [ "nixos-upgrade.service" ];
    wantedBy = [ "multi-user.target" ];
    startAt = "04:30";

    serviceConfig = {
      Type = "oneshot";
      User = "deploy";
      WorkingDirectory = "/var/lib/coords/fdroid";
    };

    path = [ pkgs.fdroidserver pkgs.coreutils pkgs.jdk17 ];


    script = ''
      YESTERDAY=$(date -d "yesterday" +%Y-%m-%d)
      STAGING="/var/lib/coords/staging/$YESTERDAY"

      if [ -d "$STAGING" ]; then
        for apk in "$STAGING"/*.apk; do
          name=$(basename "$apk" .apk)
          cp "$apk" "repo/''${name}-''${YESTERDAY}.apk"
        done
        fdroid update
        rm -rf "$STAGING"
        echo "Promoted $YESTERDAY build to F-Droid repo"

        # Clean up old versions, keep last 7 per architecture
        for prefix in app-arm64-v8a-release app-armeabi-v7a-release app-x86_64-release app-x86-release; do
          ls -t repo/''${prefix}-*.apk 2>/dev/null | tail -n +8 | xargs -r rm -v
        done
        fdroid update
        echo "Cleaned up old versions"
      else
        echo "No staging build for $YESTERDAY"
      fi
    '';
  };

  environment.systemPackages = with pkgs; [
    fdroidserver
    jdk17
    htop
    curl
    git
  ];
}
