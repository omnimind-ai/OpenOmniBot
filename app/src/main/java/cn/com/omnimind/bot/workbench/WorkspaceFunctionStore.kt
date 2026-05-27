package cn.com.omnimind.bot.workbench

import android.content.Context
import cn.com.omnimind.bot.runlog.RunLogReusableFunctionCompiler
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

    fun functionIds(limit: Int = 500): List<String> =
        list(limit).mapNotNull { spec ->
            spec["function_id"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        }

    fun delete(functionId: String): Boolean =
        functionFile(functionId.trim()).takeIf { it.exists() }?.delete() == true

    fun clear(): Map<String, Any?> {
        val dir = functionsDir
        val files = dir.listFiles { file -> file.isFile && file.extension == "json" }
            .orEmpty()
            .toList()
        var deleted = 0
        files.forEach { file ->
            if (file.delete()) deleted += 1
        }
        return mapOf(
            "success" to true,
            "deleted_count" to deleted,
            "path" to dir.absolutePath,
        )
    }

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
     * Finished and cancelled runs may both be distilled; the compiler keeps only
     * replayable steps and treats cancellation as the natural end of the trace.
     */
    fun distillFromRun(
        context: Context,
        record: InternalRunLogRecord
    ): Map<String, Any?> {
        mirrorRunLog(record)

        val spec = compileRunLogFunctionSpec(record) ?: run {
            return mapOf("success" to false, "reason" to "no_replayable_steps")
        }

        val functionId = spec["function_id"]?.toString().orEmpty()
            .ifEmpty { deriveCommandId(record) }
        val storedSpec = if (functionId == spec["function_id"]) {
            spec
        } else {
            linkedMapOf<String, Any?>().apply {
                putAll(spec)
                put("function_id", functionId)
            }
        }

        register(storedSpec)

        // Also register in SharedPreferences so OobFunctionToolHandler finds it immediately
        runCatching { OobReusableFunctionStore.register(context, storedSpec) }

        return mapOf(
            "success" to true,
            "function_id" to functionId,
            "step_count" to (
                ((storedSpec["execution"] as? Map<*, *>)?.get("steps") as? List<*>)
                    ?.size ?: 0
                ),
            "path" to functionFile(functionId).absolutePath,
        )
    }

    internal fun compileRunLogFunctionSpec(record: InternalRunLogRecord): Map<String, Any?>? {
        return RunLogReusableFunctionCompiler.compile(record)
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
    }
}
