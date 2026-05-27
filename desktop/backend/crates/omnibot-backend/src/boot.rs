use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::path::PathBuf;
use std::sync::Arc;

use anyhow::Context;
use axum::Router;
use axum::routing::{any, get};
use dashmap::DashMap;
use omnibot_common::log::init_logging;
use omnibot_db::DbHandle;
use omnibot_llm::{HttpAgentLlmClient, ModelProviderConfigStore, SceneModelRegistry};
use omnibot_mcp::{RemoteMcpClient, RemoteMcpConfigStore};
use omnibot_storage::{AppPaths, KvStore, WorkspaceStore};
use omnibot_tools::handlers::{
    BrowserSessionManager, BrowserToolHandler, ContextToolHandler, FileToolHandler,
    ImageGenerationToolHandler, McpToolHandler, MemoryToolHandler, ScheduleToolHandler,
    SkillsToolHandler, SubagentToolHandler, TerminalToolHandler,
};
use omnibot_tools::{ToolHandler, ToolRouter};
use tokio::net::TcpListener;
use tower_http::trace::TraceLayer;

use crate::api;
use crate::state::AppState;

/// Configuration provided by the launcher (Swift on macOS / C++ on Windows).
#[derive(Debug, Clone)]
pub struct BackendConfig {
    pub data_dir: Option<PathBuf>,
    pub bind: SocketAddr,
}

impl BackendConfig {
    pub fn default_loopback() -> Self {
        Self {
            data_dir: None,
            bind: SocketAddr::new(IpAddr::V4(Ipv4Addr::new(127, 0, 0, 1)), 0),
        }
    }
}

pub async fn run(config: BackendConfig) -> anyhow::Result<()> {
    let paths = AppPaths::resolve(config.data_dir.as_deref()).context("resolve paths")?;
    let _log_guard = init_logging(Some(&paths.log_dir));
    tracing::info!(?paths, "starting omnibot-backend");

    let kv = KvStore::open(paths.kv_dir()).context("open kv")?;
    let workspaces = WorkspaceStore::new(paths.workspaces_dir());
    // Ensure default workspace skeleton exists.
    let _ = workspaces.default();

    let db = DbHandle::open(&paths.db_path())
        .await
        .context("open sqlite")?;
    let llm_client = Arc::new(HttpAgentLlmClient::new());
    let model_providers = ModelProviderConfigStore::new(kv.clone());
    let scene_registry = SceneModelRegistry::new(kv.clone());
    let mcp_client = Arc::new(RemoteMcpClient::new());
    let mcp_config = RemoteMcpConfigStore::new(kv.clone());
    let browser_sessions = BrowserSessionManager::new();

    let handlers: Vec<Arc<dyn ToolHandler>> = vec![
        Arc::new(FileToolHandler::new()),
        Arc::new(TerminalToolHandler::new()),
        Arc::new(BrowserToolHandler::new(browser_sessions.clone())),
        Arc::new(MemoryToolHandler::new()),
        Arc::new(SkillsToolHandler::new()),
        Arc::new(ScheduleToolHandler::new()),
        Arc::new(ContextToolHandler::new()),
        Arc::new(ImageGenerationToolHandler::new()),
        Arc::new(McpToolHandler::new(mcp_client.clone(), mcp_config.clone())),
        Arc::new(SubagentToolHandler::new()),
    ];
    let tool_router = Arc::new(ToolRouter::new(handlers));

    let state = AppState {
        paths,
        kv,
        workspaces,
        db,
        llm_client,
        model_providers,
        scene_registry,
        mcp_client,
        mcp_config,
        tool_router,
        browser_sessions,
        active_tasks: Arc::new(DashMap::new()),
    };

    let listener = TcpListener::bind(config.bind).await.context("bind tcp")?;
    let actual_port = listener.local_addr()?.port();
    // First stdout line is read by the supervisor (Swift / C++).
    println!("OMNIBOT_BACKEND_PORT={actual_port}");
    use std::io::Write;
    let _ = std::io::stdout().flush();
    // Also persist the port to a file so the host process can pick it up without
    // having to read our stdout pipe (sandbox pipe reads have been flaky on macOS).
    let port_file = state.paths.data_dir.join(".backend.port");
    let _ = std::fs::write(&port_file, format!("{actual_port}\n"));
    tracing::info!(port = actual_port, port_file = %port_file.display(), "listening");

    let app = Router::new()
        .route("/health", get(api::health))
        .route("/channel", any(api::ws::ws_endpoint))
        .with_state(state.clone())
        .layer(TraceLayer::new_for_http());

    axum::serve(listener, app).await.context("serve")?;
    Ok(())
}
