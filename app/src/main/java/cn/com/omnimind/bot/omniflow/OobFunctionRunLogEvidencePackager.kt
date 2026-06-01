package cn.com.omnimind.bot.omniflow

import cn.com.omnimind.bot.omniflow.OobFunctionJson.firstNonBlank
import cn.com.omnimind.bot.omniflow.OobFunctionJson.listArg
import cn.com.omnimind.bot.omniflow.OobFunctionJson.mapArg

/**
 * Packages Function + RunLog evidence for the agent-side update_function skill.
 *
 * The update service owns persistence and patch application. This class owns
 * only the read-only context and prompt contract that asks the agent to analyze
 * evidence before saving an update.
 */
class OobFunctionRunLogEvidencePackager {
    private val analysis = OobFunctionRunLogAnalysisContract

    fun analysisContext(
        functionId: String,
        functionSpec: Map<String, Any?>,
        runLogTimeline: Map<String, Any?>,
        instruction: String,
    ): Map<String, Any?> {
        val execution = mapArg(functionSpec["execution"])
        return linkedMapOf(
            "schema_version" to "oob.function_runlog_analysis_context.v1",
            "function_id" to functionId,
            "user_instruction" to instruction.takeIf { it.isNotBlank() },
            "function" to linkedMapOf(
                "function_id" to firstNonBlank(functionSpec["function_id"], functionId),
                "name" to firstNonBlank(functionSpec["name"]),
                "description" to firstNonBlank(functionSpec["description"]),
                "parameters" to listArg(functionSpec["parameters"]),
                "steps" to listArg(execution["steps"]),
                "metadata" to mapArg(functionSpec["metadata"]),
            ),
            "runlog" to linkedMapOf(
                "run_id" to firstNonBlank(runLogTimeline["run_id"]),
                "goal" to firstNonBlank(runLogTimeline["goal"]),
                "run_success" to (runLogTimeline["run_success"] == true),
                "run_status" to firstNonBlank(runLogTimeline["run_status"]),
                "done_reason" to firstNonBlank(runLogTimeline["done_reason"]),
                "error_message" to firstNonBlank(runLogTimeline["error_message"]),
                "step_count" to runLogTimeline["step_count"],
                "duration_ms" to runLogTimeline["duration_ms"],
                "diagnostics" to mapArg(runLogTimeline["diagnostics"]).takeIf { it.isNotEmpty() },
                "cards" to listArg(runLogTimeline["cards"]),
            ).filterValues { it != null },
        ).filterValues { it != null }
    }

    fun agentPrompt(context: Map<String, Any?>): String {
        val functionId = firstNonBlank(context["function_id"])
        val runLog = mapArg(context["runlog"])
        val runId = firstNonBlank(runLog["run_id"])
        return """
            Analyze this OOB Function with the provided RunLog evidence, then call update_function again with analysis and the smallest safe patch.

            Required workflow:
            1. Compare function.steps with runlog.cards.
            2. Mark every useful action as ${analysis.roleChoiceText}.
            3. Identify why the RunLog succeeded or failed.
            4. Produce a structured analysis object in this exact shape:
            {
              "${analysis.FIELD_SUMMARY}": "这次 RunLog 说明 Function 为什么成功/失败",
              "${analysis.FIELD_STEP_FINDINGS}": [
                {
                  "${analysis.FIELD_FUNCTION_STEP_INDEX}": 1,
                  "${analysis.FIELD_RUNLOG_CARD_INDEX}": 3,
                  "${analysis.FIELD_LABEL}": "点击外卖入口",
                  "${analysis.FIELD_ROLE}": "${analysis.roleChoiceText}",
                  "${analysis.FIELD_REASON}": "为什么这样判断"
                }
              ],
              "${analysis.FIELD_FAILURE_REASON}": {
                "${analysis.FIELD_CODE}": "${analysis.failureCodeChoiceText}",
                "${analysis.FIELD_MESSAGE}": "具体原因"
              },
              "${analysis.FIELD_RECOMMENDED_PATCH}": {
                "${analysis.FIELD_OPS}": []
              }
            }
            5. Call update_function with functionId="$functionId", run_id="$runId", analysis=<that object>, and patch=<${analysis.FIELD_RECOMMENDED_PATCH}> only when the evidence is clear.

            Constraints:
            - If unsure, do not change the main path; return a suggested patch or an empty ${analysis.FIELD_RECOMMENDED_PATCH}.${analysis.FIELD_OPS}.
            - Ads, skip buttons, close popups, and other transient interruptions are ${analysis.ROLE_OPTIONAL_CHECKER} evidence, not mandatory steps.
            - wait, pure perception wrappers, failed cards, and repeated input are ${analysis.ROLE_NOISE} unless they explain a concrete failure.
            - Successful RunLogs may improve description, step title/summary, selector hints, and evidence metadata.
            - Failed RunLogs may only change a step when there is clear evidence.
        """.trimIndent()
    }

}
