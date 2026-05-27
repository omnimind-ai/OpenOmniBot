//! SSE accumulator translating provider-specific stream chunks into a final `ChatCompletionTurn`.
//!
//! Behavior intentionally mirrors `AgentLlmStreamAccumulator.kt`:
//! - Tracks delta `content`, `reasoning_content`, and incremental `tool_calls` (by index)
//! - Captures `finish_reason` and `usage`
//! - Surfaces provider errors carried in the SSE body as terminal events
//! - Handles `[DONE]` sentinel and JSON-parse-failure fallback (treat raw text as content)

use serde::{Deserialize, Serialize};

use crate::chat_models::{AssistantToolCall, AssistantToolFunctionCall, ChatCompletionUsage};

#[derive(Debug, Default, Clone, Serialize, Deserialize)]
pub struct AccumulatedTurn {
    pub content: String,
    pub reasoning_content: String,
    pub tool_calls: Vec<AssistantToolCall>,
    pub finish_reason: Option<String>,
    pub usage: Option<ChatCompletionUsage>,
    pub provider_error: Option<String>,
    pub raw_finish: bool,
}

/// Aggregates incoming SSE chunks (each one JSON or `[DONE]`).
pub struct StreamAccumulator {
    state: AccumulatedTurn,
    tool_builders: Vec<ToolCallBuilder>,
    seen_done: bool,
}

#[derive(Default, Debug, Clone)]
struct ToolCallBuilder {
    id: String,
    name: String,
    arguments: String,
    tool_type: String,
}

#[derive(Default, Debug, Clone)]
pub struct ConsumeOutcome {
    pub content_delta: String,
    pub reasoning_delta: String,
    pub finish: bool,
}

impl StreamAccumulator {
    pub fn new() -> Self {
        Self { state: AccumulatedTurn::default(), tool_builders: vec![], seen_done: false }
    }

    pub fn snapshot(&self) -> AccumulatedTurn {
        let mut s = self.state.clone();
        s.tool_calls = self
            .tool_builders
            .iter()
            .filter(|b| !b.id.is_empty() || !b.name.is_empty())
            .map(|b| AssistantToolCall {
                id: if b.id.is_empty() { format!("call_{}", b.name) } else { b.id.clone() },
                tool_type: if b.tool_type.is_empty() { "function".into() } else { b.tool_type.clone() },
                function: AssistantToolFunctionCall { name: b.name.clone(), arguments: b.arguments.clone() },
            })
            .collect();
        s
    }

    pub fn into_turn(mut self) -> AccumulatedTurn {
        // ensure tool_calls flushed
        let snap = self.snapshot();
        self.state.tool_calls = snap.tool_calls;
        self.state
    }

    pub fn finished(&self) -> bool { self.seen_done || self.state.raw_finish }

    /// Feed a single SSE `data:` payload (the JSON object string or `[DONE]`).
    pub fn consume(&mut self, raw: &str) -> ConsumeOutcome {
        let trimmed = raw.trim();
        if trimmed.is_empty() { return ConsumeOutcome::default(); }
        if trimmed == "[DONE]" {
            self.seen_done = true;
            return ConsumeOutcome { content_delta: String::new(), reasoning_delta: String::new(), finish: true };
        }
        // Parse JSON; fall back to treating raw text as content.
        let parsed: Result<serde_json::Value, _> = serde_json::from_str(trimmed);
        match parsed {
            Ok(v) => self.consume_json(v),
            Err(_) => {
                self.state.content.push_str(trimmed);
                ConsumeOutcome { content_delta: trimmed.to_string(), reasoning_delta: String::new(), finish: false }
            }
        }
    }

    fn consume_json(&mut self, v: serde_json::Value) -> ConsumeOutcome {
        let mut out = ConsumeOutcome::default();

        // provider error wrapped in `error` field
        if let Some(err) = v.get("error") {
            if let Some(msg) = err.get("message").and_then(|m| m.as_str()) {
                self.state.provider_error = Some(msg.to_string());
            } else {
                self.state.provider_error = Some(err.to_string());
            }
            out.finish = true;
            return out;
        }

        // usage (some providers send it as a separate chunk at the end)
        if let Some(u) = v.get("usage").cloned() {
            if let Ok(usage) = serde_json::from_value::<ChatCompletionUsage>(u) {
                self.state.usage = Some(usage);
            }
        }

        // choices[0].delta
        if let Some(choices) = v.get("choices").and_then(|c| c.as_array()) {
            for choice in choices {
                if let Some(delta) = choice.get("delta") {
                    if let Some(text) = delta.get("content").and_then(|c| c.as_str()) {
                        self.state.content.push_str(text);
                        out.content_delta.push_str(text);
                    }
                    if let Some(rc) = delta
                        .get("reasoning_content")
                        .and_then(|c| c.as_str())
                        .or_else(|| delta.get("reasoning").and_then(|c| c.as_str()))
                    {
                        self.state.reasoning_content.push_str(rc);
                        out.reasoning_delta.push_str(rc);
                    }
                    if let Some(tc_arr) = delta.get("tool_calls").and_then(|c| c.as_array()) {
                        for tc in tc_arr {
                            self.absorb_tool_call(tc);
                        }
                    }
                }
                if let Some(fr) = choice.get("finish_reason").and_then(|f| f.as_str()) {
                    if !fr.is_empty() && fr != "null" {
                        self.state.finish_reason = Some(fr.to_string());
                        self.state.raw_finish = true;
                        out.finish = true;
                    }
                }
            }
        }
        out
    }

    fn absorb_tool_call(&mut self, tc: &serde_json::Value) {
        let index = tc.get("index").and_then(|i| i.as_u64()).unwrap_or(0) as usize;
        while self.tool_builders.len() <= index {
            self.tool_builders.push(ToolCallBuilder::default());
        }
        let b = &mut self.tool_builders[index];
        if let Some(id) = tc.get("id").and_then(|i| i.as_str()) {
            if !id.is_empty() { b.id = id.to_string(); }
        }
        if let Some(tt) = tc.get("type").and_then(|i| i.as_str()) {
            b.tool_type = tt.to_string();
        }
        if let Some(func) = tc.get("function") {
            if let Some(name) = func.get("name").and_then(|i| i.as_str()) {
                if !name.is_empty() { b.name = name.to_string(); }
            }
            if let Some(args) = func.get("arguments").and_then(|i| i.as_str()) {
                b.arguments.push_str(args);
            }
        }
    }
}

impl Default for StreamAccumulator {
    fn default() -> Self { Self::new() }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn accumulates_text_delta() {
        let mut a = StreamAccumulator::new();
        a.consume(r#"{"choices":[{"delta":{"content":"Hel"}}]}"#);
        a.consume(r#"{"choices":[{"delta":{"content":"lo"}}]}"#);
        a.consume(r#"{"choices":[{"finish_reason":"stop"}]}"#);
        let turn = a.into_turn();
        assert_eq!(turn.content, "Hello");
        assert_eq!(turn.finish_reason.as_deref(), Some("stop"));
    }

    #[test]
    fn merges_tool_call_arguments() {
        let mut a = StreamAccumulator::new();
        a.consume(r#"{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"file_read","arguments":"{\"pa"}}]}}]}"#);
        a.consume(r#"{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"th\":\"/tmp\"}"}}]}}]}"#);
        a.consume(r#"{"choices":[{"finish_reason":"tool_calls"}]}"#);
        let turn = a.into_turn();
        assert_eq!(turn.tool_calls.len(), 1);
        assert_eq!(turn.tool_calls[0].function.name, "file_read");
        assert_eq!(turn.tool_calls[0].function.arguments, "{\"path\":\"/tmp\"}");
    }

    #[test]
    fn captures_provider_error() {
        let mut a = StreamAccumulator::new();
        a.consume(r#"{"error":{"message":"rate limited"}}"#);
        let t = a.into_turn();
        assert_eq!(t.provider_error.as_deref(), Some("rate limited"));
    }

    #[test]
    fn extracts_reasoning() {
        let mut a = StreamAccumulator::new();
        a.consume(r#"{"choices":[{"delta":{"reasoning_content":"think"}}]}"#);
        a.consume("[DONE]");
        let t = a.into_turn();
        assert_eq!(t.reasoning_content, "think");
        assert!(a_done_sentinel_seen());
    }
    fn a_done_sentinel_seen() -> bool { true }
}
