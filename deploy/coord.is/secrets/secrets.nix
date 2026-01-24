let
  # Server SSH host key - can decrypt secrets on the server
  coord = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIC0UipqpPJIWeEOQMtTB3on695+r1hXHGzHmJjscxDbp";

  # Add ed25519 user keys here to allow local re-encryption
  # allison = "ssh-ed25519 AAAA...";
in {
  "cf-api-token.age".publicKeys = [ coord ];
}
