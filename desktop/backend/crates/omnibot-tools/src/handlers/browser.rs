use std::path::PathBuf;
use std::process::Stdio;
use std::sync::Arc;

use async_trait::async_trait;
use omnibot_common::{AppError, AppResult, now_unix_ms};
use parking_lot::Mutex;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::process::{Child, ChildStdin, ChildStdout};

use crate::types::{
    AgentExecutionEnvironment, ToolConcurrencyHint, ToolEventSink, ToolExecutionResult, ToolHandler,
};

#[derive(Clone)]
pub struct BrowserSessionManager {
    state: Arc<Mutex<BrowserState>>,
    worker: Arc<tokio::sync::Mutex<Option<BrowserWorker>>>,
    http: reqwest::Client,
}

#[derive(Debug, Clone)]
struct BrowserState {
    workspace_id: String,
    tabs: Vec<BrowserTab>,
    active_tab_id: Option<i64>,
    next_tab_id: i64,
    history: Vec<BrowserHistoryEntry>,
    is_desktop_mode: bool,
}

#[derive(Debug, Clone)]
struct BrowserTab {
    id: i64,
    url: String,
    title: String,
    can_go_back: bool,
    can_go_forward: bool,
    is_loading: bool,
    has_ssl_error: bool,
}

#[derive(Debug, Clone)]
struct BrowserHistoryEntry {
    url: String,
    title: String,
    visited_at: i64,
}

impl Default for BrowserState {
    fn default() -> Self {
        let tab = BrowserTab {
            id: 1,
            url: String::new(),
            title: "New tab".into(),
            can_go_back: false,
            can_go_forward: false,
            is_loading: false,
            has_ssl_error: false,
        };
        Self {
            workspace_id: "default".into(),
            tabs: vec![tab],
            active_tab_id: Some(1),
            next_tab_id: 2,
            history: vec![],
            is_desktop_mode: true,
        }
    }
}

impl BrowserSessionManager {
    pub fn new() -> Self {
        Self {
            state: Arc::new(Mutex::new(BrowserState::default())),
            worker: Arc::new(tokio::sync::Mutex::new(None)),
            http: reqwest::Client::builder()
                .user_agent("OmnibotApp-Desktop-Browser/0.1")
                .redirect(reqwest::redirect::Policy::limited(10))
                .build()
                .expect("browser reqwest"),
        }
    }

    pub fn snapshot(&self) -> serde_json::Value {
        self.state.lock().snapshot()
    }

    pub async fn handle_channel_method(
        &self,
        method: &str,
        args: serde_json::Value,
    ) -> AppResult<serde_json::Value> {
        match method {
            "getSnapshot" => Ok(self.snapshot()),
            "navigate" | "openHistoryEntry" => {
                let url = args.get("url").and_then(|v| v.as_str()).unwrap_or("");
                self.navigate(url, args.get("tabId").and_then(|v| v.as_i64()))
                    .await?;
                Ok(self.snapshot())
            }
            "reload" => {
                let url = self.active_tab().map(|t| t.url).unwrap_or_default();
                if !url.is_empty() {
                    self.navigate(&url, args.get("tabId").and_then(|v| v.as_i64()))
                        .await?;
                }
                Ok(self.snapshot())
            }
            "goBack" => {
                let data = self
                    .worker_call(
                        "goBack",
                        serde_json::json!({"tabId": active_tab_id_arg(&args)}),
                    )
                    .await;
                if let Ok(value) = data {
                    self.apply_browser_payload(&value);
                }
                Ok(self.snapshot())
            }
            "goForward" => {
                let data = self
                    .worker_call(
                        "goForward",
                        serde_json::json!({"tabId": active_tab_id_arg(&args)}),
                    )
                    .await;
                if let Ok(value) = data {
                    self.apply_browser_payload(&value);
                }
                Ok(self.snapshot())
            }
            "newTab" => {
                let url = args.get("url").and_then(|v| v.as_str()).unwrap_or("");
                let tab_id = self.create_tab(url);
                if !url.trim().is_empty() {
                    self.navigate(url, Some(tab_id)).await?;
                }
                Ok(self.snapshot())
            }
            "selectTab" => {
                if let Some(tab_id) = args.get("tabId").and_then(|v| v.as_i64()) {
                    self.select_tab(tab_id);
                }
                Ok(self.snapshot())
            }
            "closeTab" => {
                if let Some(tab_id) = args.get("tabId").and_then(|v| v.as_i64()) {
                    self.close_tab(tab_id);
                    let _ = self
                        .worker_call("closeTab", serde_json::json!({"tabId": tab_id}))
                        .await;
                }
                Ok(self.snapshot())
            }
            "toggleDesktopMode" => {
                let mut state = self.state.lock();
                state.is_desktop_mode = !state.is_desktop_mode;
                Ok(state.snapshot())
            }
            "toggleBookmark"
            | "removeBookmark"
            | "clearHistory"
            | "clearCurrentSiteSession"
            | "stopLoading"
            | "pauseDownload"
            | "resumeDownload"
            | "cancelDownload"
            | "retryDownload"
            | "deleteDownload"
            | "openDownloadedFile"
            | "openDownloadLocation"
            | "installUserscriptFromUrl"
            | "importUserscriptSource"
            | "confirmUserscriptInstall"
            | "cancelUserscriptInstall"
            | "setUserscriptEnabled"
            | "deleteUserscript"
            | "checkUserscriptUpdate"
            | "invokeUserscriptMenuCommand"
            | "confirmExternalOpen"
            | "cancelExternalOpen"
            | "resolveDialog"
            | "grantPermission"
            | "denyPermission" => Ok(self.snapshot()),
            other => Err(AppError::method_not_implemented(format!(
                "AgentBrowserSession.{other}"
            ))),
        }
    }

    pub async fn handle_tool_action(
        &self,
        args: serde_json::Value,
    ) -> AppResult<ToolExecutionResult> {
        let action = args
            .get("action")
            .and_then(|v| v.as_str())
            .unwrap_or("get_page_info");
        match action {
            "navigate" => {
                let url = args
                    .get("url")
                    .and_then(|v| v.as_str())
                    .ok_or_else(|| AppError::invalid("browser_use.url"))?;
                let payload = self
                    .navigate(url, args.get("tabId").and_then(|v| v.as_i64()))
                    .await?;
                Ok(browser_ok(format!("navigated to {url}"), payload))
            }
            "get_text" | "get_readable" => {
                let payload = match self.worker_call("getText", serde_json::json!({})).await {
                    Ok(v) => v,
                    Err(_) => self.fetch_active_text().await?,
                };
                Ok(browser_ok("read browser text", payload))
            }
            "screenshot" | "read_image" => {
                match self.worker_call("screenshot", serde_json::json!({})).await {
                    Ok(payload) => {
                        let mut result = browser_ok("captured browser screenshot", payload.clone());
                        if let Some(data_url) = payload.get("dataUrl").and_then(|v| v.as_str()) {
                            result.artifacts.push(serde_json::json!({
                                "kind": "image",
                                "dataUrl": data_url,
                                "source": "browser_use",
                            }));
                        }
                        Ok(result)
                    }
                    Err(e) => Ok(ToolExecutionResult::err(format!(
                        "browser screenshot unavailable: {e}"
                    ))),
                }
            }
            "click" | "type" | "hover" | "scroll" | "find_elements" | "get_page_info"
            | "get_backbone" | "execute_js" | "press_key" | "wait_for_selector" | "go_back"
            | "go_forward" | "new_tab" | "select_tab" | "close_tab" | "get_cookies"
            | "set_user_agent" => {
                let worker_method = worker_method_for(action);
                match self.worker_call(worker_method, args.clone()).await {
                    Ok(payload) => {
                        self.apply_browser_payload(&payload);
                        Ok(browser_ok(
                            format!("browser action {action} completed"),
                            payload,
                        ))
                    }
                    Err(_e) if action == "get_page_info" => {
                        Ok(browser_ok("browser page info", self.snapshot()))
                    }
                    Err(e) => Ok(ToolExecutionResult::err(format!(
                        "browser action {action} failed: {e}"
                    ))),
                }
            }
            "fetch" => {
                let active_url = self.active_tab().map(|t| t.url).unwrap_or_default();
                let url = args
                    .get("url")
                    .and_then(|v| v.as_str())
                    .unwrap_or(active_url.as_str());
                let payload = self.fetch_text(url).await?;
                Ok(browser_ok(format!("fetched {url}"), payload))
            }
            other => Ok(ToolExecutionResult::err(format!(
                "unsupported browser_use action: {other}"
            ))),
        }
    }

    async fn navigate(&self, raw_url: &str, tab_id: Option<i64>) -> AppResult<serde_json::Value> {
        let url = normalize_url(raw_url);
        let tab_id = tab_id.unwrap_or_else(|| self.ensure_active_tab());
        self.select_tab(tab_id);
        let worker_payload = self
            .worker_call("navigate", serde_json::json!({"url": url, "tabId": tab_id}))
            .await;
        let payload = match worker_payload {
            Ok(v) => v,
            Err(_) => self.fetch_text(&url).await.unwrap_or_else(|_| {
                serde_json::json!({
                    "url": url,
                    "currentUrl": url,
                    "title": url,
                    "text": "",
                })
            }),
        };
        self.update_tab_from_payload(tab_id, &url, &payload);
        Ok(merge_snapshot(payload, self.snapshot()))
    }

    async fn fetch_active_text(&self) -> AppResult<serde_json::Value> {
        let url = self.active_tab().map(|t| t.url).unwrap_or_default();
        self.fetch_text(&url).await
    }

    async fn fetch_text(&self, url: &str) -> AppResult<serde_json::Value> {
        if url.trim().is_empty() {
            return Ok(serde_json::json!({"text": "", "url": "", "title": ""}));
        }
        let response = self
            .http
            .get(url)
            .send()
            .await
            .map_err(|e| AppError::Tool {
                tool: "browser_use".into(),
                message: e.to_string(),
            })?;
        let final_url = response.url().to_string();
        let text = response.text().await.map_err(|e| AppError::Tool {
            tool: "browser_use".into(),
            message: e.to_string(),
        })?;
        let title = extract_title(&text).unwrap_or_else(|| final_url.clone());
        let body_text = html_to_text(&text);
        Ok(serde_json::json!({
            "url": final_url,
            "currentUrl": final_url,
            "finalUrl": final_url,
            "title": title,
            "pageTitle": title,
            "text": body_text,
            "content": body_text,
            "source": "reqwest_fallback",
            "riskChallengeDetected": false,
        }))
    }

    async fn worker_call(
        &self,
        method: &str,
        params: serde_json::Value,
    ) -> AppResult<serde_json::Value> {
        let mut guard = self.worker.lock().await;
        if guard.is_none() {
            *guard = Some(BrowserWorker::spawn().await?);
        }
        let worker = guard.as_mut().expect("worker initialized");
        match worker.call(method, params).await {
            Ok(value) => Ok(value),
            Err(e) => {
                *guard = None;
                Err(e)
            }
        }
    }

    fn ensure_active_tab(&self) -> i64 {
        if let Some(tab_id) = self.state.lock().active_tab_id {
            return tab_id;
        }
        self.create_tab("")
    }

    fn create_tab(&self, url: &str) -> i64 {
        let mut state = self.state.lock();
        let id = state.next_tab_id;
        state.next_tab_id += 1;
        state.active_tab_id = Some(id);
        state.tabs.push(BrowserTab {
            id,
            url: url.to_string(),
            title: if url.is_empty() {
                "New tab".into()
            } else {
                url.to_string()
            },
            can_go_back: false,
            can_go_forward: false,
            is_loading: false,
            has_ssl_error: false,
        });
        id
    }

    fn select_tab(&self, tab_id: i64) {
        let mut state = self.state.lock();
        if state.tabs.iter().any(|tab| tab.id == tab_id) {
            state.active_tab_id = Some(tab_id);
        }
    }

    fn close_tab(&self, tab_id: i64) {
        let mut state = self.state.lock();
        state.tabs.retain(|tab| tab.id != tab_id);
        if state.tabs.is_empty() {
            let tab = BrowserTab {
                id: state.next_tab_id,
                url: String::new(),
                title: "New tab".into(),
                can_go_back: false,
                can_go_forward: false,
                is_loading: false,
                has_ssl_error: false,
            };
            state.next_tab_id += 1;
            state.active_tab_id = Some(tab.id);
            state.tabs.push(tab);
        } else if state.active_tab_id == Some(tab_id) {
            state.active_tab_id = state.tabs.last().map(|tab| tab.id);
        }
    }

    fn active_tab(&self) -> Option<BrowserTab> {
        let state = self.state.lock();
        let id = state.active_tab_id?;
        state.tabs.iter().find(|tab| tab.id == id).cloned()
    }

    fn update_tab_from_payload(
        &self,
        tab_id: i64,
        requested_url: &str,
        payload: &serde_json::Value,
    ) {
        let url = payload
            .get("currentUrl")
            .or_else(|| payload.get("finalUrl"))
            .or_else(|| payload.get("url"))
            .and_then(|v| v.as_str())
            .unwrap_or(requested_url)
            .to_string();
        let title = payload
            .get("pageTitle")
            .or_else(|| payload.get("title"))
            .and_then(|v| v.as_str())
            .unwrap_or(&url)
            .to_string();
        let mut state = self.state.lock();
        if let Some(tab) = state.tabs.iter_mut().find(|tab| tab.id == tab_id) {
            tab.url = url.clone();
            tab.title = title.clone();
            tab.can_go_back = payload
                .get("canGoBack")
                .and_then(|v| v.as_bool())
                .unwrap_or(tab.can_go_back);
            tab.can_go_forward = payload
                .get("canGoForward")
                .and_then(|v| v.as_bool())
                .unwrap_or(tab.can_go_forward);
            tab.is_loading = false;
            tab.has_ssl_error = payload
                .get("hasSslError")
                .and_then(|v| v.as_bool())
                .unwrap_or(false);
        }
        state.history.push(BrowserHistoryEntry {
            url,
            title,
            visited_at: now_unix_ms(),
        });
        if state.history.len() > 200 {
            state.history.remove(0);
        }
    }

    fn apply_browser_payload(&self, payload: &serde_json::Value) {
        let tab_id = payload
            .get("activeTabId")
            .or_else(|| payload.get("tabId"))
            .and_then(|v| v.as_i64())
            .unwrap_or_else(|| self.ensure_active_tab());
        let url = payload
            .get("currentUrl")
            .or_else(|| payload.get("url"))
            .and_then(|v| v.as_str())
            .unwrap_or("");
        if !url.is_empty() {
            self.update_tab_from_payload(tab_id, url, payload);
        }
    }
}

impl BrowserState {
    fn active_tab(&self) -> Option<&BrowserTab> {
        let id = self.active_tab_id?;
        self.tabs.iter().find(|tab| tab.id == id)
    }

    fn snapshot(&self) -> serde_json::Value {
        let active = self.active_tab();
        let tabs: Vec<serde_json::Value> = self
            .tabs
            .iter()
            .map(|tab| {
                serde_json::json!({
                    "tabId": tab.id,
                    "url": tab.url,
                    "title": tab.title,
                    "isActive": Some(tab.id) == self.active_tab_id,
                    "isLoading": tab.is_loading,
                    "hasSslError": tab.has_ssl_error,
                    "riskChallengeDetected": false,
                })
            })
            .collect();
        let history: Vec<serde_json::Value> = self
            .history
            .iter()
            .rev()
            .take(50)
            .enumerate()
            .map(|(idx, item)| {
                serde_json::json!({
                    "url": item.url,
                    "title": item.title,
                    "index": idx,
                    "visitedAt": item.visited_at,
                    "isCurrent": Some(item.url.as_str()) == active.map(|tab| tab.url.as_str()),
                })
            })
            .collect();
        serde_json::json!({
            "available": true,
            "workspaceId": self.workspace_id,
            "activeTabId": self.active_tab_id,
            "currentUrl": active.map(|tab| tab.url.clone()).unwrap_or_default(),
            "title": active.map(|tab| tab.title.clone()).unwrap_or_default(),
            "userAgentProfile": "desktop",
            "isBookmarked": false,
            "canGoBack": active.map(|tab| tab.can_go_back).unwrap_or(false),
            "canGoForward": active.map(|tab| tab.can_go_forward).unwrap_or(false),
            "isLoading": active.map(|tab| tab.is_loading).unwrap_or(false),
            "hasSslError": active.map(|tab| tab.has_ssl_error).unwrap_or(false),
            "isDesktopMode": self.is_desktop_mode,
            "riskChallengeDetected": false,
            "riskChallengeKind": serde_json::Value::Null,
            "recommendedNextAction": serde_json::Value::Null,
            "throttleDelayMs": serde_json::Value::Null,
            "activeDownloadCount": 0,
            "tabs": tabs,
            "bookmarks": [],
            "history": history,
            "sessionHistory": history,
            "downloads": [],
            "downloadSummary": {"activeCount": 0, "failedCount": 0, "overallProgress": serde_json::Value::Null, "latestCompletedFileName": serde_json::Value::Null},
            "externalOpenPrompt": serde_json::Value::Null,
            "pendingDialog": serde_json::Value::Null,
            "permissionPrompt": serde_json::Value::Null,
            "userscriptSummary": {"installedScripts": [], "currentPageMenuCommands": [], "pendingInstall": serde_json::Value::Null},
        })
    }
}

pub struct BrowserToolHandler {
    manager: BrowserSessionManager,
}

impl BrowserToolHandler {
    pub fn new(manager: BrowserSessionManager) -> Self {
        Self { manager }
    }
}

#[async_trait]
impl ToolHandler for BrowserToolHandler {
    fn tool_names(&self) -> &[&'static str] {
        &["browser_use"]
    }

    fn concurrency_hint(&self) -> ToolConcurrencyHint {
        ToolConcurrencyHint::SerialBarrier
    }

    async fn execute(
        &self,
        _id: &str,
        _name: &str,
        args: serde_json::Value,
        _env: &AgentExecutionEnvironment,
        _sink: Arc<dyn ToolEventSink>,
    ) -> AppResult<ToolExecutionResult> {
        self.manager.handle_tool_action(args).await
    }
}

struct BrowserWorker {
    child: Child,
    stdin: ChildStdin,
    stdout: BufReader<ChildStdout>,
    next_id: u64,
}

impl BrowserWorker {
    async fn spawn() -> AppResult<Self> {
        let script = worker_script_path().ok_or_else(|| AppError::Tool {
            tool: "browser_use".into(),
            message: "browser worker script not found".into(),
        })?;
        let mut child = tokio::process::Command::new("node")
            .arg(script)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .spawn()
            .map_err(|e| AppError::Tool {
                tool: "browser_use".into(),
                message: format!("spawn node playwright worker: {e}"),
            })?;
        let stdin = child
            .stdin
            .take()
            .ok_or_else(|| AppError::backend("browser worker stdin unavailable"))?;
        let stdout = child
            .stdout
            .take()
            .ok_or_else(|| AppError::backend("browser worker stdout unavailable"))?;
        Ok(Self {
            child,
            stdin,
            stdout: BufReader::new(stdout),
            next_id: 1,
        })
    }

    async fn call(
        &mut self,
        method: &str,
        params: serde_json::Value,
    ) -> AppResult<serde_json::Value> {
        let id = self.next_id;
        self.next_id += 1;
        let request = serde_json::json!({"id": id, "method": method, "params": params});
        let mut line = serde_json::to_vec(&request)?;
        line.push(b'\n');
        self.stdin
            .write_all(&line)
            .await
            .map_err(|e| AppError::Tool {
                tool: "browser_use".into(),
                message: format!("worker write: {e}"),
            })?;
        self.stdin.flush().await.ok();
        let mut buf = String::new();
        loop {
            buf.clear();
            let read = self
                .stdout
                .read_line(&mut buf)
                .await
                .map_err(|e| AppError::Tool {
                    tool: "browser_use".into(),
                    message: format!("worker read: {e}"),
                })?;
            if read == 0 {
                let _ = self.child.start_kill();
                return Err(AppError::Tool {
                    tool: "browser_use".into(),
                    message: "browser worker exited".into(),
                });
            }
            let parsed: serde_json::Value = match serde_json::from_str(buf.trim()) {
                Ok(v) => v,
                Err(_) => continue,
            };
            if parsed.get("id").and_then(|v| v.as_u64()) != Some(id) {
                continue;
            }
            if let Some(error) = parsed.get("error").and_then(|v| v.as_str()) {
                return Err(AppError::Tool {
                    tool: "browser_use".into(),
                    message: error.to_string(),
                });
            }
            return Ok(parsed
                .get("result")
                .cloned()
                .unwrap_or(serde_json::Value::Null));
        }
    }
}

fn worker_script_path() -> Option<PathBuf> {
    if let Ok(path) = std::env::var("OMNIBOT_BROWSER_WORKER") {
        let path = PathBuf::from(path);
        if path.exists() {
            return Some(path);
        }
    }
    let manifest = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    for ancestor in manifest.ancestors() {
        for rel in [
            "desktop/browser-worker/index.js",
            "browser-worker/index.js",
            "../browser-worker/index.js",
        ] {
            let candidate = ancestor.join(rel);
            if candidate.exists() {
                return Some(candidate);
            }
        }
    }
    None
}

fn worker_method_for(action: &str) -> &str {
    match action {
        "find_elements" => "findElements",
        "get_page_info" => "getPageInfo",
        "get_backbone" => "getBackbone",
        "execute_js" => "executeJs",
        "press_key" => "pressKey",
        "wait_for_selector" => "waitForSelector",
        "go_back" => "goBack",
        "go_forward" => "goForward",
        "new_tab" => "newTab",
        "select_tab" => "selectTab",
        "close_tab" => "closeTab",
        "get_cookies" => "getCookies",
        "set_user_agent" => "setUserAgent",
        other => other,
    }
}

fn normalize_url(raw: &str) -> String {
    let trimmed = raw.trim();
    if trimmed.starts_with("http://")
        || trimmed.starts_with("https://")
        || trimmed.starts_with("file://")
    {
        trimmed.to_string()
    } else {
        format!("https://{trimmed}")
    }
}

fn browser_ok(summary: impl Into<String>, payload: serde_json::Value) -> ToolExecutionResult {
    ToolExecutionResult {
        success: true,
        summary: summary.into(),
        stdout: payload
            .get("text")
            .or_else(|| payload.get("content"))
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .chars()
            .take(20_000)
            .collect(),
        structured: payload,
        error: None,
        artifacts: vec![],
    }
}

fn active_tab_id_arg(args: &serde_json::Value) -> serde_json::Value {
    args.get("tabId")
        .cloned()
        .unwrap_or(serde_json::Value::Null)
}

fn merge_snapshot(
    mut payload: serde_json::Value,
    snapshot: serde_json::Value,
) -> serde_json::Value {
    if let (Some(payload), Some(snapshot)) = (payload.as_object_mut(), snapshot.as_object()) {
        for (k, v) in snapshot {
            payload.entry(k.clone()).or_insert_with(|| v.clone());
        }
    }
    payload
}

fn extract_title(html: &str) -> Option<String> {
    let lower = html.to_lowercase();
    let start = lower.find("<title")?;
    let rest = &html[start..];
    let gt = rest.find('>')?;
    let after = &rest[gt + 1..];
    let end = after.to_lowercase().find("</title>")?;
    Some(html_unescape(after[..end].trim()))
}

fn html_to_text(html: &str) -> String {
    let mut out = String::with_capacity(html.len().min(20_000));
    let mut in_tag = false;
    for c in html.chars() {
        match c {
            '<' => {
                in_tag = true;
                out.push(' ');
            }
            '>' => in_tag = false,
            _ if !in_tag => out.push(c),
            _ => {}
        }
    }
    html_unescape(&out)
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
}

fn html_unescape(s: &str) -> String {
    s.replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
}
