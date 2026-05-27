use std::sync::Arc;

use omnibot_common::AppResult;

use crate::api::ws::WsSession;

pub async fn route(
    method: &str,
    _args: serde_json::Value,
    _session: Arc<WsSession>,
) -> AppResult<serde_json::Value> {
    match method {
        "initHalfScreenEngine" => Ok(serde_json::json!({"ok": true, "note": "desktop has single engine"})),
        "exitApp" => Ok(serde_json::json!({"ok": true})),
        "getPendingShareDraft" => Ok(serde_json::Value::Null),
        "clearPendingShareDraft" => Ok(serde_json::json!({"ok": true})),
        "getSharedOpenMode" => Ok(serde_json::json!({"mode": "agent"})),
        _ => Ok(serde_json::Value::Null),
    }
}
