# Part Iteration Log

## OOB Workbench Backend Runtime

- 完成情况：后端框架已完成本轮基座。已新增 Project 运行时后端骨架，包括 `workbench_project_progress_get`、`workbench_project_ingest_oss`、`source/manifest.json`、`logs/project_progress.jsonl`、`logs/oss_ingest.jsonl`、Project payload `sourceAssets/lastProgress`、`backend/api_spec.json` executor runtime metadata，并把 source/progress 写入 Project payload、active Project prompt context 和 export manifest。
- 功能是否完全验证：部分验证。后端单元门禁和 Agent tool definition 门禁通过；设备侧只使用 `emulator-5554`，确认 app 运行、workspace 初始化、但 Home/VLM 路径未生成 `workspace/projects`。
- UI显示：本轮不做前端展示改动。已有 Flutter 页面可继续读取 Project payload；新增 progress/source 字段先作为后端能力暴露。
- 测试：已跑 `env JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home ANDROID_HOME=/Users/wuzewen/Library/Android/sdk ./gradlew :app:testDevelopStandardDebugUnitTest --tests '*Workbench*' --tests '*AgentToolDefinitions*' -Ptarget=lib/main_standard.dart`，结果 `BUILD SUCCESSFUL`。设备侧尝试 Home prompt 创建 Project，但未产生 `workspace/projects`。
- 是否需要优化：需要。下一步补真实 workspace script/Bridge/Alpine executor，将 `workspace_python_script` 从 native-backed contract 替换为可执行 Python；OSS URL 拉取仍应走 terminal/tool 流程，不在 native 内直接联网。要做 toolvox/VLM 成功链路，需要先配置 5554 上的模型 provider 或提供可调用 Workbench Agent 的内部 runner。

## OOB Agent Tooling

- 完成情况：本轮后端控制 API 接入完成。已把 OSS ingest 和 progress get 接入 `AgentToolDefinitions`、`WorkbenchToolHandler`、`AgentSystemPrompt`、`AssistsCoreManager`、`AssistsCoreChannel`，并保持 Workbench 控制 API 不进入 Project API Registry。
- 功能是否完全验证：部分验证。静态 tool definition 测试通过；真实 OOB 内部 Agent/toolvox 路径还未成功创建 Project。
- UI显示：无 UI 改动。
- 测试：已通过 `*AgentToolDefinitions*` 相关门禁。设备上 MCP 当前只暴露 `vlm_task/task_status/task_reply/task_wait_unlock/file_transfer`；`toolvox` 字符串在源码中未找到。
- 是否需要优化：需要。若 toolvox 有独立 runner，需要把本轮 skill 的最小命令固化到 runner 配置或测试脚本里；若要从 MCP 直接调 Workbench，需要新增明确的 debug/test transport，不能混进 Project API Registry。
