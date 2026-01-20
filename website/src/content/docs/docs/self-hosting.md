---
title: Self-Hosting
description: Run your own Coords server
---

Coords is designed to be easy to self-host, and the clients support federation, so you can bring your friends with you. 

A Coords URI (Uniform Resource Identifier) looks like the following:

```
coord://<server>/add/<pubkey>#<name>
```

For example: `coord://coord.example.com/add/dGhpcyBpcyBhIHB1YmtleQ#Alice`. Clicking a Coords link thus informs the client of what server this friend's location resides on; this URI is also encoded in your Coords QR code. Your friends can be scattered across a variety of domains, and the client will simply make API requests for their location from their respective home servers. 

Home server redirection is on our roadmap, but is currently not supported, so you'll need to add friends again if you move from, say, the `coord.is` default server to your own.

## Server Configuration

### Reverse proxy

Your Coords server must be configured with TLS and a valid certificate for clients to connect to it. Caddy is recommended because it negotiates Let's Encrypt certificates automatically. The later configuration examples include this for reference.

Here's an example Caddyfile for `coord.example.com`:
```caddy
coord.example.com {
    reverse_proxy localhost:3000
}
```

We use the `/api` subroute for all API connections, so if you want the shortest possible URI (say, `example.com` rather than `coord.example.com`) while still hosting other web content on the same address, you can use a rule like the following:

```caddy
example.com {
    # Coords API
    handle /api/* {
        reverse_proxy localhost:3000
    }

    # Your other content (website, etc.)
    handle {
        root * /var/www/example.com
        file_server
    }
}
```

### NixOS (recommended)

Add the Coords flake to your NixOS configuration:

```nix
{
  inputs.coords.url = "git+https://tangled.org/bentley.sh/coords";

  outputs = { self, nixpkgs, coords }: {
    nixosConfigurations.myserver = nixpkgs.lib.nixosSystem {
      modules = [
        coords.nixosModules.default
        {
          services.coords.enable = true;
          # Optional: change the port (default: 3000)
          # services.coords.port = 3000;

          # Caddy reverse proxy for TLS
          services.caddy = {
            enable = true;
            virtualHosts."coords.example.com".extraConfig = ''
              reverse_proxy localhost:3000
            '';
          };
        }
      ];
    };
  };
}
```

The Coords service runs with systemd hardening enabled by default. Caddy will automatically obtain and renew Let's Encrypt certificates.

## Binary

Requires Rust, which you can get via Nix (automatically if you have direnv) or from [rustup.rs](https://rustup.rs/).

```bash
git clone https://tangled.org/bentley.sh/coords
cd coords
cargo build --release -p coords-server
./target/release/coords-server
```

The server listens on `0.0.0.0:3000` by default, or set `COORDS_PORT` to change it.

## Pointing the app to your server

You can move to a new server on your Profile page in the app, or during the initial setup flow.
