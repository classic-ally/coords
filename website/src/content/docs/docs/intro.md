---
title: Introduction
description: What is Coords and how does it work?
---

Coords is a private location sharing app. Share your real-time location with friends without giving up your privacy to big tech.

## How it works

1. **Generate a keypair** - Your device generates an Ed25519 keypair. The public key is your identity.

2. **Encrypt your location** - Your location is encrypted with keys shared only with your friends.

3. **Upload to the relay** - The encrypted blob is signed and uploaded to the Coords server.

4. **Friends fetch and decrypt** - Your friends fetch the blob and decrypt it with the shared key.

The server never sees your actual location - only encrypted data that it cannot read.

## Privacy guarantees

- **No accounts** - Your identity is just a public key
- **No server-side decryption** - The server stores opaque blobs
- **No tracking** - Locations expire after 24 hours
- **No metadata** - The server doesn't log who fetches whose location

## Getting the app

- [Android (Play Store)](#) - Coming soon
- [iOS (App Store)](#) - Coming soon
- [F-Droid](#) - Coming soon
