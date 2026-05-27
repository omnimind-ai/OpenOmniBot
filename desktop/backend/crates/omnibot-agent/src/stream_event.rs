use serde::{Deserialize, Serialize};

/// Wire-format event the backend pushes through `onAgentStreamEvent` to Flutter.
/// Field shape mirrors Android `AgentStreamEvent.kt` so Dart deserialization is identical.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AgentStreamEvent {
    #[serde(rename = "taskId")] pub task_id: String,
    #[serde(rename = "conversationId")] pub conversation_id: Option<i64>,
    #[serde(rename = "conversationMode")] pub conversation_mode: String,
    pub seq: i64,
    pub kind: String, // chat_message | reasoning | tool_start | tool_progress | tool_complete | finish | error | token_usage | compaction
    #[serde(default, skip_serializing_if = "Option::is_none")] pub text: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")] pub is_final: Option<bool>,
    #[serde(default, skip_serializing_if = "Option::is_none", rename = "toolName")] pub tool_name: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none", rename = "toolCallId")] pub tool_call_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")] pub status: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none", rename = "argsJson")] pub args_json: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")] pub progress: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none", rename = "resultPreviewJson")] pub result_preview_json: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none", rename = "promptTokens")] pub prompt_tokens: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none", rename = "promptTokenThreshold")] pub prompt_token_threshold: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none", rename = "isCompacting")] pub is_compacting: Option<bool>,
    #[serde(default, skip_serializing_if = "Option::is_none")] pub error: Option<String>,
}

impl AgentStreamEvent {
    pub fn chat_message(task_id: &str, mode: &str, seq: i64, content: String, is_final: bool) -> Self {
        Self {
            task_id: task_id.into(),
            conversation_id: None,
            conversation_mode: mode.into(),
            seq,
            kind: "chat_message".into(),
            text: Some(content),
            is_final: Some(is_final),
            ..default_event()
        }
    }
    pub fn reasoning(task_id: &str, mode: &str, seq: i64, content: String) -> Self {
        Self {
            task_id: task_id.into(),
            conversation_id: None,
            conversation_mode: mode.into(),
            seq,
            kind: "reasoning".into(),
            text: Some(content),
            ..default_event()
        }
    }
    pub fn finish(task_id: &str, mode: &str, seq: i64) -> Self {
        Self {
            task_id: task_id.into(),
            conversation_id: None,
            conversation_mode: mode.into(),
            seq,
            kind: "finish".into(),
            is_final: Some(true),
            ..default_event()
        }
    }
    pub fn error(task_id: &str, mode: &str, seq: i64, message: String) -> Self {
        Self {
            task_id: task_id.into(),
            conversation_id: None,
            conversation_mode: mode.into(),
            seq,
            kind: "error".into(),
            error: Some(message),
            ..default_event()
        }
    }
}

fn default_event() -> AgentStreamEvent {
    AgentStreamEvent {
        task_id: String::new(),
        conversation_id: None,
        conversation_mode: String::new(),
        seq: 0,
        kind: String::new(),
        text: None,
        is_final: None,
        tool_name: None,
        tool_call_id: None,
        status: None,
        args_json: None,
        progress: None,
        result_preview_json: None,
        prompt_tokens: None,
        prompt_token_threshold: None,
        is_compacting: None,
        error: None,
    }
}
