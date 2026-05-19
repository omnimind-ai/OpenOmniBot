---
name: omnibot-app-control
description: 软件设置控制、应用控制、本地模型服务控制、MCP 服务、聊天历史搜索、会话管理、workspace 记忆、诊断。Use when the user asks to change Omnibot settings, control local services, search past chats, inspect local app state, archive/rename conversations, or troubleshoot app behavior.
---

# Omnibot App Control

Use this skill for direct, local control of the Omnibot Android app. It merges chat history search with broader control over preferences, Room data, workspace files, service state, permissions, diagnostics, and local model configuration.

When this skill is loaded, the agent runtime exposes the `app_control` native bridge tool for this turn. Prefer `app_control` for live settings, MMKV-backed values, MCP server state, and local model service start/stop; use the helper script for SQLite, workspace files, diagnostics, and offline app-private file work.

## Safety Rules

- Confirm before deleting data, force-stopping apps, changing permissions/appops, or exposing tokens/API keys.
- Use `terminal_execute` for app-private files and SQLite. It runs in Alpine with app data bind-mounted at `/data/data/<package>`.
- App-private files are physically writable from Alpine, but do not patch MMKV bytes by hand. For MMKV-backed settings, use an MMKV-aware native/app bridge or UI/native API so encoding, checksums, locks, and in-process caches stay consistent.
- Back up before writes. The bundled script creates backups for preference, database, and memory edits.
- Helper output redacts secret-looking keys by default. Use `--no-redact` only after the user explicitly asks to view sensitive values.

## First Step

For live app settings and services, start by calling `app_control` with:

```json
{"action":"setting.list"}
```

Run the helper once when you need direct app-private paths, SQLite, or workspace files:

```bash
python3 <scriptsDir>/omnibot_control.py paths
```

If the package cannot be detected, pass `--package cn.com.omnimind.bot` or `--package cn.com.omnimind.bot.debug`.

## Common Controls

Flutter SharedPreferences live at:

```text
/data/data/<package>/shared_prefs/FlutterSharedPreferences.xml
```

The helper automatically adds the `flutter.` prefix unless `--raw-key` is used.

```bash
# Theme: system | light | dark
python3 <scriptsDir>/omnibot_control.py prefs-set theme_option string dark

# Language: system | zhHans | en
python3 <scriptsDir>/omnibot_control.py prefs-set language_option string en

# Experience toggles
python3 <scriptsDir>/omnibot_control.py prefs-set auto_back_to_chat_after_task bool false
python3 <scriptsDir>/omnibot_control.py prefs-set use_independent_chat_send_button bool true
python3 <scriptsDir>/omnibot_control.py prefs-set habitual_hand string left

# Hide greeting on the empty home screen
python3 <scriptsDir>/omnibot_control.py prefs-json-merge home_greeting_settings '{"greetingEnabled":false}'
```

Most preference changes are read on the next screen rebuild or app restart. If a live native side effect is required, use the matching tool/channel path instead of only editing XML.

## Conversation Metadata

Room database:

```text
/data/data/<package>/databases/omnibot_cache_databaseoss
```

```bash
python3 <scriptsDir>/omnibot_control.py conversation-list --limit 20
python3 <scriptsDir>/omnibot_control.py conversation-update 12 --title "New title"
python3 <scriptsDir>/omnibot_control.py conversation-update 12 --archived true
python3 <scriptsDir>/omnibot_control.py conversation-update 12 --threshold 128000
```

## Chat History Search

Search local chat history from the same Room database:

```bash
python3 <scriptsDir>/omnibot_control.py chat-search "关键词" --limit 20 --text
python3 <scriptsDir>/omnibot_control.py chat-search "keyword" --conversation-id 12
python3 <scriptsDir>/omnibot_control.py chat-conversation 12 --text --limit 200
python3 <scriptsDir>/omnibot_control.py chat-recent --limit 20
python3 <scriptsDir>/omnibot_control.py chat-stats
```

Raw SQL uses a read-only connection unless `--write` is supplied:

```bash
python3 <scriptsDir>/omnibot_control.py db-query "SELECT id,title FROM conversations LIMIT 5"
```

## Workspace Memory And Prompts

Prefer `memory_write_daily`, `memory_upsert_longterm`, `memory_search`, and `memory_rollup_day` when the tools are available. For direct file control:

```bash
python3 <scriptsDir>/omnibot_control.py memory-read long
python3 <scriptsDir>/omnibot_control.py memory-append-long "User prefers concise Chinese answers."
python3 <scriptsDir>/omnibot_control.py memory-append-daily "Finished configuring Omnibot app control skill."
python3 <scriptsDir>/omnibot_control.py workspace-read soul
python3 <scriptsDir>/omnibot_control.py workspace-write chat --stdin
```

Workspace paths:

- shell root: `/workspace`
- Android root: `/data/data/<package>/workspace`
- skills: `/workspace/.omnibot/skills`
- memory: `/workspace/.omnibot/memory`
- agent prompts: `/workspace/.omnibot/agent/SOUL.md` and `CHAT.md`

## Live Services

This skill can inspect and control persisted service state, scheduled automation, reminders, calendar operations, playback, skill registry state, Android permissions/appops, diagnostics, and app launch/stop behavior when the corresponding app capability is available.

For local model service state, this skill can inspect saved OmniInfer configuration and guide changes to backend, active model, port, auto-preheat, and model downloads. Immediate start/stop of the in-process inference server requires calling the app's native local-model capability; changing files alone may update persisted preferences but does not load or unload a model.

```bash
python3 <scriptsDir>/omnibot_control.py local-model-probe
python3 <scriptsDir>/omnibot_control.py local-model-probe --port 9099 --port 8080
```

Examples for diagnostics and app control:

```json
{"action":"package_control.launch_activity","arguments":{"packageName":"cn.com.omnimind.bot"}}
{"action":"diagnostics.logcat_tail","arguments":{"buffer":"main","lines":"200"}}
{"action":"package_control.force_stop","arguments":{"packageName":"cn.com.omnimind.bot","confirmed":"true"}}
```

Only pass `confirmed=true` after explicit user approval.

## More Detail

Read `references/control-surfaces.md` when you need exact keys, storage locations, service boundaries, or MMKV-backed settings.
