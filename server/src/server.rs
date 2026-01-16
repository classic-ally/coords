use axum::{
    extract::State,
    http::StatusCode,
    response::Html,
    routing::{get, post, put},
    Json, Router,
};
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use dashmap::DashMap;
use ed25519_dalek::{Signature, Verifier, VerifyingKey};
use serde::{Deserialize, Serialize};
use std::{collections::HashMap, sync::Arc, time::SystemTime};

const MAX_BLOB_SIZE: usize = 64 * 1024;
const MAX_TIMESTAMP_AGE_SECS: u64 = 300;

#[derive(Clone, Serialize)]
struct StoredLocation {
    #[serde(serialize_with = "as_base64")]
    blob: Vec<u8>,
    updated: u64,
}

fn as_base64<S: serde::Serializer>(bytes: &Vec<u8>, s: S) -> Result<S::Ok, S::Error> {
    s.serialize_str(&URL_SAFE_NO_PAD.encode(bytes))
}

type PostResponse = HashMap<String, Option<StoredLocation>>;

type LocationStore = Arc<DashMap<[u8; 32], StoredLocation>>;

#[derive(Deserialize)]
struct PutRequest {
    pubkey: String,
    timestamp: u64,
    blob: String,
    signature: String,
}

#[derive(Deserialize)]
struct PostRequest {
    ids: Vec<String>,
}


const VERSION: &str = env!("CARGO_PKG_VERSION");

pub fn app() -> Router {
    let store: LocationStore = Arc::new(DashMap::new());
    Router::new()
        .route("/api/version", get(version_info))
        .route("/api/privacy", get(privacy))
        .route("/api/location", put(put_location))
        .route("/api/location", post(post_location))
        .with_state(store)
}

async fn version_info() -> Json<serde_json::Value> {
    Json(serde_json::json!({
        "name": "coords",
        "version": VERSION
    }))
}

async fn privacy() -> Html<&'static str> {
    Html(include_str!("privacy.html"))
}

async fn put_location(
    State(store): State<LocationStore>,
    Json(req): Json<PutRequest>,
) -> Result<StatusCode, (StatusCode, &'static str)> {
    let pubkey_prefix = req.pubkey.chars().take(8).collect::<String>();
    println!("[PUT /api/location] pubkey={}..., blob_size={}", pubkey_prefix, req.blob.len());

    // Decode pubkey
    let pubkey_bytes: [u8; 32] = URL_SAFE_NO_PAD
        .decode(&req.pubkey)
        .map_err(|_| (StatusCode::BAD_REQUEST, "invalid pubkey encoding"))?
        .try_into()
        .map_err(|_| (StatusCode::BAD_REQUEST, "invalid pubkey length"))?;

    let verifying_key = VerifyingKey::from_bytes(&pubkey_bytes)
        .map_err(|_| (StatusCode::BAD_REQUEST, "invalid pubkey"))?;

    // Decode blob
    let blob = URL_SAFE_NO_PAD
        .decode(&req.blob)
        .map_err(|_| (StatusCode::BAD_REQUEST, "invalid blob encoding"))?;

    if blob.len() > MAX_BLOB_SIZE {
        return Err((StatusCode::PAYLOAD_TOO_LARGE, "blob exceeds 64KB"));
    }

    // Decode signature
    let sig_bytes: [u8; 64] = URL_SAFE_NO_PAD
        .decode(&req.signature)
        .map_err(|_| (StatusCode::BAD_REQUEST, "invalid signature encoding"))?
        .try_into()
        .map_err(|_| (StatusCode::BAD_REQUEST, "invalid signature length"))?;

    let signature = Signature::from_bytes(&sig_bytes);

    // Verify timestamp
    let now = SystemTime::now()
        .duration_since(SystemTime::UNIX_EPOCH)
        .unwrap()
        .as_secs();

    if now.saturating_sub(req.timestamp) > MAX_TIMESTAMP_AGE_SECS {
        return Err((StatusCode::UNAUTHORIZED, "timestamp too old"));
    }

    // Verify signature over blob || timestamp
    let mut message = blob.clone();
    message.extend_from_slice(&req.timestamp.to_be_bytes());

    verifying_key
        .verify(&message, &signature)
        .map_err(|_| (StatusCode::UNAUTHORIZED, "invalid signature"))?;

    // Store
    store.insert(
        pubkey_bytes,
        StoredLocation {
            blob,
            updated: now,
        },
    );

    println!("[PUT /api/location] stored successfully for {}...", pubkey_prefix);
    Ok(StatusCode::NO_CONTENT)
}

async fn post_location(
    State(store): State<LocationStore>,
    Json(req): Json<PostRequest>,
) -> Result<Json<PostResponse>, (StatusCode, &'static str)> {
    println!("[POST /api/location] requesting {} ids", req.ids.len());
    for id in &req.ids {
        let prefix = id.chars().take(8).collect::<String>();
        println!("[POST /api/location]   - {}...", prefix);
    }

    if req.ids.len() > 50 {
        return Err((StatusCode::BAD_REQUEST, "max 50 ids per request"));
    }

    let mut results = PostResponse::new();

    for id in req.ids {
        let pubkey_bytes: Result<[u8; 32], _> = URL_SAFE_NO_PAD
            .decode(&id)
            .map_err(|_| ())
            .and_then(|b| b.try_into().map_err(|_| ()));

        let id_prefix = id.chars().take(8).collect::<String>();
        let value = match pubkey_bytes {
            Ok(key) => {
                let found = store.get(&key).map(|entry| entry.clone());
                println!("[POST /api/location]   {}... -> {}", id_prefix, if found.is_some() { "FOUND" } else { "NOT FOUND" });
                found
            },
            Err(_) => {
                println!("[POST /api/location]   {}... -> INVALID KEY", id_prefix);
                None
            },
        };

        results.insert(id, value);
    }

    Ok(Json(results))
}
