package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.bot.runlog.OmniflowActionRuntime
import cn.com.omnimind.bot.runlog.OobPageVectorSet
import cn.com.omnimind.bot.runlog.OobUdegNodeStore
import cn.com.omnimind.bot.runlog.PendingActionStack
import cn.com.omnimind.bot.runlog.RunLogReplayPolicy

/**
 * Aligns the pending replay stack with the currently visible page before a
 * step is executed. This keeps page-vector skip/fail policy out of the main
 * deterministic replay loop.
 */
class OobFunctionSourceAlignmentController(
    private val runResultBuilder: OobFunctionRunResultBuilder = OobFunctionRunResultBuilder(),
) {
    fun align(stack: PendingActionStack): Result {
        if (!stack.sourceAlignmentEnabled) return Result()
        val top = stack.peek() ?: return Result()
        val window = stack.windowUntilNextKey()
        val candidates = window.filter { it.hasSourcePage }
        if (candidates.isEmpty()) return Result()

        val observedAtMs = System.currentTimeMillis()
        val currentXml = runCatching { OmniflowActionRuntime.backend.currentXml()?.trim().orEmpty() }
            .getOrDefault("")
        if (currentXml.isBlank()) return Result()
        val currentPackage = runCatching { OmniflowActionRuntime.backend.currentPackageName()?.trim().orEmpty() }
            .getOrDefault("")
        val currentVector = OobPageVectorSet.encode(xml = currentXml, packageName = currentPackage)
            ?: return Result()

        var bestFrame: PendingActionStack.ActionFrame? = null
        var bestScore = Float.NEGATIVE_INFINITY
        candidates.forEach { frame ->
            val sourceVector = frame.sourceVector ?: return@forEach
            val score = OobPageVectorSet.cosine(currentVector.vector, sourceVector.vector)
            if (score > bestScore) {
                bestScore = score
                bestFrame = frame
            }
        }

        val matched = bestFrame?.takeIf { bestScore >= OobUdegNodeStore.STRONG_PAGE_MATCH_SCORE }
        if (matched == null) {
            if (!top.hasSourcePage) return Result()
            val failedAtMs = System.currentTimeMillis()
            return Result(
                failureResult = runResultBuilder.failureStep(
                    stepId = top.stepId,
                    tool = top.tool,
                    executor = RunLogReplayPolicy.EXECUTOR_OMNIFLOW,
                    summary = "Current page does not match the pending function source window",
                    errorCode = "OOB_SOURCE_ALIGNMENT_MISS",
                    extras = mapOf(
                        "index" to top.originalIndex,
                        "source_alignment" to linkedMapOf(
                            "matched" to false,
                            "best_score" to bestScore.takeIf { it.isFinite() },
                            "min_score" to OobUdegNodeStore.STRONG_PAGE_MATCH_SCORE,
                            "window" to window.map(::frameSummary),
                            "current" to linkedMapOf(
                                "node_id" to currentVector.nodeId,
                                "package_name" to currentVector.packageName.takeIf { it.isNotBlank() },
                                "signature" to currentVector.signature,
                                "observed_at_ms" to observedAtMs,
                            ).filterValues { it != null },
                        ).filterValues { it != null },
                        "recovery" to linkedMapOf(
                            "refetched_current_page" to true,
                            "reason" to "source_alignment_miss",
                            "navigate_recovery_available" to false,
                            "target_window" to window.map(::frameSummary),
                        ),
                        "started_at_ms" to observedAtMs,
                        "finished_at_ms" to failedAtMs,
                        "duration_ms" to (failedAtMs - observedAtMs).coerceAtLeast(0),
                    ),
                )
            )
        }
        if (matched == top) return Result()

        val skipped = stack.popSkippedUntil(matched)
        val skippedAtMs = System.currentTimeMillis()
        val skippedResults = skipped.map { frame ->
            linkedMapOf<String, Any?>(
                "step_id" to frame.stepId,
                "index" to frame.originalIndex,
                "tool" to frame.tool,
                "executor" to RunLogReplayPolicy.EXECUTOR_OMNIFLOW,
                "model_free" to true,
                "skipped" to true,
                "skipped_by_source_alignment" to true,
                "success" to true,
                "summary" to "Skipped by source alignment: current page already matches step ${matched.originalIndex + 1}",
                "source_alignment" to linkedMapOf(
                    "matched" to true,
                    "matched_step_index" to matched.originalIndex,
                    "matched_step_id" to matched.stepId,
                    "page_similarity" to bestScore,
                    "min_score" to OobUdegNodeStore.STRONG_PAGE_MATCH_SCORE,
                    "current_node_id" to currentVector.nodeId,
                    "current_package" to currentVector.packageName.takeIf { it.isNotBlank() },
                    "target_frame" to frameSummary(matched),
                ).filterValues { it != null },
                "started_at_ms" to observedAtMs,
                "finished_at_ms" to skippedAtMs,
                "duration_ms" to (skippedAtMs - observedAtMs).coerceAtLeast(0),
            )
        }
        return Result(skippedResults = skippedResults)
    }

    private fun frameSummary(
        frame: PendingActionStack.ActionFrame,
    ): Map<String, Any?> = linkedMapOf(
        "step_index" to frame.originalIndex,
        "step_id" to frame.stepId,
        "tool" to frame.tool,
        "role" to frame.role,
        "is_key_action" to frame.isKeyAction,
        "source_package" to frame.sourcePackage.takeIf { it.isNotBlank() },
        "source_node_id" to frame.sourceVector?.nodeId,
        "source_signature" to frame.sourceVector?.signature,
    ).filterValues { it != null }

    data class Result(
        val skippedResults: List<Map<String, Any?>> = emptyList(),
        val failureResult: Map<String, Any?>? = null,
    )
}
