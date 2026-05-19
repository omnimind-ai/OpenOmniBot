# Omnibot Control Surfaces

## App Data Roots

- Production package: `cn.com.omnimind.bot`
- Debug package: `cn.com.omnimind.bot.debug`
- App private data: `/data/data/<package>` or `/data/user/0/<package>`
- Terminal workspace alias: `/workspace`
- Android workspace root: `/data/data/<package>/workspace`
- Omnibot internal workspace: `/workspace/.omnibot`
- SQLite database: `/data/data/<package>/databases/omnibot_cache_databaseoss`
- Flutter prefs: `/data/data/<package>/shared_prefs/FlutterSharedPreferences.xml`
- Shared-open prefs: `/data/data/<package>/shared_prefs/shared_open_preferences.xml`
- MMKV root: `/data/data/<package>/files/mmkv`
- App control bridge: `content://<package>.appcontrol`

`terminal_execute` can access app-private paths through the app-owned Alpine environment. `file_read` usually cannot read `/data/data/<package>` directly.

## Preference Keys

Flutter `shared_preferences` keys are stored with a `flutter.` prefix in `FlutterSharedPreferences.xml`.

| Logical key | XML key | Type | Values |
| --- | --- | --- | --- |
| `theme_option` | `flutter.theme_option` | string | `system`, `light`, `dark` |
| `language_option` | `flutter.language_option` | string | `system`, `zhHans`, `en` |
| `auto_back_to_chat_after_task` | `flutter.auto_back_to_chat_after_task` | bool | `true`, `false` |
| `use_independent_chat_send_button` | `flutter.use_independent_chat_send_button` | bool | `true`, `false` |
| `habitual_hand` | `flutter.habitual_hand` | string | `left`, `right` |
| `hide_from_recents` | `flutter.hide_from_recents` | bool | persisted UI flag only; native task-affinity effect needs the channel/UI |
| `agentAvatarIndex` | `flutter.agentAvatarIndex` | int | `0` to `5` |
| `agentAvatarCustomImagePath` | `flutter.agentAvatarCustomImagePath` | string | app-private image path |
| `user_message_tips` | `flutter.user_message_tips` | string-list | recent user message tips |
| `home_greeting_settings` | `flutter.home_greeting_settings` | JSON string | see below |
| `app_background_config_v1` | `flutter.app_background_config_v1` | JSON string | see below |
| `manual_model_context_thresholds` | `flutter.manual_model_context_thresholds` | JSON string | `{modelId: threshold}` |
| `chat_terminal_environment_variables` | `flutter.chat_terminal_environment_variables` | JSON string | terminal env map |

Native SharedPreferences used by setting pages:

| Logical key | Prefs file | Type | Values |
| --- | --- | --- | --- |
| `image_open_mode` | `shared_open_preferences` | string | `default`, `workspace` |
| `file_open_mode` | `shared_open_preferences` | string | `default`, `workspace` |

`home_greeting_settings` shape:

```json
{
  "greetingEnabled": true,
  "quickPrompts": [
    {
      "id": "custom_...",
      "title": "...",
      "prompt": "...",
      "iconKey": "spark",
      "builtIn": false
    }
  ],
  "pinnedQuickPromptIds": []
}
```

`app_background_config_v1` shape:

```json
{
  "enabled": false,
  "sourceType": "none",
  "localImagePath": "",
  "remoteImageUrl": "",
  "blurSigma": 8,
  "frostOpacity": 0.18,
  "brightness": 1,
  "focalX": 0,
  "focalY": 0,
  "imageScale": 1,
  "chatTextSize": 14,
  "chatTextColorMode": "auto",
  "chatTextHexColor": ""
}
```

## SQLite Tables Worth Knowing

- `conversations`: title, mode, archive flag, summary, context compaction fields, token threshold, timestamps.
- `agent_conversation_entries`: full chat stream entries; `payloadJson` contains message/card/tool payloads.
- `token_usage_records`: local/cloud token accounting.
- `codex_thread_bindings`: conversation to Codex thread/cwd binding.
- `app_icons`, `study_records`, `favorite_records`, `execution_records`: task history and app metadata.

For conversation metadata writes, update `updatedAt` to current Unix milliseconds.

The helper's `db-query` command opens SQLite in read-only mode by default and refuses mutations unless `--write` is provided. Values whose column names look like tokens, API keys, passwords, secrets, or bearer credentials are redacted unless `--no-redact` is explicitly supplied.

## Chat History Search

The merged chat-history commands query:

- `agent_conversation_entries.id`: local entry row id.
- `agent_conversation_entries.conversationId`: owning conversation id.
- `agent_conversation_entries.entryType`: usually `user_message`, `assistant_message`, `tool_event`, or `ui_card`.
- `agent_conversation_entries.summary`: short searchable summary.
- `agent_conversation_entries.payloadJson`: full JSON payload. For normal chat bubbles, the real text is usually under `content.text`.
- `agent_conversation_entries.createdAt`: Unix milliseconds.

Use Python sqlite3 through `terminal_execute`; it handles UTF-8 `LIKE` searches more reliably than the sqlite3 CLI inside proot-style shells.

## Workspace Files

- `/workspace/.omnibot/agent/SOUL.md`: assistant identity and behavior rules.
- `/workspace/.omnibot/agent/CHAT.md`: chat prompt addendum.
- `/workspace/.omnibot/memory/MEMORY.md`: long-term memory.
- `/workspace/.omnibot/memory/short-memories/YY-MM-DD.md`: daily short memory.
- `/workspace/.omnibot/memory/index`: embedding index cache.
- `/workspace/.omnibot/skills`: installed skills and `.skill_registry.json`.

Prefer memory and skills tools when available because they refresh indexes/registry state.

## Service Boundaries

Direct preference/database writes change persisted state but may not trigger in-memory listeners. Tell the user when a restart or UI refresh is needed.

Live service actions often need the app process to execute native code, not just a file edit. If there is no currently available native/tool route for a specific service, report that gap and suggest adding a narrow app-control bridge.

## Settings Coverage

Alpine runs with access to the app-private directory, so persisted data is physically writable. The practical boundary is format and runtime semantics: XML and SQLite can be changed directly with standard parsers, while MMKV should be changed through an MMKV-aware writer or app/native bridge rather than byte patching.

Broad coverage:

- UI preferences: theme, language, gesture/handedness, send-button behavior, home greeting, background config, avatar prefs, chat context thresholds.
- Chat history: search, recent conversations, conversation details, archive state, title, context threshold.
- Workspace state: memory files, prompt files, skill directory metadata.
- Diagnostics: paths, SQLite queries, runtime logs through available app/device capabilities.

MMKV/native-required coverage:

- Model provider profiles, scene model bindings, voice settings, remote MCP servers, MCP local server, reminders, memory rollup scheduling, vibration, and local model service config are MMKV-backed or native-service-backed. They are controllable in principle, but the safe implementation path is a small native/app-control bridge that calls MMKV `encode(...)`/`remove...` and service methods from the app process.
- Permission/appops/system settings require Android or Shizuku-level capabilities.

## Local Model Service

Open-source `standard` builds install a no-op local-model feature. `omniinfer` builds install `OmniInferLocalModelFeature`, which exposes the Flutter channel `cn.com.omnimind.bot/MnnLocalModels`.

The channel supports local model operations such as:

- `getOverview`, `getConfig`, `saveConfig`
- `setActiveModel`
- `startApiService`, `stopApiService`
- `listInstalledModels`, `listMarketModels`
- `startDownload`, `pauseDownload`, `deleteModel`
- `setBackend`, `getBackend`
- `stopLanProxy`, `refreshLanProxyToken`

Persisted OmniInfer keys include:

- `omniinfer_config`: `apiPort`, `omniinfer_selected_backend`, `omniinfer_loaded_backend`, `omniinfer_loaded_model_id`
- `omniinfer_mnn_active_model_id`, `omniinfer_mnn_auto_start_on_app_open`, `omniinfer_mnn_download_provider`
- `omniinfer_llama_active_model_id`, `omniinfer_llama_auto_start_on_app_open`, `omniinfer_llama_download_provider`
- `omniinfer_qnn_active_model_id`, `omniinfer_qnn_auto_start_on_app_open`
- `omniinfer_litert_active_model_id`, `omniinfer_litert_auto_start_on_app_open`
- LAN proxy keys: `omniinfer_lan_proxy_token`, `omniinfer_lan_proxy_port`, `omniinfer_lan_proxy_last_error`

Persisted keys can change default backend, port, active model, or auto-start behavior when written correctly. Do not claim that changing these keys has immediately started or stopped the inference server: starting/stopping calls `OmniInferServer.loadModel(...)` or `OmniInferServer.stop()` inside the app process.

The helper command `local-model-probe` can check whether the local HTTP API is reachable on localhost. It is a status probe only; it does not start or stop the server.

## MMKV-Backed Settings

Do not byte-edit files in `/data/data/<package>/files/mmkv` directly. Use code that links to MMKV, or add an app-control bridge that runs inside the app process and calls the same stores the UI uses.

Known MMKV keys from source:

- MCP local server: `mcp_server_enabled`, `mcp_server_port`, `mcp_server_host`, encrypted `mcp_server_token_v2`
- Remote MCP list: `remote_mcp_servers`
- Model providers: `model_provider_profiles_v1`, `model_provider_editing_profile_id`, flat `model_provider_openai_base_url`, `model_provider_openai_api_key`
- Scene model bindings: `scene_model_binding_map_v1`
- Voice scene: `scene_voice_config_v1`
- Local model provider: `mnn_local_provider_port`, `mnn_local_provider_api_key`, `mnn_local_provider_ready`
- Memory rollup/embedding: `workspace_memory_embedding_enabled_v1`, `workspace_memory_rollup_enabled_v1`, `workspace_memory_rollup_next_run_at_v1`
- Reminders: `agent_exact_alarm_records_v2`, `agent_alarm_sound_settings_v1`
- Legacy scheduled VLM: `scheduled_task_id`, `scheduled_task_jsonData`
- Vibration: `app_vibrate`

If the user asks to change one of these, prefer the corresponding UI/native method or built-in tool. If no tool exists, implement or request a narrow native bridge/debug command endpoint rather than pretending a raw file edit is equivalent.
