pub mod envelope;
pub mod handlers;
pub mod router;
pub mod ws;

use axum::{Json, response::IntoResponse};

pub async fn health() -> impl IntoResponse {
    Json(serde_json::json!({"ok": true, "name": "omnibot-backend"}))
}
