use std::sync::Arc;

use omnibot_common::AppResult;

use crate::api::ws::WsSession;

/// Desktop M0 does not host an internal MCP server. Status methods return a stable "disabled" payload.
pub async fn route(
    method: &str,
    _args: serde_json::Value,
    _session: Arc<WsSession>,
) -> AppResult<serde_json::Value> {
    match method {
        "state" => Ok(serde_json::json!({
            "enabled": false,
            "running": false,
            "port": null,
            "token": "",
            "note": "internal MCP server not available on desktop"
        })),
        "setEnabled" => Ok(serde_json::json!({
            "ok": false,
            "error": "internal MCP server not supported on desktop"
        })),
        "refreshToken" => Ok(serde_json::json!({"ok": false})),
        _ => Ok(serde_json::Value::Null),
    }
}
