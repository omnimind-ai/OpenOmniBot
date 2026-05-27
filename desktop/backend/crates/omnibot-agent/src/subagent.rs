use std::sync::Arc;

use omnibot_common::AppResult;
use tokio::sync::Semaphore;

/// Lightweight skeleton — full subagent recursion will reuse `AgentOrchestrator` once the
/// orchestrator settles. For now we surface the dispatcher API so the tool catalog and Dart
/// UI can show the `subagent_dispatch` capability.
pub struct SubagentDispatcher {
    pub max_concurrency: usize,
}

impl SubagentDispatcher {
    pub fn new(max_concurrency: usize) -> Self { Self { max_concurrency } }

    pub async fn dispatch(&self, tasks: Vec<SubagentTaskSpec>) -> AppResult<Vec<SubagentRunResult>> {
        let sem = Arc::new(Semaphore::new(self.max_concurrency.max(1)));
        let mut joinset = tokio::task::JoinSet::new();
        for (idx, task) in tasks.into_iter().enumerate() {
            let sem = sem.clone();
            joinset.spawn(async move {
                let _permit = sem.acquire_owned().await.unwrap();
                SubagentRunResult {
                    task_index: idx,
                    profile_id: task.profile_id,
                    final_text: format!("(subagent stub) {}", task.instruction),
                    success: true,
                }
            });
        }
        let mut out = vec![];
        while let Some(res) = joinset.join_next().await {
            if let Ok(r) = res { out.push(r); }
        }
        out.sort_by_key(|r| r.task_index);
        Ok(out)
    }
}

#[derive(Debug, Clone)]
pub struct SubagentTaskSpec {
    pub profile_id: String,
    pub instruction: String,
}

#[derive(Debug, Clone)]
pub struct SubagentRunResult {
    pub task_index: usize,
    pub profile_id: String,
    pub final_text: String,
    pub success: bool,
}
