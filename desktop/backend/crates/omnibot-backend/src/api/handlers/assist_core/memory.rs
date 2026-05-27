use std::path::PathBuf;
use std::sync::Arc;

use omnibot_common::AppResult;

use crate::api::ws::WsSession;

pub async fn load_soul(_args: serde_json::Value, session: Arc<WsSession>) -> AppResult<serde_json::Value> {
    let path = memory_dir(&session)?.join("soul.md");
    let content = tokio::fs::read_to_string(&path).await.unwrap_or_default();
    Ok(serde_json::json!({"content": content}))
}

pub async fn save_soul(args: serde_json::Value, session: Arc<WsSession>) -> AppResult<serde_json::Value> {
    save_named("soul.md", args, session).await
}

pub async fn load_named(
    file_name: &str,
    _args: serde_json::Value,
    session: Arc<WsSession>,
) -> AppResult<serde_json::Value> {
    let path = memory_dir(&session)?.join(file_name);
    let content = tokio::fs::read_to_string(&path).await.unwrap_or_default();
    Ok(serde_json::json!({"content": content}))
}

pub async fn save_named(
    file_name: &str,
    args: serde_json::Value,
    session: Arc<WsSession>,
) -> AppResult<serde_json::Value> {
    let content = args.get("content").and_then(|v| v.as_str()).unwrap_or("");
    let dir = memory_dir(&session)?;
    std::fs::create_dir_all(&dir)?;
    tokio::fs::write(dir.join(file_name), content).await?;
    Ok(serde_json::json!({"ok": true, "content": content}))
}

pub async fn short_memories(
    _args: serde_json::Value,
    _session: Arc<WsSession>,
) -> AppResult<serde_json::Value> {
    Ok(serde_json::json!({"items": []}))
}

pub async fn embedding_config(
    _args: serde_json::Value,
    _session: Arc<WsSession>,
) -> AppResult<serde_json::Value> {
    Ok(default_embedding_config())
}

pub async fn save_embedding_config(
    args: serde_json::Value,
    _session: Arc<WsSession>,
) -> AppResult<serde_json::Value> {
    let mut config = default_embedding_config();
    if let Some(map) = config.as_object_mut() {
        if let Some(enabled) = args.get("enabled").and_then(|v| v.as_bool()) {
            map.insert("enabled".into(), serde_json::json!(enabled));
        }
        if let Some(profile) = args.get("providerProfileId").and_then(|v| v.as_str()) {
            map.insert("providerProfileId".into(), serde_json::json!(profile));
        }
        if let Some(model) = args.get("modelId").and_then(|v| v.as_str()) {
            map.insert("modelId".into(), serde_json::json!(model));
        }
    }
    Ok(config)
}

pub async fn rollup_status(
    _args: serde_json::Value,
    _session: Arc<WsSession>,
) -> AppResult<serde_json::Value> {
    Ok(serde_json::json!({
        "enabled": false,
        "lastRunAtMillis": null,
        "lastRunSummary": null,
        "nextRunAtMillis": null,
    }))
}

pub async fn save_rollup_enabled(
    args: serde_json::Value,
    _session: Arc<WsSession>,
) -> AppResult<serde_json::Value> {
    Ok(serde_json::json!({
        "enabled": args.get("enabled").and_then(|v| v.as_bool()).unwrap_or(false),
        "lastRunAtMillis": null,
        "lastRunSummary": null,
        "nextRunAtMillis": null,
    }))
}

pub async fn run_rollup_now(
    _args: serde_json::Value,
    _session: Arc<WsSession>,
) -> AppResult<serde_json::Value> {
    Ok(serde_json::json!({
        "ok": false,
        "message": "Workspace memory rollup is unavailable on desktop dev stub.",
    }))
}

fn memory_dir(session: &WsSession) -> AppResult<PathBuf> {
    let workspace = session.state.workspaces.default()?;
    Ok(PathBuf::from(workspace.current_cwd).join(".omnibot/memory"))
}

fn default_embedding_config() -> serde_json::Value {
    serde_json::json!({
        "enabled": false,
        "configured": false,
        "sceneId": "workspace_memory",
        "providerProfileId": null,
        "providerProfileName": null,
        "modelId": null,
        "apiBase": null,
        "hasApiKey": false,
    })
}
