use std::sync::Arc;

use dashmap::DashMap;
use omnibot_db::DbHandle;
use omnibot_llm::{HttpAgentLlmClient, ModelProviderConfigStore, SceneModelRegistry};
use omnibot_mcp::{RemoteMcpClient, RemoteMcpConfigStore};
use omnibot_storage::{AppPaths, KvStore, WorkspaceStore};
use omnibot_tools::ToolRouter;

/// Shared backend state passed into every channel handler.
#[derive(Clone)]
pub struct AppState {
    pub paths: AppPaths,
    pub kv: KvStore,
    pub workspaces: WorkspaceStore,
    pub db: DbHandle,
    pub llm_client: Arc<HttpAgentLlmClient>,
    pub model_providers: ModelProviderConfigStore,
    pub scene_registry: SceneModelRegistry,
    pub mcp_client: Arc<RemoteMcpClient>,
    pub mcp_config: RemoteMcpConfigStore,
    pub tool_router: Arc<ToolRouter>,
    /// Active agent tasks keyed by taskId, holds a cancellation flag.
    pub active_tasks: Arc<DashMap<String, ActiveTaskHandle>>,
}

#[derive(Clone)]
pub struct ActiveTaskHandle {
    pub cancel: Arc<tokio::sync::Notify>,
    pub conversation_id: Option<i64>,
    pub started_at_ms: i64,
}
