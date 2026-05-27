use std::sync::Arc;

use omnibot_common::AppResult;
use sqlx::Row;

use crate::api::ws::WsSession;

pub async fn list_ai_request_logs(args: serde_json::Value, session: Arc<WsSession>) -> AppResult<serde_json::Value> {
    let limit = args.get("limit").and_then(|v| v.as_i64()).unwrap_or(50).clamp(1, 500);
    let rows = sqlx::query(
        "SELECT request_id, conversation_id, scene, model, duration_ms, started_at, finished_at, error
         FROM ai_request_logs ORDER BY started_at DESC LIMIT ?",
    )
    .bind(limit)
    .fetch_all(session.state.db.pool())
    .await
    .map_err(|e| omnibot_common::AppError::backend(e.to_string()))?;
    let items: Vec<serde_json::Value> = rows.iter().map(|r| {
        serde_json::json!({
            "requestId": r.try_get::<String, _>("request_id").unwrap_or_default(),
            "conversationId": r.try_get::<Option<i64>, _>("conversation_id").unwrap_or(None),
            "scene": r.try_get::<String, _>("scene").unwrap_or_default(),
            "model": r.try_get::<String, _>("model").unwrap_or_default(),
            "durationMs": r.try_get::<i64, _>("duration_ms").unwrap_or_default(),
            "startedAt": r.try_get::<i64, _>("started_at").unwrap_or_default(),
            "finishedAt": r.try_get::<Option<i64>, _>("finished_at").unwrap_or(None),
            "error": r.try_get::<Option<String>, _>("error").unwrap_or(None),
        })
    }).collect();
    Ok(serde_json::Value::Array(items))
}

pub async fn list_runtime_logs(_args: serde_json::Value, session: Arc<WsSession>) -> AppResult<serde_json::Value> {
    // Read most recent `backend.log.<date>` produced by tracing-appender.
    let dir = &session.state.paths.log_dir;
    let mut latest: Option<std::path::PathBuf> = None;
    if let Ok(entries) = std::fs::read_dir(dir) {
        for entry in entries.flatten() {
            let name = entry.file_name().to_string_lossy().into_owned();
            if name.starts_with("backend.log") {
                latest = Some(latest.map(|p| {
                    let a = p.metadata().and_then(|m| m.modified()).ok();
                    let b = entry.metadata().and_then(|m| m.modified()).ok();
                    if b >= a { entry.path() } else { p }
                }).unwrap_or_else(|| entry.path()));
            }
        }
    }
    let content = match latest {
        Some(p) => tokio::fs::read_to_string(p).await.unwrap_or_default(),
        None => String::new(),
    };
    // Trim to last 64 KiB to keep payload small.
    let tail: String = content.chars().rev().take(65_536).collect::<Vec<_>>().into_iter().rev().collect();
    Ok(serde_json::json!({"content": tail}))
}
