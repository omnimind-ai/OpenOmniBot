use std::sync::Arc;

use async_trait::async_trait;
use omnibot_common::AppResult;

use crate::types::{
    AgentExecutionEnvironment, ToolConcurrencyHint, ToolEventSink, ToolExecutionResult, ToolHandler,
};

/// OpenAI-compatible image generation. Configure with:
/// - OMNIBOT_IMAGE_BASE_URL, e.g. https://api.openai.com/v1/images/generations
/// - OMNIBOT_IMAGE_API_KEY
/// - OMNIBOT_IMAGE_MODEL, optional
pub struct ImageGenerationToolHandler {
    http: reqwest::Client,
}

impl ImageGenerationToolHandler {
    pub fn new() -> Self {
        Self {
            http: reqwest::Client::new(),
        }
    }
}

#[async_trait]
impl ToolHandler for ImageGenerationToolHandler {
    fn tool_names(&self) -> &[&'static str] {
        &["image_generate", "image_generation"]
    }

    fn concurrency_hint(&self) -> ToolConcurrencyHint {
        ToolConcurrencyHint::SerialBarrier
    }

    async fn execute(
        &self,
        _id: &str,
        _name: &str,
        args: serde_json::Value,
        _env: &AgentExecutionEnvironment,
        _sink: Arc<dyn ToolEventSink>,
    ) -> AppResult<ToolExecutionResult> {
        let endpoint = std::env::var("OMNIBOT_IMAGE_BASE_URL")
            .unwrap_or_else(|_| "https://api.openai.com/v1/images/generations".into());
        let api_key = std::env::var("OMNIBOT_IMAGE_API_KEY")
            .or_else(|_| std::env::var("OPENAI_API_KEY"))
            .unwrap_or_default();
        if api_key.trim().is_empty() {
            return Ok(ToolExecutionResult::err(
                "image_generate requires OMNIBOT_IMAGE_API_KEY or OPENAI_API_KEY on desktop",
            ));
        }
        let prompt = args
            .get("prompt")
            .or_else(|| args.get("description"))
            .and_then(|v| v.as_str())
            .unwrap_or("");
        if prompt.trim().is_empty() {
            return Ok(ToolExecutionResult::err(
                "image_generate.prompt is required",
            ));
        }
        let model = args
            .get("model")
            .and_then(|v| v.as_str())
            .map(|s| s.to_string())
            .or_else(|| std::env::var("OMNIBOT_IMAGE_MODEL").ok())
            .unwrap_or_else(|| "gpt-image-1".into());
        let size = args
            .get("size")
            .and_then(|v| v.as_str())
            .unwrap_or("1024x1024");
        let body = serde_json::json!({
            "model": model,
            "prompt": prompt,
            "size": size,
            "n": args.get("n").and_then(|v| v.as_u64()).unwrap_or(1).min(4),
        });
        let resp = self
            .http
            .post(endpoint)
            .bearer_auth(api_key)
            .json(&body)
            .send()
            .await;
        let resp = match resp {
            Ok(resp) => resp,
            Err(e) => {
                return Ok(ToolExecutionResult::err(format!(
                    "image_generate request failed: {e}"
                )));
            }
        };
        let status = resp.status();
        let raw: serde_json::Value = match resp.json().await {
            Ok(v) => v,
            Err(e) => {
                return Ok(ToolExecutionResult::err(format!(
                    "image_generate decode failed: {e}"
                )));
            }
        };
        if !status.is_success() {
            return Ok(ToolExecutionResult::err(format!(
                "image_generate provider error {status}: {raw}"
            )));
        }
        let artifacts = raw
            .get("data")
            .and_then(|v| v.as_array())
            .map(|items| {
                items
                    .iter()
                    .enumerate()
                    .map(|(idx, item)| {
                        let url = item
                            .get("url")
                            .and_then(|v| v.as_str())
                            .map(|s| s.to_string());
                        let b64 = item
                            .get("b64_json")
                            .and_then(|v| v.as_str())
                            .map(|s| format!("data:image/png;base64,{s}"));
                        serde_json::json!({
                            "kind": "image",
                            "index": idx,
                            "url": url,
                            "dataUrl": b64,
                        })
                    })
                    .collect::<Vec<_>>()
            })
            .unwrap_or_default();
        Ok(ToolExecutionResult {
            success: true,
            summary: format!("generated {} image(s)", artifacts.len()),
            stdout: String::new(),
            structured: serde_json::json!({"response": raw, "artifacts": artifacts}),
            error: None,
            artifacts,
        })
    }
}
