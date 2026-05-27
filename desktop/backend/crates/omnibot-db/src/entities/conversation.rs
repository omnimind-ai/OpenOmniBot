use serde::{Deserialize, Serialize};
use sqlx::FromRow;

#[derive(Debug, Clone, Serialize, Deserialize, FromRow)]
pub struct Conversation {
    pub id: i64,
    pub title: String,
    pub mode: String,
    pub is_archived: i64,
    pub is_pinned: i64,
    pub parent_conversation_id: Option<i64>,
    pub parent_conversation_mode: Option<String>,
    pub scheduled_task_id: Option<String>,
    pub summary: Option<String>,
    pub context_summary: Option<String>,
    pub context_summary_cutoff_entry_db_id: Option<i64>,
    pub context_summary_updated_at: i64,
    pub status: i64,
    pub last_message: Option<String>,
    pub message_count: i64,
    pub latest_prompt_tokens: i64,
    pub prompt_token_threshold: i64,
    pub latest_prompt_tokens_updated_at: i64,
    pub created_at: i64,
    pub updated_at: i64,
}

impl Conversation {
    pub fn to_dart_payload(&self) -> serde_json::Value {
        serde_json::json!({
            "id": self.id,
            "title": self.title,
            "mode": self.mode,
            "isArchived": self.is_archived != 0,
            "isPinned": self.is_pinned != 0,
            "parentConversationId": self.parent_conversation_id,
            "parentConversationMode": self.parent_conversation_mode,
            "scheduledTaskId": self.scheduled_task_id,
            "summary": self.summary,
            "contextSummary": self.context_summary,
            "contextSummaryCutoffEntryDbId": self.context_summary_cutoff_entry_db_id,
            "contextSummaryUpdatedAt": self.context_summary_updated_at,
            "status": self.status,
            "lastMessage": self.last_message,
            "messageCount": self.message_count,
            "latestPromptTokens": self.latest_prompt_tokens,
            "promptTokenThreshold": self.prompt_token_threshold,
            "latestPromptTokensUpdatedAt": self.latest_prompt_tokens_updated_at,
            "createdAt": self.created_at,
            "updatedAt": self.updated_at,
        })
    }
}
