---
title: API Reference
description: Coords server API documentation
---

Coords is a privacy-preserving location sharing service. The server is a stateless relay that stores encrypted blobs it cannot read.

**Architecture:**
- In-memory key-value store mapping public keys to encrypted location blobs
- No persistence, no user accounts, no friend graph
- Server cannot decrypt locations or determine relationships between users

**Primitives:**
- **Identity**: Ed25519 keypair. Public key serves as user ID.
- **Authentication**: Signatures over payloads prove write authorization. No tokens or sessions.
- **Encryption**: Location blobs encrypted client-side using X25519 derived from Ed25519 keys. Multi-recipient encryption allows sharing with multiple friends.
- **Key exchange**: Out-of-band via QR code or deep link containing Ed25519 public key.

## Endpoints

All endpoints are prefixed with `/api`.

### GET /api/version

Returns server identification and version.

**Response:**
```json
{
  "name": "coords",
  "version": "2026.1.14"
}
```

Used by clients to validate a server URL and compatibility with the version during configuration.

### PUT /api/location

Publish an encrypted location blob.

**Request:**
```json
{
  "pubkey": "<base64url Ed25519 public key>",
  "timestamp": 1701345600,
  "blob": "<base64url encrypted location>",
  "signature": "<base64url Ed25519 signature>"
}
```

**Signature:** `sign(blob || timestamp, privkey)` - timestamp as 8-byte big-endian uint64, concatenated with raw blob bytes.

**Response:** `204 No Content`

**Errors:**
- `400` - Malformed request
- `401` - Invalid signature or timestamp too old (>5 min)
- `413` - Blob exceeds 64KB

### POST /api/location

Fetch location blobs for multiple friends.

**Request:**
```json
{
  "ids": ["<base64url Ed25519 pubkey>", ...]
}
```

**Response:**
```json
{
  "<pubkey>": {"blob": "<base64url>", "updated": 1701345600},
  "<pubkey>": null,
  ...
}
```

Null for IDs with no stored location.

**Errors:**
- `400` - Malformed request

