package cn.com.omnimind.bot.omniflow

/**
 * Packages Function + RunLog evidence for the agent-side update_function skill.
 *
 * The update service owns persistence and patch application. This class owns
 * only the read-only context and prompt contract that asks the agent to analyze
 * evidence before saving an update.
 */
class OobFunctionRunLogEvidencePackager {
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
            2. Mark every useful action as required_action, optional_checker, noise, duplicate, failed_action, or success_evidence.
            3. Identify why the RunLog succeeded or failed.
            4. Produce a structured analysis object in this exact shape:
            {
              "summary": "这次 RunLog 说明 Function 为什么成功/失败",
              "step_findings": [
                {
                  "function_step_index": 1,
                  "runlog_card_index": 3,
                  "label": "点击外卖入口",
                  "role": "required_action | optional_checker | noise | duplicate | failed_action | success_evidence",
                  "reason": "为什么这样判断"
                }
              ],
              "failure_reason": {
                "code": "wrong_target | target_missing | ad_interruption | repeated_input | unstable_coordinate | unknown",
                "message": "具体原因"
              },
              "recommended_patch": {
                "ops": []
              }
            }
            5. Call update_function with functionId="$functionId", run_id="$runId", analysis=<that object>, and patch=<recommended_patch> only when the evidence is clear.

            Constraints:
            - If unsure, do not change the main path; return a suggested patch or an empty recommended_patch.ops.
            - Ads, skip buttons, close popups, and other transient interruptions are optional_checker evidence, not mandatory steps.
            - wait, pure perception wrappers, failed cards, and repeated input are noise unless they explain a concrete failure.
            - Successful RunLogs may improve description, step title/summary, selector hints, and evidence metadata.
            - Failed RunLogs may only change a step when there is clear evidence.
        """.trimIndent()
    }

    private fun firstNonBlank(vararg values: Any?): String {
        for (value in values) {
            val text = value?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) return text
        }
        return ""
    }

    private fun mapArg(value: Any?): Map<String, Any?> =
        when (value) {
            is Map<*, *> -> linkedMapOf<String, Any?>().apply {
                value.forEach { (key, item) ->
                    if (key != null) put(key.toString(), item)
                }
            }
            else -> emptyMap()
        }

    private fun listArg(value: Any?): List<Any?> =
        when (value) {
            is List<*> -> value
            is Array<*> -> value.toList()
            else -> emptyList()
        }
}
