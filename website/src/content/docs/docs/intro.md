---
title: Introduction
description: What is Coords and how does it work?
---

Coords is a private location sharing app that uses end-to-end encryption to ensure your location is only shared with people you choose. All parts of the platform - iOS/Android clients, the server, and the core engine responsible for cryptography - are open source so the code can be audited for verification or improved by community action. 

Coords also aims to be a unified platform on which developers can securely build services involving location data. The MPL-licensed core gives developers the flexibility to build alternative location upload clients for applications such as fleet tracking. Also on our roadmap is an on-device API enabling other apps to build location-enabled experiences on top of the Coords social graph without needing to handle cryptography themselves.

## How it works

1. **Generate a keypair** - Your device generates an Ed25519 keypair. The private key stays on your device and never leaves. Your public key is your identity.

2. **Add friends** - When you add a friend, you scan their QR code and they scan yours. This exchanges your public keys. Your devices then derive a shared secret using X25519 key agreement:
   - You compute: `f(your_private_key, their_public_key)`
   - They compute: `f(their_private_key, your_public_key)`

   Both produce the same shared secret, and only you two can compute it.

3. **Encrypt your location** - Your device generates a random location key for each upload and encrypts your location with AES-256-GCM. Then the location key is wrapped (encrypted) separately for each friend using your shared secret with them. The blob contains one encrypted location plus one wrapped key per friend.

4. **Upload to the relay** - The encrypted blob is signed with your private key and uploaded to the Coords server. The server can verify the signature so nobody can pretend to be you, but it cannot read your location.

5. **Friends fetch and decrypt** - Your friends fetch your blob, unwrap the location key using the shared secret, and decrypt your location.

The server never sees your actual location—only encrypted data that it cannot read.

## Privacy guarantees

- **No accounts** - Your identity is just your public key, no registration
- **No server-side decryption** - The server stores opaque blobs
- **No tracking** - The server only stores the most recent uploaded blob
- **No metadata** - The server doesn't log who fetches whose location

## Getting the app

- [iOS (TestFlight)](https://testflight.apple.com/join/e2DQGWDB)
- [Android (F-Droid)](/docs/android)
