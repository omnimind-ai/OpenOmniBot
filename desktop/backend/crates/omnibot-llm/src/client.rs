use std::sync::Arc;

use async_trait::async_trait;
use eventsource_stream::Eventsource;
use futures_util::StreamExt;
use serde::{Deserialize, Serialize};
use thiserror::Error;

use crate::chat_models::{ChatCompletionMessage, ChatCompletionRequest, ChatCompletionUsage};
use crate::reasoning_policy::ReasoningStreamPolicy;
use crate::stream_accumulator::{AccumulatedTurn, StreamAccumulator};

#[derive(Debug, Error)]
pub enum LlmError {
    #[error("http error: {0}")]
    Http(String),
    #[error("provider error: {status} {body}")]
    Provider { status: u16, body: String },
    #[error("decode error: {0}")]
    Decode(String),
    #[error("cancelled")]
    Cancelled,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ChatCompletionTurn {
    pub message: ChatCompletionMessage,
    pub finish_reason: Option<String>,
    pub usage: Option<ChatCompletionUsage>,
    pub provider_error: Option<String>,
}

/// Sink of streaming deltas reaching the UI / caller.
#[async_trait]
pub trait StreamSink: Send + Sync {
    async fn on_content_delta(&self, full: &str, delta: &str);
    async fn on_reasoning_snapshot(&self, full: &str);
}

#[async_trait]
pub trait AgentLlmClient: Send + Sync {
    async fn stream_turn(
        &self,
        request: ChatCompletionRequest,
        endpoint: &LlmEndpoint,
        sink: Arc<dyn StreamSink>,
    ) -> Result<ChatCompletionTurn, LlmError>;
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LlmEndpoint {
    /// Full chat completion URL, e.g. `https://api.openai.com/v1/chat/completions`.
    pub url: String,
    pub api_key: Option<String>,
    /// Optional extra headers (e.g. `anthropic-version`).
    #[serde(default)]
    pub headers: std::collections::HashMap<String, String>,
}

#[derive(Clone)]
pub struct HttpAgentLlmClient {
    http: reqwest::Client,
}

impl HttpAgentLlmClient {
    pub fn new() -> Self {
        let http = reqwest::Client::builder()
            .user_agent("OmnibotApp-Desktop/0.1 (+rust)")
            .pool_idle_timeout(std::time::Duration::from_secs(30))
            .build()
            .expect("reqwest client");
        Self { http }
    }
}

impl Default for HttpAgentLlmClient {
    fn default() -> Self { Self::new() }
}

#[async_trait]
impl AgentLlmClient for HttpAgentLlmClient {
    async fn stream_turn(
        &self,
        mut request: ChatCompletionRequest,
        endpoint: &LlmEndpoint,
        sink: Arc<dyn StreamSink>,
    ) -> Result<ChatCompletionTurn, LlmError> {
        request.stream = Some(true);
        if request.stream_options.is_none() {
            request.stream_options = Some(crate::chat_models::StreamOptions { include_usage: true });
        }

        let mut builder = self.http.post(&endpoint.url)
            .header("accept", "text/event-stream")
            .header("content-type", "application/json")
            .json(&request);
        if let Some(key) = endpoint.api_key.as_ref().filter(|k| !k.is_empty()) {
            builder = builder.bearer_auth(key);
        }
        for (k, v) in &endpoint.headers {
            builder = builder.header(k, v);
        }

        let resp = builder.send().await.map_err(|e| LlmError::Http(e.to_string()))?;
        let status = resp.status();
        if !status.is_success() {
            let body = resp.text().await.unwrap_or_default();
            return Err(LlmError::Provider { status: status.as_u16(), body });
        }

        let mut accum = StreamAccumulator::new();
        let mut policy = ReasoningStreamPolicy::new();
        let mut stream = resp.bytes_stream().eventsource();

        while let Some(event) = stream.next().await {
            let event = event.map_err(|e| LlmError::Decode(e.to_string()))?;
            let outcome = accum.consume(&event.data);
            if !outcome.content_delta.is_empty() {
                let snapshot = accum.snapshot();
                sink.on_content_delta(&snapshot.content, &outcome.content_delta).await;
            }
            if !outcome.reasoning_delta.is_empty() {
                let snap = accum.snapshot();
                if let Some(text) = policy.append(&outcome.reasoning_delta, &snap.reasoning_content) {
                    sink.on_reasoning_snapshot(&text).await;
                }
            }
            if outcome.finish || accum.finished() { break; }
        }

        // Flush any pending reasoning chunk after the stream ends.
        let snap = accum.snapshot();
        if let Some(text) = policy.force_flush(&snap.reasoning_content) {
            sink.on_reasoning_snapshot(&text).await;
        }

        let turn: AccumulatedTurn = accum.into_turn();
        if let Some(err) = turn.provider_error.clone() {
            return Err(LlmError::Provider { status: 200, body: err });
        }
        let assistant = ChatCompletionMessage {
            role: "assistant".into(),
            content: if turn.content.is_empty() { None } else { Some(serde_json::Value::String(turn.content)) },
            name: None,
            tool_call_id: None,
            tool_calls: turn.tool_calls,
            reasoning_content: if turn.reasoning_content.is_empty() { None } else { Some(turn.reasoning_content) },
        };
        Ok(ChatCompletionTurn {
            message: assistant,
            finish_reason: turn.finish_reason,
            usage: turn.usage,
            provider_error: None,
        })
    }
}
