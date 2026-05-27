//! Remote MCP client — JSON-RPC over HTTP with SSE fallback.
//!
//! Mirrors `RemoteMcpClient.kt`:
//! - Initialize handshake with capabilities + clientInfo
//! - tools/list and tools/call wrappers
//! - Session map keyed by server config id, persisting `Mcp-Session-Id` + protocol version
//! - Bearer token + custom headers
//! - SSE endpoint auto-detect by URL suffix

use std::sync::Arc;
use std::time::Duration;

use dashmap::DashMap;
use eventsource_stream::Eventsource;
use futures_util::StreamExt;
use omnibot_common::{AppError, AppResult};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::config_store::RemoteMcpServerConfig;
use crate::models::{DEFAULT_PROTOCOL_VERSION, JsonRpcRequest, JsonRpcResponse};

#[derive(Clone, Debug, Default)]
struct McpSession {
    session_id: Option<String>,
    protocol_version: Option<String>,
}

#[derive(Clone)]
pub struct RemoteMcpClient {
    http: reqwest::Client,
    sessions: Arc<DashMap<String, McpSession>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RemoteMcpToolDescriptor {
    pub server_id: String,
    pub server_name: String,
    pub tool_name: String,
    pub description: Option<String>,
    pub input_schema: serde_json::Value,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RemoteMcpCallResult {
    pub summary_text: String,
    pub preview_json: serde_json::Value,
    pub raw_result_json: serde_json::Value,
    pub success: bool,
}

impl RemoteMcpClient {
    pub fn new() -> Self {
        Self {
            http: reqwest::Client::builder()
                .user_agent("Omnibot-Desktop-MCP/0.1")
                .timeout(Duration::from_secs(60))
                .build()
                .expect("reqwest"),
            sessions: Arc::new(DashMap::new()),
        }
    }

    pub async fn initialize(&self, cfg: &RemoteMcpServerConfig) -> AppResult<serde_json::Value> {
        let params = serde_json::json!({
            "protocolVersion": DEFAULT_PROTOCOL_VERSION,
            "capabilities": {"tools": {}},
            "clientInfo": {"name": "omnibot-desktop", "version": "0.1.0"},
        });
        let result = self.call_json_rpc(cfg, "initialize", params).await?;
        // Optionally fire notifications/initialized; ignore failures.
        let _ = self.notify(cfg, "notifications/initialized", serde_json::Value::Null).await;
        Ok(result)
    }

    pub async fn list_tools(
        &self,
        cfg: &RemoteMcpServerConfig,
    ) -> AppResult<Vec<RemoteMcpToolDescriptor>> {
        let result = self.call_json_rpc(cfg, "tools/list", serde_json::json!({})).await?;
        let mut out = vec![];
        if let Some(arr) = result.get("tools").and_then(|t| t.as_array()) {
            for t in arr {
                let name = t.get("name").and_then(|n| n.as_str()).unwrap_or("").to_string();
                let description = t.get("description").and_then(|n| n.as_str()).map(|s| s.to_string());
                let input_schema = t
                    .get("inputSchema")
                    .cloned()
                    .or_else(|| t.get("parameters").cloned())
                    .unwrap_or(serde_json::json!({"type": "object", "properties": {}}));
                out.push(RemoteMcpToolDescriptor {
                    server_id: cfg.id.clone(),
                    server_name: cfg.name.clone(),
                    tool_name: name,
                    description,
                    input_schema,
                });
            }
        }
        Ok(out)
    }

    pub async fn call_tool(
        &self,
        cfg: &RemoteMcpServerConfig,
        tool_name: &str,
        arguments: serde_json::Value,
    ) -> AppResult<RemoteMcpCallResult> {
        let params = serde_json::json!({"name": tool_name, "arguments": arguments});
        let raw = self.call_json_rpc(cfg, "tools/call", params).await?;
        let is_error = raw.get("isError").and_then(|v| v.as_bool()).unwrap_or(false);
        // MCP tool result: { content: [{type,text}], isError }
        let summary = extract_summary(&raw);
        Ok(RemoteMcpCallResult {
            summary_text: summary.clone(),
            preview_json: raw
                .get("content")
                .cloned()
                .unwrap_or(serde_json::Value::Null),
            raw_result_json: raw,
            success: !is_error,
        })
    }

    async fn notify(
        &self,
        cfg: &RemoteMcpServerConfig,
        method: &str,
        params: serde_json::Value,
    ) -> AppResult<()> {
        let body = serde_json::json!({
            "jsonrpc": "2.0",
            "method": method,
            "params": params,
        });
        let resp = self.request_with_session(cfg, body).await?;
        // Notifications return no body; ignore.
        let _ = resp.text().await;
        Ok(())
    }

    async fn call_json_rpc(
        &self,
        cfg: &RemoteMcpServerConfig,
        method: &str,
        params: serde_json::Value,
    ) -> AppResult<serde_json::Value> {
        let id = Uuid::new_v4().to_string();
        let req = JsonRpcRequest { jsonrpc: "2.0", id: id.clone(), method, params };
        let body = serde_json::to_value(&req)?;

        let response = self.request_with_session(cfg, body).await?;
        let status = response.status();
        let content_type = response
            .headers()
            .get("content-type")
            .and_then(|v| v.to_str().ok())
            .map(|s| s.to_string())
            .unwrap_or_default();

        let body_text = if content_type.contains("text/event-stream") {
            // SSE response: consume events, looking for the matching JSON-RPC result.
            self.read_sse_response(response, &id).await?
        } else {
            response.text().await.map_err(|e| AppError::Mcp(format!("read body: {e}")))?
        };

        if !status.is_success() {
            return Err(AppError::Mcp(format!("HTTP {} {}", status, body_text)));
        }

        // Parse JSON-RPC envelope; some servers wrap multiple objects in an array.
        let parsed: serde_json::Value = serde_json::from_str(&body_text)
            .map_err(|e| AppError::Mcp(format!("invalid JSON-RPC response: {e}: body={body_text}")))?;
        let envelope = match parsed {
            serde_json::Value::Array(mut arr) => arr.pop().unwrap_or(serde_json::Value::Null),
            other => other,
        };
        let rpc: JsonRpcResponse = serde_json::from_value(envelope)
            .map_err(|e| AppError::Mcp(format!("invalid JSON-RPC envelope: {e}")))?;
        if let Some(err) = rpc.error {
            return Err(AppError::Mcp(format!("rpc error {}: {}", err.code, err.message)));
        }
        Ok(rpc.result.unwrap_or(serde_json::Value::Null))
    }

    async fn request_with_session(
        &self,
        cfg: &RemoteMcpServerConfig,
        body: serde_json::Value,
    ) -> AppResult<reqwest::Response> {
        let mut builder = self.http.post(&cfg.endpoint_url)
            .header("content-type", "application/json")
            .header("accept", "application/json, text/event-stream");
        if !cfg.bearer_token.is_empty() {
            builder = builder.bearer_auth(&cfg.bearer_token);
        }
        for (k, v) in &cfg.headers { builder = builder.header(k, v); }
        // Apply known session headers if present.
        if let Some(s) = self.sessions.get(&cfg.id) {
            if let Some(sid) = &s.session_id {
                builder = builder.header("Mcp-Session-Id", sid);
            }
            if let Some(pv) = &s.protocol_version {
                builder = builder.header("MCP-Protocol-Version", pv);
            }
        } else {
            builder = builder.header("MCP-Protocol-Version", DEFAULT_PROTOCOL_VERSION);
        }
        let resp = builder.body(serde_json::to_vec(&body)?)
            .send().await
            .map_err(|e| AppError::Mcp(format!("send: {e}")))?;
        // Capture session id if server sent one.
        if let Some(sid) = resp.headers().get("Mcp-Session-Id").and_then(|h| h.to_str().ok()) {
            let mut entry = self.sessions.entry(cfg.id.clone()).or_default();
            entry.session_id = Some(sid.to_string());
            entry.protocol_version = entry.protocol_version.clone().or(Some(DEFAULT_PROTOCOL_VERSION.into()));
        }
        Ok(resp)
    }

    async fn read_sse_response(
        &self,
        response: reqwest::Response,
        rpc_id: &str,
    ) -> AppResult<String> {
        let mut stream = response.bytes_stream().eventsource();
        while let Some(event) = stream.next().await {
            let event = event.map_err(|e| AppError::Mcp(format!("sse: {e}")))?;
            let trimmed = event.data.trim();
            if trimmed.is_empty() { continue; }
            // Try to match JSON-RPC id; otherwise return first object we see.
            if let Ok(v) = serde_json::from_str::<serde_json::Value>(trimmed) {
                if let Some(id) = v.get("id").and_then(|i| i.as_str()) {
                    if id == rpc_id { return Ok(trimmed.to_string()); }
                }
                return Ok(trimmed.to_string());
            }
        }
        Err(AppError::Mcp("sse closed before yielding response".into()))
    }
}

impl Default for RemoteMcpClient { fn default() -> Self { Self::new() } }

fn extract_summary(result: &serde_json::Value) -> String {
    if let Some(content) = result.get("content").and_then(|c| c.as_array()) {
        let mut texts = vec![];
        for item in content {
            if let Some(t) = item.get("text").and_then(|t| t.as_str()) {
                texts.push(t.to_string());
            }
        }
        if !texts.is_empty() { return texts.join("\n"); }
    }
    result.to_string()
}
