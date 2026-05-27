use serde::{Deserialize, Serialize};
use sqlx::FromRow;

#[derive(Debug, Clone, Serialize, Deserialize, FromRow)]
pub struct TokenUsageRecord {
    pub id: i64,
    pub conversation_id: i64,
    pub is_local: i64,
    pub model: String,
    pub prompt_tokens: i64,
    pub completion_tokens: i64,
    pub reasoning_tokens: i64,
    pub text_tokens: i64,
    pub cached_tokens: i64,
    pub created_at: i64,
}
