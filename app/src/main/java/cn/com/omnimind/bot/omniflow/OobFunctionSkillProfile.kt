package cn.com.omnimind.bot.omniflow

import android.content.Context
import cn.com.omnimind.baselib.i18n.PromptLocale
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.AgentToolDefinitions
import cn.com.omnimind.bot.agent.AgentToolJson.mapToJsonElement
import cn.com.omnimind.bot.agent.config.AgentToolFeatureStore
import cn.com.omnimind.bot.runlog.OobFunctionSchemaBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Small native profile that exposes the tools used by the OmniFlow management skill.
 *
 * Workflow rules and prompts belong in `oob-function-management/SKILL.md`.
 */
object OobFunctionSkillProfile {
    const val PROFILE = "function_management"
    const val SKILL_ID = "oob-function-management"
    private const val MAX_DYNAMIC_FUNCTION_TOOLS = 500
    private const val MAX_PROMPT_FUNCTION_CANDIDATES = 5
    private val MODEL_TOOL_NAME_REGEX = Regex("^[A-Za-z0-9_-]{1,64}$")

    val toolNames: Set<String> = OobFunctionToolNames.profileTools

    fun isProfile(profile: String?): Boolean =
        normalizeProfile(profile) == PROFILE

    fun allowedToolsForProfile(profile: String?): Set<String>? =
        if (isProfile(profile)) toolNames else null

    fun staticToolDefinitions(locale: PromptLocale): List<JsonObject> =
        functionManagementToolDefinitions.map { definition ->
            AgentToolDefinitions.decorateToolDefinition(definition, locale)
        }

    fun dynamicFunctionToolDefinitions(
        context: Context,
        locale: PromptLocale,
        forceInclude: Boolean = false,
    ): List<JsonObject> {
        if (!forceInclude && !AgentToolFeatureStore.isOobFunctionAsToolEnabled(context)) {
            return emptyList()
        }
        return runCatching {
            OobFunctionRepository(context)
                .listSpecs(MAX_DYNAMIC_FUNCTION_TOOLS)
                .mapNotNull { spec -> toDynamicFunctionToolDefinition(spec, locale) }
        }.onFailure {
            OmniLog.w("OobFunctionSkillProfile", "load dynamic Function tools failed: ${it.message}")
        }.getOrDefault(emptyList())
    }

    fun shouldKeepDynamicFunctionForProfile(profile: String?, toolType: String): Boolean =
        isProfile(profile) && toolType == "oob_function"

    fun promptCandidateContext(
        context: Context,
        locale: PromptLocale,
        limit: Int = MAX_PROMPT_FUNCTION_CANDIDATES,
    ): String {
        if (!AgentToolFeatureStore.isOobFunctionAsToolEnabled(context)) {
            return ""
        }
        val candidates = runCatching {
            OobFunctionRepository(context).listSpecs(limit.coerceIn(1, MAX_PROMPT_FUNCTION_CANDIDATES))
        }.onFailure {
            OmniLog.w("OobFunctionSkillProfile", "load prompt Function candidates failed: ${it.message}")
        }.getOrDefault(emptyList())
        if (candidates.isEmpty()) return ""

        return buildString {
            when (locale) {
                PromptLocale.ZH_CN -> {
                    appendLine("当前可复用的 OmniFlow Functions（候选摘要，不是完整 spec）：")
                    appendLine("- 如果用户目标与某个 Function 高置信匹配，优先 `${OobFunctionToolNames.FUNCTION_GUARD_CHECK}` -> `${OobFunctionToolNames.FUNCTION_RUN}`，不要先裸跑 `vlm_task`。")
                    appendLine("- 如果只是相似但不确定，先 guard 或 `${OobFunctionToolNames.FUNCTION_GET}` 查看；证据不足再用 `vlm_task`。")
                }
                PromptLocale.EN_US -> {
                    appendLine("Reusable OmniFlow Functions available right now (candidate summaries, not full specs):")
                    appendLine("- If the user goal clearly matches a Function, prefer `${OobFunctionToolNames.FUNCTION_GUARD_CHECK}` -> `${OobFunctionToolNames.FUNCTION_RUN}` before raw `vlm_task`.")
                    appendLine("- If the match is only tentative, inspect with guard or `${OobFunctionToolNames.FUNCTION_GET}`; use `vlm_task` when evidence is insufficient.")
                }
            }
            candidates.forEachIndexed { index, spec ->
                appendLine(formatPromptCandidate(index + 1, spec, locale))
            }
        }.trim()
    }

    private fun normalizeProfile(profile: String?): String = profile
        ?.trim()
        ?.lowercase()
        ?.replace('-', '_')
        .orEmpty()

    private fun formatPromptCandidate(
        ordinal: Int,
        spec: Map<String, Any?>,
        locale: PromptLocale,
    ): String {
        val functionId = spec["function_id"]?.toString()?.trim().orEmpty()
        val name = spec["name"]?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: functionId
        val description = spec["description"]?.toString()?.trim()
            ?.replace(Regex("\\s+"), " ")
            ?.takeIf { it.isNotEmpty() }
            ?: name
        val metadata = spec["metadata"] as? Map<*, *>
        val agentReuse = metadata?.get("agent_reuse") as? Map<*, *>
        val reuseWhen = agentReuse?.get("reuse_when")?.toString()?.trim().orEmpty()
        val successSignal = agentReuse?.get("success_signal")?.toString()?.trim().orEmpty()
        val inputSchema = OobFunctionSchemaBuilder.inputSchema(spec)
        val params = ((inputSchema["properties"] as? Map<*, *>)?.keys ?: emptySet<Any?>())
            .mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            .take(6)
            .joinToString(", ")
            .ifBlank {
                when (locale) {
                    PromptLocale.ZH_CN -> "无显式参数"
                    PromptLocale.EN_US -> "no explicit params"
                }
            }
        val clippedDescription = description.take(220)
        return when (locale) {
            PromptLocale.ZH_CN -> buildString {
                append("- $ordinal. `$functionId` — $name：$clippedDescription；参数: $params")
                if (reuseWhen.isNotEmpty()) append("；适用: ${reuseWhen.take(120)}")
                if (successSignal.isNotEmpty()) append("；成功标志: ${successSignal.take(120)}")
            }
            PromptLocale.EN_US -> buildString {
                append("- $ordinal. `$functionId` — $name: $clippedDescription; params: $params")
                if (reuseWhen.isNotEmpty()) append("; use when: ${reuseWhen.take(120)}")
                if (successSignal.isNotEmpty()) append("; success: ${successSignal.take(120)}")
            }
        }
    }

    private fun toDynamicFunctionToolDefinition(
        spec: Map<String, Any?>,
        locale: PromptLocale
    ): JsonObject? {
        val functionId = spec["function_id"]?.toString()?.trim().orEmpty()
        if (functionId.isEmpty()) return null
        if (!MODEL_TOOL_NAME_REGEX.matches(functionId)) {
            OmniLog.w("OobFunctionSkillProfile", "skip invalid Function tool name: $functionId")
            return null
        }
        val displayName = spec["name"]?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: functionId
        val description = spec["description"]?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: displayName
        val parameters = mapToJsonElement(
            OobFunctionSchemaBuilder.inputSchema(spec)
        ) as? JsonObject ?: JsonObject(emptyMap())

        return AgentToolDefinitions.decorateToolDefinition(buildJsonObject {
            put("type", JsonPrimitive("function"))
            put("function", buildJsonObject {
                put("name", JsonPrimitive(functionId))
                put("displayName", JsonPrimitive(displayName))
                put("toolType", JsonPrimitive("oob_function"))
                put("description", JsonPrimitive(description))
                put("parameters", parameters)
            })
        }, locale)
    }

    private val oobFunctionListTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", OobFunctionToolNames.FUNCTION_LIST)
            put("displayName", "列出复用指令")
            put("toolType", "workbench")
            put("description", "列出本机已注册的 OOB 复用指令。用于查看可选 Function 候选；不会执行任何手机操作。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("limit") {
                        put("type", "integer")
                        put("description", "可选。返回数量上限，默认 100，最大 500。")
                    }
                }
            }
        }
    }

    private val oobFunctionGetTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", OobFunctionToolNames.FUNCTION_GET)
            put("displayName", "查看复用指令")
            put("toolType", "workbench")
            put("description", "读取一个 OOB 复用指令的结构化 Function spec，用于确认步骤、参数和来源。不会执行手机操作。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("functionId") {
                        put("type", "string")
                        put("description", "要读取的 Function id。")
                    }
                    putJsonObject("function_id") {
                        put("type", "string")
                        put("description", "functionId 的 snake-case 兼容字段。")
                    }
                }
            }
        }
    }

    private val oobFunctionRegisterTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", OobFunctionToolNames.FUNCTION_REGISTER)
            put("displayName", "注册复用指令")
            put("toolType", "workbench")
            put("description", "注册或更新一个 OOB 复用指令。优先使用轻量字段 functionId/name/description/steps；只有已有完整底层结构时才传 functionSpec。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("functionId") { put("type", "string") }
                    putJsonObject("function_id") { put("type", "string") }
                    putJsonObject("name") { put("type", "string") }
                    putJsonObject("description") { put("type", "string") }
                    putJsonObject("packageName") { put("type", "string") }
                    putJsonObject("sourcePage") { put("type", "object") }
                    putJsonObject("parameters") { put("type", "array") }
                    putJsonObject("steps") {
                        put("type", "array")
                        putJsonObject("items") { put("type", "object") }
                    }
                    putJsonObject("functionSpec") { put("type", "object") }
                    putJsonObject("function_spec") { put("type", "object") }
                }
            }
        }
    }

    private val updateFunctionTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", OobFunctionToolNames.FUNCTION_UPDATE)
            put("displayName", "更新复用指令")
            put("toolType", "workbench")
            put("description", "根据结构化 patch、用户纠错指令或 RunLog 证据分析更新一个已保存的 OOB Function。传 run_id 且不传 analysis/patch 时只返回 agent 分析上下文；不会执行手机操作。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("functionId") { put("type", "string") }
                    putJsonObject("function_id") { put("type", "string") }
                    putJsonObject("run_id") {
                        put("type", "string")
                        put("description", "Optional local RunLog id. With no analysis/patch, ${OobFunctionToolNames.FUNCTION_UPDATE} returns analysis_context and agent_prompt for evidence analysis.")
                    }
                    putJsonObject("runId") {
                        put("type", "string")
                        put("description", "Camel-case alias for run_id.")
                    }
                    putJsonObject("instruction") { put("type", "string") }
                    putJsonObject("mode") {
                        put("type", "string")
                        putJsonArray("enum") {
                            add("enhance")
                            add("repair")
                            add("annotate")
                        }
                    }
                    putJsonObject("dryRun") { put("type", "boolean") }
                    putJsonObject("allowExecutionChange") { put("type", "boolean") }
                    putJsonObject("allowStructuralChange") { put("type", "boolean") }
                    putJsonObject("analysis") {
                        put("type", "object")
                        put("description", "Agent-authored RunLog evidence analysis. Saved into Function metadata and may include recommended_patch.")
                    }
                    putJsonObject("patch") { put("type", "object") }
                }
            }
        }
    }

    private val oobFunctionRunTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", OobFunctionToolNames.FUNCTION_RUN)
            put("displayName", "执行复用指令")
            put("toolType", "workbench")
            put(
                "description",
                "执行一个已保存的 OOB/OmniFlow Function。用户目标与候选 Function 高置信匹配时，优先先调用 ${OobFunctionToolNames.FUNCTION_GUARD_CHECK} 再调用本工具，不要先裸跑 vlm_task。失败时返回 fallback_context，agent 可接管失败步骤，然后用 resume_from_step/start_step_index 从失败或下一步恢复继续。"
            )
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("functionId") { put("type", "string") }
                    putJsonObject("function_id") { put("type", "string") }
                    putJsonObject("arguments") { put("type", "object") }
                    putJsonObject("resume_from_step") {
                        put("type", "integer")
                        put("description", "0-based step index. Omit or set 0 for a fresh run; set to the failed/next step when resuming after agent fallback.")
                    }
                    putJsonObject("start_step_index") {
                        put("type", "integer")
                        put("description", "Alias for resume_from_step. Use this when the caller wants to start or resume from a specific Function step.")
                    }
                    putJsonObject("startStepIndex") {
                        put("type", "integer")
                        put("description", "Camel-case alias for start_step_index.")
                    }
                    putJsonObject("resumeFromStep") {
                        put("type", "integer")
                        put("description", "Camel-case alias for resume_from_step.")
                    }
                    putJsonObject("fallback_session_id") {
                        put("type", "string")
                        put("description", "Optional id returned by fallback_context to link replay -> agent fallback -> replay resume.")
                    }
                    putJsonObject("fallbackSessionId") {
                        put("type", "string")
                        put("description", "Camel-case alias for fallback_session_id.")
                    }
                    putJsonObject("fallback_attempt") {
                        put("type", "integer")
                        put("description", "Optional retry counter returned by fallback_context; used to avoid infinite fallback loops.")
                    }
                    putJsonObject("fallbackAttempt") {
                        put("type", "integer")
                        put("description", "Camel-case alias for fallback_attempt.")
                    }
                    putJsonObject("dryRun") { put("type", "boolean") }
                    putJsonObject("continueWithAgent") { put("type", "boolean") }
                    putJsonObject("executionMode") { put("type", "string") }
                    putJsonObject("confirmed") { put("type", "boolean") }
                }
                putJsonArray("required") { add("functionId") }
            }
        }
    }

    private val oobFunctionGuardCheckTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", OobFunctionToolNames.FUNCTION_GUARD_CHECK)
            put("displayName", "检查复用指令")
            put("toolType", "workbench")
            put("description", "执行前检查一个 OOB 复用指令的参数和运行风险。不会执行手机操作。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("functionId") { put("type", "string") }
                    putJsonObject("function_id") { put("type", "string") }
                    putJsonObject("arguments") { put("type", "object") }
                }
            }
        }
    }

    private val oobFunctionDeleteTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", OobFunctionToolNames.FUNCTION_DELETE)
            put("displayName", "删除复用指令")
            put("toolType", "workbench")
            put("description", "删除一个 OOB 复用指令。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("functionId") { put("type", "string") }
                    putJsonObject("function_id") { put("type", "string") }
                }
            }
        }
    }

    private val oobFunctionClearTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", OobFunctionToolNames.FUNCTION_CLEAR)
            put("displayName", "清空复用指令")
            put("toolType", "workbench")
            put("description", "清空所有 OOB 复用指令。只有用户明确要求清空全部时使用，必须传 confirm=true。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("confirm") { put("type", "boolean") }
                }
            }
        }
    }

    private val oobRunLogListTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", OobFunctionToolNames.RUN_LOG_LIST)
            put("displayName", "列出 RunLog")
            put("toolType", "workbench")
            put("description", "列出 OOB 内部最近的 RunLogs，用于选择可固化或检查的历史执行。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {}
            }
        }
    }

    private val oobRunLogGetTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", OobFunctionToolNames.RUN_LOG_GET)
            put("displayName", "查看 RunLog")
            put("toolType", "workbench")
            put("description", "读取一个 OOB 内部 RunLog 时间线。只在需要检查具体历史执行时使用。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("run_id") { put("type", "string") }
                    putJsonObject("runId") { put("type", "string") }
                }
            }
        }
    }

    private val oobRunLogConvertTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", OobFunctionToolNames.RUN_LOG_CONVERT)
            put("displayName", "转换 RunLog")
            put("toolType", "workbench")
            put("description", "把成功完成的 RunLog 转换为 oob.reusable_function.v1。register=true 时同时注册为可直接调用的 OOB 指令。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("run_id") { put("type", "string") }
                    putJsonObject("runId") { put("type", "string") }
                    putJsonObject("register") { put("type", "boolean") }
                    putJsonObject("function_id") { put("type", "string") }
                    putJsonObject("name") { put("type", "string") }
                    putJsonObject("description") { put("type", "string") }
                }
            }
        }
    }

    private val functionManagementToolDefinitions: List<JsonObject> = listOf(
        oobFunctionListTool,
        oobFunctionGetTool,
        oobFunctionRegisterTool,
        updateFunctionTool,
        oobFunctionGuardCheckTool,
        oobFunctionRunTool,
        oobFunctionDeleteTool,
        oobFunctionClearTool,
        oobRunLogListTool,
        oobRunLogGetTool,
        oobRunLogConvertTool,
    )
}
