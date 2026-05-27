use std::sync::Arc;

use omnibot_common::AppResult;
use omnibot_db::{AgentEntryRepo, ConversationRepo};

use crate::api::ws::WsSession;

pub async fn list(args: serde_json::Value, session: Arc<WsSession>) -> AppResult<serde_json::Value> {
    let mode = args.get("mode").and_then(|v| v.as_str());
    let conversations = ConversationRepo::list(session.state.db.pool(), mode).await?;
    Ok(serde_json::Value::Array(
        conversations.iter().map(|c| c.to_dart_payload()).collect(),
    ))
}

pub async fn get(args: serde_json::Value, session: Arc<WsSession>) -> AppResult<serde_json::Value> {
    let id = args.get("id").and_then(|v| v.as_i64()).unwrap_or(0);
    let row = ConversationRepo::get(session.state.db.pool(), id).await?;
    Ok(row.map(|c| c.to_dart_payload()).unwrap_or(serde_json::Value::Null))
}

pub async fn create(args: serde_json::Value, session: Arc<WsSession>) -> AppResult<serde_json::Value> {
    let title = args.get("title").and_then(|v| v.as_str()).unwrap_or("New Conversation");
    let mode = args.get("mode").and_then(|v| v.as_str()).unwrap_or("normal");
    let conv = ConversationRepo::create(session.state.db.pool(), title, mode).await?;
    Ok(serde_json::json!(conv.id))
}

pub async fn update(args: serde_json::Value, session: Arc<WsSession>) -> AppResult<serde_json::Value> {
    let source = args.get("conversation").unwrap_or(&args);
    let id = source
        .get("id")
        .or_else(|| args.get("conversationId"))
        .and_then(|v| v.as_i64())
        .unwrap_or(0);
    if let Some(title) = source.get("title").and_then(|v| v.as_str()) {
        ConversationRepo::update_title(session.state.db.pool(), id, title).await?;
    }
    let conv = ConversationRepo::get(session.state.db.pool(), id).await?;
    Ok(if conv.is_some() { serde_json::json!("SUCCESS") } else { serde_json::json!("FAIL") })
}

pub async fn update_title(args: serde_json::Value, session: Arc<WsSession>) -> AppResult<serde_json::Value> {
    let id = args
        .get("conversationId")
        .or_else(|| args.get("id"))
        .and_then(|v| v.as_i64())
        .unwrap_or(0);
    let title = args
        .get("newTitle")
        .or_else(|| args.get("title"))
        .and_then(|v| v.as_str())
        .unwrap_or("");
    ConversationRepo::update_title(session.state.db.pool(), id, title).await?;
    Ok(serde_json::json!("SUCCESS"))
}

pub async fn update_prompt_token_threshold(
    args: serde_json::Value,
    session: Arc<WsSession>,
) -> AppResult<serde_json::Value> {
    let id = args.get("conversationId").and_then(|v| v.as_i64()).unwrap_or(0);
    let threshold = args
        .get("promptTokenThreshold")
        .and_then(|v| v.as_i64())
        .unwrap_or(128_000);
    ConversationRepo::set_prompt_token_threshold(session.state.db.pool(), id, threshold).await?;
    Ok(serde_json::json!("SUCCESS"))
}

pub async fn delete(args: serde_json::Value, session: Arc<WsSession>) -> AppResult<serde_json::Value> {
    let id = args
        .get("conversationId")
        .or_else(|| args.get("id"))
        .and_then(|v| v.as_i64())
        .unwrap_or(0);
    ConversationRepo::delete(session.state.db.pool(), id).await?;
    Ok(serde_json::json!("SUCCESS"))
}

pub async fn archive(args: serde_json::Value, session: Arc<WsSession>) -> AppResult<serde_json::Value> {
    let id = args.get("id").and_then(|v| v.as_i64()).unwrap_or(0);
    let archived = args.get("archived").and_then(|v| v.as_bool()).unwrap_or(true);
    ConversationRepo::set_archived(session.state.db.pool(), id, archived).await?;
    Ok(serde_json::json!({"ok": true}))
}

pub async fn pin(args: serde_json::Value, session: Arc<WsSession>) -> AppResult<serde_json::Value> {
    let id = args.get("id").and_then(|v| v.as_i64()).unwrap_or(0);
    let pinned = args.get("pinned").and_then(|v| v.as_bool()).unwrap_or(true);
    ConversationRepo::set_pinned(session.state.db.pool(), id, pinned).await?;
    Ok(serde_json::json!({"ok": true}))
}

pub async fn messages(args: serde_json::Value, session: Arc<WsSession>) -> AppResult<serde_json::Value> {
    let id = args.get("id").and_then(|v| v.as_i64()).unwrap_or(0);
    let mode = args.get("mode").and_then(|v| v.as_str()).unwrap_or("normal");
    let rows = AgentEntryRepo::list(session.state.db.pool(), id, mode).await?;
    Ok(serde_json::Value::Array(rows.iter().map(|e| e.to_dart_payload()).collect()))
}

pub async fn replace_messages(args: serde_json::Value, session: Arc<WsSession>) -> AppResult<serde_json::Value> {
    let id = args.get("id").and_then(|v| v.as_i64()).unwrap_or(0);
    let mode = args.get("mode").and_then(|v| v.as_str()).unwrap_or("normal");
    AgentEntryRepo::clear(session.state.db.pool(), id, mode).await?;
    if let Some(arr) = args.get("entries").and_then(|v| v.as_array()) {
        for e in arr {
            let entry_id = e.get("entryId").and_then(|v| v.as_str()).unwrap_or("");
            let entry_type = e.get("entryType").and_then(|v| v.as_str()).unwrap_or("");
            let status = e.get("status").and_then(|v| v.as_str()).unwrap_or("done");
            let summary = e.get("summary").and_then(|v| v.as_str()).unwrap_or("");
            let payload = e.get("payload").cloned().unwrap_or(serde_json::Value::Null);
            AgentEntryRepo::upsert(session.state.db.pool(), id, mode, entry_id, entry_type, status, summary, &payload).await?;
        }
    }
    Ok(serde_json::json!({"ok": true}))
}

pub async fn clear_messages(args: serde_json::Value, session: Arc<WsSession>) -> AppResult<serde_json::Value> {
    let id = args.get("id").and_then(|v| v.as_i64()).unwrap_or(0);
    let mode = args.get("mode").and_then(|v| v.as_str()).unwrap_or("normal");
    AgentEntryRepo::clear(session.state.db.pool(), id, mode).await?;
    Ok(serde_json::json!({"ok": true}))
}

pub async fn upsert_ui_card(args: serde_json::Value, session: Arc<WsSession>) -> AppResult<serde_json::Value> {
    let conv = args.get("conversationId").and_then(|v| v.as_i64()).unwrap_or(0);
    let mode = args.get("mode").and_then(|v| v.as_str()).unwrap_or("normal");
    let entry_id = args.get("entryId").and_then(|v| v.as_str()).unwrap_or("");
    let entry_type = args.get("entryType").and_then(|v| v.as_str()).unwrap_or("ui_card");
    let status = args.get("status").and_then(|v| v.as_str()).unwrap_or("done");
    let summary = args.get("summary").and_then(|v| v.as_str()).unwrap_or("");
    let payload = args.get("payload").cloned().unwrap_or(serde_json::Value::Null);
    let row = AgentEntryRepo::upsert(
        session.state.db.pool(), conv, mode, entry_id, entry_type, status, summary, &payload
    ).await?;
    Ok(row.to_dart_payload())
}
