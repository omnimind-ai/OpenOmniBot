package cn.com.omnimind.bot.omniflow

import android.content.Context
import cn.com.omnimind.baselib.i18n.PromptLocale
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.AgentToolDefinitions
import cn.com.omnimind.bot.agent.config.AgentToolFeatureStore
import cn.com.omnimind.bot.runlog.OobFunctionSchemaBuilder
import cn.com.omnimind.bot.runlog.OobRunLogReplayService
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
    private val MODEL_TOOL_NAME_REGEX = Regex("^[A-Za-z0-9_-]{1,64}$")

    val toolNames: Set<String> = setOf(
        "oob_function_list",
        "oob_function_get",
        "oob_function_register",
        "update_function",
        "oob_function_guard_check",
        "oob_function_run",
        "oob_function_delete",
        "oob_function_clear",
        "oob_run_log_list",
        "oob_run_log_get",
        "oob_run_log_convert",
    )

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
            OobRunLogReplayService(context)
                .listFunctionSpecs(MAX_DYNAMIC_FUNCTION_TOOLS)
                .mapNotNull { spec -> toDynamicFunctionToolDefinition(spec, locale) }
        }.onFailure {
            OmniLog.w("OobFunctionSkillProfile", "load dynamic Function tools failed: ${it.message}")
        }.getOrDefault(emptyList())
    }

    fun shouldKeepDynamicFunctionForProfile(profile: String?, toolType: String): Boolean =
        isProfile(profile) && toolType == "oob_function"

    private fun normalizeProfile(profile: String?): String = profile
        ?.trim()
        ?.lowercase()
        ?.replace('-', '_')
        .orEmpty()

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

    private fun mapToJsonElement(value: Any?): kotlinx.serialization.json.JsonElement {
        return when (value) {
            null -> kotlinx.serialization.json.JsonNull
            is kotlinx.serialization.json.JsonElement -> value
            is Map<*, *> -> kotlinx.serialization.json.JsonObject(
                value.entries.associate { (key, item) ->
                    key.toString() to mapToJsonElement(item)
                }
            )
            is List<*> -> kotlinx.serialization.json.JsonArray(value.map { mapToJsonElement(it) })
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        }
    }

    private val oobFunctionListTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "oob_function_list")
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
            put("name", "oob_function_get")
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
            put("name", "oob_function_register")
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
            put("name", "update_function")
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
                        put("description", "Optional local RunLog id. With no analysis/patch, update_function returns analysis_context and agent_prompt for evidence analysis.")
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
            put("name", "oob_function_run")
            put("displayName", "执行复用指令")
            put("toolType", "workbench")
            put("description", "显式执行一个用户或 agent 已选择的 OOB 复用指令；失败时返回 fallback_context，agent 可接管失败步骤后用 resume_from_step 回来继续。")
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
            put("name", "oob_function_guard_check")
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
            put("name", "oob_function_delete")
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
            put("name", "oob_function_clear")
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
            put("name", "oob_run_log_list")
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
            put("name", "oob_run_log_get")
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
            put("name", "oob_run_log_convert")
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
