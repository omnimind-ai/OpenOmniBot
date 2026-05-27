use std::sync::Arc;

use omnibot_common::AppResult;

use crate::api::ws::WsSession;

/// Generic HTTP passthrough used by Flutter for some non-LLM calls.
/// Implemented on top of reqwest with a per-request client to honor user-supplied headers.
pub async fn route(
    method: &str,
    args: serde_json::Value,
    _session: Arc<WsSession>,
) -> AppResult<serde_json::Value> {
    match method {
        "sendRequest" => {
            let url = args.get("url").and_then(|v| v.as_str()).unwrap_or("");
            let http_method = args.get("method").and_then(|v| v.as_str()).unwrap_or("GET").to_uppercase();
            let body = args.get("body");
            let headers = args.get("headers").and_then(|v| v.as_object()).cloned().unwrap_or_default();

            let client = reqwest::Client::new();
            let mut req = client.request(
                reqwest::Method::from_bytes(http_method.as_bytes())
                    .map_err(|e| omnibot_common::AppError::backend(format!("method: {e}")))?,
                url,
            );
            for (k, v) in headers {
                if let Some(s) = v.as_str() { req = req.header(k.as_str(), s); }
            }
            if let Some(b) = body {
                let serialized = if b.is_string() { b.as_str().unwrap().to_string() } else { b.to_string() };
                req = req.body(serialized);
            }
            let resp = req.send().await.map_err(|e| omnibot_common::AppError::backend(format!("send: {e}")))?;
            let status = resp.status().as_u16();
            let headers_out = resp.headers().iter()
                .filter_map(|(k, v)| v.to_str().ok().map(|s| (k.to_string(), s.to_string())))
                .collect::<std::collections::BTreeMap<_, _>>();
            let bytes = resp.bytes().await.map_err(|e| omnibot_common::AppError::backend(format!("read body: {e}")))?;
            let body_text = String::from_utf8_lossy(&bytes).into_owned();
            Ok(serde_json::json!({
                "status": status,
                "headers": headers_out,
                "body": body_text,
            }))
        }
        _ => Ok(serde_json::Value::Null),
    }
}
