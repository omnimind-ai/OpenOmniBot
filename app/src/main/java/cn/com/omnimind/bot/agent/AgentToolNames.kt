package cn.com.omnimind.bot.agent

/**
 * Canonical in-app agent tool names shared by tool definitions, handlers, MCP
 * adapters, and RunLog classification.
 *
 * Function and RunLog lifecycle tool names live in
 * [cn.com.omnimind.bot.omniflow.OobFunctionToolNames]. Replay-only taxonomy
 * such as `call_function` stays in
 * [cn.com.omnimind.bot.runlog.RunLogReplayPolicy].
 */
object AgentToolNames {
    const val VLM_TASK = "vlm_task"
    const val WEB_SEARCH = "web_search"
    const val BROWSER_USE = "browser_use"
    const val ANDROID_PRIVILEGED_ACTION = "android_privileged_action"
}
