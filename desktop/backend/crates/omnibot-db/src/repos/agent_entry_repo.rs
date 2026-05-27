use omnibot_common::{AppError, AppResult, now_unix_ms};
use sqlx::SqlitePool;

use crate::entities::AgentConversationEntry;

pub struct AgentEntryRepo;

impl AgentEntryRepo {
    pub async fn upsert(
        pool: &SqlitePool,
        conversation_id: i64,
        conversation_mode: &str,
        entry_id: &str,
        entry_type: &str,
        status: &str,
        summary: &str,
        payload: &serde_json::Value,
    ) -> AppResult<AgentConversationEntry> {
        let payload_json = serde_json::to_string(payload)?;
        let now = now_unix_ms();
        let row = sqlx::query_as::<_, AgentConversationEntry>(
            r#"INSERT INTO agent_conversation_entries
               (conversation_id, conversation_mode, entry_id, entry_type, status, summary, payload_json, created_at, updated_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT(conversation_id, conversation_mode, entry_id) DO UPDATE SET
                 entry_type=excluded.entry_type,
                 status=excluded.status,
                 summary=excluded.summary,
                 payload_json=excluded.payload_json,
                 updated_at=excluded.updated_at
               RETURNING *"#,
        )
        .bind(conversation_id)
        .bind(conversation_mode)
        .bind(entry_id)
        .bind(entry_type)
        .bind(status)
        .bind(summary)
        .bind(payload_json)
        .bind(now)
        .bind(now)
        .fetch_one(pool)
        .await
        .map_err(|e| AppError::backend(format!("upsert entry: {e}")))?;
        Ok(row)
    }

    pub async fn list(
        pool: &SqlitePool,
        conversation_id: i64,
        conversation_mode: &str,
    ) -> AppResult<Vec<AgentConversationEntry>> {
        let rows = sqlx::query_as::<_, AgentConversationEntry>(
            r#"SELECT * FROM agent_conversation_entries
               WHERE conversation_id=? AND conversation_mode=?
               ORDER BY updated_at ASC, id ASC"#,
        )
        .bind(conversation_id)
        .bind(conversation_mode)
        .fetch_all(pool)
        .await
        .map_err(|e| AppError::backend(format!("list entries: {e}")))?;
        Ok(rows)
    }

    pub async fn clear(
        pool: &SqlitePool,
        conversation_id: i64,
        conversation_mode: &str,
    ) -> AppResult<()> {
        sqlx::query(
            "DELETE FROM agent_conversation_entries WHERE conversation_id=? AND conversation_mode=?",
        )
        .bind(conversation_id)
        .bind(conversation_mode)
        .execute(pool)
        .await
        .map_err(|e| AppError::backend(format!("clear entries: {e}")))?;
        Ok(())
    }
}
