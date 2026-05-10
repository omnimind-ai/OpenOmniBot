# Part Iteration Log

## OOB Workbench Backend Runtime

- 完成情况：后端框架已完成本轮基座。已新增 Project 运行时后端骨架，包括 `workbench_project_progress_get`、`workbench_project_ingest_oss`、`source/manifest.json`、`logs/project_progress.jsonl`、`logs/oss_ingest.jsonl`、Project payload `sourceAssets/lastProgress`、`backend/api_spec.json` executor runtime metadata，并把 source/progress 写入 Project payload、active Project prompt context 和 export manifest。
- 功能是否完全验证：后端单元门禁和 Agent tool definition 门禁已通过；设备侧只使用 `emulator-5554`，Dashboard-token backend E2E 已真实创建 `oob-workbench-quick-capture` 和 `oob-workbench-vlm-quick-note`，激活后通过 `capture.ingest` 写入数据，并验证 app-data runtime 文件存在。Home/VLM/toolvox 直跑仍未声明成功：模型 provider 已配置并能到达 DashScope VLM，但 Flutter Home 输入焦点阻塞了提示提交。
- UI显示：已在 5554 的 OOB 原生 Flutter 页面看到 `随手记 Inbox · NOTE`。第一次页面显示 `3 active / 0 archived`、`OOB native UI`、`4 APIs`，截图为 `/tmp/oob_quick_capture_project_5554_recheck.png`；最新页面显示 `3 active / 1 archived`、`OOB native UI`、`4 APIs`，并把 `Invoice receipt 256 RMB` 标为 `Summary`，截图为 `/tmp/oob_quick_note_final_clean_5554.png`。新增 progress/source 字段继续作为后端能力暴露。
- 测试：已跑 `env JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home ANDROID_HOME=/Users/wuzewen/Library/Android/sdk ./gradlew :app:testDevelopStandardDebugUnitTest --tests '*Workbench*' --tests '*AgentToolDefinitions*' -Ptarget=lib/main_standard.dart`，结果 `BUILD SUCCESSFUL`。本轮功能验证执行了 `./gradlew :app:assembleDevelopStandardDebug -Ptarget=lib/main_standard.dart`、`adb -s emulator-5554 install -r ...`、`debug_model_provider_configure`、`vlm_task` provider smoke、`POST /mcp/workbench/call` create/activate/API/open、native Flutter UI `capture.ingest`/archive smoke，以及 `adb -s emulator-5554 shell run-as ... cat/head/tail` runtime 文件检查。
- 是否需要优化：需要。下一步补真实 workspace script/Bridge/Alpine executor，将 `workspace_python_script` 从 native-backed contract 替换为可执行 Python；OSS URL 拉取仍应走 terminal/tool 流程，不在 native 内直接联网。要做 `vlm_task`/toolvox 直接成功链路，需要解决 Flutter Home 输入的 accessibility focus / in-app runner 问题，不能把 Dashboard debug route 冒充为 VLM 创建成功。

## OOB Agent Tooling

- 完成情况：本轮后端控制 API 接入完成。已把 OSS ingest 和 progress get 接入 `AgentToolDefinitions`、`WorkbenchToolHandler`、`AgentSystemPrompt`、`AssistsCoreManager`、`AssistsCoreChannel`，并保持 Workbench 控制 API 不进入 Project API Registry。
- 功能是否完全验证：Agent tool definition 门禁通过；Dashboard-token debug route 已作为本地认证 E2E 入口调用同一 `WorkbenchProjectStore`。真实 OOB 内部 Agent/toolvox 路径还未成功创建 Project，不能混淆为已完成；但模型 provider debug route 已验证可写入 provider/profile stores 并让 `vlm_task` 进入 DashScope VLM。
- UI显示：`workbench_project_open` 现在通过 Android 主线程调用 `TaskCompletionNavigator` 导航到真实 Project Flutter Display，本轮已在 5554 显示随手记 Project。
- 测试：已通过 `*AgentToolDefinitions*` 相关门禁。设备上 MCP 当前只暴露 `vlm_task/task_status/task_reply/task_wait_unlock/file_transfer`；`toolvox` 字符串在源码中未找到。
- 是否需要优化：需要。若 toolvox 有独立 runner，需要把本轮 skill 的最小命令固化到 runner 配置或测试脚本里；若要从 MCP 直接调 Workbench，只能走明确的 debug/test transport，不能混进 Project API Registry。VLM Home 输入需要单独修复，不应靠 app 进程执行 `input text/keyevent`，因为该路径在 5554 上被 Android 拒绝 `INJECT_EVENTS`。
