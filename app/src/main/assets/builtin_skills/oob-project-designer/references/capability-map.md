# OOB Project Capability Map

What a Workbench Project can and cannot do. Use this when designing APIs or answering user questions about what's possible.

---

## Full Tool Inventory

All tools available inside `run: {use: "agent"}` prompts:

### Vision & Media

| Tool | What it does | Notes |
|---|---|---|
| `vlm_task(imagePath, question)` | VLM analyzes any image | Returns structured text/JSON per question; pass a specific JSON schema in the question |
| `image_picker(source)` | User picks from `camera` or `gallery` | Returns `{path: "/workspace/..."}` — use path in vlm_task |

### Device & System

| Tool | What it does | Notes |
|---|---|---|
| `notification_send(title, body, data?)` | Local push notification | User sees it in notification tray; tap can reopen the app |
| `context_time_now()` | Current device time | Returns ISO string and formatted components |
| `context_apps_query(query)` | Query installed apps context | For routing decisions, not for reading app data |
| `music_playback_control(action)` | Control system music player | Play, pause, next, previous |

### Calendar & Scheduler

| Tool | What it does | Notes |
|---|---|---|
| `calendar_list()` | List available calendars | Returns calendar IDs and names |
| `calendar_event_create(...)` | Create a calendar event | Appears in system calendar; user must grant permission once |
| `calendar_event_list(startDate, endDate)` | Read calendar events in range | Can read events from any calendar the user has access to |
| `calendar_event_update(eventId, ...)` | Update an existing event | |
| `calendar_event_delete(eventId)` | Delete a calendar event | |
| `schedule_task_create(title, recurrence, time, prompt)` | Recurring agent task | Runs at a scheduled time, executes a prompt as an agent |
| `schedule_task_list/update/delete` | Manage scheduled tasks | |
| `alarm_reminder_create(title, time, ...)` | Device alarm/reminder | Fires even when app is backgrounded |
| `alarm_reminder_list/delete` | Manage alarms | |

### File System (/workspace)

| Tool | What it does | Notes |
|---|---|---|
| `file_read(path, lineStart?, lineCount?)` | Read a file | Supports partial reads |
| `file_write(path, content)` | Write/overwrite a file | Creates parent dirs automatically |
| `file_edit(path, oldText, newText)` | Surgical edit | Finds and replaces first occurrence |
| `file_list(path)` | List directory contents | |
| `file_search(path, query)` | Search files by content | |
| `file_stat(path)` | File metadata | size, modified, isDir |
| `file_move(source, dest)` | Move or rename | |

### Terminal / Compute (Alpine sandbox)

| Tool | What it does | Notes |
|---|---|---|
| `terminal_execute(command)` | Single shell command | Python, pip, uv, common Linux tools available |
| `terminal_session_start()` | Start interactive session | Returns sessionId |
| `terminal_session_exec(sessionId, command)` | Run in session | State persists between calls (venv, cwd) |
| `terminal_session_read(sessionId)` | Read session output | |
| `terminal_session_stop(sessionId)` | End session | |

Preinstalled in Alpine: Python 3, pip, uv, git, curl, wget, jq, awk, sed, grep. Network access available.

### Web & Browser

| Tool | What it does | Notes |
|---|---|---|
| `web_search(query, limit?)` | Lightweight search | Returns titles, URLs, snippets |
| `browser_use(url, task)` | Full browser automation | Can click, fill forms, extract data; slower than web_search |

### Android UI Automation (Accessibility)

| Tool | What it does | Notes |
|---|---|---|
| `android_privileged_action(action, ...)` | Tap, swipe, input text, screenshot, read UI tree | Requires accessibility permission (user enables once) |
| `android_privileged_session_start/exec/read/stop` | Interactive accessibility session | Maintains state across steps in complex automation flows |

Use cases: Read WeChat messages, parse Alipay bills, interact with any installed app's UI.

### Memory

| Tool | What it does | Notes |
|---|---|---|
| `memory_search(query)` | Search workspace long-term memory | Returns relevant memory entries |
| `memory_write_daily(content)` | Write daily memory | Auto-dated |
| `memory_upsert_longterm(key, content)` | Write/update a memory entry | Persists across sessions |
| `memory_rollup_day(date?)` | Compress daily memories | Runs automatically but can be triggered |

### Multi-Agent

| Tool | What it does | Notes |
|---|---|---|
| `subagent_dispatch(prompt, context?)` | Spawn a sub-agent | Runs independently; use for long-running parallel work |

---

## What Projects CAN Do

| Capability | How |
|---|---|
| Persist structured data across sessions | `project.items` + `native.collection.*` |
| Analyze any photo (food, receipt, document, screen) | `image_picker` → `vlm_task` in agent task |
| Read any other app's screen | `android_privileged_action` (screenshot + OCR or UI tree) in agent task |
| Import data from WeChat/Alipay/any app | `android_privileged_session_*` for multi-step UI automation |
| Send push notifications | `notification_send` in agent task |
| Create/read system calendar events | `calendar_event_create/list` in agent task |
| Run Python scripts locally | `terminal_execute` or `run: {use: "script"}` |
| Fetch live web data | `web_search` or `browser_use` in agent task |
| Schedule recurring automated workflows | `schedule_task_create` |
| Export data as CSV, JSON, or HTML file | Python script writing to `{spacePath}/exports/` |
| Access the file system | File tools within `/workspace` |
| Store user preferences across sessions | `memory_upsert_longterm` in agent task |
| Display charts and visualizations | Chart.js in HTML (CDN) |
| Trigger multi-step complex workflows from a button | Agent task with `onProjectUpdated` callback |

---

## What Projects CANNOT Do

| Limitation | Reason | Workaround |
|---|---|---|
| Call arbitrary Android APIs from HTML | WebView bridge is restricted to `window.oob.*` only | Wrap capability in a registered Project Tool with `run: {use: "agent"}` |
| Establish persistent WebSocket connections from HTML | WebView networking is not exposed | Use `onProjectUpdated` for async updates from agent tasks |
| Access hardware sensors from HTML (GPS, accelerometer, gyroscope, microphone) | Not bridged | File a request; for location, use `context_apps_query` or calendar heuristics |
| Write outside `/workspace` | Sandbox restriction | Not circumventable — all Project data lives in /workspace |
| Read other Projects' `items.json` directly | Data isolation | Use `workbench_project_get` in an agent task if cross-project read is needed |
| Push notifications from cloud (FCM/APNs) | No server-side integration | Use `notification_send` for local-only notifications |
| Import npm / PyPI packages in HTML | Only CDN URLs work | Use CDN for JS; for Python, only stdlib + pre-installed packages |
| Background execution triggered from HTML `setTimeout` | JS timer dies when WebView is hidden | Use `schedule_task_create` for persistence across sessions |
| Real-time multi-device sync | Local-only storage | Not supported in current architecture |
| Play custom audio from HTML | Audio bridge not exposed | Use `music_playback_control` for system player only |
| Inter-app data sharing (ContentProvider, Intent) | Android restrictions | Use `android_privileged_action` for UI automation instead |

---

## Boundary Cases (Gray Zone)

| Scenario | What to do |
|---|---|
| User wants a timer that fires when app is closed | Use `alarm_reminder_create` or `schedule_task_create` — not `setTimeout` |
| User wants real-time stock/weather updates | Use `schedule_task_create` (e.g., every 30 min) to fetch and write to project data |
| User wants to export PDF | Write HTML/CSS to file in Alpine, then use `terminal_execute` to convert (or provide as HTML export for user to save) |
| User wants to sync with a cloud service | Use `browser_use` or `web_search` + `terminal_execute` for HTTP; no native OAuth flow |
| User wants to read SMS/call logs | Requires `android_privileged_action` + accessibility — feasible but fragile |
| User wants to control smart home devices | Use `browser_use` + device web interface or `terminal_execute` with HTTP calls to local API |
| User wants to share a project with another user | Use `workbench_project_export` → share the zip file; no native share sheet from HTML |

---

## Performance Characteristics

| Operation | Speed | Cost |
|---|---|---|
| `native.collection.create/archive/update` | ~50ms | Zero (no LLM) |
| `native.collection.list` via `getProject()` | ~20ms | Zero |
| Python script execution | 200ms–2s | Zero |
| VLM image analysis | 2–8s | Medium (VLM token cost) |
| Agent task (multi-step) | 10–60s | High (LLM + tools) |
| `web_search` | 1–3s | Low |
| `browser_use` | 5–30s | Low–Medium |
| `android_privileged_action` | 1–5s per action | Low |

Design the primary flow to use `native.collection.*` for latency-sensitive interactions. Defer VLM and agent tasks to secondary/background actions.
