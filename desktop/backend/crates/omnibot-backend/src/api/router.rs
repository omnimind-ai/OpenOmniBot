use std::sync::Arc;

use omnibot_common::{AppError, AppResult};

use crate::api::handlers;
use crate::api::ws::WsSession;

pub struct ChannelRouter;

impl ChannelRouter {
    pub async fn route(
        channel: &str,
        method: &str,
        args: serde_json::Value,
        session: Arc<WsSession>,
    ) -> AppResult<serde_json::Value> {
        match channel {
            "cn.com.omnimind.bot/AssistCoreEvent" => {
                handlers::assist_core::route(method, args, session).await
            }
            "cn.com.omnimind.bot/CacheDataEvent" => {
                handlers::cache_data::route(method, args, session).await
            }
            "cn.com.omnimind.bot/McpServer" => {
                handlers::mcp_server::route(method, args, session).await
            }
            "cn.com.omnimind.bot/network" => {
                handlers::network::route(method, args, session).await
            }
            "cn.com.omnimind.bot/CodexAppServer" => {
                handlers::codex_app_server::route(method, args, session).await
            }
            "device_info" => handlers::device_info::route(method, args, session).await,
            "cn.com.omnimind.bot/app_state" => {
                handlers::app_state::route(method, args, session).await
            }
            "cn.com.omnimind.bot/app_update" => {
                handlers::app_update::route(method, args, session).await
            }
            "cn.com.omnimind.bot/file_save" => {
                handlers::file_save::route(method, args, session).await
            }
            "cn.com.omnimind.bot/RemoteMcpConfig" => {
                handlers::remote_mcp_config::route(method, args, session).await
            }
            other => Err(AppError::method_not_implemented(format!("{other}.{method}"))),
        }
    }

    pub async fn subscribe(
        channel: &str,
        sub_id: &str,
        args: serde_json::Value,
        session: Arc<WsSession>,
    ) {
        match channel {
            "cn.com.omnimind.bot/AssistCoreEvent" => {
                handlers::assist_core::subscribe(sub_id, args, session).await
            }
            "cn.com.omnimind.bot/CodexAppServer"
            | "cn.com.omnimind.bot/CodexAppServerEvents" => {
                handlers::codex_app_server::subscribe(sub_id, args, session).await
            }
            _ => {
                // Unknown event channels are kept alive but never produce data.
                tracing::debug!(channel, sub_id, "stub event channel");
            }
        }
    }
}
