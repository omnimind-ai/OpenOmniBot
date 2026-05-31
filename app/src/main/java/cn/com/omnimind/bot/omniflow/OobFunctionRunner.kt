package cn.com.omnimind.bot.omniflow

import android.content.Context
import cn.com.omnimind.baselib.runlog.OobReusableFunctionStore
import cn.com.omnimind.bot.agent.tool.handlers.OobFunctionToolHandler
import cn.com.omnimind.bot.agent.tool.handlers.SharedHelper
import cn.com.omnimind.bot.workbench.WorkspaceFunctionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Executes a registered OOB Function after public tool facades have completed
 * argument parsing, guard checks, and response-shape decisions.
 */
class OobFunctionRunner(
    private val context: Context,
    private val workspaceFunctionStore: WorkspaceFunctionStore,
    private val functionRepository: OobFunctionRepository,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
    }

    suspend fun execute(
        functionId: String,
        arguments: Map<String, Any?>,
        allowAgentFallback: Boolean,
        resumeFromStep: Int = 0,
        fallbackSessionId: String = "",
        fallbackAttempt: Int = 0,
    ): Map<String, Any?> = withContext(Dispatchers.Default) {
        val timing = FunctionExecutionTiming()
        val spec = timing.measure("load_function_spec_ms") {
            functionRepository.get(functionId)
        }
            ?: return@withContext errorPayload(
                code = "OOB_FUNCTION_NOT_FOUND",
                message = "OOB reusable function not found: $functionId",
                functionId = functionId
            ).let { attachExecutionTiming(it, timing) }
        val missing = timing.measure("check_arguments_ms") {
            OobReusableFunctionStore.missingRequiredArguments(spec, arguments)
        }
        if (missing.isNotEmpty()) {
            return@withContext errorPayload(
                code = "OOB_FUNCTION_ARGUMENTS_MISSING",
                message = "Missing required arguments: ${missing.joinToString(", ")}",
                functionId = functionId
            ).let { attachExecutionTiming(it + linkedMapOf("missing_required_arguments" to missing), timing) }
        }
        val materialized = timing.measure("materialize_function_ms") {
            OobReusableFunctionStore.materialize(spec, arguments)
        }
        val runner = timing.measure("create_runner_ms") {
            OobFunctionToolHandler(
                context = context,
                helper = SharedHelper(context, json)
            ).apply {
                this.workspaceFunctionStore = workspaceFunctionStore
            }
        }
        val payload = runCatching {
            timing.measureSuspend("run_materialized_function_ms") {
                runner.runMaterializedFunction(
                    functionId = functionId,
                    spec = spec,
                    materializedSpec = materialized,
                    allowAgentFallback = allowAgentFallback,
                    allowToolDelegationWithoutRouter = false,
                    resumeFromStep = resumeFromStep,
                    fallbackSessionId = fallbackSessionId,
                    fallbackAttempt = fallbackAttempt
                )
            }
        }.getOrElse { error ->
            errorPayload(
                code = "OOB_FUNCTION_RUN_FAILED",
                message = error.message.orEmpty(),
                functionId = functionId
            )
        }
        attachExecutionTiming(payload, timing)
    }

    private fun attachExecutionTiming(
        payload: Map<String, Any?>,
        timing: FunctionExecutionTiming,
    ): Map<String, Any?> {
        val toolkitTiming = timing.finish()
        val toolkitPhaseMs = mapArg(toolkitTiming["phase_ms"])
        val existingTiming = mapArg(payload["timing"])
        val runnerSource = existingTiming["source"]?.toString().orEmpty()
        val runnerPhaseMs = mapArg(existingTiming["phase_ms"])
        val runnerStartedAtMs = longArg(existingTiming["started_at_ms"])
        val runnerFinishedAtMs = longArg(existingTiming["finished_at_ms"])
        val runnerDurationMs = longArg(
            existingTiming["runner_duration_ms"],
            existingTiming["duration_ms"],
        )
        val startupPhaseMs = linkedMapOf<String, Any?>()
        listOf(
            "load_function_spec_ms",
            "check_arguments_ms",
            "materialize_function_ms",
            "create_runner_ms",
        ).forEach { phaseName ->
            startupPhaseMs[phaseName] = longArg(toolkitPhaseMs[phaseName])
        }
        if (runnerPhaseMs.isNotEmpty()) {
            startupPhaseMs["runner_pre_step_loop_ms"] = longArg(runnerPhaseMs["pre_step_loop_ms"])
        }
        val startupDurationMs = startupPhaseMs.values.sumOf { longArg(it) }
        val mergedTiming = linkedMapOf<String, Any?>().apply {
            putAll(existingTiming)
            if (runnerSource.isNotBlank()) put("runner_source", runnerSource)
            if (runnerStartedAtMs > 0L) put("runner_started_at_ms", runnerStartedAtMs)
            if (runnerFinishedAtMs > 0L) put("runner_finished_at_ms", runnerFinishedAtMs)
            if (runnerDurationMs > 0L) put("runner_duration_ms", runnerDurationMs)
            if (runnerPhaseMs.isNotEmpty()) put("runner_phase_ms", runnerPhaseMs)
            put("source", "oob_function_execute")
            put("started_at_ms", toolkitTiming["started_at_ms"])
            put("finished_at_ms", toolkitTiming["finished_at_ms"])
            put("duration_ms", toolkitTiming["duration_ms"])
            put("phase_ms", toolkitPhaseMs)
            put("startup_phase_ms", startupPhaseMs)
            put("startup_duration_ms", startupDurationMs)
        }
        return linkedMapOf<String, Any?>().apply {
            putAll(payload)
            put("timing", mergedTiming)
        }
    }

    private fun errorPayload(
        code: String,
        message: String,
        functionId: String = ""
    ): Map<String, Any?> = linkedMapOf(
        "success" to false,
        "error_code" to code,
        "error_message" to message,
        "function_id" to functionId,
        "function_kind" to "oob_reusable_function",
        "asset_state" to "native_local"
    )

    private class FunctionExecutionTiming {
        private val startedAtNanos = System.nanoTime()
        val startedAtMs: Long = System.currentTimeMillis()
        private val phases = linkedMapOf<String, Long>()

        fun <T> measure(phaseName: String, block: () -> T): T {
            val phaseStartedAtNanos = System.nanoTime()
            return try {
                block()
            } finally {
                phases[phaseName] = elapsedMs(phaseStartedAtNanos)
            }
        }

        suspend fun <T> measureSuspend(phaseName: String, block: suspend () -> T): T {
            val phaseStartedAtNanos = System.nanoTime()
            return try {
                block()
            } finally {
                phases[phaseName] = elapsedMs(phaseStartedAtNanos)
            }
        }

        fun finish(): Map<String, Any?> {
            val finishedAtMs = System.currentTimeMillis()
            val completedPhases = linkedMapOf<String, Long>()
            listOf(
                "load_function_spec_ms",
                "check_arguments_ms",
                "materialize_function_ms",
                "create_runner_ms",
                "run_materialized_function_ms",
            ).forEach { phaseName ->
                completedPhases[phaseName] = phases[phaseName] ?: 0L
            }
            phases.forEach { (phaseName, durationMs) ->
                completedPhases.putIfAbsent(phaseName, durationMs)
            }
            return linkedMapOf(
                "source" to "oob_function_execute",
                "started_at_ms" to startedAtMs,
                "finished_at_ms" to finishedAtMs,
                "duration_ms" to elapsedMs(startedAtNanos),
                "phase_ms" to completedPhases,
            )
        }

        private fun elapsedMs(startedAtNanos: Long): Long =
            ((System.nanoTime() - startedAtNanos) / 1_000_000L).coerceAtLeast(0L)
    }

    private companion object {
        fun mapArg(value: Any?): Map<String, Any?> {
            @Suppress("UNCHECKED_CAST")
            return (value as? Map<*, *>)?.entries
                ?.associate { it.key.toString() to it.value }
                ?: emptyMap()
        }

        fun longArg(vararg values: Any?): Long {
            values.forEach { value ->
                when (value) {
                    is Number -> return value.toLong()
                    is String -> value.trim().toLongOrNull()?.let { return it }
                }
            }
            return 0L
        }
    }
}
