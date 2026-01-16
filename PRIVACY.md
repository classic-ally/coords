# Coords Privacy Policy

*Last updated: January 14, 2026*

Coords is a location sharing app designed with privacy as a core principle. This policy explains what data we collect, how it's protected, and your rights.

## Summary

- **Your location is end-to-end encrypted** — only friends you explicitly add can see it
- **The server cannot read your location** — it only stores encrypted data it cannot decrypt
- **No analytics or tracking** — we don't use any third-party analytics, advertising, or tracking services
- **Your data stays on your device** — cryptographic keys never leave your phone

---

## Data We Collect

### Location Data
- Latitude and longitude (precise location)
- Location accuracy (in meters)
- Timestamp of when location was captured

**How it's used:** Shared with friends you choose, displayed on the map.

**How it's protected:** Encrypted on your device using AES-256-GCM before transmission. The server only stores encrypted data it cannot decrypt.

### Identity Information
- Display name (chosen by you)
- Cryptographic public key (used to identify you to friends)

**How it's used:** Your display name is shared with friends via friend links. Your public key identifies you in the system.

**How it's protected:** Display name is stored locally. Cryptographic keys are stored in secure device storage (iOS Keychain / Android Keystore).

### Friend List
- Friends' names, public keys, and server URLs
- Your sharing preferences for each friend

**How it's used:** Determines who can see your location and whose location you can see.

**How it's protected:** Stored locally on your device only.

---

## Data We Do NOT Collect

- Email addresses or phone numbers
- Passwords or account credentials
- Device identifiers or advertising IDs
- Usage analytics or behavioral data
- Crash reports or diagnostics
- Any data from your contacts, photos, or other apps

---

## How Encryption Works

Coords uses end-to-end encryption, meaning your location is encrypted on your device before it ever leaves your phone.

1. **Key Generation:** When you create your identity, cryptographic keys (Ed25519 and X25519) are generated on your device
2. **Encryption:** Your location is encrypted with AES-256-GCM using keys derived from your friends' public keys
3. **Transmission:** Only encrypted data is sent to the server
4. **Decryption:** Only friends with the matching private keys can decrypt your location

**The server never has access to your unencrypted location data.**

---

## Data Storage

### On Your Device
| Data | Storage Method |
|------|----------------|
| Private keys | iOS Keychain / Android Keystore (hardware-backed when available) |
| Display name | iOS UserDefaults / Android SharedPreferences |
| Friends list | Local JSON file |
| Cached friend locations | Local storage |

### On Servers
- Encrypted location blobs (server cannot decrypt)
- Public keys (necessary for the protocol)
- Timestamps

---

## Third-Party Services

### Maps
- **iOS:** Apple MapKit (Apple's privacy policy applies)
- **Android:** MapLibre (open-source, no data transmitted)

Maps are display-only. Your location data is not sent to mapping services.

### No Other Third Parties
We do not use:
- Analytics services (Google Analytics, Mixpanel, etc.)
- Crash reporting (Sentry, Crashlytics, etc.)
- Advertising networks
- Social media SDKs

---

## Data Sharing

Your location is only shared with:
- **Friends you explicitly add** — via QR code or link
- **The Coords server** — as encrypted data only

We do not sell, rent, or share your data with any third parties for marketing or advertising purposes.

---

## Data Retention

### On Device
- Data persists until you delete the app or remove it manually
- Uninstalling the app deletes all local data

### On Server
- The server stores encrypted location data
- Server retention policies depend on which server you use
- Self-hosted servers: you control retention
- Default server (coord.is): contact us for retention details

---

## Your Rights

### Access & Deletion
- View all your data within the app
- Delete your identity and all local data through the app
- Remove individual friends at any time

### Data Portability
- Your cryptographic identity is stored on your device
- Friend links allow you to share your identity

### Opt-Out
- Disable location sharing entirely
- Disable background location updates
- Choose which friends can see you (per-friend controls)

---

## Background Location

If you enable "Always Allow" location permission:
- The app may update your location in the background
- Updates occur approximately every 15 minutes or when you move significantly (~500m)
- You can disable this at any time in Settings

Background location is **optional** and only used if you enable automatic sharing.

---

## Security

- **AES-256-GCM** encryption for location data
- **Ed25519** signatures for authentication
- **X25519** key exchange for secure key derivation
- **HTTPS** for all network communication
- **Hardware-backed** key storage when available

---

## Self-Hosting

Coords supports self-hosted servers. If you run your own server:
- You control all server-side data
- You determine retention policies
- You can audit the server code (open source)

---

## Children's Privacy

Coords is not intended for children under 13. We do not knowingly collect data from children.

---

## Changes to This Policy

We may update this policy from time to time. Significant changes will be noted in app updates.

---

## Contact

For privacy questions or data requests, contact: **privacy@bentley.sh**

---

## Open Source

Coords is open source software. The source code for the iOS app, Android app, and server is available upon request. Contact us if you'd like to review or audit the code.
