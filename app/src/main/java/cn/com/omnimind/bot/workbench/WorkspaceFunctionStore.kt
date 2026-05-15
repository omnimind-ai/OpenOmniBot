package cn.com.omnimind.bot.workbench

import android.content.Context
import cn.com.omnimind.baselib.runlog.InternalRunLogRecord
import cn.com.omnimind.baselib.runlog.OobReusableFunctionStore
import cn.com.omnimind.baselib.util.OmniLog
import com.google.gson.GsonBuilder
import java.io.File

/**
 * Portable OOB 指令 (command) store backed by workspace files instead of SharedPreferences.
 *
 * 指令 specs are stored at {workspaceRoot}/commands/{commandId}.json and travel with
 * the workspace on export/import. OobFunctionToolHandler checks this store alongside
 * the SharedPreferences-based OobReusableFunctionStore.
 *
 * Primary use: save InternalRunLog records as named, replayable 指令 so agent work
 * is preserved as callable memory across devices and sessions.
 */
class WorkspaceFunctionStore(private val workspaceRoot: File) {

    private val gson = GsonBuilder()
        .disableHtmlEscaping()
        .setPrettyPrinting()
        .create()

    private val functionsDir: File
        get() = File(workspaceRoot, "commands").apply { mkdirs() }

    private val runLogsDir: File
        get() = File(workspaceRoot, "run_logs").apply { mkdirs() }

    // ── Function CRUD ────────────────────────────────────────────────────────

    fun register(spec: Map<String, Any?>): Map<String, Any?> {
        val functionId = spec["function_id"]?.toString()?.trim()
            ?: return mapOf("success" to false, "errorMessage" to "function_id required")
        val file = functionFile(functionId)
        val tmp = File(file.parentFile, "${file.name}.tmp")
        return runCatching {
            tmp.writeText(gson.toJson(spec))
            if (!tmp.renameTo(file)) {
                file.writeText(gson.toJson(spec))
                tmp.delete()
            }
            mapOf("success" to true, "function_id" to functionId,
                "path" to file.absolutePath)
        }.onFailure {
            tmp.delete()
            OmniLog.w(TAG, "register function failed: $functionId, ${it.message}")
        }.getOrElse { mapOf("success" to false, "errorMessage" to it.message) }
    }

    fun get(functionId: String): Map<String, Any?>? {
        val file = functionFile(functionId.trim())
        if (!file.exists()) return null
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            gson.fromJson(file.readText(), Map::class.java) as? Map<String, Any?>
        }.getOrNull()
    }

    fun list(limit: Int = 100): List<Map<String, Any?>> {
        val dir = functionsDir
        return dir.listFiles { f -> f.isFile && f.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            ?.take(limit.coerceIn(1, 500))
            ?.mapNotNull { file ->
                runCatching {
                    @Suppress("UNCHECKED_CAST")
                    gson.fromJson(file.readText(), Map::class.java) as? Map<String, Any?>
                }.getOrNull()
            }
            .orEmpty()
    }

    fun delete(functionId: String): Boolean =
        functionFile(functionId.trim()).takeIf { it.exists() }?.delete() == true

    fun canHandle(functionId: String): Boolean = functionFile(functionId.trim()).exists()

    // ── Run log mirror ────────────────────────────────────────────────────────

    /** Write a portable copy of the run to workspace/run_logs/. */
    fun mirrorRunLog(record: InternalRunLogRecord) {
        val safeId = record.runId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
        val file = File(runLogsDir, "$safeId.json")
        runCatching {
            file.writeText(gson.toJson(record))
        }.onFailure {
            OmniLog.w(TAG, "mirror run log failed: ${record.runId}, ${it.message}")
        }
    }

    // ── Distillation: InternalRunLogRecord → OobReusableFunction spec ─────────

    /**
     * Converts a finished run's tool-call cards into a replayable OobReusableFunction
     * spec and registers it in this workspace store AND in SharedPreferences so
     * OobFunctionToolHandler can pick it up immediately.
     *
     * Only called when run.success == true.
     */
    fun distillFromRun(
        context: Context,
        record: InternalRunLogRecord
    ): Map<String, Any?> {
        mirrorRunLog(record)

        val steps = record.cards
            .filter { card -> card["success"] != false }  // skip failed steps
            .mapNotNull { card -> cardToStep(card) }
            .filter { it.isNotEmpty() }

        if (steps.isEmpty()) {
            return mapOf("success" to false, "reason" to "no_replayable_steps")
        }

        val functionId = deriveCommandId(record)
        val spec = linkedMapOf<String, Any?>(
            "function_id" to functionId,
            "name" to (record.operationDescription.ifBlank { record.goal }.take(80)),
            "description" to record.goal,
            "parameters" to emptyList<Any>(),
            "execution" to linkedMapOf(
                "steps" to steps,
                "step_count" to steps.size
            ),
            "source" to linkedMapOf(
                "run_id" to record.runId,
                "goal" to record.goal,
                "tool_name" to record.toolName,
                "distilled_at" to System.currentTimeMillis().toString()
            ),
            "_oob_registry" to linkedMapOf(
                "registered_at" to System.currentTimeMillis().toString(),
                "updated_at" to System.currentTimeMillis().toString(),
                "runner" to "oob_agent_reusable_function",
                "storage" to "workspace"
            )
        )

        register(spec)

        // Also register in SharedPreferences so OobFunctionToolHandler finds it immediately
        runCatching { OobReusableFunctionStore.register(context, spec) }

        return mapOf(
            "success" to true,
            "function_id" to functionId,
            "step_count" to steps.size,
            "path" to functionFile(functionId).absolutePath
        )
    }

    // ── Card → Step conversion ────────────────────────────────────────────────

    private fun cardToStep(card: Map<String, Any?>): Map<String, Any?> {
        val toolName = (card["tool_name"] ?: card["toolName"])?.toString().orEmpty()
        val stepIndex = (card["step_index"] as? Number)?.toInt() ?: 0
        val title = card["title"]?.toString()?.ifBlank { toolName } ?: toolName
        val argsJson = extractArgsJson(card)
        val args = parseArgsJson(argsJson)
        val stepId = "step_${stepIndex + 1}"

        return when {
            toolName in SKIP_TOOLS -> emptyMap()

            toolName == "android_privileged_action" -> {
                val action = (args["action"] ?: args["omniflow_action"])
                    ?.toString()?.trim()?.lowercase().orEmpty()
                if (action !in OMNIFLOW_ACTIONS) return emptyMap()
                val stepArgs = args.filterKeys { it != "action" && it != "omniflow_action" }
                linkedMapOf<String, Any?>(
                    "id" to stepId,
                    "title" to title,
                    "executor" to "omniflow",
                    "omniflow_action" to action,
                    "local_action" to action,
                    "model_free" to true,
                    "args" to stepArgs,
                    // Preserve source_context for coordinate remapping
                    "source_context" to args["source_context"],
                    "coordinate_hook" to if (action in COORDINATE_ACTIONS) "omniflow" else null
                ).filterValues { it != null }
            }

            toolName in AGENT_TOOLS -> {
                // Not deterministic — record as an agent step with original prompt context
                linkedMapOf(
                    "id" to stepId,
                    "title" to title,
                    "executor" to "agent",
                    "agent_call" to linkedMapOf(
                        "tool" to toolName,
                        "args" to args
                    ),
                    "fallback" to linkedMapOf(
                        "prompt" to "Re-execute: $title (original tool: $toolName, args: $argsJson)"
                    )
                )
            }

            else -> {
                // Deterministic tool call — replay directly
                linkedMapOf(
                    "id" to stepId,
                    "title" to title,
                    "executor" to "tool",
                    "callable_tool" to toolName,
                    "tool" to toolName,
                    "args" to args
                )
            }
        }
    }

    private fun extractArgsJson(card: Map<String, Any?>): String {
        // Try various field names where arguments might be stored
        val toolCall = card["tool_call"] as? Map<*, *>
        return (toolCall?.get("arguments")
            ?: card["params"]
            ?: card["arguments"]
            ?: "{}")
            .toString()
    }

    private fun parseArgsJson(json: String): Map<String, Any?> {
        if (json.isBlank() || json == "{}") return emptyMap()
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            gson.fromJson(json, Map::class.java) as? Map<String, Any?> ?: emptyMap()
        }.getOrElse { emptyMap() }
    }

    private fun deriveCommandId(record: InternalRunLogRecord): String {
        val base = record.toolName.ifBlank { record.goal }
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(40)
            .ifBlank { "cmd" }
        val suffix = record.runId.takeLast(8).replace(Regex("[^A-Za-z0-9]"), "")
        return "oob_cmd_${base}_$suffix"
    }

    private fun functionFile(functionId: String): File {
        val safe = functionId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
        return File(functionsDir, "$safe.json")
    }

    companion object {
        private const val TAG = "WorkspaceFunctionStore"

        private val OMNIFLOW_ACTIONS = setOf(
            "click", "long_press", "scroll", "type",
            "open_app", "press_home", "press_back", "hot_key", "wait"
        )
        private val COORDINATE_ACTIONS = setOf("click", "long_press", "scroll")

        // These tools produce live perceptual output — replay as agent steps
        private val AGENT_TOOLS = setOf(
            "vlm_task", "image_picker", "browser_use",
            "android_privileged_action_screenshot", "screen_capture"
        )

        // These tools have no replay value
        private val SKIP_TOOLS = setOf(
            "notification_send", "calendar_event_create",
            "skills_loaded", "status_update"
        )
    }
}
