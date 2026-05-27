use std::sync::Arc;

use omnibot_common::{AppError, AppResult};

use crate::api::ws::WsSession;

pub async fn route(
    method: &str,
    args: serde_json::Value,
    session: Arc<WsSession>,
) -> AppResult<serde_json::Value> {
    let store = &session.state.mcp_config;
    match method {
        "list" => Ok(serde_json::to_value(store.list()?)?),
        "upsert" => {
            let server: omnibot_mcp::RemoteMcpServerConfig = serde_json::from_value(args)
                .map_err(|e| AppError::invalid(format!("upsert: {e}")))?;
            Ok(serde_json::to_value(store.upsert(server)?)?)
        }
        "delete" => {
            let id = args.get("id").and_then(|v| v.as_str()).unwrap_or("");
            Ok(serde_json::to_value(store.delete(id)?)?)
        }
        "listTools" => {
            let id = args.get("id").and_then(|v| v.as_str()).unwrap_or("");
            let cfg = store.list()?.into_iter().find(|c| c.id == id)
                .ok_or_else(|| AppError::NotFound(format!("mcp server {id}")))?;
            let _ = session.state.mcp_client.initialize(&cfg).await;
            let tools = session.state.mcp_client.list_tools(&cfg).await?;
            Ok(serde_json::to_value(tools)?)
        }
        _ => Ok(serde_json::Value::Null),
    }
}
