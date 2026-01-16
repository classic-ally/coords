---
title: Self-Hosting
description: Run your own Coords server
---

Coords is designed to be easy to self-host. You can run your own server and point the mobile apps to it.

## NixOS (recommended)

Add the Coords flake to your NixOS configuration:

```nix
{
  inputs.coords.url = "github:classic-ally/coords";

  outputs = { self, nixpkgs, coords }: {
    nixosConfigurations.myserver = nixpkgs.lib.nixosSystem {
      modules = [
        coords.nixosModules.default
        {
          services.coords.enable = true;
          # Optional: change the port (default: 3000)
          # services.coords.port = 3000;
        }
      ];
    };
  };
}
```

The service runs with systemd hardening enabled by default.

## Binary

Download the latest release from GitHub and run:

```bash
./coords-server
```

The server listens on `0.0.0.0:3000` by default.

## Reverse proxy

Put a reverse proxy (Caddy, nginx) in front for TLS:

```caddy
coords.example.com {
    reverse_proxy localhost:3000
}
```

## Pointing the app to your server

In the mobile app settings, set the server URL to your domain (e.g., `https://coords.example.com`).
