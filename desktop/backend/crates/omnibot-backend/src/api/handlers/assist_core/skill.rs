use std::path::PathBuf;
use std::sync::Arc;

use omnibot_common::AppResult;
use walkdir::WalkDir;

use crate::api::ws::WsSession;

pub async fn list(_args: serde_json::Value, session: Arc<WsSession>) -> AppResult<serde_json::Value> {
    let workspace = session.state.workspaces.default()?;
    let dir = PathBuf::from(workspace.current_cwd).join(".omnibot/skills");
    std::fs::create_dir_all(&dir)?;
    let mut items = vec![];
    for entry in WalkDir::new(&dir).max_depth(2).into_iter().filter_map(|e| e.ok()) {
        if entry.file_type().is_dir() { continue; }
        let name = entry.file_name().to_string_lossy().into_owned();
        if !name.ends_with(".md") { continue; }
        items.push(serde_json::json!({
            "id": name.trim_end_matches(".md").to_string(),
            "name": name.trim_end_matches(".md").to_string(),
            "path": entry.path().display().to_string(),
            "enabled": true,
        }));
    }
    Ok(serde_json::Value::Array(items))
}

pub async fn install(args: serde_json::Value, session: Arc<WsSession>) -> AppResult<serde_json::Value> {
    let name = args.get("name").and_then(|v| v.as_str()).unwrap_or("untitled");
    let content = args.get("content").and_then(|v| v.as_str()).unwrap_or("");
    let workspace = session.state.workspaces.default()?;
    let dir = PathBuf::from(workspace.current_cwd).join(".omnibot/skills");
    std::fs::create_dir_all(&dir)?;
    tokio::fs::write(dir.join(format!("{name}.md")), content).await?;
    Ok(serde_json::json!({"ok": true, "id": name}))
}

pub async fn delete(args: serde_json::Value, session: Arc<WsSession>) -> AppResult<serde_json::Value> {
    let name = args.get("name").and_then(|v| v.as_str()).unwrap_or("");
    let workspace = session.state.workspaces.default()?;
    let path = PathBuf::from(workspace.current_cwd).join(format!(".omnibot/skills/{name}.md"));
    let _ = tokio::fs::remove_file(path).await;
    Ok(serde_json::json!({"ok": true}))
}

pub async fn set_enabled(_args: serde_json::Value, _session: Arc<WsSession>) -> AppResult<serde_json::Value> {
    // First version stores skills as enabled-by-presence; toggling is a no-op.
    Ok(serde_json::json!({"ok": true}))
}
