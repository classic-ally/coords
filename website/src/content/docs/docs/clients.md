---
title: Client Architecture
description: iOS and Android client implementation details
---

Native iOS and Android clients for the Coords location-sharing service. Clients handle all cryptographic operations—the server is an untrusted relay.

**Goals:**
- Privacy: Server learns nothing. All encryption/decryption client-side.
- Reliability: Background operation survives app suspension.
- Interoperability: Identical wire format across platforms via shared Rust core.

**Architecture:**
- Shared Rust core for crypto, blob encoding, storage logic
- Native shells (Swift/Kotlin) for platform integration
- Communication via UniFFI-generated bindings

**Platform responsibilities:**

| Rust Core                | Native Shell                                       |
|--------------------------|---------------------------------------------------|
| Ed25519/X25519 crypto    | Secure key storage (Keychain/Keystore)            |
| AES-256-GCM encryption   | Location services                                 |
| Blob encode/decode       | Background task scheduling                        |
| Friend/cache persistence | HTTP transport (background URLSession/WorkManager)|
| Link generation/parsing  | UI                                                |

## Architecture

### Rationale

**Why Rust core:**
- Crypto must be identical across platforms. One implementation, one test suite.
- Blob format consistency guaranteed—no subtle encoding bugs.
- Storage logic unified—single schema, single migration path.

**Why native shells:**
- Secure key storage requires Keychain (iOS) / Keystore (Android).
- Background execution models differ fundamentally between platforms.
- HTTP via background URLSession (iOS) / WorkManager (Android) provides system-level reliability that survives app suspension.

### Data Flow: Publish Location

```
┌─────────┐      ┌─────────┐      ┌─────────┐      ┌─────────┐
│Location │      │  Rust   │      │ Native  │      │ Server  │
│Service  │      │  Core   │      │  HTTP   │      │         │
└────┬────┘      └────┬────┘      └────┬────┘      └────┬────┘
     │                │                │                │
     │ lat/long/alt   │                │                │
     ├───────────────>│                │                │
     │                │                │                │
     │                │ load_privkey() │                │
     │                │<───────────────┤                │
     │                │                │                │
     │                │ encrypt +      │                │
     │                │ sign           │                │
     │                │                │                │
     │                │ PreparedRequest│                │
     │                ├───────────────>│                │
     │                │                │                │
     │                │                │  PUT /location │
     │                │                ├───────────────>│
     │                │                │                │
     │                │                │      204       │
     │                │                │<───────────────┤
     │                │                │                │
     │                │ update cache   │                │
     │                │<───────────────┤                │
     │                │                │                │
```

### Data Flow: Fetch Friends

```
┌─────────┐      ┌─────────┐      ┌─────────┐      ┌─────────┐
│  App    │      │  Rust   │      │ Native  │      │ Servers │
│         │      │  Core   │      │  HTTP   │      │  (N)    │
└────┬────┘      └────┬────┘      └────┬────┘      └────┬────┘
     │                │                │                │
     │ refresh()      │                │                │
     ├───────────────>│                │                │
     │                │                │                │
     │                │ list_friends() │                │
     │                │ group by server│                │
     │                │                │                │
     │                │ PreparedRequest│                │
     │                │ per server     │                │
     │                ├───────────────>│                │
     │                │                │                │
     │                │                │ POST /location │
     │                │                ├───────────────>│
     │                │                │   (parallel)   │
     │                │                │                │
     │                │                │  responses     │
     │                │                │<───────────────┤
     │                │                │                │
     │                │ decrypt blobs  │                │
     │                │<───────────────┤                │
     │                │                │                │
     │                │ update cache   │                │
     │                │                │                │
     │ locations      │                │                │
     │<───────────────┤                │                │
     │                │                │                │
```

## Rust Core

### Modules

| Module | Purpose |
|--------|---------|
| `identity` | Ed25519 keypair generation, signing, verification |
| `crypto` | X25519 key exchange, AES-256-GCM, multi-recipient encryption |
| `protocol` | Blob encoding, link parsing, request preparation |
| `storage` | Flatfile persistence, mutex-protected state |

### Types

```rust
struct Location {
    latitude: f64,      // degrees (converted to microdegrees for wire format)
    longitude: f64,     // degrees
    altitude: f64,      // meters (converted to i16 for wire format)
    accuracy: f32,      // meters (converted to u16 for wire format)
    timestamp: u64,     // ms since epoch
}

struct Friend {
    pubkey: String,             // base64url Ed25519 public key
    server: String,             // server URL
    name: String,               // display name
    location: Option<Location>, // last known
    fetched_at: Option<u64>,    // ms since epoch
}

struct AppState {
    friends: Vec<Friend>,
}

struct PreparedRequest {
    url: String,
    method: String,             // "PUT" or "POST"
    headers: Map<String, String>,
    body: Vec<u8>,
}
```

## Native Layer

### FFI Boundary

Rust core exposes functions via UniFFI. Native shells call these as regular functions.

**Identity & Crypto:**
```
generate_keypair() → (privkey_bytes, pubkey_bytes)
sign(privkey_bytes, message) → signature
pubkey_to_base64url(pubkey_bytes) → String
```

**Location Publishing:**
```
prepare_location_upload(
    privkey_bytes,
    location: Location,
    friends: Vec<Friend>,
    my_server: String
) → PreparedRequest
```

**Location Fetching:**
```
prepare_location_fetch(
    friends: Vec<Friend>
) → Vec<PreparedRequest>  // grouped by server

process_fetch_response(
    privkey_bytes,
    server: String,
    response_body: bytes
) → Vec<(pubkey, Option<Location>)>
```

**Friend Management:**
```
add_friend(pubkey: String, server: String, name: String)
remove_friend(pubkey: String)
list_friends() → Vec<Friend>
generate_friend_link(pubkey_bytes, server: String) → String
parse_friend_link(url: String) → Option<(pubkey, server)>
```

### iOS

**Secure Storage:**
- Keychain Services for private key
- `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` for background access

**Location:**
- `CLLocationManager` with `requestLocation()` for single fix
- `allowsBackgroundLocationUpdates = true`
- Significant location change as backup trigger

**Background Execution:**
- `BGTaskScheduler` for periodic refresh (minimum 15 min)
- Background URLSession for HTTP—survives app termination
- App relaunched on download completion to process + cache

### Android

**Secure Storage:**
- Android Keystore for private key
- `setUserAuthenticationRequired(false)` for background access

**Location:**
- AOSP `LocationManager` (not `FusedLocationProviderClient`)
- Request from `GPS_PROVIDER` and `NETWORK_PROVIDER`
- No sensor fusion—independent readings from each provider
- Compatible with microG / UnifiedNlp backends
- Works on de-Googled devices (GrapheneOS, CalyxOS, etc.)

**Background Execution:**
- `WorkManager` with `PeriodicWorkRequest` (minimum 15 min)
- `setExpedited()` for reliability
- Survives Doze mode with proper constraints

### Location Provider Tradeoffs (Android)

Using AOSP `LocationManager` instead of Play Services `FusedLocationProviderClient`:

| Aspect | FusedLocationProvider | LocationManager |
|--------|----------------------|-----------------|
| GMS required | Yes | No |
| Sensor fusion | GPS + WiFi + cell + IMU | None—raw readings |
| Battery optimization | Automatic batching | Manual |
| Time to first fix | Faster | Slower cold start |
| De-Googled devices | ❌ | ✅ |

On de-Googled devices (GrapheneOS, etc.), `NETWORK_PROVIDER` requires user-installed backends (microG + UnifiedNlp). Without one, GPS only—slower fixes, no indoor positioning. User's choice.

Acceptable tradeoff for 15-minute update interval.

## Location Upload Paths

| Path | Freshness | Rationale | Platform |
|------|-----------|-----------|----------|
| App launch | Cached if <5min, else fresh | Quick startup | Both |
| Periodic timer (60s) | Always fresh | User is actively using app | Both |
| Share Now button | Always fresh | Explicit user action | Both |
| Add friend | Always fresh | Explicit user action | Both |
| Background task | Cached if <5min, else fresh | Preserve execution time | Both |
| Significant location change | From delegate | System provides it | iOS only |

**Notes:**
- **Cached location**: iOS uses CoreLocation's cached location; Android uses the passive provider with <100m accuracy threshold
- **Fresh location**: Both platforms request an active GPS fix with ~10s timeout
- **Background task**: iOS uses BGAppRefreshTask; Android uses WorkManager (both minimum 15min interval)
- **Significant location change**: iOS-only feature that wakes the app when device moves ~500m

## Friend Exchange

Links use the `coord://` URI scheme. See [Protocol](/docs/protocol#link-format) for the format specification.

### In-Person Flow (QR)

1. Alice taps "Add Friend" → shows her QR code
2. Bob taps "Add Friend" → scans Alice's QR
3. Bob's screen immediately shows his QR code
4. Alice taps "Next" → scans Bob's QR
5. Both now have each other

Two scans, but feels like one interaction.

### Remote Flow (Link Share)

1. Alice taps "Add Friend" → "Share Link"
2. Alice sends link via Signal/iMessage/etc.
3. Bob taps link → app opens, adds Alice
4. App auto-copies Bob's link to clipboard, shows toast
5. App offers share sheet for convenience
6. Bob sends his link back to Alice
7. Alice taps link → done

### Federation

Friends can be on different servers. Each friend entry stores their server URL. When fetching:
- Group friends by server
- POST to each server in parallel
- Each server only sees the pubkeys it hosts

## Non-Goals

Explicitly out of scope:

- **Location history**: Privacy risk. Server stores only latest blob.
- **Presence/online status**: Safety concern. Removal is silent—last-seen shows final authorized post time.
- **Read receipts**: Exposes social graph to server.
- **Group sharing**: One-time setup cost is acceptable.

**Post-MVP:**
- Expiring shares (client-side, no protocol changes)

## Privacy Design Notes

### Offline Geocoding

Reverse geocoding (coordinates → city name) uses a bundled offline database of ~1,400 cities. No coordinates are ever sent to geocoding services. This prevents location leakage through geocoding APIs.

### Map Tile Provider Separation

Map tiles are fetched from third-party providers (Apple MapKit on iOS, MapTiler on Android). This is intentional—**not** a privacy weakness.

**Why not self-host tiles?**

If the Coords server operator also hosted tiles, they could correlate:
- Pubkey fetch patterns (who you're tracking)
- Tile request patterns (which map regions you view)

This would reveal friend relationships even without decrypting location blobs:
> "User fetched pubkey X's blob, then immediately zoomed to Brooklyn"

By using separate providers, this correlation requires collusion between organizations. The Coords server sees pubkeys but not map views. The tile provider sees map views but not pubkeys. Neither can reconstruct the full picture alone.

**Trade-off:** Tile providers learn which geographic regions you view (but not your actual location or friends' locations). Future enhancement may include optional self-hosting of map data to allow users with self-hosted Coords to avoid leaking tile request data to these third parties.
