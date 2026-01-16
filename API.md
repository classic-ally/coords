# Coords API

## Overview

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

## Blob Format

The location blob is encrypted client-side and opaque to the server.

**Location fields:**

| Field | Type | Size | Range/Precision |
|-------|------|------|-----------------|
| lat | i32 | 4 | microdegrees, ~11cm |
| long | i32 | 4 | microdegrees, ~11cm |
| alt | i16 | 2 | meters, ±32km |
| accuracy | u16 | 2 | meters, 0-65km |
| timestamp | u64 | 8 | ms since epoch |

**Encryption:**

Multi-recipient hybrid encryption. Location encrypted once with symmetric key K, then K encrypted separately for each friend.

| Layer | Key | Why no nonce storage |
|-------|-----|----------------------|
| Location encryption | K (random each update) | K never reused |
| K encryption per friend | shared_secret (fresh ephemeral keypair) | Ephemeral keypair never reused |

Fixed nonce (zero) is safe at both layers since keys are always unique.

**Sender process:**
1. Generate random 32-byte symmetric key K
2. Generate ephemeral X25519 keypair
3. encrypted_location = AES-256-GCM(key=K, nonce=0, plaintext=location)
4. For each friend:
   - shared_secret = X25519(ephemeral_priv, friend_pubkey)
   - encrypted_K = encrypt(key=shared_secret, nonce=0, plaintext=K)
5. Assemble blob: encrypted_location || ephemeral_pub || encrypted_K_entries[]

**Recipient process:**
1. shared_secret = X25519(my_privkey, ephemeral_pub)
2. For each encrypted_K entry:
   - Try to decrypt with shared_secret
   - On success: K found
3. location = AES-256-GCM(key=K, nonce=0, ciphertext=encrypted_location)

AES-256-GCM output includes 16-byte auth tag for integrity verification.

**Blob size:** 36 bytes + 32 bytes + 48 bytes per friend. 64KB limit allows ~1300 friends.

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

Used by clients to validate a server URL during configuration.

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

