use serde::{Deserialize, Serialize};

/// Frames sent **from Dart bridge to backend**.
#[derive(Debug, Clone, Deserialize)]
#[serde(tag = "type")]
pub enum IncomingFrame {
    #[serde(rename = "method_call")]
    MethodCall {
        #[serde(rename = "requestId")] request_id: String,
        channel: String,
        method: String,
        #[serde(default)] arguments: serde_json::Value,
    },
    #[serde(rename = "event_listen")]
    EventListen {
        #[serde(rename = "subscriptionId")] subscription_id: String,
        channel: String,
        #[serde(default)] arguments: serde_json::Value,
    },
    #[serde(rename = "event_cancel")]
    EventCancel {
        #[serde(rename = "subscriptionId")] subscription_id: String,
    },
    #[serde(rename = "ping")]
    Ping,
}

/// Frames sent **from backend to Dart bridge**.
#[derive(Debug, Clone, Serialize)]
#[serde(tag = "type")]
pub enum OutgoingFrame {
    #[serde(rename = "method_response")]
    MethodResponse {
        #[serde(rename = "requestId")] request_id: String,
        ok: bool,
        #[serde(default, skip_serializing_if = "Option::is_none")] value: Option<serde_json::Value>,
        #[serde(default, skip_serializing_if = "Option::is_none")] error: Option<ErrorPayload>,
    },
    /// Backend-initiated method call (e.g. `onAgentStreamEvent`).
    #[serde(rename = "method_invoke")]
    MethodInvoke {
        channel: String,
        method: String,
        #[serde(default)] arguments: serde_json::Value,
    },
    #[serde(rename = "event_data")]
    EventData {
        #[serde(rename = "subscriptionId")] subscription_id: String,
        data: serde_json::Value,
    },
    #[serde(rename = "event_error")]
    EventError {
        #[serde(rename = "subscriptionId")] subscription_id: String,
        code: String,
        message: String,
    },
    #[serde(rename = "event_end")]
    EventEnd {
        #[serde(rename = "subscriptionId")] subscription_id: String,
    },
    #[serde(rename = "system_event")]
    SystemEvent {
        kind: String,
        #[serde(default)] data: serde_json::Value,
    },
    #[serde(rename = "pong")]
    Pong,
}

#[derive(Debug, Clone, Serialize)]
pub struct ErrorPayload {
    pub code: String,
    pub message: String,
}
