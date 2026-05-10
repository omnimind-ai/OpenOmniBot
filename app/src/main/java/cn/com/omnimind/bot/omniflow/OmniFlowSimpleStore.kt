package cn.com.omnimind.bot.omniflow

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class OmniFlowSimpleStore internal constructor(
    private val dataDir: File,
    private val json: Json = defaultJson
) {
    constructor(
        context: Context,
        json: Json = defaultJson
    ) : this(File(context.filesDir, "omniflow_simple_utg"), json)

    private val stateFile = File(dataDir, "state.json")
    private val lock = Any()

    fun status(): Map<String, Any?> {
        val state = readState()
        return linkedMapOf(
            "project_id" to OMNIFLOW_SIMPLE_PROJECT_ID,
            "provider_url" to OMNIFLOW_SIMPLE_PROVIDER_URL,
            "run_log_count" to state.runLogs.size,
            "function_count" to state.functions.size,
            "updated_at" to state.updatedAtMs.toIsoString(),
            "data_path" to stateFile.absolutePath,
            "store_path" to stateFile.absolutePath
        )
    }

    fun listRunLogs(): List<OmniFlowSimpleRunLog> {
        return readState().runLogs.sortedByDescending { it.createdAtMs }
    }

    fun listFunctions(): List<OmniFlowSimpleFunction> {
        return readState().functions.sortedByDescending { it.updatedAtMs }
    }

    fun getRunLog(runId: String): OmniFlowSimpleRunLog? {
        return readState().runLogs.firstOrNull { it.runId == runId }
    }

    fun getFunction(functionId: String): OmniFlowSimpleFunction? {
        return readState().functions.firstOrNull { it.functionId == functionId }
    }

    fun saveRunLog(runLog: OmniFlowSimpleRunLog): OmniFlowSimpleRunLog {
        mutate { state ->
            state.copy(runLogs = listOf(runLog) + state.runLogs.filterNot { it.runId == runLog.runId })
        }
        return runLog
    }

    fun saveFunction(function: OmniFlowSimpleFunction): OmniFlowSimpleFunction {
        mutate { state ->
            state.copy(functions = listOf(function) + state.functions.filterNot {
                it.functionId == function.functionId
            })
        }
        return function
    }

    fun updateRunStats(functionId: String, runId: String, success: Boolean): OmniFlowSimpleFunction? {
        var updated: OmniFlowSimpleFunction? = null
        mutate { state ->
            val functions = state.functions.map { function ->
                if (function.functionId != functionId) {
                    function
                } else {
                    function.copy(
                        runStats = function.runStats.record(runId, success),
                        updatedAtMs = System.currentTimeMillis()
                    ).also { updated = it }
                }
            }
            state.copy(functions = functions)
        }
        return updated
    }

    fun deleteFunction(functionId: String): Boolean {
        var deleted = false
        mutate { state ->
            val functions = state.functions.filterNot {
                val matches = it.functionId == functionId
                deleted = deleted || matches
                matches
            }
            state.copy(functions = functions)
        }
        return deleted
    }

    fun deleteRunLog(runId: String): Boolean {
        var deleted = false
        mutate { state ->
            val runLogs = state.runLogs.filterNot {
                val matches = it.runId == runId
                deleted = deleted || matches
                matches
            }
            state.copy(runLogs = runLogs)
        }
        return deleted
    }

    private fun readState(): OmniFlowSimpleState {
        synchronized(lock) {
            return readStateLocked()
        }
    }

    private fun mutate(block: (OmniFlowSimpleState) -> OmniFlowSimpleState) {
        synchronized(lock) {
            val next = block(readStateLocked()).copy(updatedAtMs = System.currentTimeMillis())
            writeStateLocked(next)
        }
    }

    private fun readStateLocked(): OmniFlowSimpleState {
        if (!stateFile.exists()) return OmniFlowSimpleState()
        return runCatching {
            json.decodeFromString<OmniFlowSimpleState>(stateFile.readText())
        }.getOrElse {
            OmniFlowSimpleState()
        }
    }

    private fun writeStateLocked(state: OmniFlowSimpleState) {
        if (!dataDir.exists()) dataDir.mkdirs()
        stateFile.writeText(json.encodeToString(state))
    }

    companion object {
        val defaultJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
            prettyPrint = true
        }
    }
}
