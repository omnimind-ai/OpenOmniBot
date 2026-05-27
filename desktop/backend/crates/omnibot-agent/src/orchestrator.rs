use std::sync::Arc;
use std::sync::atomic::{AtomicI64, Ordering};

use async_trait::async_trait;
use omnibot_llm::{
    AgentLlmClient, ChatCompletionMessage, ChatCompletionRequest, LlmEndpoint, StreamSink,
};
use omnibot_tools::{
    AgentExecutionEnvironment, ToolBatch, ToolEventSink, ToolExecutionResult, ToolRouter,
    partition,
};

use crate::callback::{AgentCallback, ToolEventSinkOverCallback};
use crate::memory::AgentChatMemory;
use crate::stream_event::AgentStreamEvent;

/// Agent orchestrator: a multi-round LLM loop with tool dispatch and length-continuation.
/// Equivalent of Android `AgentOrchestrator.kt`, scaled to desktop deployment.
pub struct AgentOrchestrator {
    llm_client: Arc<dyn AgentLlmClient>,
    tool_router: Arc<ToolRouter>,
    endpoint: LlmEndpoint,
    model: String,
    compactor: Option<Arc<crate::compactor::AgentConversationContextCompactor>>,
}

#[derive(Clone)]
pub struct AgentRunInput {
    pub task_id: String,
    pub callback: Arc<dyn AgentCallback>,
    pub initial_messages: Vec<ChatCompletionMessage>,
    pub environment: AgentExecutionEnvironment,
    pub max_rounds: usize,
    /// Conversation database id. Required for context compaction to fire.
    pub conversation_id: Option<i64>,
}

#[derive(Debug, Clone)]
pub enum AgentResult {
    Finished { rounds: usize, last_message: String },
    Aborted { reason: String },
    Error { message: String },
}

impl AgentOrchestrator {
    pub fn new(
        llm_client: Arc<dyn AgentLlmClient>,
        tool_router: Arc<ToolRouter>,
        endpoint: LlmEndpoint,
        model: String,
    ) -> Self {
        Self { llm_client, tool_router, endpoint, model, compactor: None }
    }

    pub fn with_compactor(
        mut self,
        compactor: Arc<crate::compactor::AgentConversationContextCompactor>,
    ) -> Self {
        self.compactor = Some(compactor);
        self
    }

    pub async fn run(&self, input: AgentRunInput) -> AgentResult {
        let callback = input.callback.clone();
        let memory = AgentChatMemory::from_initial(input.initial_messages);
        let seq = Arc::new(AtomicI64::new(0));
        let mode = input.environment.conversation_mode.clone();
        let max_rounds = if input.max_rounds == 0 { 16 } else { input.max_rounds };
        let mut completed_rounds = 0usize;

        loop {
            if completed_rounds >= max_rounds {
                callback.on_error("max rounds reached").await;
                return AgentResult::Aborted { reason: "max rounds reached".into() };
            }
            completed_rounds += 1;
            callback.on_thinking_start().await;

            let request = ChatCompletionRequest {
                model: self.model.clone(),
                messages: memory.snapshot(),
                temperature: Some(0.6),
                top_p: None,
                max_tokens: None,
                stream: Some(true),
                stream_options: Some(omnibot_llm::StreamOptions { include_usage: true }),
                tools: Some(crate::tool_catalog::static_tool_definitions()),
                tool_choice: Some(serde_json::Value::String("auto".into())),
                parallel_tool_calls: Some(true),
                extras: Default::default(),
            };

            let sink_cb = callback.clone();
            let task_id = input.task_id.clone();
            let mode_for_sink = mode.clone();
            let seq_for_sink = seq.clone();
            let sink = Arc::new(CallbackStreamSink {
                cb: sink_cb,
                task_id,
                mode: mode_for_sink,
                seq: seq_for_sink,
            });

            let turn = match self.llm_client
                .stream_turn(request, &self.endpoint, sink.clone() as Arc<dyn StreamSink>)
                .await
            {
                Ok(t) => t,
                Err(e) => {
                    callback.on_error(&format!("LLM error: {e}")).await;
                    callback.on_stream_event(AgentStreamEvent::error(
                        &input.task_id,
                        &mode,
                        seq.fetch_add(1, Ordering::SeqCst),
                        e.to_string(),
                    )).await;
                    return AgentResult::Error { message: e.to_string() };
                }
            };

            // Persist the assistant message into memory before tool calls.
            let assistant_msg = turn.message.clone();
            memory.push(assistant_msg.clone());

            if let Some(usage) = &turn.usage {
                callback
                    .on_prompt_token_usage_changed(usage.prompt_tokens, None)
                    .await;
                callback.on_stream_event(AgentStreamEvent {
                    task_id: input.task_id.clone(),
                    conversation_id: None,
                    conversation_mode: mode.clone(),
                    seq: seq.fetch_add(1, Ordering::SeqCst),
                    kind: "token_usage".into(),
                    prompt_tokens: Some(usage.prompt_tokens),
                    ..Default::default()
                }).await;

                // Trigger context compaction in the background. Refresh of the in-memory
                // transcript happens on the next agent task; we don't replay mid-loop.
                if let (Some(compactor), Some(cid)) = (&self.compactor, input.conversation_id) {
                    let compactor = compactor.clone();
                    let prompt_tokens = usage.prompt_tokens;
                    let mode_for_compact = mode.clone();
                    let cb_for_compact = callback.clone();
                    tokio::spawn(async move {
                        cb_for_compact.on_context_compaction_state(true, Some(prompt_tokens), None).await;
                        let outcome = compactor.compact_if_needed(cid, &mode_for_compact, prompt_tokens).await;
                        cb_for_compact.on_context_compaction_state(false, Some(prompt_tokens), None).await;
                        if let Err(e) = outcome {
                            tracing::warn!(error=%e, conversation_id=cid, "compactor failed");
                        }
                    });
                }
            }

            // If no tool calls, finalize this turn.
            if assistant_msg.tool_calls.is_empty() {
                let final_text = assistant_msg.text_content();
                callback.on_chat_message(&final_text, true).await;
                callback.on_stream_event(AgentStreamEvent::chat_message(
                    &input.task_id,
                    &mode,
                    seq.fetch_add(1, Ordering::SeqCst),
                    final_text.clone(),
                    true,
                )).await;

                if turn.finish_reason.as_deref() == Some("length") && completed_rounds < max_rounds {
                    // Length-continuation: keep going so the model can finish its answer.
                    memory.push(ChatCompletionMessage::user("Continue."));
                    continue;
                }
                callback.on_stream_event(AgentStreamEvent::finish(
                    &input.task_id,
                    &mode,
                    seq.fetch_add(1, Ordering::SeqCst),
                )).await;
                callback.on_complete(&final_text).await;
                return AgentResult::Finished { rounds: completed_rounds, last_message: final_text };
            }

            // Tool-call phase: partition into parallel/serial batches.
            let calls = assistant_msg.tool_calls.clone();
            let tool_sink: Arc<dyn ToolEventSink> = ToolEventSinkOverCallback::new(callback.clone());
            let mut tool_results: Vec<(String, ToolExecutionResult)> = vec![];
            let batches = partition(calls.clone(), |tc| self.tool_router.concurrency_hint(&tc.function.name));

            for batch in batches {
                match batch {
                    ToolBatch::Parallel(items) => {
                        let mut joinset = tokio::task::JoinSet::new();
                        for tc in items {
                            let router = self.tool_router.clone();
                            let env = input.environment.clone();
                            let sink = tool_sink.clone();
                            let cb = callback.clone();
                            let task_id = input.task_id.clone();
                            let mode = mode.clone();
                            let seq = seq.clone();
                            joinset.spawn(async move {
                                let parsed_args: serde_json::Value = serde_json::from_str(&tc.function.arguments).unwrap_or(serde_json::Value::Null);
                                cb.on_tool_call_start(&tc.function.name, &parsed_args).await;
                                cb.on_stream_event(AgentStreamEvent {
                                    task_id: task_id.clone(),
                                    conversation_id: None,
                                    conversation_mode: mode.clone(),
                                    seq: seq.fetch_add(1, Ordering::SeqCst),
                                    kind: "tool_start".into(),
                                    tool_name: Some(tc.function.name.clone()),
                                    tool_call_id: Some(tc.id.clone()),
                                    status: Some("running".into()),
                                    args_json: Some(tc.function.arguments.clone()),
                                    ..Default::default()
                                }).await;

                                let result = router.execute(&tc.id, &tc.function.name, parsed_args.clone(), &env, sink).await
                                    .unwrap_or_else(|e| ToolExecutionResult::err(e.to_string()));
                                cb.on_tool_call_complete(&tc.function.name, &result).await;
                                cb.on_stream_event(AgentStreamEvent {
                                    task_id: task_id.clone(),
                                    conversation_id: None,
                                    conversation_mode: mode.clone(),
                                    seq: seq.fetch_add(1, Ordering::SeqCst),
                                    kind: "tool_complete".into(),
                                    tool_name: Some(tc.function.name.clone()),
                                    tool_call_id: Some(tc.id.clone()),
                                    status: Some(if result.success { "succeeded".into() } else { "failed".into() }),
                                    result_preview_json: Some(serde_json::to_string(&result.structured).unwrap_or_default()),
                                    progress: Some(result.summary.clone()),
                                    ..Default::default()
                                }).await;
                                (tc.id.clone(), result)
                            });
                        }
                        while let Some(joined) = joinset.join_next().await {
                            if let Ok((id, result)) = joined { tool_results.push((id, result)); }
                        }
                    }
                    ToolBatch::Serial(tc) => {
                        let parsed_args: serde_json::Value = serde_json::from_str(&tc.function.arguments).unwrap_or(serde_json::Value::Null);
                        callback.on_tool_call_start(&tc.function.name, &parsed_args).await;
                        callback.on_stream_event(AgentStreamEvent {
                            task_id: input.task_id.clone(),
                            conversation_id: None,
                            conversation_mode: mode.clone(),
                            seq: seq.fetch_add(1, Ordering::SeqCst),
                            kind: "tool_start".into(),
                            tool_name: Some(tc.function.name.clone()),
                            tool_call_id: Some(tc.id.clone()),
                            status: Some("running".into()),
                            args_json: Some(tc.function.arguments.clone()),
                            ..Default::default()
                        }).await;
                        let result = self
                            .tool_router
                            .execute(&tc.id, &tc.function.name, parsed_args, &input.environment, tool_sink.clone())
                            .await
                            .unwrap_or_else(|e| ToolExecutionResult::err(e.to_string()));
                        callback.on_tool_call_complete(&tc.function.name, &result).await;
                        callback.on_stream_event(AgentStreamEvent {
                            task_id: input.task_id.clone(),
                            conversation_id: None,
                            conversation_mode: mode.clone(),
                            seq: seq.fetch_add(1, Ordering::SeqCst),
                            kind: "tool_complete".into(),
                            tool_name: Some(tc.function.name.clone()),
                            tool_call_id: Some(tc.id.clone()),
                            status: Some(if result.success { "succeeded".into() } else { "failed".into() }),
                            result_preview_json: Some(serde_json::to_string(&result.structured).unwrap_or_default()),
                            progress: Some(result.summary.clone()),
                            ..Default::default()
                        }).await;
                        tool_results.push((tc.id.clone(), result));
                    }
                }
            }

            // Append tool results into memory for the next round.
            for (id, result) in &tool_results {
                let content = serde_json::Value::String(if result.success { result.summary.clone() } else { result.error.clone().unwrap_or_default() });
                memory.push(ChatCompletionMessage::tool_result(id.clone(), content));
            }

            // If a tool requested stop, finish gracefully.
            if tool_results.iter().any(|(_, r)| matches!(r.structured.get("action"), Some(serde_json::Value::String(s)) if s == "stop_conversation")) {
                callback.on_stream_event(AgentStreamEvent::finish(
                    &input.task_id,
                    &mode,
                    seq.fetch_add(1, Ordering::SeqCst),
                )).await;
                callback.on_complete("conversation stopped by tool").await;
                return AgentResult::Finished { rounds: completed_rounds, last_message: String::new() };
            }
        }
    }
}

struct CallbackStreamSink {
    cb: Arc<dyn AgentCallback>,
    task_id: String,
    mode: String,
    seq: Arc<AtomicI64>,
}

#[async_trait]
impl StreamSink for CallbackStreamSink {
    async fn on_content_delta(&self, full: &str, _delta: &str) {
        self.cb.on_chat_message(full, false).await;
        self.cb.on_stream_event(AgentStreamEvent::chat_message(
            &self.task_id,
            &self.mode,
            self.seq.fetch_add(1, Ordering::SeqCst),
            full.to_string(),
            false,
        )).await;
    }
    async fn on_reasoning_snapshot(&self, full: &str) {
        self.cb.on_thinking_update(full).await;
        self.cb.on_stream_event(AgentStreamEvent::reasoning(
            &self.task_id,
            &self.mode,
            self.seq.fetch_add(1, Ordering::SeqCst),
            full.to_string(),
        )).await;
    }
}

impl Default for AgentStreamEvent {
    fn default() -> Self {
        Self {
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
}
