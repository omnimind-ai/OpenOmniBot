CREATE TABLE IF NOT EXISTS conversations (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    title TEXT NOT NULL,
    mode TEXT NOT NULL DEFAULT 'normal',
    is_archived INTEGER NOT NULL DEFAULT 0,
    is_pinned INTEGER NOT NULL DEFAULT 0,
    parent_conversation_id INTEGER,
    parent_conversation_mode TEXT,
    scheduled_task_id TEXT,
    summary TEXT,
    context_summary TEXT,
    context_summary_cutoff_entry_db_id INTEGER,
    context_summary_updated_at INTEGER NOT NULL DEFAULT 0,
    status INTEGER NOT NULL DEFAULT 0,
    last_message TEXT,
    message_count INTEGER NOT NULL DEFAULT 0,
    latest_prompt_tokens INTEGER NOT NULL DEFAULT 0,
    prompt_token_threshold INTEGER NOT NULL DEFAULT 128000,
    latest_prompt_tokens_updated_at INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_conversations_mode_updated ON conversations(mode, updated_at);

CREATE TABLE IF NOT EXISTS agent_conversation_entries (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    conversation_id INTEGER NOT NULL,
    conversation_mode TEXT NOT NULL,
    entry_id TEXT NOT NULL,
    entry_type TEXT NOT NULL,
    status TEXT NOT NULL,
    summary TEXT NOT NULL,
    payload_json TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_agent_entries_unique
    ON agent_conversation_entries(conversation_id, conversation_mode, entry_id);
CREATE INDEX IF NOT EXISTS idx_agent_entries_updated
    ON agent_conversation_entries(conversation_id, conversation_mode, updated_at);

CREATE TABLE IF NOT EXISTS token_usage_records (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    conversation_id INTEGER NOT NULL,
    is_local INTEGER NOT NULL,
    model TEXT NOT NULL DEFAULT '',
    prompt_tokens INTEGER NOT NULL DEFAULT 0,
    completion_tokens INTEGER NOT NULL DEFAULT 0,
    reasoning_tokens INTEGER NOT NULL DEFAULT 0,
    text_tokens INTEGER NOT NULL DEFAULT 0,
    cached_tokens INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_token_usage_created ON token_usage_records(created_at);
CREATE INDEX IF NOT EXISTS idx_token_usage_conv ON token_usage_records(conversation_id);

CREATE TABLE IF NOT EXISTS codex_thread_binding (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    conversation_id INTEGER NOT NULL,
    thread_id TEXT NOT NULL,
    created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS ai_request_logs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    request_id TEXT NOT NULL,
    conversation_id INTEGER,
    scene TEXT NOT NULL DEFAULT '',
    model TEXT NOT NULL DEFAULT '',
    request_payload TEXT NOT NULL,
    response_payload TEXT,
    error TEXT,
    duration_ms INTEGER NOT NULL DEFAULT 0,
    started_at INTEGER NOT NULL,
    finished_at INTEGER
);
CREATE INDEX IF NOT EXISTS idx_ai_req_started ON ai_request_logs(started_at);
