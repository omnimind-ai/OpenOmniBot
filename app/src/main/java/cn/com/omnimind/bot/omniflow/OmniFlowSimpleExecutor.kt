package cn.com.omnimind.bot.omniflow

import android.content.Context
import cn.com.omnimind.assists.controller.accessibility.AccessibilityController
import cn.com.omnimind.assists.task.vlmserver.AndroidDeviceOperator
import cn.com.omnimind.assists.task.vlmserver.OperationResult
import kotlinx.coroutines.delay

interface OmniFlowSimpleActionRunner {
    suspend fun run(action: OmniFlowSimpleAction): OperationResult
}

class AndroidOmniFlowSimpleActionRunner(context: Context) : OmniFlowSimpleActionRunner {
    private val device = AndroidDeviceOperator(null, context)

    override suspend fun run(action: OmniFlowSimpleAction): OperationResult {
        if (!AccessibilityController.initController()) {
            return OperationResult(false, "Accessibility service is not ready")
        }
        return when (action.normalizedType()) {
            "click" -> device.clickCoordinate(action.floatParam("x"), action.floatParam("y"))
            "long_press" -> device.longClickCoordinate(
                action.floatParam("x"),
                action.floatParam("y"),
                action.longParam("duration_ms") ?: 1000L
            )
            "input_text" -> device.inputText(
                action.stringParam("text") ?: action.stringParam("content") ?: ""
            )
            "swipe" -> device.slideCoordinate(
                action.floatParam("x1"),
                action.floatParam("y1"),
                action.floatParam("x2"),
                action.floatParam("y2"),
                action.longParam("duration_ms")
                    ?: action.longParam("duration")?.times(1000)
                    ?: 500L
            )
            "open_app" -> device.launchApplication(
                action.stringParam("package_name")
                    ?: action.stringParam("packageName")
                    ?: return OperationResult(false, "open_app missing package_name")
            )
            "press_key" -> runKey(action)
            "wait" -> runWait(action)
            "finished" -> OperationResult(
                true,
                action.stringParam("content")?.takeIf { it.isNotBlank() } ?: "任务完成"
            )
            else -> OperationResult(false, "Unsupported simple UTG action: ${action.type}")
        }
    }

    private suspend fun runKey(action: OmniFlowSimpleAction): OperationResult {
        return when ((action.stringParam("key") ?: "").trim().uppercase()) {
            "BACK" -> device.goBack()
            "HOME" -> device.goHome()
            "ENTER" -> device.pressHotKey("ENTER")
            "" -> OperationResult(false, "press_key missing key")
            else -> device.pressHotKey(action.stringParam("key").orEmpty())
        }
    }

    private suspend fun runWait(action: OmniFlowSimpleAction): OperationResult {
        val waitMs = action.longParam("duration_ms")
            ?: action.longParam("duration")?.times(1000)
            ?: 1000L
        delay(waitMs.coerceIn(0, 30_000))
        return OperationResult(true, "等待${waitMs}ms")
    }
}

class OmniFlowSimpleExecutor(
    private val store: OmniFlowSimpleStore,
    private val provider: OmniFlowSimpleProvider? = OmniFlowSimpleProviderClient(),
    private val runner: OmniFlowSimpleActionRunner
) {
    suspend fun execute(
        functionId: String,
        input: Map<String, Any?> = emptyMap()
    ): OmniFlowSimpleExecutionResult {
        val function = store.getFunction(functionId)
            ?: return OmniFlowSimpleExecutionResult(
                success = false,
                route = "missing",
                functionId = functionId,
                runId = "",
                message = "Function not found: $functionId"
            )
        val providerHit = provider?.execute(function, input)
        if (providerHit != null) {
            val runId = "provider_${System.currentTimeMillis()}"
            val providerSuccess = providerHit.response.providerSuccess()
            store.updateRunStats(function.functionId, runId, providerSuccess)
            return OmniFlowSimpleExecutionResult(
                success = providerSuccess,
                route = "provider",
                functionId = providerHit.functionId ?: function.functionId,
                runId = runId,
                message = "Executed by OmniFlow provider",
                providerResponse = providerHit.response
            )
        }

        val runId = "local_${System.currentTimeMillis()}"
        val stepResults = mutableListOf<Map<String, Any?>>()
        var success = true
        var message = "Local replay completed"
        for ((index, action) in function.actions.withIndex()) {
            if (action.normalizedType() !in SUPPORTED_ACTION_TYPES) {
                val unsupportedMessage = "Unsupported simple UTG action: ${action.type}"
                stepResults.add(
                    linkedMapOf(
                        "index" to index,
                        "action_type" to action.normalizedType(),
                        "success" to false,
                        "message" to unsupportedMessage,
                        "tool_call" to action.toToolCallMap()
                    )
                )
                success = false
                message = unsupportedMessage
                break
            }
            val result = runner.run(action)
            stepResults.add(
                linkedMapOf(
                    "index" to index,
                    "action_type" to action.normalizedType(),
                    "success" to result.success,
                    "message" to result.message,
                    "tool_call" to action.toToolCallMap()
                )
            )
            if (!result.success) {
                success = false
                message = result.message
                break
            }
            if (action.normalizedType() == "finished") {
                message = result.message
                break
            }
        }
        store.updateRunStats(function.functionId, runId, success)
        return OmniFlowSimpleExecutionResult(
            success = success,
            route = "local_replay",
            functionId = function.functionId,
            runId = runId,
            message = message,
            steps = stepResults
        )
    }
}

private fun OmniFlowSimpleAction.stringParam(key: String): String? {
    return params[key]?.toInteropValue()?.toString()
}

private fun OmniFlowSimpleAction.longParam(key: String): Long? {
    val value = params[key]?.toInteropValue()
    return when (value) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }
}

private fun OmniFlowSimpleAction.floatParam(key: String): Float {
    val value = params[key]?.toInteropValue()
    return when (value) {
        is Number -> value.toFloat()
        is String -> value.toFloatOrNull()
        else -> null
    } ?: 0f
}

private fun Map<String, Any?>.providerSuccess(): Boolean {
    val value = this["success"]
    return when (value) {
        is Boolean -> value
        is String -> value.equals("true", ignoreCase = true)
        null -> true
        else -> true
    }
}
