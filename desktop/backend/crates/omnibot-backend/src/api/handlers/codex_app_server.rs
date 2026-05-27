use std::sync::Arc;

use omnibot_common::AppResult;

use crate::api::ws::WsSession;

pub async fn route(
    method: &str,
    _args: serde_json::Value,
    _session: Arc<WsSession>,
) -> AppResult<serde_json::Value> {
    match method {
        "status" => Ok(serde_json::json!({
            "connected": false,
            "ready": false,
            "version": "",
            "codexHome": "",
            "cwd": "",
            "runtime": "",
            "remoteEnabled": false,
            "remoteBridgeUrl": "",
            "note": "Codex backend not implemented on desktop M0"
        })),
        "getConfig" => Ok(serde_json::json!({"profiles": []})),
        "connectLocal" | "connectRemote" => Ok(serde_json::json!({
            "ok": false,
            "error": "Codex not supported on desktop yet"
        })),
        _ => Ok(serde_json::Value::Null),
    }
}

pub async fn subscribe(
    _sub_id: &str,
    _args: serde_json::Value,
    _session: Arc<WsSession>,
) {
    // No events emitted; the subscription stays idle until Dart cancels.
}
