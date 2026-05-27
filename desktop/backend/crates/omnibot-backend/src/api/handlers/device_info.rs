use std::sync::Arc;

use omnibot_common::{AppError, AppResult};

use crate::api::ws::WsSession;

pub async fn route(
    method: &str,
    _args: serde_json::Value,
    _session: Arc<WsSession>,
) -> AppResult<serde_json::Value> {
    match method {
        "getDeviceInfo" => Ok(serde_json::json!({
            "platform": std::env::consts::OS,
            "arch": std::env::consts::ARCH,
            "hostname": hostname(),
            "isDesktop": true,
        })),
        "getAppVersion" => Ok(serde_json::json!({
            "version": env!("CARGO_PKG_VERSION"),
            "build": env!("CARGO_PKG_VERSION"),
        })),
        "getAndroidId" => Ok(serde_json::json!({"androidId": ""})),
        "getIpAddress" => Ok(serde_json::json!({"ip": "127.0.0.1"})),
        other => Err(AppError::method_not_implemented(format!(
            "device_info.{other}"
        ))),
    }
}

fn hostname() -> String {
    std::env::var("HOSTNAME")
        .or_else(|_| std::env::var("COMPUTERNAME"))
        .unwrap_or_else(|_| "localhost".into())
}
