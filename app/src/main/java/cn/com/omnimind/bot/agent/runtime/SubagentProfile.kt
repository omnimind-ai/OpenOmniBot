package cn.com.omnimind.bot.agent

/**
 * A subagent profile defines the persona / tool budget / model budget of a
 * spawned subagent. Profiles are intentionally constrained — each subagent
 * gets a narrow purpose so the parent can decompose tasks and trust the
 * result.
 *
 * Hard-coded exclusions across all profiles:
 *  - `subagent_dispatch`  → forbids recursion
 *  - `terminal_execute`, all `android_privileged_*` → forbids privileged ops
 *  - all `file_write` / `file_edit` / `file_move`   → no mutating writes
 *  - all `schedule_*` / `alarm_*` / `calendar_*`    → no scheduling
 *
 * Profile-specific allowed tools are listed below.
 */
data class SubagentProfile(
    val id: String,
    val displayName: String,
    val systemPrompt: String,
    val allowedTools: Set<String>,
    val maxRounds: Int = 12,
    val maxOutputTokens: Int = 4096
)

object SubagentProfileRegistry {

    private val FORBIDDEN: Set<String> = setOf(
        "subagent_dispatch",
        "terminal_execute",
        "android_privileged_action",
        "android_privileged_session_start",
        "android_privileged_session_exec",
        "android_privileged_session_read",
        "android_privileged_session_stop",
        "terminal_session_start",
        "terminal_session_exec",
        "terminal_session_read",
        "terminal_session_stop",
        "file_write",
        "file_edit",
        "file_move",
        "file_delete",
        "schedule_task_create",
        "schedule_task_update",
        "schedule_task_delete",
        "schedule_task_list",
        "alarm_create",
        "alarm_update",
        "alarm_delete",
        "alarm_list",
        "calendar_create",
        "calendar_update",
        "calendar_delete",
        "calendar_list"
    )

    private fun strip(tools: Set<String>): Set<String> = tools - FORBIDDEN

    val general: SubagentProfile = SubagentProfile(
        id = "general",
        displayName = "通用子任务",
        systemPrompt = """
            你是一名通用子 Agent，由父 Agent 分派来完成一个独立的小任务。
            约束：
            - 你不能再调用 subagent_dispatch（已禁用），所有事情自己完成。
            - 你不能执行任何写入/删除/特权/终端/日程操作。
            - 在最多 12 轮内得出结论；如果不能完成，明确说明原因。
            - 完成后用一段简洁的自然语言概括结果，便于父 Agent 聚合。
        """.trimIndent(),
        allowedTools = strip(
            setOf(
                "file_read", "file_list", "file_search", "file_stat",
                "context_apps_query", "context_time_now",
                "memory_search", "memory_load",
                "skills_list", "skills_read"
            )
        )
    )

    val explorer: SubagentProfile = SubagentProfile(
        id = "explorer",
        displayName = "探索者",
        systemPrompt = """
            你是一名探索者子 Agent，专注于读取、搜索、归纳信息。
            - 你只能使用只读类工具进行检索；任何写入或修改都属于越权。
            - 在结果中先给出"核心结论"再附上"相关证据"（文件路径/记忆 slug）。
            - 最多 12 轮，结果保持紧凑。
        """.trimIndent(),
        allowedTools = strip(
            setOf(
                "file_read", "file_list", "file_search", "file_stat",
                "context_apps_query", "context_time_now",
                "memory_search", "memory_load",
                "skills_list", "skills_read"
            )
        )
    )

    val memoryCurator: SubagentProfile = SubagentProfile(
        id = "memory-curator",
        displayName = "记忆管理员",
        systemPrompt = """
            你是一名记忆管理员子 Agent，负责整理 / 写入 workspace 记忆。
            - 你可以使用 memory_search、memory_load、memory_write_daily、memory_upsert_longterm。
            - 写入前必须先检索，避免重复或冲突。
            - 把"用户偏好 / 长期约束 / 关键事实"沉淀为长期记忆；过程性细节走日记。
            - 完成后简洁说明做了什么（新增 N 条短期、M 条长期）。
        """.trimIndent(),
        allowedTools = strip(
            setOf(
                "memory_search", "memory_load",
                "memory_write_daily", "memory_upsert_longterm"
            )
        )
    )

    val planner: SubagentProfile = SubagentProfile(
        id = "planner",
        displayName = "规划器",
        systemPrompt = """
            你是一名规划器子 Agent，不调用任何工具，只输出一份结构化的执行计划。
            - 第一行：单句目标摘要。
            - 然后列出有序步骤，每步描述：动作、所需工具或资源、成功判据。
            - 标注潜在风险或依赖。
            - 最后用 2-3 句给出关键决策。
            - 不要进入实际执行；输出后即结束。
        """.trimIndent(),
        allowedTools = emptySet(),
        maxRounds = 3
    )

    private val byId: Map<String, SubagentProfile> = listOf(
        general, explorer, memoryCurator, planner
    ).associateBy { it.id }

    fun get(id: String?): SubagentProfile {
        val key = id?.trim()?.lowercase().orEmpty()
        return byId[key] ?: general
    }

    fun all(): List<SubagentProfile> = byId.values.toList()

    fun isForbidden(toolName: String): Boolean = toolName in FORBIDDEN
}
