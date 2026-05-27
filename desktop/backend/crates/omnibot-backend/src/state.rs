use std::sync::Arc;
use std::sync::atomic::AtomicBool;

use dashmap::DashMap;
use omnibot_db::DbHandle;
use omnibot_llm::{HttpAgentLlmClient, ModelProviderConfigStore, SceneModelRegistry};
use omnibot_mcp::{RemoteMcpClient, RemoteMcpConfigStore};
use omnibot_storage::{AppPaths, KvStore, WorkspaceStore};
use omnibot_tools::{BrowserSessionManager, ToolRouter};

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
    pub browser_sessions: BrowserSessionManager,
    /// Active agent tasks keyed by taskId, holds a cancellation flag.
    pub active_tasks: Arc<DashMap<String, ActiveTaskHandle>>,
}

#[derive(Clone)]
pub struct ActiveTaskHandle {
    pub cancel: Arc<tokio::sync::Notify>,
    pub cancelled: Arc<AtomicBool>,
    pub conversation_id: Option<i64>,
    pub started_at_ms: i64,
}
