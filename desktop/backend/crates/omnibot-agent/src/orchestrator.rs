use std::collections::{HashMap, HashSet};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};

use async_trait::async_trait;
use futures::FutureExt;
use omnibot_llm::{
    AgentLlmClient, AssistantToolCall, ChatCompletionMessage, ChatCompletionRequest,
    ChatCompletionTool, LlmEndpoint, StreamSink,
};
use omnibot_mcp::{RemoteMcpClient, RemoteMcpConfigStore};
use omnibot_tools::{
    AgentExecutionEnvironment, ToolBatch, ToolEvent, ToolEventSink, ToolExecutionResult,
    ToolRouter, partition,
};
use parking_lot::Mutex;

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
    mcp_client: Option<Arc<RemoteMcpClient>>,
    mcp_config: Option<RemoteMcpConfigStore>,
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
    pub cancel: Option<Arc<tokio::sync::Notify>>,
    pub cancel_flag: Option<Arc<AtomicBool>>,
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
        Self {
            llm_client,
            tool_router,
            endpoint,
            model,
            compactor: None,
            mcp_client: None,
            mcp_config: None,
        }
    }

    pub fn with_compactor(
        mut self,
        compactor: Arc<crate::compactor::AgentConversationContextCompactor>,
    ) -> Self {
        self.compactor = Some(compactor);
        self
    }

    pub fn with_mcp_catalog(
        mut self,
        client: Arc<RemoteMcpClient>,
        config: RemoteMcpConfigStore,
    ) -> Self {
        self.mcp_client = Some(client);
        self.mcp_config = Some(config);
        self
    }

    pub async fn run(&self, input: AgentRunInput) -> AgentResult {
        let callback = input.callback.clone();
        let memory = AgentChatMemory::from_initial(input.initial_messages);
        let seq = Arc::new(AtomicI64::new(0));
        let mode = input.environment.conversation_mode.clone();
        let max_rounds = if input.max_rounds == 0 {
            16
        } else {
            input.max_rounds
        };
        let mut completed_rounds = 0usize;

        loop {
            if input
                .cancel_flag
                .as_ref()
                .map(|flag| flag.load(Ordering::SeqCst))
                .unwrap_or(false)
            {
                callback.on_error("cancelled").await;
                return AgentResult::Aborted {
                    reason: "cancelled".into(),
                };
            }
            if let Some(cancel) = &input.cancel {
                if let Ok(()) = cancel.notified().now_or_never().ok_or(()) {
                    callback.on_error("cancelled").await;
                    return AgentResult::Aborted {
                        reason: "cancelled".into(),
                    };
                }
            }
            if completed_rounds >= max_rounds {
                callback.on_error("max rounds reached").await;
                return AgentResult::Aborted {
                    reason: "max rounds reached".into(),
                };
            }
            completed_rounds += 1;
            callback.on_thinking_start().await;
            let tools = self
                .build_tool_catalog(&input.environment.conversation_mode)
                .await;

            let request = ChatCompletionRequest {
                model: self.model.clone(),
                messages: memory.snapshot(),
                temperature: Some(0.6),
                top_p: None,
                max_tokens: None,
                stream: Some(true),
                stream_options: Some(omnibot_llm::StreamOptions {
                    include_usage: true,
                }),
                tools: Some(tools.clone()),
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

            let stream_future = self.llm_client.stream_turn(
                request,
                &self.endpoint,
                sink.clone() as Arc<dyn StreamSink>,
            );
            let turn_result = if let Some(cancel) = &input.cancel {
                tokio::select! {
                    _ = cancel.notified() => {
                        callback.on_error("cancelled").await;
                        return AgentResult::Aborted { reason: "cancelled".into() };
                    }
                    result = stream_future => result,
                }
            } else {
                stream_future.await
            };
            let turn = match turn_result {
                Ok(t) => t,
                Err(e) => {
                    callback.on_error(&format!("LLM error: {e}")).await;
                    callback
                        .on_stream_event(AgentStreamEvent::error(
                            &input.task_id,
                            &mode,
                            seq.fetch_add(1, Ordering::SeqCst),
                            e.to_string(),
                        ))
                        .await;
                    return AgentResult::Error {
                        message: e.to_string(),
                    };
                }
            };

            // Persist the assistant message into memory before tool calls.
            let assistant_msg = turn.message.clone();
            memory.push(assistant_msg.clone());

            if let Some(usage) = &turn.usage {
                callback
                    .on_prompt_token_usage_changed(usage.prompt_tokens, None)
                    .await;
                callback
                    .on_stream_event(AgentStreamEvent {
                        task_id: input.task_id.clone(),
                        conversation_id: None,
                        conversation_mode: mode.clone(),
                        seq: seq.fetch_add(1, Ordering::SeqCst),
                        kind: "token_usage".into(),
                        prompt_tokens: Some(usage.prompt_tokens),
                        ..Default::default()
                    })
                    .await;

                // Trigger context compaction in the background. Refresh of the in-memory
                // transcript happens on the next agent task; we don't replay mid-loop.
                if let (Some(compactor), Some(cid)) = (&self.compactor, input.conversation_id) {
                    let compactor = compactor.clone();
                    let prompt_tokens = usage.prompt_tokens;
                    let mode_for_compact = mode.clone();
                    let cb_for_compact = callback.clone();
                    let memory_for_compact = memory.clone();
                    tokio::spawn(async move {
                        cb_for_compact
                            .on_context_compaction_state(true, Some(prompt_tokens), None)
                            .await;
                        let outcome = compactor
                            .compact_if_needed(cid, &mode_for_compact, prompt_tokens)
                            .await;
                        cb_for_compact
                            .on_context_compaction_state(false, Some(prompt_tokens), None)
                            .await;
                        match outcome {
                            Ok(true) => trim_memory_after_compaction(&memory_for_compact),
                            Ok(false) => {}
                            Err(e) => {
                                tracing::warn!(error=%e, conversation_id=cid, "compactor failed")
                            }
                        }
                    });
                }
            }

            // If no tool calls, finalize this turn.
            if assistant_msg.tool_calls.is_empty() {
                let final_text = assistant_msg.text_content();
                callback.on_chat_message(&final_text, true).await;
                callback
                    .on_stream_event(AgentStreamEvent::chat_message(
                        &input.task_id,
                        &mode,
                        seq.fetch_add(1, Ordering::SeqCst),
                        final_text.clone(),
                        true,
                    ))
                    .await;

                if turn.finish_reason.as_deref() == Some("length") && completed_rounds < max_rounds
                {
                    // Length-continuation: keep going so the model can finish its answer.
                    memory.push(ChatCompletionMessage::user("Continue."));
                    continue;
                }
                callback
                    .on_stream_event(AgentStreamEvent::finish(
                        &input.task_id,
                        &mode,
                        seq.fetch_add(1, Ordering::SeqCst),
                    ))
                    .await;
                callback.on_complete(&final_text).await;
                return AgentResult::Finished {
                    rounds: completed_rounds,
                    last_message: final_text,
                };
            }

            // Tool-call phase: partition into parallel/serial batches.
            let calls = assistant_msg.tool_calls.clone();
            let prepared = match prepare_tool_calls(calls, &tools, &self.tool_router) {
                Ok(prepared) => prepared,
                Err(invalid_results) => {
                    for (id, result) in &invalid_results {
                        memory.push(ChatCompletionMessage::tool_result(
                            id.clone(),
                            result.to_payload(),
                        ));
                    }
                    continue;
                }
            };
            let tool_sink: Arc<dyn ToolEventSink> =
                ToolEventSinkOverCallback::new(callback.clone());
            let mut tool_results: Vec<(String, ToolExecutionResult)> = vec![];
            let batches = partition(prepared, |tc| {
                self.tool_router.concurrency_hint(&tc.call.function.name)
            });

            for batch in batches {
                match batch {
                    ToolBatch::Parallel(items) => {
                        let mut joinset = tokio::task::JoinSet::new();
                        for tc in items {
                            if input
                                .cancel_flag
                                .as_ref()
                                .map(|flag| flag.load(Ordering::SeqCst))
                                .unwrap_or(false)
                            {
                                callback.on_error("cancelled").await;
                                return AgentResult::Aborted {
                                    reason: "cancelled".into(),
                                };
                            }
                            if let Some(cancel) = &input.cancel {
                                if let Ok(()) = cancel.notified().now_or_never().ok_or(()) {
                                    callback.on_error("cancelled").await;
                                    return AgentResult::Aborted {
                                        reason: "cancelled".into(),
                                    };
                                }
                            }
                            let router = self.tool_router.clone();
                            let env = input.environment.clone();
                            let sink = tool_sink.clone();
                            let cb = callback.clone();
                            let task_id = input.task_id.clone();
                            let mode = mode.clone();
                            let seq = seq.clone();
                            joinset.spawn(async move {
                                let parsed_args = tc.args.clone();
                                cb.on_tool_call_start(&tc.call.function.name, &parsed_args)
                                    .await;
                                cb.on_stream_event(AgentStreamEvent {
                                    task_id: task_id.clone(),
                                    conversation_id: None,
                                    conversation_mode: mode.clone(),
                                    seq: seq.fetch_add(1, Ordering::SeqCst),
                                    kind: "tool_start".into(),
                                    tool_name: Some(tc.call.function.name.clone()),
                                    tool_call_id: Some(tc.call.id.clone()),
                                    status: Some("running".into()),
                                    args_json: Some(tc.call.function.arguments.clone()),
                                    ..Default::default()
                                })
                                .await;

                                let result = router
                                    .execute(
                                        &tc.call.id,
                                        &tc.call.function.name,
                                        parsed_args.clone(),
                                        &env,
                                        sink,
                                    )
                                    .await
                                    .unwrap_or_else(|e| ToolExecutionResult::err(e.to_string()));
                                cb.on_tool_call_complete(&tc.call.function.name, &result)
                                    .await;
                                cb.on_stream_event(AgentStreamEvent {
                                    task_id: task_id.clone(),
                                    conversation_id: None,
                                    conversation_mode: mode.clone(),
                                    seq: seq.fetch_add(1, Ordering::SeqCst),
                                    kind: "tool_complete".into(),
                                    tool_name: Some(tc.call.function.name.clone()),
                                    tool_call_id: Some(tc.call.id.clone()),
                                    status: Some(if result.success {
                                        "succeeded".into()
                                    } else {
                                        "failed".into()
                                    }),
                                    result_preview_json: Some(
                                        serde_json::to_string(&result.to_payload())
                                            .unwrap_or_default(),
                                    ),
                                    progress: Some(result.summary.clone()),
                                    ..Default::default()
                                })
                                .await;
                                (tc.call.id.clone(), result)
                            });
                        }
                        while let Some(joined) = joinset.join_next().await {
                            if let Ok((id, result)) = joined {
                                tool_results.push((id, result));
                            }
                        }
                    }
                    ToolBatch::Serial(tc) => {
                        if input
                            .cancel_flag
                            .as_ref()
                            .map(|flag| flag.load(Ordering::SeqCst))
                            .unwrap_or(false)
                        {
                            callback.on_error("cancelled").await;
                            return AgentResult::Aborted {
                                reason: "cancelled".into(),
                            };
                        }
                        if let Some(cancel) = &input.cancel {
                            if let Ok(()) = cancel.notified().now_or_never().ok_or(()) {
                                callback.on_error("cancelled").await;
                                return AgentResult::Aborted {
                                    reason: "cancelled".into(),
                                };
                            }
                        }
                        let parsed_args = tc.args.clone();
                        callback
                            .on_tool_call_start(&tc.call.function.name, &parsed_args)
                            .await;
                        callback
                            .on_stream_event(AgentStreamEvent {
                                task_id: input.task_id.clone(),
                                conversation_id: None,
                                conversation_mode: mode.clone(),
                                seq: seq.fetch_add(1, Ordering::SeqCst),
                                kind: "tool_start".into(),
                                tool_name: Some(tc.call.function.name.clone()),
                                tool_call_id: Some(tc.call.id.clone()),
                                status: Some("running".into()),
                                args_json: Some(tc.call.function.arguments.clone()),
                                ..Default::default()
                            })
                            .await;
                        let result = if tc.call.function.name == "subagent_dispatch" {
                            self.execute_subagent_dispatch(parsed_args, &input.environment)
                                .await
                        } else {
                            self.tool_router
                                .execute(
                                    &tc.call.id,
                                    &tc.call.function.name,
                                    parsed_args,
                                    &input.environment,
                                    tool_sink.clone(),
                                )
                                .await
                                .unwrap_or_else(|e| ToolExecutionResult::err(e.to_string()))
                        };
                        callback
                            .on_tool_call_complete(&tc.call.function.name, &result)
                            .await;
                        callback
                            .on_stream_event(AgentStreamEvent {
                                task_id: input.task_id.clone(),
                                conversation_id: None,
                                conversation_mode: mode.clone(),
                                seq: seq.fetch_add(1, Ordering::SeqCst),
                                kind: "tool_complete".into(),
                                tool_name: Some(tc.call.function.name.clone()),
                                tool_call_id: Some(tc.call.id.clone()),
                                status: Some(if result.success {
                                    "succeeded".into()
                                } else {
                                    "failed".into()
                                }),
                                result_preview_json: Some(
                                    serde_json::to_string(&result.to_payload()).unwrap_or_default(),
                                ),
                                progress: Some(result.summary.clone()),
                                ..Default::default()
                            })
                            .await;
                        tool_results.push((tc.call.id.clone(), result));
                    }
                }
            }

            // Append tool results into memory for the next round.
            for (id, result) in &tool_results {
                memory.push(ChatCompletionMessage::tool_result(
                    id.clone(),
                    result.to_payload(),
                ));
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

    async fn build_tool_catalog(&self, mode: &str) -> Vec<ChatCompletionTool> {
        let mut tools = crate::tool_catalog::static_tool_definitions();
        if let (Some(client), Some(config)) = (&self.mcp_client, &self.mcp_config) {
            tools.extend(crate::tool_catalog::dynamic_mcp_tool_definitions(client, config).await);
        }
        crate::mode_policy::filter_tools_for_mode(tools, mode)
    }

    async fn execute_subagent_dispatch(
        &self,
        args: serde_json::Value,
        parent_env: &AgentExecutionEnvironment,
    ) -> ToolExecutionResult {
        let raw_tasks = args
            .get("tasks")
            .and_then(|v| v.as_array())
            .cloned()
            .unwrap_or_else(|| {
                vec![serde_json::json!({
                    "profileId": args.get("profileId").and_then(|v| v.as_str()).unwrap_or("general"),
                    "instruction": args.get("instruction").or_else(|| args.get("prompt")).and_then(|v| v.as_str()).unwrap_or(""),
                })]
            });
        let max_tasks = args
            .get("maxConcurrency")
            .or_else(|| args.get("max_concurrency"))
            .and_then(|v| v.as_u64())
            .unwrap_or(3)
            .clamp(1, 6) as usize;
        let mut results = vec![];
        for (idx, task) in raw_tasks.into_iter().take(max_tasks).enumerate() {
            let profile = task
                .get("profileId")
                .or_else(|| task.get("profile"))
                .and_then(|v| v.as_str())
                .unwrap_or("general")
                .to_string();
            let instruction = task
                .get("instruction")
                .or_else(|| task.get("prompt"))
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string();
            if instruction.trim().is_empty() {
                results.push(serde_json::json!({
                    "taskIndex": idx,
                    "profileId": profile,
                    "success": false,
                    "finalText": "",
                    "error": "empty subagent instruction",
                }));
                continue;
            }
            let callback = Arc::new(CollectingCallback::default());
            let mut env = parent_env.clone();
            env.agent_run_id = format!("{}:subagent:{idx}", parent_env.agent_run_id);
            env.user_message = instruction.clone();
            env.conversation_mode = crate::mode_policy::SUBAGENT_MODE.into();
            let messages = vec![
                ChatCompletionMessage::system(crate::prompt::build_system_prompt(
                    &env.workspace,
                    crate::mode_policy::SUBAGENT_MODE,
                )),
                ChatCompletionMessage::user(format!(
                    "Subagent profile: {profile}\n\nInstruction:\n{instruction}"
                )),
            ];
            let result = Box::pin(self.run(AgentRunInput {
                task_id: env.agent_run_id.clone(),
                callback: callback.clone(),
                initial_messages: messages,
                environment: env,
                        max_rounds: 6,
                        conversation_id: None,
                        cancel: None,
                        cancel_flag: None,
                    }))
            .await;
            results.push(serde_json::json!({
                "taskIndex": idx,
                "profileId": profile,
                "success": matches!(result, AgentResult::Finished { .. }),
                "finalText": callback.final_text(),
                "result": format!("{result:?}"),
            }));
        }
        ToolExecutionResult::ok(
            format!("completed {} subagent task(s)", results.len()),
            serde_json::json!({"results": results, "recursiveLlm": true}),
        )
    }
}

#[derive(Debug, Clone)]
struct PreparedToolCall {
    call: AssistantToolCall,
    args: serde_json::Value,
}

fn prepare_tool_calls(
    calls: Vec<AssistantToolCall>,
    tools: &[ChatCompletionTool],
    router: &ToolRouter,
) -> Result<Vec<PreparedToolCall>, Vec<(String, ToolExecutionResult)>> {
    let schemas: HashMap<String, serde_json::Value> = tools
        .iter()
        .map(|tool| (tool.function.name.clone(), tool.function.parameters.clone()))
        .collect();
    let supported_names: HashSet<String> = tools
        .iter()
        .map(|tool| tool.function.name.clone())
        .collect();
    let mut prepared = Vec::with_capacity(calls.len());
    let mut invalid = vec![];
    for call in calls {
        let tool_name = &call.function.name;
        let args: serde_json::Value = match serde_json::from_str(&call.function.arguments) {
            Ok(value) => value,
            Err(e) => {
                invalid.push((
                    call.id.clone(),
                    ToolExecutionResult::err(format!(
                        "invalid JSON arguments for {tool_name}: {e}"
                    )),
                ));
                continue;
            }
        };
        let known_dynamic_mcp =
            tool_name.starts_with("mcp__") && supported_names.contains(tool_name);
        if !router.supports(tool_name) && !known_dynamic_mcp {
            invalid.push((
                call.id.clone(),
                ToolExecutionResult::err(format!("unknown tool: {tool_name}")),
            ));
            continue;
        }
        if let Some(schema) = schemas.get(tool_name) {
            if let Some(missing) = missing_required_fields(schema, &args) {
                invalid.push((
                    call.id.clone(),
                    ToolExecutionResult::err(format!(
                        "missing required field(s) for {tool_name}: {}",
                        missing.join(", ")
                    )),
                ));
                continue;
            }
        }
        prepared.push(PreparedToolCall { call, args });
    }
    if invalid.is_empty() {
        Ok(prepared)
    } else {
        Err(invalid)
    }
}

fn missing_required_fields(
    schema: &serde_json::Value,
    args: &serde_json::Value,
) -> Option<Vec<String>> {
    let required = schema.get("required").and_then(|v| v.as_array())?;
    let mut missing = vec![];
    for field in required.iter().filter_map(|v| v.as_str()) {
        if !args.get(field).map(|v| !v.is_null()).unwrap_or(false) {
            missing.push(field.to_string());
        }
    }
    if missing.is_empty() {
        None
    } else {
        Some(missing)
    }
}

fn trim_memory_after_compaction(memory: &AgentChatMemory) {
    let snapshot = memory.snapshot();
    if snapshot.len() <= 10 {
        return;
    }
    let mut compacted = Vec::with_capacity(10);
    if let Some(system) = snapshot.first() {
        compacted.push(system.clone());
    }
    compacted.push(ChatCompletionMessage::assistant_text(
        "Earlier conversation context was compacted and saved as a conversation summary. Continue using the recent messages below.",
    ));
    let keep = snapshot.len().saturating_sub(8);
    compacted.extend(snapshot.into_iter().skip(keep));
    memory.replace(compacted);
}

#[derive(Default)]
struct CollectingCallback {
    final_text: Mutex<String>,
}

impl CollectingCallback {
    fn final_text(&self) -> String {
        self.final_text.lock().clone()
    }
}

#[async_trait]
impl AgentCallback for CollectingCallback {
    async fn on_thinking_start(&self) {}
    async fn on_thinking_update(&self, _text: &str) {}
    async fn on_chat_message(&self, content: &str, is_final: bool) {
        if is_final {
            *self.final_text.lock() = content.to_string();
        }
    }
    async fn on_tool_call_start(&self, _tool_name: &str, _args: &serde_json::Value) {}
    async fn on_tool_call_progress(&self, _event: ToolEvent) {}
    async fn on_tool_call_complete(&self, _tool_name: &str, _result: &ToolExecutionResult) {}
    async fn on_prompt_token_usage_changed(&self, _latest: i64, _threshold: Option<i64>) {}
    async fn on_context_compaction_state(
        &self,
        _compacting: bool,
        _latest: Option<i64>,
        _threshold: Option<i64>,
    ) {
    }
    async fn on_stream_event(&self, _event: AgentStreamEvent) {}
    async fn on_error(&self, message: &str) {
        *self.final_text.lock() = message.to_string();
    }
    async fn on_complete(&self, summary: &str) {
        *self.final_text.lock() = summary.to_string();
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
        self.cb
            .on_stream_event(AgentStreamEvent::chat_message(
                &self.task_id,
                &self.mode,
                self.seq.fetch_add(1, Ordering::SeqCst),
                full.to_string(),
                false,
            ))
            .await;
    }
    async fn on_reasoning_snapshot(&self, full: &str) {
        self.cb.on_thinking_update(full).await;
        self.cb
            .on_stream_event(AgentStreamEvent::reasoning(
                &self.task_id,
                &self.mode,
                self.seq.fetch_add(1, Ordering::SeqCst),
                full.to_string(),
            ))
            .await;
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
