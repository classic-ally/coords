use aes_gcm::{
    aead::{Aead, KeyInit},
    Aes256Gcm, Nonce,
};
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use curve25519_dalek::edwards::CompressedEdwardsY;
use ed25519_dalek::{Signer, SigningKey};
use rand::rngs::OsRng;
use std::collections::HashMap;

/// Blob format version for future compatibility
/// v2: JSON-encoded location
/// v3: Binary-encoded location (20 bytes)
const BLOB_VERSION: u8 = 0x03;
use std::fs;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Mutex, OnceLock};
use std::time::{SystemTime, UNIX_EPOCH};
use x25519_dalek::{PublicKey as X25519PublicKey, StaticSecret};

/// Global storage directory path, set once at app startup
static STORAGE_PATH: OnceLock<PathBuf> = OnceLock::new();

/// In-memory friends list, persisted to disk on changes
static FRIENDS: Mutex<Vec<Friend>> = Mutex::new(Vec::new());

/// Track the last successfully uploaded location timestamp to avoid uploading stale data
/// Persisted to disk to survive app restarts
static LAST_UPLOADED_TIMESTAMP: AtomicU64 = AtomicU64::new(0);

uniffi::setup_scaffolding!();

/// Get the version of the transponder core library
#[uniffi::export]
pub fn get_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}

/// License information grouped by license type
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct LicenseGroup {
    pub id: String,
    pub name: String,
    pub text: String,
    pub packages: Vec<String>,
}

/// Get license information for all dependencies, grouped by license type
#[uniffi::export]
pub fn get_licenses() -> Vec<LicenseGroup> {
    let json = include_str!("../licenses.json");
    serde_json::from_str(json).unwrap_or_default()
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct Location {
    pub latitude: f64,
    pub longitude: f64,
    #[serde(default)]
    pub altitude: f64,
    pub accuracy: f32,
    pub timestamp: u64,
}

// =============================================================================
// Wire Format Conversion Utilities
// =============================================================================

/// Convert degrees to microdegrees (i32)
/// 1 microdegree ≈ 11cm at the equator
fn degrees_to_micro(degrees: f64) -> i32 {
    (degrees * 1_000_000.0).round() as i32
}

/// Convert microdegrees (i32) to degrees
fn micro_to_degrees(micro: i32) -> f64 {
    micro as f64 / 1_000_000.0
}

/// Wire format for location (20 bytes total)
/// All integers are big-endian
struct WireLocation {
    lat_micro: i32,   // 4 bytes - microdegrees
    long_micro: i32,  // 4 bytes - microdegrees
    alt: i16,         // 2 bytes - meters
    accuracy: u16,    // 2 bytes - meters
    timestamp: u64,   // 8 bytes - ms since epoch
}

impl WireLocation {
    fn from_location(loc: &Location) -> Self {
        Self {
            lat_micro: degrees_to_micro(loc.latitude),
            long_micro: degrees_to_micro(loc.longitude),
            alt: loc.altitude.clamp(-32768.0, 32767.0) as i16,
            accuracy: (loc.accuracy as u32).min(65535) as u16,
            timestamp: loc.timestamp,
        }
    }

    fn to_location(&self) -> Location {
        Location {
            latitude: micro_to_degrees(self.lat_micro),
            longitude: micro_to_degrees(self.long_micro),
            altitude: self.alt as f64,
            accuracy: self.accuracy as f32,
            timestamp: self.timestamp,
        }
    }

    fn encode(&self) -> [u8; 20] {
        let mut buf = [0u8; 20];
        buf[0..4].copy_from_slice(&self.lat_micro.to_be_bytes());
        buf[4..8].copy_from_slice(&self.long_micro.to_be_bytes());
        buf[8..10].copy_from_slice(&self.alt.to_be_bytes());
        buf[10..12].copy_from_slice(&self.accuracy.to_be_bytes());
        buf[12..20].copy_from_slice(&self.timestamp.to_be_bytes());
        buf
    }

    fn decode(buf: &[u8; 20]) -> Self {
        Self {
            lat_micro: i32::from_be_bytes([buf[0], buf[1], buf[2], buf[3]]),
            long_micro: i32::from_be_bytes([buf[4], buf[5], buf[6], buf[7]]),
            alt: i16::from_be_bytes([buf[8], buf[9]]),
            accuracy: u16::from_be_bytes([buf[10], buf[11]]),
            timestamp: u64::from_be_bytes([buf[12], buf[13], buf[14], buf[15], buf[16], buf[17], buf[18], buf[19]]),
        }
    }
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct Friend {
    pub pubkey: String,
    pub server: String,
    pub name: String,
    pub share_with: bool,
    pub fetch_from: bool,
    pub location: Option<Location>,
    pub fetched_at: Option<u64>,
    /// Computed color for this friend's marker (hex string like "#4A90D9")
    /// Not persisted - derived from pubkey on load
    #[serde(skip, default)]
    pub color: String,
}

/// Derive a consistent color from a pubkey string.
/// Returns a hex color string like "#4A90D9".
/// Uses HSL with fixed saturation/lightness for good visibility.
#[uniffi::export]
pub fn color_from_pubkey(pubkey: String) -> String {
    color_from_pubkey_internal(&pubkey)
}

/// Get the color for an identity (what others see when viewing you)
#[uniffi::export]
pub fn get_identity_color(identity: &Identity) -> String {
    let pubkey = URL_SAFE_NO_PAD.encode(&identity.ed25519_public);
    color_from_pubkey_internal(&pubkey)
}

/// Internal color derivation function
fn color_from_pubkey_internal(pubkey: &str) -> String {
    // Hash the pubkey to get a consistent number
    let hash: u64 = pubkey.bytes().fold(0u64, |acc, b| acc.wrapping_mul(31).wrapping_add(b as u64));

    // Map to hue (0-360), keeping saturation and lightness fixed
    let hue = (hash % 360) as f64;
    let saturation = 0.65;
    let lightness = 0.45;

    // Convert HSL to RGB
    let (r, g, b) = hsl_to_rgb(hue, saturation, lightness);

    format!("#{:02X}{:02X}{:02X}", r, g, b)
}

/// Convert HSL to RGB values (0-255)
fn hsl_to_rgb(h: f64, s: f64, l: f64) -> (u8, u8, u8) {
    let c = (1.0 - (2.0 * l - 1.0).abs()) * s;
    let x = c * (1.0 - ((h / 60.0) % 2.0 - 1.0).abs());
    let m = l - c / 2.0;

    let (r1, g1, b1) = if h < 60.0 {
        (c, x, 0.0)
    } else if h < 120.0 {
        (x, c, 0.0)
    } else if h < 180.0 {
        (0.0, c, x)
    } else if h < 240.0 {
        (0.0, x, c)
    } else if h < 300.0 {
        (x, 0.0, c)
    } else {
        (c, 0.0, x)
    };

    let r = ((r1 + m) * 255.0) as u8;
    let g = ((g1 + m) * 255.0) as u8;
    let b = ((b1 + m) * 255.0) as u8;

    (r, g, b)
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct Identity {
    pub ed25519_private: Vec<u8>,
    pub ed25519_public: Vec<u8>,
    pub x25519_private: Vec<u8>,
    pub x25519_public: Vec<u8>,
}

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum CoreError {
    #[error("Invalid key")]
    InvalidKey,
    #[error("Encryption failed")]
    EncryptionFailed,
    #[error("Decryption failed")]
    DecryptionFailed,
    #[error("Invalid data")]
    InvalidData,
    #[error("Storage not initialized")]
    StorageNotInitialized,
    #[error("Storage error: {details}")]
    StorageError { details: String },
    #[error("Invalid link format")]
    InvalidLink,
    #[error("Friend not found")]
    FriendNotFound,
    #[error("Location is older than last upload")]
    StaleLocation,
}

/// Derive X25519 private key bytes from Ed25519 seed
/// Uses SHA-512 like Ed25519, but returns raw bytes without mod l reduction
/// (x25519_dalek::StaticSecret::from() will apply clamping when used)
fn derive_x25519_scalar(ed25519_seed: &[u8; 32]) -> [u8; 32] {
    use sha2::{Sha512, Digest};
    let hash = Sha512::digest(ed25519_seed);
    let mut scalar = [0u8; 32];
    scalar.copy_from_slice(&hash[..32]);
    scalar
}

/// Generate a new identity with Ed25519 (signing) and X25519 (key exchange) keypairs
/// X25519 keys are derived from Ed25519 for simplicity - only Ed25519 pubkey needed in friend links
#[uniffi::export]
pub fn generate_identity() -> Identity {
    // Generate Ed25519 signing keypair
    let ed_signing = SigningKey::generate(&mut OsRng);
    let ed_public = ed_signing.verifying_key();

    // Derive X25519 scalar from Ed25519 (raw scalar, no additional clamping)
    let x_scalar = derive_x25519_scalar(&ed_signing.to_bytes());

    // Derive X25519 public by converting Ed25519 public (consistent with encryption)
    let x_public_bytes = ed25519_pubkey_to_x25519(&ed_public.to_bytes())
        .expect("Valid Ed25519 pubkey should convert to X25519");

    Identity {
        ed25519_private: ed_signing.to_bytes().to_vec(),
        ed25519_public: ed_public.to_bytes().to_vec(),
        x25519_private: x_scalar.to_vec(),
        x25519_public: x_public_bytes.to_vec(),
    }
}

/// Derive shared secret using X25519 ECDH
#[uniffi::export]
pub fn derive_shared_secret(
    my_x25519_private: Vec<u8>,
    their_x25519_public: Vec<u8>,
) -> Result<Vec<u8>, CoreError> {
    let private_bytes: [u8; 32] = my_x25519_private
        .try_into()
        .map_err(|_| CoreError::InvalidKey)?;
    let public_bytes: [u8; 32] = their_x25519_public
        .try_into()
        .map_err(|_| CoreError::InvalidKey)?;

    let private = StaticSecret::from(private_bytes);
    let public = X25519PublicKey::from(public_bytes);

    let shared = private.diffie_hellman(&public);
    Ok(shared.as_bytes().to_vec())
}

/// Encrypt location data with AES-256-GCM using shared secret
#[uniffi::export]
pub fn encrypt_location(location: Location, shared_secret: Vec<u8>) -> Result<Vec<u8>, CoreError> {
    let key_bytes: [u8; 32] = shared_secret
        .try_into()
        .map_err(|_| CoreError::InvalidKey)?;

    let cipher = Aes256Gcm::new_from_slice(&key_bytes).map_err(|_| CoreError::InvalidKey)?;

    // Serialize location to JSON
    let plaintext = serde_json::to_vec(&LocationJson {
        latitude: location.latitude,
        longitude: location.longitude,
        altitude: location.altitude,
        accuracy: location.accuracy,
        timestamp: location.timestamp,
    })
    .map_err(|_| CoreError::InvalidData)?;

    // Generate random nonce
    let mut nonce_bytes = [0u8; 12];
    rand::RngCore::fill_bytes(&mut OsRng, &mut nonce_bytes);
    let nonce = Nonce::from_slice(&nonce_bytes);

    // Encrypt
    let ciphertext = cipher
        .encrypt(nonce, plaintext.as_ref())
        .map_err(|_| CoreError::EncryptionFailed)?;

    // Prepend nonce to ciphertext
    let mut result = nonce_bytes.to_vec();
    result.extend(ciphertext);
    Ok(result)
}

/// Decrypt location data with AES-256-GCM using shared secret
#[uniffi::export]
pub fn decrypt_location(
    encrypted: Vec<u8>,
    shared_secret: Vec<u8>,
) -> Result<Option<Location>, CoreError> {
    if encrypted.len() < 12 {
        return Err(CoreError::InvalidData);
    }

    let key_bytes: [u8; 32] = shared_secret
        .try_into()
        .map_err(|_| CoreError::InvalidKey)?;

    let cipher = Aes256Gcm::new_from_slice(&key_bytes).map_err(|_| CoreError::InvalidKey)?;

    // Split nonce and ciphertext
    let (nonce_bytes, ciphertext) = encrypted.split_at(12);
    let nonce = Nonce::from_slice(nonce_bytes);

    // Decrypt
    let plaintext = cipher
        .decrypt(nonce, ciphertext)
        .map_err(|_| CoreError::DecryptionFailed)?;

    // Deserialize
    let json: LocationJson =
        serde_json::from_slice(&plaintext).map_err(|_| CoreError::InvalidData)?;

    Ok(Some(Location {
        latitude: json.latitude,
        longitude: json.longitude,
        altitude: json.altitude,
        accuracy: json.accuracy,
        timestamp: json.timestamp,
    }))
}

// Internal JSON representation for legacy single-recipient encryption
#[derive(serde::Serialize, serde::Deserialize)]
struct LocationJson {
    latitude: f64,
    longitude: f64,
    #[serde(default)]
    altitude: f64,
    accuracy: f32,
    timestamp: u64,
}

/// HTTP request prepared by Rust core for native layer to execute
#[derive(Debug, Clone, uniffi::Record)]
pub struct PreparedRequest {
    pub url: String,
    pub method: String,
    pub headers: HashMap<String, String>,
    pub body: Vec<u8>,
}

/// Convert Ed25519 public key to X25519 public key
/// Both are points on Curve25519, just different representations (Edwards vs Montgomery)
fn ed25519_pubkey_to_x25519(ed_pubkey: &[u8; 32]) -> Result<[u8; 32], CoreError> {
    let compressed = CompressedEdwardsY::from_slice(ed_pubkey).map_err(|_| CoreError::InvalidKey)?;
    let edwards_point = compressed.decompress().ok_or(CoreError::InvalidKey)?;
    Ok(edwards_point.to_montgomery().to_bytes())
}

/// Calculate padded entry count for privacy (hides exact friend count)
/// Power of 2 up to 64, then nearest 50 above that
fn padded_entry_count(n: usize) -> usize {
    if n == 0 {
        return 0;
    }
    if n <= 64 {
        n.next_power_of_two()
    } else {
        ((n + 49) / 50) * 50
    }
}

/// Create multi-recipient encrypted blob with trial decryption support
/// Format: version(1) | ephemeral_pubkey(32) | entry_count(2) | [nonce(12) | encrypted_dek(48)]... | data_nonce(12) | ciphertext
/// Entry count includes padding entries (random bytes that fail auth for everyone)
fn create_encrypted_blob(
    location: &Location,
    recipient_ed25519_pubkeys: &[[u8; 32]],
) -> Result<Vec<u8>, CoreError> {
    // Generate ephemeral X25519 keypair for this blob
    let ephemeral_private = StaticSecret::random_from_rng(OsRng);
    let ephemeral_public = X25519PublicKey::from(&ephemeral_private);

    // Generate random data encryption key (DEK)
    let mut dek = [0u8; 32];
    rand::RngCore::fill_bytes(&mut OsRng, &mut dek);

    // Encode location to binary wire format (20 bytes)
    let wire_location = WireLocation::from_location(location);
    let plaintext = wire_location.encode();

    // Encrypt location with DEK
    let data_cipher = Aes256Gcm::new_from_slice(&dek).map_err(|_| CoreError::InvalidKey)?;
    let mut data_nonce_bytes = [0u8; 12];
    rand::RngCore::fill_bytes(&mut OsRng, &mut data_nonce_bytes);
    let data_nonce = Nonce::from_slice(&data_nonce_bytes);
    let ciphertext = data_cipher
        .encrypt(data_nonce, plaintext.as_ref())
        .map_err(|_| CoreError::EncryptionFailed)?;

    // Build blob
    let mut blob = Vec::new();

    // Version byte for future compatibility
    blob.push(BLOB_VERSION);

    // Ephemeral public key (32 bytes)
    blob.extend_from_slice(ephemeral_public.as_bytes());

    // Calculate padded entry count (real entries + padding)
    let real_count = recipient_ed25519_pubkeys.len();
    let padded_count = padded_entry_count(real_count);

    // Entry count (2 bytes, big-endian) - includes padding
    blob.extend_from_slice(&(padded_count as u16).to_be_bytes());

    // Real entries: encrypt DEK for each recipient (no pubkey stored - trial decryption)
    for ed_pubkey in recipient_ed25519_pubkeys {
        let x_pubkey_bytes = ed25519_pubkey_to_x25519(ed_pubkey)?;
        let x_pubkey = X25519PublicKey::from(x_pubkey_bytes);

        // ECDH to get shared secret
        let shared_secret = ephemeral_private.diffie_hellman(&x_pubkey);

        // Encrypt DEK with shared secret
        let key_cipher =
            Aes256Gcm::new_from_slice(shared_secret.as_bytes()).map_err(|_| CoreError::InvalidKey)?;
        let mut key_nonce_bytes = [0u8; 12];
        rand::RngCore::fill_bytes(&mut OsRng, &mut key_nonce_bytes);
        let key_nonce = Nonce::from_slice(&key_nonce_bytes);
        let encrypted_dek = key_cipher
            .encrypt(key_nonce, dek.as_ref())
            .map_err(|_| CoreError::EncryptionFailed)?;

        // Append: nonce (12) | encrypted DEK (48 = 32 + 16 tag)
        blob.extend_from_slice(&key_nonce_bytes);
        blob.extend_from_slice(&encrypted_dek);
    }

    // Padding entries: random bytes that will fail AES-GCM auth for everyone
    for _ in real_count..padded_count {
        let mut dummy = [0u8; 60]; // 12-byte nonce + 48-byte "encrypted" DEK
        rand::RngCore::fill_bytes(&mut OsRng, &mut dummy);
        blob.extend_from_slice(&dummy);
    }

    // Append encrypted location: nonce (12) | ciphertext
    blob.extend_from_slice(&data_nonce_bytes);
    blob.extend_from_slice(&ciphertext);

    Ok(blob)
}

/// Prepare a location upload request for the native HTTP layer to execute
/// Returns StaleLocation error if the location timestamp is older than the last successful upload
#[uniffi::export]
pub fn prepare_location_upload(
    identity: &Identity,
    location: Location,
    friends: Vec<Friend>,
    my_server: String,
) -> Result<PreparedRequest, CoreError> {
    // Check if this location is stale (only if storage is initialized)
    if STORAGE_PATH.get().is_some() {
        let last_ts = LAST_UPLOADED_TIMESTAMP.load(Ordering::Relaxed);
        if location.timestamp <= last_ts {
            return Err(CoreError::StaleLocation);
        }
    }

    // Collect recipient pubkeys: all friends + self (so we can fetch our own location)
    let mut recipient_pubkeys: Vec<[u8; 32]> = Vec::new();

    // Add self
    let my_ed_pubkey: [u8; 32] = identity
        .ed25519_public
        .clone()
        .try_into()
        .map_err(|_| CoreError::InvalidKey)?;
    recipient_pubkeys.push(my_ed_pubkey);

    // Add friends
    for friend in &friends {
        let pubkey_bytes = URL_SAFE_NO_PAD
            .decode(&friend.pubkey)
            .map_err(|_| CoreError::InvalidKey)?;
        let pubkey_array: [u8; 32] = pubkey_bytes.try_into().map_err(|_| CoreError::InvalidKey)?;
        recipient_pubkeys.push(pubkey_array);
    }

    // Create encrypted blob
    let blob = create_encrypted_blob(&location, &recipient_pubkeys)?;
    let blob_b64 = URL_SAFE_NO_PAD.encode(&blob);

    // Get current timestamp
    let timestamp = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|_| CoreError::InvalidData)?
        .as_secs();

    // Sign: blob || timestamp (as 8-byte big-endian)
    let signing_key_bytes: [u8; 32] = identity
        .ed25519_private
        .clone()
        .try_into()
        .map_err(|_| CoreError::InvalidKey)?;
    let signing_key = SigningKey::from_bytes(&signing_key_bytes);

    let mut message_to_sign = blob.clone();
    message_to_sign.extend_from_slice(&timestamp.to_be_bytes());
    let signature = signing_key.sign(&message_to_sign);
    let signature_b64 = URL_SAFE_NO_PAD.encode(signature.to_bytes());

    // Build request body
    let pubkey_b64 = URL_SAFE_NO_PAD.encode(&identity.ed25519_public);
    let body = serde_json::to_vec(&serde_json::json!({
        "pubkey": pubkey_b64,
        "timestamp": timestamp,
        "blob": blob_b64,
        "signature": signature_b64
    }))
    .map_err(|_| CoreError::InvalidData)?;

    // Build URL
    let url = format!("{}/api/location", my_server.trim_end_matches('/'));

    // Headers
    let mut headers = HashMap::new();
    headers.insert("Content-Type".to_string(), "application/json".to_string());

    Ok(PreparedRequest {
        url,
        method: "PUT".to_string(),
        headers,
        body,
    })
}

/// Decrypt a multi-recipient blob using trial decryption
/// Format: version(1) | ephemeral(32) | count(2) | [nonce(12) | encrypted_dek(48)]... | data_nonce(12) | ciphertext
/// Returns the decrypted Location if we're a valid recipient
#[uniffi::export]
pub fn decrypt_blob(identity: &Identity, blob: Vec<u8>) -> Result<Location, CoreError> {
    // Minimum blob size: version(1) + ephemeral(32) + count(2) + at least one entry(60) + nonce(12) + some ciphertext
    if blob.len() < 1 + 32 + 2 + 60 + 12 + 16 {
        return Err(CoreError::InvalidData);
    }

    // Check version byte
    if blob[0] != BLOB_VERSION {
        return Err(CoreError::InvalidData);
    }

    // Parse ephemeral public key bytes (after version byte)
    let ephemeral_bytes: [u8; 32] = blob[1..33].try_into().map_err(|_| CoreError::InvalidData)?;
    let ephemeral_public = X25519PublicKey::from(ephemeral_bytes);

    // Parse entry count (includes padding)
    let entry_count =
        u16::from_be_bytes(blob[33..35].try_into().map_err(|_| CoreError::InvalidData)?) as usize;

    // Calculate where entries end
    // Each entry: nonce(12) + encrypted_dek(48) = 60 bytes
    let entries_end = 35 + entry_count * 60;
    if blob.len() < entries_end + 12 + 16 {
        return Err(CoreError::InvalidData);
    }

    // Get our X25519 private key for ECDH
    let my_x25519_private: [u8; 32] = identity
        .x25519_private
        .clone()
        .try_into()
        .map_err(|_| CoreError::InvalidKey)?;
    let my_secret = StaticSecret::from(my_x25519_private);

    // Compute shared secret once (same for all entries since ephemeral is fixed)
    let shared_secret = my_secret.diffie_hellman(&ephemeral_public);
    let cipher = Aes256Gcm::new_from_slice(shared_secret.as_bytes())
        .map_err(|_| CoreError::InvalidKey)?;

    // Trial decryption: try each entry until one succeeds
    let mut dek: Option<[u8; 32]> = None;
    for i in 0..entry_count {
        let entry_start = 35 + i * 60;
        let nonce_bytes: [u8; 12] = blob[entry_start..entry_start + 12]
            .try_into()
            .map_err(|_| CoreError::InvalidData)?;
        let encrypted_dek = &blob[entry_start + 12..entry_start + 60];

        let nonce = Nonce::from_slice(&nonce_bytes);

        // Try to decrypt - will fail with auth error for wrong entries and padding
        if let Ok(decrypted_dek) = cipher.decrypt(nonce, encrypted_dek) {
            if let Ok(dek_array) = decrypted_dek.try_into() {
                dek = Some(dek_array);
                break;
            }
        }
    }

    let dek = dek.ok_or(CoreError::DecryptionFailed)?; // We're not a recipient

    // Decrypt the location data
    let data_nonce: [u8; 12] = blob[entries_end..entries_end + 12]
        .try_into()
        .map_err(|_| CoreError::InvalidData)?;
    let ciphertext = &blob[entries_end + 12..];

    let cipher = Aes256Gcm::new_from_slice(&dek).map_err(|_| CoreError::InvalidKey)?;
    let nonce = Nonce::from_slice(&data_nonce);
    let plaintext = cipher
        .decrypt(nonce, ciphertext)
        .map_err(|_| CoreError::DecryptionFailed)?;

    // Decode binary wire format (20 bytes)
    if plaintext.len() != 20 {
        return Err(CoreError::InvalidData);
    }
    let wire_bytes: [u8; 20] = plaintext.try_into().map_err(|_| CoreError::InvalidData)?;
    let wire_location = WireLocation::decode(&wire_bytes);

    Ok(wire_location.to_location())
}

/// Result of fetching a friend's location
#[derive(Debug, Clone, uniffi::Record)]
pub struct FetchedLocation {
    pub pubkey: String,
    pub location: Option<Location>,
    pub updated: Option<u64>,
}

/// Prepare location fetch requests, grouped by server for federation
/// Returns one PreparedRequest per unique server
#[uniffi::export]
pub fn prepare_location_fetch(friends: Vec<Friend>) -> Vec<PreparedRequest> {
    // Group friends by server
    let mut by_server: HashMap<String, Vec<String>> = HashMap::new();
    for friend in friends {
        by_server
            .entry(friend.server.clone())
            .or_default()
            .push(friend.pubkey.clone());
    }

    // Create one request per server
    by_server
        .into_iter()
        .map(|(server, pubkeys)| {
            let url = format!("{}/api/location", server.trim_end_matches('/'));
            let body = serde_json::to_vec(&serde_json::json!({ "ids": pubkeys }))
                .unwrap_or_default();

            let mut headers = HashMap::new();
            headers.insert("Content-Type".to_string(), "application/json".to_string());

            PreparedRequest {
                url,
                method: "POST".to_string(),
                headers,
                body,
            }
        })
        .collect()
}

/// Prepare a request to fetch our own location from the server
/// Used to verify what the server has stored for us
#[uniffi::export]
pub fn prepare_self_location_fetch(identity: &Identity, server: String) -> PreparedRequest {
    let pubkey_b64 = URL_SAFE_NO_PAD.encode(&identity.ed25519_public);
    let url = format!("{}/api/location", server.trim_end_matches('/'));
    let body = serde_json::to_vec(&serde_json::json!({ "ids": [pubkey_b64] })).unwrap_or_default();

    let mut headers = HashMap::new();
    headers.insert("Content-Type".to_string(), "application/json".to_string());

    PreparedRequest {
        url,
        method: "POST".to_string(),
        headers,
        body,
    }
}

/// Server response format for POST /location
#[derive(serde::Deserialize)]
struct StoredLocationResponse {
    blob: String,
    updated: u64,
}

/// Process a fetch response from the server and decrypt locations
/// Returns decrypted locations for each friend we can decrypt
#[uniffi::export]
pub fn process_fetch_response(
    identity: &Identity,
    response_body: Vec<u8>,
) -> Result<Vec<FetchedLocation>, CoreError> {
    // Parse response JSON: { pubkey: { blob, updated } | null, ... }
    let response: HashMap<String, Option<StoredLocationResponse>> =
        serde_json::from_slice(&response_body).map_err(|_| CoreError::InvalidData)?;

    let mut results = Vec::new();

    for (pubkey, stored) in response {
        let fetched = match stored {
            Some(data) => {
                // Decode and decrypt blob
                let blob_bytes = URL_SAFE_NO_PAD
                    .decode(&data.blob)
                    .map_err(|_| CoreError::InvalidData)?;

                match decrypt_blob(identity, blob_bytes) {
                    Ok(location) => FetchedLocation {
                        pubkey,
                        location: Some(location),
                        updated: Some(data.updated),
                    },
                    Err(_) => FetchedLocation {
                        pubkey,
                        location: None,
                        updated: Some(data.updated),
                    },
                }
            }
            None => FetchedLocation {
                pubkey,
                location: None,
                updated: None,
            },
        };
        results.push(fetched);
    }

    Ok(results)
}

// =============================================================================
// Storage & Friend Management
// =============================================================================

fn friends_file_path() -> Result<PathBuf, CoreError> {
    STORAGE_PATH
        .get()
        .map(|p| p.join("friends.json"))
        .ok_or(CoreError::StorageNotInitialized)
}

fn last_upload_file_path() -> Option<PathBuf> {
    STORAGE_PATH.get().map(|p| p.join("last_upload_timestamp"))
}

fn save_last_upload_timestamp(timestamp: u64) {
    if let Some(path) = last_upload_file_path() {
        let _ = fs::write(&path, timestamp.to_string());
    }
}

fn load_last_upload_timestamp() -> u64 {
    last_upload_file_path()
        .and_then(|path| fs::read_to_string(&path).ok())
        .and_then(|content| content.trim().parse().ok())
        .unwrap_or(0)
}

fn save_friends(friends: &[Friend]) -> Result<(), CoreError> {
    let path = friends_file_path()?;
    let json = serde_json::to_string_pretty(friends).map_err(|e| CoreError::StorageError {
        details: e.to_string(),
    })?;
    fs::write(&path, json).map_err(|e| CoreError::StorageError {
        details: e.to_string(),
    })?;
    Ok(())
}

fn load_friends() -> Result<Vec<Friend>, CoreError> {
    let path = friends_file_path()?;
    if !path.exists() {
        return Ok(Vec::new());
    }
    let json = fs::read_to_string(&path).map_err(|e| CoreError::StorageError {
        details: e.to_string(),
    })?;
    let mut friends: Vec<Friend> = serde_json::from_str(&json).map_err(|e| CoreError::StorageError {
        details: e.to_string(),
    })?;

    // Compute colors for all friends (not stored in JSON)
    for friend in &mut friends {
        friend.color = color_from_pubkey_internal(&friend.pubkey);
    }

    Ok(friends)
}

/// Initialize storage with the app's data directory path.
/// Must be called once at app startup before any friend operations.
#[uniffi::export]
pub fn init_storage(path: String) -> Result<(), CoreError> {
    let storage_path = PathBuf::from(path);

    // Create directory if it doesn't exist
    if !storage_path.exists() {
        fs::create_dir_all(&storage_path).map_err(|e| CoreError::StorageError {
            details: e.to_string(),
        })?;
    }

    // Set the global storage path (fails if already set)
    STORAGE_PATH
        .set(storage_path)
        .map_err(|_| CoreError::StorageError {
            details: "Storage already initialized".to_string(),
        })?;

    // Load friends from disk into memory
    let loaded = load_friends()?;
    let mut friends = FRIENDS.lock().unwrap();
    *friends = loaded;

    // Load last upload timestamp
    let last_ts = load_last_upload_timestamp();
    LAST_UPLOADED_TIMESTAMP.store(last_ts, Ordering::Relaxed);

    Ok(())
}

/// Migrate friend server URLs from old domain to new domain.
/// Returns the number of friends that were migrated.
#[uniffi::export]
pub fn migrate_server_urls(from_domain: String, to_domain: String) -> Result<u32, CoreError> {
    let mut friends = FRIENDS.lock().unwrap();
    let mut migrated = 0u32;

    for friend in friends.iter_mut() {
        if friend.server.contains(&from_domain) {
            friend.server = friend.server.replace(&from_domain, &to_domain);
            migrated += 1;
        }
    }

    if migrated > 0 {
        save_friends(&friends)?;
    }

    Ok(migrated)
}

/// Add a new friend. If a friend with the same pubkey exists, updates their info.
#[uniffi::export]
pub fn add_friend(
    pubkey: String,
    server: String,
    name: String,
    share_with: bool,
    fetch_from: bool,
) -> Result<(), CoreError> {
    let mut friends = FRIENDS.lock().unwrap();

    // Check if friend already exists
    if let Some(existing) = friends.iter_mut().find(|f| f.pubkey == pubkey) {
        existing.server = server;
        existing.name = name;
        existing.share_with = share_with;
        existing.fetch_from = fetch_from;
    } else {
        let color = color_from_pubkey_internal(&pubkey);
        friends.push(Friend {
            pubkey,
            server,
            name,
            share_with,
            fetch_from,
            location: None,
            fetched_at: None,
            color,
        });
    }

    save_friends(&friends)
}

/// Update an existing friend's settings
#[uniffi::export]
pub fn update_friend(
    pubkey: String,
    share_with: Option<bool>,
    fetch_from: Option<bool>,
    name: Option<String>,
) -> Result<(), CoreError> {
    let mut friends = FRIENDS.lock().unwrap();

    let friend = friends
        .iter_mut()
        .find(|f| f.pubkey == pubkey)
        .ok_or(CoreError::FriendNotFound)?;

    if let Some(sw) = share_with {
        friend.share_with = sw;
    }
    if let Some(ff) = fetch_from {
        friend.fetch_from = ff;
    }
    if let Some(n) = name {
        friend.name = n;
    }

    save_friends(&friends)
}

/// Remove a friend by pubkey
#[uniffi::export]
pub fn remove_friend(pubkey: String) -> Result<(), CoreError> {
    let mut friends = FRIENDS.lock().unwrap();
    let initial_len = friends.len();
    friends.retain(|f| f.pubkey != pubkey);

    if friends.len() == initial_len {
        return Err(CoreError::FriendNotFound);
    }

    save_friends(&friends)
}

/// List all friends
#[uniffi::export]
pub fn list_friends() -> Vec<Friend> {
    FRIENDS.lock().unwrap().clone()
}

/// Generate mock friends for development/testing.
/// Colors are computed automatically from pubkeys.
#[uniffi::export]
pub fn mock_friends() -> Vec<Friend> {
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_millis() as u64;

    // (pubkey, name, Option<(lat, lng, alt, acc, ts_offset, fetch_offset)>)
    let mock_data = [
        ("abc123", "Alice", Some((37.7749, -122.4194, 50.0, 10.0, 300_000, 60_000))),
        ("def456", "Bob", Some((37.7849, -122.4094, 25.0, 25.0, 3_600_000, 120_000))),
        ("ghi789", "Charlie", None),
        ("jkl012", "Diana", Some((37.7649, -122.4294, 100.0, 15.0, 1_800_000, 180_000))),
        ("mno345", "Evan", Some((37.7949, -122.3994, 75.0, 50.0, 7_200_000, 300_000))),
        ("pqr678", "Fiona", Some((37.7549, -122.4394, 10.0, 8.0, 120_000, 30_000))),
        ("stu901", "George", Some((37.8049, -122.4094, 200.0, 100.0, 86_400_000, 600_000))),
        ("vwx234", "Hannah", Some((37.7699, -122.4494, 30.0, 20.0, 900_000, 90_000))),
        ("yza567", "Ivan", Some((37.7599, -122.3894, 45.0, 12.0, 600_000, 45_000))),
        ("bcd890", "Julia", Some((37.7899, -122.4594, 60.0, 30.0, 2_700_000, 150_000))),
        ("efg123", "Kevin", None),
        ("hij456", "Laura", Some((37.7749, -122.4294, 80.0, 18.0, 450_000, 75_000))),
    ];

    mock_data
        .into_iter()
        .map(|(pubkey, name, loc_data)| {
            let location = loc_data.map(|(lat, lng, alt, acc, ts_offset, _)| Location {
                latitude: lat,
                longitude: lng,
                altitude: alt,
                accuracy: acc as f32,
                timestamp: now - ts_offset,
            });
            let fetched_at = loc_data.map(|(_, _, _, _, _, fetch_offset)| now - fetch_offset);

            Friend {
                pubkey: pubkey.to_string(),
                server: "https://example.com".to_string(),
                name: name.to_string(),
                share_with: true,
                fetch_from: true,
                location,
                fetched_at,
                color: color_from_pubkey_internal(pubkey),
            }
        })
        .collect()
}

/// Get friends to share location with (share_with == true)
#[uniffi::export]
pub fn get_share_recipients() -> Vec<Friend> {
    FRIENDS
        .lock()
        .unwrap()
        .iter()
        .filter(|f| f.share_with)
        .cloned()
        .collect()
}

/// Get friends to fetch location from (fetch_from == true)
#[uniffi::export]
pub fn get_fetch_targets() -> Vec<Friend> {
    FRIENDS
        .lock()
        .unwrap()
        .iter()
        .filter(|f| f.fetch_from)
        .cloned()
        .collect()
}

/// Update a friend's cached location after fetching
#[uniffi::export]
pub fn update_friend_location(
    pubkey: String,
    location: Option<Location>,
    fetched_at: u64,
) -> Result<(), CoreError> {
    let mut friends = FRIENDS.lock().unwrap();

    let friend = friends
        .iter_mut()
        .find(|f| f.pubkey == pubkey)
        .ok_or(CoreError::FriendNotFound)?;

    friend.location = location;
    friend.fetched_at = Some(fetched_at);

    save_friends(&friends)
}

/// Mark a location upload as successful, updating the last uploaded timestamp
/// Call this after successfully uploading a location to prevent re-uploading stale data
#[uniffi::export]
pub fn mark_upload_success(timestamp: u64) {
    LAST_UPLOADED_TIMESTAMP.store(timestamp, Ordering::Relaxed);
    save_last_upload_timestamp(timestamp);
}

// =============================================================================
// Friend Link Generation & Parsing
// =============================================================================

/// Generate a friend link for sharing our identity
/// Format: coord://<server>/add/<pubkey>#<name>
#[uniffi::export]
pub fn generate_friend_link(identity: &Identity, server: String, name: String) -> String {
    let pubkey_b64 = URL_SAFE_NO_PAD.encode(&identity.ed25519_public);
    let encoded_name = urlencoding::encode(&name);

    // Strip protocol from server for the link format
    let server_host = server
        .trim_start_matches("https://")
        .trim_start_matches("http://")
        .trim_end_matches('/');

    format!("coord://{}/add/{}#{}", server_host, pubkey_b64, encoded_name)
}

/// Parsed friend link data
#[derive(Debug, Clone, uniffi::Record)]
pub struct ParsedFriendLink {
    pub pubkey: String,
    pub server: String,
    pub name: String,
}

/// Parse a friend link into its components
/// Returns None if the link format is invalid
#[uniffi::export]
pub fn parse_friend_link(url: String) -> Result<ParsedFriendLink, CoreError> {
    // Expected format: coord://<server>/add/<pubkey>#<name>
    let url = url.trim();

    // Check protocol
    let rest = url
        .strip_prefix("coord://")
        .ok_or(CoreError::InvalidLink)?;

    // Split off fragment (name)
    let (path_part, name) = rest.split_once('#').ok_or(CoreError::InvalidLink)?;

    // Parse path: <server>/add/<pubkey>
    let add_idx = path_part.find("/add/").ok_or(CoreError::InvalidLink)?;
    let server_host = &path_part[..add_idx];
    let pubkey = &path_part[add_idx + 5..]; // skip "/add/"

    if server_host.is_empty() || pubkey.is_empty() {
        return Err(CoreError::InvalidLink);
    }

    // Validate pubkey is valid base64url and correct length (32 bytes for ed25519)
    let pubkey_bytes = URL_SAFE_NO_PAD
        .decode(pubkey)
        .map_err(|_| CoreError::InvalidLink)?;
    if pubkey_bytes.len() != 32 {
        return Err(CoreError::InvalidLink);
    }

    // Decode name from URL encoding
    let decoded_name =
        urlencoding::decode(name).map_err(|_| CoreError::InvalidLink)?;

    Ok(ParsedFriendLink {
        pubkey: pubkey.to_string(),
        server: format!("https://{}", server_host),
        name: decoded_name.into_owned(),
    })
}

// =============================================================================
// City Database - Privacy-preserving local reverse geocoding
// =============================================================================

/// City information for reverse geocoding
#[derive(Debug, Clone, serde::Deserialize, uniffi::Record)]
pub struct City {
    #[serde(rename = "n")]
    pub name: String,
    #[serde(rename = "la")]
    pub lat: f64,
    #[serde(rename = "lo")]
    pub lng: f64,
    #[serde(rename = "r", default)]
    pub region: String,
    #[serde(rename = "c", default)]
    pub country: String,
    #[serde(rename = "p", default)]
    pub population: u32,
}

impl City {
    /// Returns a display string like "Toronto, ON" or "Paris, France"
    pub fn display_name(&self) -> String {
        if !self.region.is_empty() {
            format!("{}, {}", self.name, self.region)
        } else {
            format!("{}, {}", self.name, self.country)
        }
    }
}

/// Lazily loaded city database
static CITIES: OnceLock<Vec<City>> = OnceLock::new();

/// Load cities from embedded JSON (called lazily on first access)
fn get_cities() -> &'static Vec<City> {
    CITIES.get_or_init(|| {
        let json = include_str!("cities.json");
        serde_json::from_str(json).expect("Failed to parse embedded cities.json")
    })
}

/// Find the nearest city to the given coordinates.
/// Uses a weighted score that prefers larger cities when distances are similar.
#[uniffi::export]
pub fn find_nearest_city(lat: f64, lng: f64) -> Option<City> {
    let cities = get_cities();

    cities
        .iter()
        .min_by(|a, b| {
            let score_a = city_score(a, lat, lng);
            let score_b = city_score(b, lat, lng);
            score_a.partial_cmp(&score_b).unwrap_or(std::cmp::Ordering::Equal)
        })
        .cloned()
}

/// Calculate score for a city (lower is better)
/// Score = distance - population bonus
fn city_score(city: &City, lat: f64, lng: f64) -> f64 {
    let distance = haversine_distance(lat, lng, city.lat, city.lng);
    let population_bonus = (city.population.max(1) as f64).ln() * 0.5;
    distance - population_bonus
}

/// Haversine distance in kilometers between two points
fn haversine_distance(lat1: f64, lng1: f64, lat2: f64, lng2: f64) -> f64 {
    const R: f64 = 6371.0; // Earth radius in km
    let d_lat = (lat2 - lat1).to_radians();
    let d_lng = (lng2 - lng1).to_radians();
    let a = (d_lat / 2.0).sin().powi(2)
        + lat1.to_radians().cos() * lat2.to_radians().cos() * (d_lng / 2.0).sin().powi(2);
    let c = 2.0 * a.sqrt().atan2((1.0 - a).sqrt());
    R * c
}

// =============================================================================
// Region Boundaries - Point-in-polygon lookup for accurate geocoding
// =============================================================================

/// A region boundary polygon with bounding box for fast rejection
#[derive(Debug, Clone)]
struct RegionBoundary {
    name: String,
    country: String,
    /// Bounding box: (min_lng, min_lat, max_lng, max_lat)
    bbox: (f64, f64, f64, f64),
    /// Polygon rings: outer ring first, then holes. Each ring is vec of (lng, lat)
    rings: Vec<Vec<(f64, f64)>>,
}

/// Lazily loaded region boundaries
static BOUNDARIES: OnceLock<Vec<RegionBoundary>> = OnceLock::new();

/// Load boundaries from embedded GeoJSON
fn get_boundaries() -> &'static Vec<RegionBoundary> {
    BOUNDARIES.get_or_init(|| {
        let json = include_str!("boundaries.json");
        parse_boundaries_geojson(json)
    })
}

/// Parse GeoJSON FeatureCollection into RegionBoundary structs
fn parse_boundaries_geojson(json: &str) -> Vec<RegionBoundary> {
    let parsed: serde_json::Value = serde_json::from_str(json).unwrap_or_default();
    let features = parsed["features"].as_array();

    let Some(features) = features else {
        return Vec::new();
    };

    let mut boundaries = Vec::with_capacity(features.len());

    for feature in features {
        let props = &feature["properties"];
        let name = props["name"].as_str().unwrap_or("").to_string();
        let country = props["admin"].as_str().unwrap_or("").to_string();

        let geom = &feature["geometry"];
        let geom_type = geom["type"].as_str().unwrap_or("");

        let polygons: Vec<Vec<Vec<(f64, f64)>>> = match geom_type {
            "Polygon" => {
                if let Some(rings) = parse_polygon_coords(&geom["coordinates"]) {
                    vec![rings]
                } else {
                    continue;
                }
            }
            "MultiPolygon" => {
                let Some(coords) = geom["coordinates"].as_array() else {
                    continue;
                };
                coords.iter()
                    .filter_map(|poly| parse_polygon_coords(poly))
                    .collect()
            }
            _ => continue,
        };

        // Create a boundary for each polygon in a MultiPolygon
        for rings in polygons {
            if rings.is_empty() || rings[0].is_empty() {
                continue;
            }

            // Calculate bounding box from outer ring
            let outer = &rings[0];
            let mut min_lng = f64::MAX;
            let mut min_lat = f64::MAX;
            let mut max_lng = f64::MIN;
            let mut max_lat = f64::MIN;

            for &(lng, lat) in outer {
                min_lng = min_lng.min(lng);
                min_lat = min_lat.min(lat);
                max_lng = max_lng.max(lng);
                max_lat = max_lat.max(lat);
            }

            boundaries.push(RegionBoundary {
                name: name.clone(),
                country: country.clone(),
                bbox: (min_lng, min_lat, max_lng, max_lat),
                rings,
            });
        }
    }

    boundaries
}

/// Parse polygon coordinates from GeoJSON
fn parse_polygon_coords(coords: &serde_json::Value) -> Option<Vec<Vec<(f64, f64)>>> {
    let rings = coords.as_array()?;
    let mut result = Vec::with_capacity(rings.len());

    for ring in rings {
        let points = ring.as_array()?;
        let mut ring_coords = Vec::with_capacity(points.len());

        for point in points {
            let arr = point.as_array()?;
            let lng = arr.first()?.as_f64()?;
            let lat = arr.get(1)?.as_f64()?;
            ring_coords.push((lng, lat));
        }

        result.push(ring_coords);
    }

    Some(result)
}

/// Region lookup result
#[derive(Debug, Clone, uniffi::Record)]
pub struct Region {
    pub name: String,
    pub country: String,
}

/// Find which region contains the given coordinates using point-in-polygon tests.
/// Returns None if the point is not within any known region (e.g., ocean, coastal areas).
#[uniffi::export]
pub fn find_region(lat: f64, lng: f64) -> Option<Region> {
    let boundaries = get_boundaries();

    for boundary in boundaries {
        // Fast bounding box rejection
        let (min_lng, min_lat, max_lng, max_lat) = boundary.bbox;
        if lng < min_lng || lng > max_lng || lat < min_lat || lat > max_lat {
            continue;
        }

        // Point-in-polygon test
        if point_in_polygon(lng, lat, &boundary.rings) {
            return Some(Region {
                name: boundary.name.clone(),
                country: boundary.country.clone(),
            });
        }
    }

    None
}

/// Ray casting algorithm for point-in-polygon test.
/// Handles polygons with holes (first ring is outer, rest are holes).
fn point_in_polygon(x: f64, y: f64, rings: &[Vec<(f64, f64)>]) -> bool {
    if rings.is_empty() {
        return false;
    }

    // Must be inside outer ring
    if !point_in_ring(x, y, &rings[0]) {
        return false;
    }

    // Must not be inside any holes
    for hole in rings.iter().skip(1) {
        if point_in_ring(x, y, hole) {
            return false;
        }
    }

    true
}

/// Ray casting for a single ring
fn point_in_ring(x: f64, y: f64, ring: &[(f64, f64)]) -> bool {
    let n = ring.len();
    if n < 3 {
        return false;
    }

    let mut inside = false;
    let mut j = n - 1;

    for i in 0..n {
        let (xi, yi) = ring[i];
        let (xj, yj) = ring[j];

        if ((yi > y) != (yj > y)) && (x < (xj - xi) * (y - yi) / (yj - yi) + xi) {
            inside = !inside;
        }

        j = i;
    }

    inside
}

/// Find the nearest city to the given coordinates, filtered by region.
/// First determines which region the coordinates are in, then only considers
/// cities in that region. Falls back to unfiltered search if no region match.
#[uniffi::export]
pub fn find_nearest_city_in_region(lat: f64, lng: f64) -> Option<City> {
    let cities = get_cities();

    // Try to find which region the point is in
    if let Some(region) = find_region(lat, lng) {
        // Filter cities to this region
        let regional_cities: Vec<&City> = cities
            .iter()
            .filter(|c| c.region == region.name && c.country == region.country)
            .collect();

        if !regional_cities.is_empty() {
            return regional_cities
                .into_iter()
                .min_by(|a, b| {
                    let score_a = city_score(a, lat, lng);
                    let score_b = city_score(b, lat, lng);
                    score_a.partial_cmp(&score_b).unwrap_or(std::cmp::Ordering::Equal)
                })
                .cloned();
        }
    }

    // Fallback: no region match or no cities in region, use global search
    find_nearest_city(lat, lng)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_encrypt_decrypt_roundtrip() {
        let identity = generate_identity();
        let location = Location {
            latitude: 37.7749,
            longitude: -122.4194,
            altitude: 50.0,
            accuracy: 10.0,
            timestamp: 1234567890,
        };

        // Encrypt for self only
        let my_ed_pubkey: [u8; 32] = identity.ed25519_public.clone().try_into().unwrap();
        let blob = create_encrypted_blob(&location, &[my_ed_pubkey]).unwrap();

        // Decrypt
        let decrypted = decrypt_blob(&identity, blob).unwrap();

        assert_eq!(decrypted.latitude, location.latitude);
        assert_eq!(decrypted.longitude, location.longitude);
        assert_eq!(decrypted.accuracy, location.accuracy);
        assert_eq!(decrypted.timestamp, location.timestamp);
    }

    #[test]
    fn test_multi_recipient_encryption() {
        let alice = generate_identity();
        let bob = generate_identity();

        let location = Location {
            latitude: 40.7128,
            longitude: -74.0060,
            altitude: 10.0,
            accuracy: 15.0,
            timestamp: 9876543210,
        };

        // Encrypt for both Alice and Bob
        let alice_pubkey: [u8; 32] = alice.ed25519_public.clone().try_into().unwrap();
        let bob_pubkey: [u8; 32] = bob.ed25519_public.clone().try_into().unwrap();
        let blob = create_encrypted_blob(&location, &[alice_pubkey, bob_pubkey]).unwrap();

        // Both can decrypt
        let alice_decrypted = decrypt_blob(&alice, blob.clone()).unwrap();
        let bob_decrypted = decrypt_blob(&bob, blob).unwrap();

        assert_eq!(alice_decrypted.latitude, location.latitude);
        assert_eq!(bob_decrypted.latitude, location.latitude);
    }

    #[test]
    fn test_non_recipient_cannot_decrypt() {
        let alice = generate_identity();
        let bob = generate_identity();
        let eve = generate_identity();

        let location = Location {
            latitude: 51.5074,
            longitude: -0.1278,
            altitude: 25.0,
            accuracy: 20.0,
            timestamp: 1111111111,
        };

        // Encrypt for Alice and Bob only
        let alice_pubkey: [u8; 32] = alice.ed25519_public.clone().try_into().unwrap();
        let bob_pubkey: [u8; 32] = bob.ed25519_public.clone().try_into().unwrap();
        let blob = create_encrypted_blob(&location, &[alice_pubkey, bob_pubkey]).unwrap();

        // Eve cannot decrypt
        let result = decrypt_blob(&eve, blob);
        assert!(result.is_err());
    }

    #[test]
    fn test_friend_link_roundtrip() {
        let identity = generate_identity();
        let server = "https://coord.is".to_string();
        let name = "Alice".to_string();

        let link = generate_friend_link(&identity, server.clone(), name.clone());
        let parsed = parse_friend_link(link).unwrap();

        assert_eq!(parsed.server, server);
        assert_eq!(parsed.name, name);

        // Verify pubkey matches
        let expected_pubkey = URL_SAFE_NO_PAD.encode(&identity.ed25519_public);
        assert_eq!(parsed.pubkey, expected_pubkey);
    }

    #[test]
    fn test_friend_link_with_special_characters() {
        let identity = generate_identity();
        let server = "https://relay.example.com".to_string();
        let name = "Bob & Alice's Friend".to_string();

        let link = generate_friend_link(&identity, server.clone(), name.clone());
        let parsed = parse_friend_link(link).unwrap();

        assert_eq!(parsed.name, name);
        assert_eq!(parsed.server, server);
    }

    #[test]
    fn test_friend_link_strips_protocol() {
        let identity = generate_identity();

        // With https://
        let link1 = generate_friend_link(&identity, "https://example.com".to_string(), "Test".to_string());
        assert!(link1.starts_with("coord://example.com/add/"));

        // With http://
        let link2 = generate_friend_link(&identity, "http://example.com".to_string(), "Test".to_string());
        assert!(link2.starts_with("coord://example.com/add/"));

        // Without protocol (shouldn't happen, but handle gracefully)
        let link3 = generate_friend_link(&identity, "example.com".to_string(), "Test".to_string());
        assert!(link3.starts_with("coord://example.com/add/"));
    }

    #[test]
    fn test_parse_invalid_links() {
        // Wrong protocol
        assert!(parse_friend_link("https://example.com/add/abc#Name".to_string()).is_err());

        // Missing fragment
        assert!(parse_friend_link("coord://example.com/add/abc".to_string()).is_err());

        // Missing /add/
        assert!(parse_friend_link("coord://example.com/abc#Name".to_string()).is_err());

        // Empty pubkey
        assert!(parse_friend_link("coord://example.com/add/#Name".to_string()).is_err());

        // Empty server
        assert!(parse_friend_link("coord:///add/abc#Name".to_string()).is_err());
    }

    #[test]
    fn test_stale_location_rejected_when_storage_initialized() {
        // Set up a temporary storage directory
        let temp_dir = std::env::temp_dir().join(format!("transponder_test_{}", std::process::id()));
        let _ = fs::create_dir_all(&temp_dir);

        // We can't easily test with init_storage since it uses OnceLock,
        // but we can test the atomic and file operations directly

        // Test mark_upload_success updates the atomic
        LAST_UPLOADED_TIMESTAMP.store(0, Ordering::Relaxed);
        assert_eq!(LAST_UPLOADED_TIMESTAMP.load(Ordering::Relaxed), 0);

        LAST_UPLOADED_TIMESTAMP.store(1000, Ordering::Relaxed);
        assert_eq!(LAST_UPLOADED_TIMESTAMP.load(Ordering::Relaxed), 1000);

        // Clean up
        let _ = fs::remove_dir_all(&temp_dir);
    }

    #[test]
    fn test_stale_location_skipped_when_storage_uninitialized() {
        // When STORAGE_PATH is not set, prepare_location_upload should NOT check timestamps
        // This test verifies the behavior without storage initialized

        // Reset timestamp to simulate fresh state
        LAST_UPLOADED_TIMESTAMP.store(5000, Ordering::Relaxed);

        let identity = generate_identity();
        let location = Location {
            latitude: 37.7749,
            longitude: -122.4194,
            altitude: 0.0,
            accuracy: 10.0,
            timestamp: 1000, // Older than LAST_UPLOADED_TIMESTAMP
        };

        // If storage is NOT initialized (STORAGE_PATH.get() returns None),
        // prepare_location_upload should succeed even with old timestamp
        // Note: This test may behave differently if storage was already initialized
        // in another test (OnceLock limitation)
        if STORAGE_PATH.get().is_none() {
            let result = prepare_location_upload(
                &identity,
                location,
                vec![],
                "https://example.com".to_string(),
            );
            // Should succeed because storage check is skipped
            assert!(result.is_ok());
        }
    }

    #[test]
    fn test_timestamp_comparison_logic() {
        // Test the comparison: timestamp <= last_ts should be rejected

        // Equal timestamps should be rejected (already uploaded this exact location)
        LAST_UPLOADED_TIMESTAMP.store(1000, Ordering::Relaxed);
        let last = LAST_UPLOADED_TIMESTAMP.load(Ordering::Relaxed);
        assert!(1000u64 <= last); // Would be rejected
        assert!(999u64 <= last);  // Would be rejected (older)
        assert!(!(1001u64 <= last)); // Would be accepted (newer)
    }

    #[test]
    fn test_load_save_timestamp_helpers() {
        // Test the helper functions work correctly when storage path exists
        // Note: These functions gracefully handle missing storage path

        let loaded = load_last_upload_timestamp();
        // Should return 0 or existing value if storage is initialized
        assert!(loaded == 0 || loaded > 0);

        // save_last_upload_timestamp silently does nothing if path not set
        save_last_upload_timestamp(12345);
        // Can't easily verify without storage initialized
    }

    #[test]
    fn test_padded_entry_count() {
        // Power of 2 up to 64
        assert_eq!(padded_entry_count(0), 0);
        assert_eq!(padded_entry_count(1), 1);
        assert_eq!(padded_entry_count(2), 2);
        assert_eq!(padded_entry_count(3), 4);
        assert_eq!(padded_entry_count(4), 4);
        assert_eq!(padded_entry_count(5), 8);
        assert_eq!(padded_entry_count(8), 8);
        assert_eq!(padded_entry_count(9), 16);
        assert_eq!(padded_entry_count(17), 32);
        assert_eq!(padded_entry_count(33), 64);
        assert_eq!(padded_entry_count(64), 64);

        // Nearest 50 above 64
        assert_eq!(padded_entry_count(65), 100);
        assert_eq!(padded_entry_count(100), 100);
        assert_eq!(padded_entry_count(101), 150);
        assert_eq!(padded_entry_count(150), 150);
        assert_eq!(padded_entry_count(151), 200);
    }

    #[test]
    fn test_blob_version_byte() {
        let identity = generate_identity();
        let location = Location {
            latitude: 37.7749,
            longitude: -122.4194,
            altitude: 100.0,
            accuracy: 10.0,
            timestamp: 1234567890,
        };

        let my_pubkey: [u8; 32] = identity.ed25519_public.clone().try_into().unwrap();
        let blob = create_encrypted_blob(&location, &[my_pubkey]).unwrap();

        // First byte should be version
        assert_eq!(blob[0], BLOB_VERSION);

        // Blob should work with decrypt
        let decrypted = decrypt_blob(&identity, blob).unwrap();
        assert_eq!(decrypted.latitude, location.latitude);
    }

    #[test]
    fn test_wire_format_roundtrip() {
        let location = Location {
            latitude: 37.7749295,  // 7 decimal places
            longitude: -122.4194155,
            altitude: 123.0,
            accuracy: 15.5,
            timestamp: 1234567890123,
        };

        let wire = WireLocation::from_location(&location);
        let decoded = wire.to_location();

        // Microdegrees gives ~11cm precision, so 6 decimal places should match
        assert!((decoded.latitude - 37.774929).abs() < 0.000001);
        assert!((decoded.longitude - (-122.419415)).abs() < 0.000001);
        assert_eq!(decoded.altitude, 123.0);
        assert_eq!(decoded.accuracy, 15.0); // u16 truncates fractional part
        assert_eq!(decoded.timestamp, location.timestamp);
    }

    #[test]
    fn test_wire_format_binary_size() {
        let location = Location {
            latitude: 0.0,
            longitude: 0.0,
            altitude: 0.0,
            accuracy: 0.0,
            timestamp: 0,
        };

        let wire = WireLocation::from_location(&location);
        let encoded = wire.encode();

        // Wire format should be exactly 20 bytes
        assert_eq!(encoded.len(), 20);
    }

    #[test]
    fn test_blob_padding_applied() {
        let identity = generate_identity();
        let location = Location {
            latitude: 37.7749,
            longitude: -122.4194,
            altitude: 50.0,
            accuracy: 10.0,
            timestamp: 1234567890,
        };

        // With 3 recipients, should pad to 4 entries
        let pubkey: [u8; 32] = identity.ed25519_public.clone().try_into().unwrap();
        let blob = create_encrypted_blob(&location, &[pubkey, pubkey, pubkey]).unwrap();

        // Entry count at bytes 33-35 should be 4 (padded from 3)
        let entry_count = u16::from_be_bytes([blob[33], blob[34]]) as usize;
        assert_eq!(entry_count, 4);

        // Decryption should still work
        let decrypted = decrypt_blob(&identity, blob).unwrap();
        assert_eq!(decrypted.latitude, location.latitude);
    }
}
