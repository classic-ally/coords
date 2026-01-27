---
title: Privacy Policy
description: How Coords handles your data
---

*Last updated: January 27, 2026*

## Overview

Coords is designed with privacy as a core principle. Your location data is end-to-end encrypted and can only be read by friends you explicitly add. The Coords server acts as a relay for encrypted data it cannot decrypt.

## Data We Collect

### Location Data

When you enable location sharing, your device encrypts your location using AES-256-GCM before uploading it to the Coords server. The server stores only the most recent encrypted blob for each user. **The server cannot decrypt your location**—only friends who have exchanged keys with you can decrypt it.

- Location data is encrypted on your device before transmission
- Only your most recent location is stored (no history)
- Encrypted blobs are automatically deleted when replaced by newer uploads

### Cryptographic Identity

Your device generates an Ed25519 keypair when you first use the app. Your public key serves as your identity on the network. Your private key never leaves your device.

- No account registration required
- No email, phone number, or personal information collected
- Your identity is just a cryptographic public key

### Server Logs

The Coords server may temporarily log:

- IP addresses (for rate limiting and abuse prevention)
- Timestamps of requests
- Public keys involved in uploads/fetches

These logs are not correlated with location data (which the server cannot read) and are retained only as long as necessary for operational purposes.

## Data We Do Not Collect

- Your actual location (only encrypted blobs)
- Your name, email, or phone number
- Your contacts or address book
- Device identifiers or advertising IDs
- Analytics or usage tracking data
- Location history (only the latest upload is stored)

## How Your Data Is Used

- **Encrypted location blobs**: Relayed to friends who request them
- **Public keys**: Used to verify signatures on uploaded data
- **Server logs**: Used only for rate limiting and abuse prevention

We do not sell, share, or monetize your data in any way.

## Third-Party Services

### Map Tiles

The app displays maps using third-party providers:

- **iOS**: Apple MapKit
- **Android**: MapTiler

These providers may receive your device's approximate viewport location when loading map tiles. This is standard for any map-based application. Refer to [Apple's Privacy Policy](https://www.apple.com/legal/privacy/) and [MapTiler's Privacy Policy](https://www.maptiler.com/privacy-policy/) for details.

## Data Retention

- **Encrypted location blobs**: Replaced with each new upload; only the latest is stored
- **Server logs**: Retained for a limited period for operational purposes, then deleted

## Your Rights

You have the right to:

- **Delete your data**: Stop sharing and your encrypted blob will be replaced or expire
- **Export your data**: Your cryptographic identity is stored locally on your device
- **Opt out**: Simply uninstall the app; no account deletion needed since there are no accounts

## Security

- All data in transit uses HTTPS/TLS
- Location data is encrypted with AES-256-GCM before leaving your device
- Key exchange uses X25519 elliptic curve Diffie-Hellman
- Digital signatures use Ed25519
- The server is open source and can be self-hosted

## Children's Privacy

Coords is not intended for children under 13. We do not knowingly collect personal information from children under 13. If you believe a child under 13 has provided us with personal information, please contact us.

## Changes to This Policy

We may update this privacy policy from time to time. We will notify users of any material changes by updating the "Last updated" date at the top of this page.

## Open Source

Coords is fully open source. You can audit the code yourself through the links available on this site.

## Contact

If you have questions about this privacy policy or your data, contact:

Allison Bentley
Email: allison@bentley.sh
Website: https://coord.is