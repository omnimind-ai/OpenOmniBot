use std::sync::Arc;

use omnibot_common::AppResult;

use crate::api::ws::WsSession;

pub async fn route(
    _method: &str,
    _args: serde_json::Value,
    _session: Arc<WsSession>,
) -> AppResult<serde_json::Value> {
    Ok(serde_json::json!({
        "ok": true,
        "hasUpdate": false,
        "note": "desktop auto-update not implemented in M0"
    }))
}
