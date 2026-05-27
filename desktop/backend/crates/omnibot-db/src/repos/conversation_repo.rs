use omnibot_common::{AppError, AppResult, now_unix_ms};
use sqlx::SqlitePool;

use crate::entities::Conversation;

pub struct ConversationRepo;

impl ConversationRepo {
    pub async fn create(
        pool: &SqlitePool,
        title: &str,
        mode: &str,
    ) -> AppResult<Conversation> {
        let now = now_unix_ms();
        let rec = sqlx::query_as::<_, Conversation>(
            r#"INSERT INTO conversations (title, mode, created_at, updated_at)
               VALUES (?, ?, ?, ?)
               RETURNING *"#,
        )
        .bind(title)
        .bind(mode)
        .bind(now)
        .bind(now)
        .fetch_one(pool)
        .await
        .map_err(|e| AppError::backend(format!("create conversation: {e}")))?;
        Ok(rec)
    }

    pub async fn get(pool: &SqlitePool, id: i64) -> AppResult<Option<Conversation>> {
        let row = sqlx::query_as::<_, Conversation>("SELECT * FROM conversations WHERE id=?")
            .bind(id)
            .fetch_optional(pool)
            .await
            .map_err(|e| AppError::backend(format!("get conversation: {e}")))?;
        Ok(row)
    }

    pub async fn list(pool: &SqlitePool, mode: Option<&str>) -> AppResult<Vec<Conversation>> {
        let rows = match mode {
            Some(m) => sqlx::query_as::<_, Conversation>(
                "SELECT * FROM conversations WHERE mode=? AND is_archived=0 ORDER BY is_pinned DESC, updated_at DESC",
            )
            .bind(m)
            .fetch_all(pool)
            .await,
            None => sqlx::query_as::<_, Conversation>(
                "SELECT * FROM conversations WHERE is_archived=0 ORDER BY is_pinned DESC, updated_at DESC",
            )
            .fetch_all(pool)
            .await,
        }
        .map_err(|e| AppError::backend(format!("list conversations: {e}")))?;
        Ok(rows)
    }

    pub async fn update_title(pool: &SqlitePool, id: i64, title: &str) -> AppResult<()> {
        let now = now_unix_ms();
        sqlx::query("UPDATE conversations SET title=?, updated_at=? WHERE id=?")
            .bind(title)
            .bind(now)
            .bind(id)
            .execute(pool)
            .await
            .map_err(|e| AppError::backend(format!("update title: {e}")))?;
        Ok(())
    }

    pub async fn delete(pool: &SqlitePool, id: i64) -> AppResult<()> {
        sqlx::query("DELETE FROM agent_conversation_entries WHERE conversation_id=?")
            .bind(id)
            .execute(pool)
            .await
            .map_err(|e| AppError::backend(format!("delete entries: {e}")))?;
        sqlx::query("DELETE FROM conversations WHERE id=?")
            .bind(id)
            .execute(pool)
            .await
            .map_err(|e| AppError::backend(format!("delete conversation: {e}")))?;
        Ok(())
    }

    pub async fn touch(
        pool: &SqlitePool,
        id: i64,
        last_message: Option<&str>,
        delta_count: i64,
    ) -> AppResult<()> {
        let now = now_unix_ms();
        sqlx::query(
            r#"UPDATE conversations
               SET last_message=COALESCE(?, last_message),
                   message_count = message_count + ?,
                   updated_at=?
               WHERE id=?"#,
        )
        .bind(last_message)
        .bind(delta_count)
        .bind(now)
        .bind(id)
        .execute(pool)
        .await
        .map_err(|e| AppError::backend(format!("touch conversation: {e}")))?;
        Ok(())
    }

    pub async fn set_archived(pool: &SqlitePool, id: i64, archived: bool) -> AppResult<()> {
        sqlx::query("UPDATE conversations SET is_archived=?, updated_at=? WHERE id=?")
            .bind(if archived { 1 } else { 0 })
            .bind(now_unix_ms())
            .bind(id)
            .execute(pool)
            .await
            .map_err(|e| AppError::backend(format!("archive: {e}")))?;
        Ok(())
    }

    pub async fn set_pinned(pool: &SqlitePool, id: i64, pinned: bool) -> AppResult<()> {
        sqlx::query("UPDATE conversations SET is_pinned=?, updated_at=? WHERE id=?")
            .bind(if pinned { 1 } else { 0 })
            .bind(now_unix_ms())
            .bind(id)
            .execute(pool)
            .await
            .map_err(|e| AppError::backend(format!("pin: {e}")))?;
        Ok(())
    }

    pub async fn set_prompt_token_threshold(
        pool: &SqlitePool,
        id: i64,
        threshold: i64,
    ) -> AppResult<()> {
        sqlx::query("UPDATE conversations SET prompt_token_threshold=?, updated_at=? WHERE id=?")
            .bind(threshold)
            .bind(now_unix_ms())
            .bind(id)
            .execute(pool)
            .await
            .map_err(|e| AppError::backend(format!("set prompt token threshold: {e}")))?;
        Ok(())
    }
}
