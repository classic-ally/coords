---
title: Protocol
description: Wire format and cryptographic primitives
---

Coords clients communicate through an untrusted server relay. This document specifies the cryptographic protocol that enables end-to-end encrypted location sharing.

## Identity

Each user has an Ed25519 keypair. The public key serves as their identity.

- **Signing**: Ed25519 for authenticating uploads to the server
- **Key exchange**: X25519 derived from the Ed25519 keypair

X25519 keys are derived from Ed25519 to simplify key exchange—friend links only need to contain the Ed25519 public key. Both curves use the same underlying Curve25519; Ed25519 uses the Edwards form while X25519 uses the Montgomery form.

**Derivation:**
- X25519 private scalar: first 32 bytes of SHA-512(Ed25519 seed)
- X25519 public key: convert Ed25519 public point from Edwards to Montgomery form

## Location

| Field     | Type | Size | Range/Precision     |
|-----------|------|------|---------------------|
| lat       | i32  | 4    | microdegrees, ~11cm |
| long      | i32  | 4    | microdegrees, ~11cm |
| alt       | i16  | 2    | meters, ±32km       |
| accuracy  | u16  | 2    | meters, 0-65km      |
| timestamp | u64  | 8    | ms since epoch      |

## Blob Format (v3)

Multi-recipient hybrid encryption. Location encrypted once with a random data encryption key (DEK), then the DEK is encrypted separately for each friend.

**Wire format:**

```
version (1 byte)           - 0x03 for current format
ephemeral_pubkey (32)      - X25519 public key for this blob
entry_count (2)            - big-endian u16, includes padding entries
entries (60 × count)       - [nonce (12) | encrypted_DEK (48)] per entry
data_nonce (12)            - nonce for location ciphertext
ciphertext (36)            - AES-256-GCM encrypted location (20 bytes + 16-byte tag)
```

**Entry padding:** To hide the exact friend count, entries are padded:
- Up to 64 friends: round up to next power of 2
- Above 64: round up to nearest 50

Padding entries contain random bytes that fail AES-GCM authentication.

**Sender process:**
1. Generate random 32-byte DEK
2. Generate ephemeral X25519 keypair
3. Encrypt location: `AES-256-GCM(key=DEK, nonce=random, plaintext=location_bytes)`
4. For each friend:
   - `shared_secret = X25519(ephemeral_private, friend_x25519_pubkey)`
   - `encrypted_DEK = AES-256-GCM(key=shared_secret, nonce=random, plaintext=DEK)`
5. Add padding entries (random bytes)
6. Assemble blob

**Recipient process (trial decryption):**
1. `shared_secret = X25519(my_x25519_private, ephemeral_pubkey)`
2. Try to decrypt each entry with shared_secret
   - AES-GCM auth failure → not our entry (or padding)
   - Success → found DEK
3. Decrypt location with DEK

**Blob size:** ~107 bytes base + 60 bytes per padded entry. 64KB server limit allows ~1000 friends.

## Authentication

Uploads are authenticated with Ed25519 signatures to prove the uploader owns the pubkey.

**Signature:**
```
signature = Ed25519_sign(blob || upload_timestamp, privkey)
```

Where `upload_timestamp` is the current time in seconds (u64 big-endian), distinct from the location timestamp inside the encrypted blob.

**Replay protection:** Server rejects requests where `now - upload_timestamp > 300 seconds` (5 minutes). This prevents:
- Replaying old captured requests
- Forging timestamps (signature covers the timestamp)

The location timestamp inside the blob may be older (e.g., cached location), but the upload itself must be fresh.

## Link Format

```
coord://<server>/add/<pubkey>#<name>
```

- `server`: User's server URL (host only, HTTPS assumed)
- `pubkey`: Base64url-encoded Ed25519 public key
- `name`: Display name (URL-encoded, in fragment so server never sees it)

Example:
```
coord://relay.example.com/add/dGhpcyBpcyBhIHB1YmtleQ#Alice
```
