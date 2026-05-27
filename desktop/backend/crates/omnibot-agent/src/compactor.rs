use std::sync::Arc;

use omnibot_db::{AgentEntryRepo, ConversationRepo, DbHandle};
use omnibot_llm::{
    AgentLlmClient, ChatCompletionMessage, ChatCompletionRequest, HttpAgentLlmClient, LlmEndpoint,
};

/// Decides whether the current `prompt_tokens` exceeds the threshold and produces a summary
/// to replace the older portion of the conversation. Default threshold matches Android.
pub const DEFAULT_PROMPT_TOKEN_THRESHOLD: i64 = 128_000;

pub struct AgentConversationContextCompactor {
    pub db: DbHandle,
    pub llm: Arc<HttpAgentLlmClient>,
    pub endpoint: LlmEndpoint,
    pub model: String,
    pub keep_recent: usize,
}

impl AgentConversationContextCompactor {
    pub async fn compact_if_needed(
        &self,
        conversation_id: i64,
        mode: &str,
        latest_prompt_tokens: i64,
    ) -> omnibot_common::AppResult<bool> {
        let conv = ConversationRepo::get(self.db.pool(), conversation_id).await?;
        let threshold = conv
            .as_ref()
            .map(|c| c.prompt_token_threshold)
            .unwrap_or(DEFAULT_PROMPT_TOKEN_THRESHOLD);
        if latest_prompt_tokens <= threshold { return Ok(false); }

        let entries = AgentEntryRepo::list(self.db.pool(), conversation_id, mode).await?;
        if entries.len() <= self.keep_recent { return Ok(false); }

        // Build prompt: instruct LLM to summarize older entries.
        let mut messages = vec![ChatCompletionMessage::system(
            "Summarize the conversation so far into a compact briefing. \
             Preserve user intent, key facts, decisions, file paths, and pending TODOs. \
             Use 200-400 tokens. Output only the briefing.",
        )];
        for e in entries.iter().take(entries.len().saturating_sub(self.keep_recent)) {
            messages.push(ChatCompletionMessage::user(format!(
                "[{}] {}: {}",
                e.entry_type, e.status, e.summary
            )));
        }
        let request = ChatCompletionRequest {
            model: self.model.clone(),
            messages,
            temperature: Some(0.3),
            top_p: None,
            max_tokens: Some(512),
            stream: None,
            stream_options: None,
            tools: None,
            tool_choice: None,
            parallel_tool_calls: None,
            extras: Default::default(),
        };

        let sink: Arc<dyn omnibot_llm::client::StreamSink> = Arc::new(NoopSink);
        let turn = self.llm.stream_turn(request, &self.endpoint, sink).await
            .map_err(|e| omnibot_common::AppError::Llm(e.to_string()))?;
        let summary = turn.message.text_content();
        // Persist as `context_summary` on the conversation row.
        sqlx::query(
            "UPDATE conversations SET context_summary=?, context_summary_updated_at=? WHERE id=?",
        )
        .bind(&summary)
        .bind(omnibot_common::now_unix_ms())
        .bind(conversation_id)
        .execute(self.db.pool())
        .await
        .map_err(|e| omnibot_common::AppError::backend(format!("save context_summary: {e}")))?;
        Ok(true)
    }
}

struct NoopSink;
#[async_trait::async_trait]
impl omnibot_llm::client::StreamSink for NoopSink {
    async fn on_content_delta(&self, _full: &str, _delta: &str) {}
    async fn on_reasoning_snapshot(&self, _full: &str) {}
}
