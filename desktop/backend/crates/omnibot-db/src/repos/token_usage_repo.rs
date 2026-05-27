use omnibot_common::{AppError, AppResult, now_unix_ms};
use sqlx::SqlitePool;

use crate::entities::TokenUsageRecord;

pub struct TokenUsageRepo;

impl TokenUsageRepo {
    pub async fn record(
        pool: &SqlitePool,
        conversation_id: i64,
        model: &str,
        prompt_tokens: i64,
        completion_tokens: i64,
        reasoning_tokens: i64,
        text_tokens: i64,
        cached_tokens: i64,
    ) -> AppResult<TokenUsageRecord> {
        let row = sqlx::query_as::<_, TokenUsageRecord>(
            r#"INSERT INTO token_usage_records
               (conversation_id, is_local, model, prompt_tokens, completion_tokens, reasoning_tokens, text_tokens, cached_tokens, created_at)
               VALUES (?, 0, ?, ?, ?, ?, ?, ?, ?)
               RETURNING *"#,
        )
        .bind(conversation_id)
        .bind(model)
        .bind(prompt_tokens)
        .bind(completion_tokens)
        .bind(reasoning_tokens)
        .bind(text_tokens)
        .bind(cached_tokens)
        .bind(now_unix_ms())
        .fetch_one(pool)
        .await
        .map_err(|e| AppError::backend(format!("record token usage: {e}")))?;
        Ok(row)
    }
}
