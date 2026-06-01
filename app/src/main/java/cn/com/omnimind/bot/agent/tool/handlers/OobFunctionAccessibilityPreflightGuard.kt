package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.bot.runlog.OmniflowActionRuntime
import cn.com.omnimind.bot.runlog.OmniflowStepExecutor
import cn.com.omnimind.bot.runlog.RunLogReplayPolicy

/**
 * Blocks deterministic replay before the step loop when required accessibility
 * runtime permissions are unavailable.
 */
class OobFunctionAccessibilityPreflightGuard(
    private val stepClassifier: OobFunctionStepClassifier,
    private val runResultBuilder: OobFunctionRunResultBuilder,
) {
    fun failureIfBlocked(
        functionId: String,
        spec: Map<String, Any?>,
        auditRunId: String,
        startedAtMs: Long,
        steps: List<Map<String, Any?>>,
    ): Map<String, Any?>? {
        val indexedStep = steps.withIndex().firstOrNull { (_, step) ->
            !stepClassifier.isSkippedLegacyStep(step) &&
                OmniflowStepExecutor.requiresAccessibility(step)
        } ?: return null
        if (OmniflowActionRuntime.backend.isReady()) return null

        val step = indexedStep.value
        val stepId = step["id"]?.toString() ?: "step_${indexedStep.index + 1}"
        val action = OmniflowStepExecutor.actionNameForStep(step)
        val message = "请先开启无障碍权限，复用指令才能执行点击、滑动和输入。"
        return runResultBuilder.failedRun(
            functionId = functionId,
            spec = spec,
            auditRunId = auditRunId,
            startedAtMs = startedAtMs,
            errorCode = "OOB_ACCESSIBILITY_REQUIRED",
            errorMessage = message,
            extras = linkedMapOf(
                "step_count" to steps.size,
                "required_permission" to "accessibility",
                "missing_permissions" to listOf("accessibility"),
                "blocked_step_index" to indexedStep.index,
                "step_results" to listOf(
                    runResultBuilder.failureStep(
                        stepId = stepId,
                        tool = action,
                        executor = RunLogReplayPolicy.EXECUTOR_OMNIFLOW,
                        summary = message,
                        errorCode = "OOB_ACCESSIBILITY_REQUIRED",
                        extras = linkedMapOf(
                            "index" to indexedStep.index,
                            "required_permission" to "accessibility",
                        )
                    )
                )
            )
        )
    }
}
