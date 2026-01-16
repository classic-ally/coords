use axum::{
    body::Body,
    http::{Request, StatusCode},
};
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use ed25519_dalek::{Signer, SigningKey};
use http_body_util::BodyExt;
use rand::rngs::OsRng;
use serde_json::{json, Value};
use std::time::{SystemTime, UNIX_EPOCH};
use tower::ServiceExt;

fn timestamp() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_secs()
}

fn sign_blob(signing_key: &SigningKey, blob: &[u8], timestamp: u64) -> Vec<u8> {
    let mut message = blob.to_vec();
    message.extend_from_slice(&timestamp.to_be_bytes());
    signing_key.sign(&message).to_bytes().to_vec()
}

#[tokio::test]
async fn test_put_and_get_location() {
    let app = coords_server::app();

    // Generate keypair
    let signing_key = SigningKey::generate(&mut OsRng);
    let pubkey = signing_key.verifying_key().to_bytes();
    let pubkey_b64 = URL_SAFE_NO_PAD.encode(pubkey);

    // Create and sign blob
    let blob = b"test location data";
    let ts = timestamp();
    let signature = sign_blob(&signing_key, blob, ts);

    let put_body = json!({
        "pubkey": pubkey_b64,
        "timestamp": ts,
        "blob": URL_SAFE_NO_PAD.encode(blob),
        "signature": URL_SAFE_NO_PAD.encode(signature),
    });

    // PUT request
    let response = app
        .clone()
        .oneshot(
            Request::builder()
                .method("PUT")
                .uri("/api/location")
                .header("content-type", "application/json")
                .body(Body::from(put_body.to_string()))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::NO_CONTENT);

    // POST request to retrieve
    let post_body = json!({
        "ids": [pubkey_b64]
    });

    let response = app
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/location")
                .header("content-type", "application/json")
                .body(Body::from(post_body.to_string()))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);

    let body = response.into_body().collect().await.unwrap().to_bytes();
    let result: Value = serde_json::from_slice(&body).unwrap();

    let location = result.get(&pubkey_b64).unwrap();
    assert!(!location.is_null());
    assert_eq!(
        location.get("blob").unwrap().as_str().unwrap(),
        URL_SAFE_NO_PAD.encode(blob)
    );
    assert!(location.get("updated").unwrap().as_u64().is_some());
}

#[tokio::test]
async fn test_invalid_signature() {
    let app = coords_server::app();

    let signing_key = SigningKey::generate(&mut OsRng);
    let pubkey = signing_key.verifying_key().to_bytes();
    let pubkey_b64 = URL_SAFE_NO_PAD.encode(pubkey);

    let blob = b"test";
    let ts = timestamp();
    let bad_signature = vec![0u8; 64]; // Invalid signature

    let put_body = json!({
        "pubkey": pubkey_b64,
        "timestamp": ts,
        "blob": URL_SAFE_NO_PAD.encode(blob),
        "signature": URL_SAFE_NO_PAD.encode(bad_signature),
    });

    let response = app
        .oneshot(
            Request::builder()
                .method("PUT")
                .uri("/api/location")
                .header("content-type", "application/json")
                .body(Body::from(put_body.to_string()))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn test_old_timestamp() {
    let app = coords_server::app();

    let signing_key = SigningKey::generate(&mut OsRng);
    let pubkey = signing_key.verifying_key().to_bytes();
    let pubkey_b64 = URL_SAFE_NO_PAD.encode(pubkey);

    let blob = b"test";
    let old_ts = timestamp() - 400; // 6+ minutes ago
    let signature = sign_blob(&signing_key, blob, old_ts);

    let put_body = json!({
        "pubkey": pubkey_b64,
        "timestamp": old_ts,
        "blob": URL_SAFE_NO_PAD.encode(blob),
        "signature": URL_SAFE_NO_PAD.encode(signature),
    });

    let response = app
        .oneshot(
            Request::builder()
                .method("PUT")
                .uri("/api/location")
                .header("content-type", "application/json")
                .body(Body::from(put_body.to_string()))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn test_missing_location() {
    let app = coords_server::app();

    let fake_pubkey = URL_SAFE_NO_PAD.encode([0u8; 32]);

    let post_body = json!({
        "ids": [fake_pubkey]
    });

    let response = app
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/location")
                .header("content-type", "application/json")
                .body(Body::from(post_body.to_string()))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);

    let body = response.into_body().collect().await.unwrap().to_bytes();
    let result: Value = serde_json::from_slice(&body).unwrap();

    assert!(result.get(&fake_pubkey).unwrap().is_null());
}
