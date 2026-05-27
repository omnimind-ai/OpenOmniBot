use serde::{Deserialize, Serialize};
use sqlx::FromRow;

#[derive(Debug, Clone, Serialize, Deserialize, FromRow)]
pub struct AgentConversationEntry {
    pub id: i64,
    pub conversation_id: i64,
    pub conversation_mode: String,
    pub entry_id: String,
    pub entry_type: String,
    pub status: String,
    pub summary: String,
    pub payload_json: String,
    pub created_at: i64,
    pub updated_at: i64,
}

impl AgentConversationEntry {
    pub fn to_dart_payload(&self) -> serde_json::Value {
        let payload: serde_json::Value = serde_json::from_str(&self.payload_json)
            .unwrap_or(serde_json::Value::Null);
        serde_json::json!({
            "id": self.id,
            "conversationId": self.conversation_id,
            "conversationMode": self.conversation_mode,
            "entryId": self.entry_id,
            "entryType": self.entry_type,
            "status": self.status,
            "summary": self.summary,
            "payload": payload,
            "createdAt": self.created_at,
            "updatedAt": self.updated_at,
        })
    }
}
