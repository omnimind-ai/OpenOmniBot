package cn.com.omnimind.bot.runlog

import java.util.ArrayDeque
import java.util.Locale

/**
 * Runtime state for call_function replay.
 *
 * The stack owns the list of actions that have not been executed yet. Source
 * alignment can pop already-satisfied non-key frames before the executor acts.
 */
class PendingActionStack private constructor(
    frames: List<ActionFrame>,
    val sourceAlignmentEnabled: Boolean,
) {
    private val remaining = ArrayDeque<ActionFrame>(frames)

    data class ActionFrame(
        val originalIndex: Int,
        val step: Map<String, Any?>,
        val stepId: String,
        val tool: String,
        val sourceContext: Map<String, Any?>,
        val sourcePageXml: String,
        val sourcePackage: String,
        val sourceVector: OobPageVectorSet.PageVector?,
        val role: String,
        val isKeyAction: Boolean,
        val explicitRole: Boolean,
    ) {
        val hasSourcePage: Boolean get() = sourceVector != null
    }

    fun isEmpty(): Boolean = remaining.isEmpty()

    fun peek(): ActionFrame? = remaining.peekFirst()

    fun popExecuted(): ActionFrame? = remaining.pollFirst()

    fun windowUntilNextKey(): List<ActionFrame> {
        val window = mutableListOf<ActionFrame>()
        for (frame in remaining) {
            window += frame
            if (frame.isKeyAction) break
        }
        return window
    }

    fun popSkippedUntil(target: ActionFrame): List<ActionFrame> {
        val skipped = mutableListOf<ActionFrame>()
        while (remaining.isNotEmpty() && remaining.peekFirst() != target) {
            remaining.pollFirst()?.let { skipped += it } ?: break
        }
        return skipped
    }

    companion object {
        fun fromSteps(
            steps: List<Map<String, Any?>>,
            functionSpec: Map<String, Any?>,
            originalSpec: Map<String, Any?> = emptyMap(),
        ): PendingActionStack {
            val frames = steps.mapIndexed { index, step ->
                val role = annotatedActionRole(functionSpec, originalSpec, step, index)
                    ?: defaultActionRole(actionNameForStep(step))
                val sourceContext = sourceContextForStep(step)
                val srcCtx = mapArg(sourceContext["src_ctx"])
                val srcXml = pageXmlFromContext(srcCtx)
                val srcPackage = firstNonBlank(srcCtx["package_name"], srcCtx["packageName"])
                val sourceVector = srcXml.takeIf { it.isNotBlank() }?.let {
                    OobPageVectorSet.encode(xml = it, packageName = srcPackage)
                }
                ActionFrame(
                    originalIndex = index,
                    step = step,
                    stepId = firstNonBlank(step["id"], step["step_id"], step["stepId"])
                        .ifBlank { "step_${index + 1}" },
                    tool = actionNameForStep(step),
                    sourceContext = sourceContext,
                    sourcePageXml = srcXml,
                    sourcePackage = srcPackage,
                    sourceVector = sourceVector,
                    role = role,
                    isKeyAction = role == "semantic",
                    explicitRole = annotatedActionRole(functionSpec, originalSpec, step, index) != null,
                )
            }
            val hasExplicitKeyAction = frames.any { it.isKeyAction && it.explicitRole }
            return PendingActionStack(
                frames = frames,
                sourceAlignmentEnabled = hasExplicitKeyAction,
            )
        }

        private fun sourceContextForStep(step: Map<String, Any?>): Map<String, Any?> =
            mapArg(step["source_context"]).ifEmpty { mapArg(mapArg(step["args"])["source_context"]) }

        private fun pageXmlFromContext(context: Map<String, Any?>): String =
            firstNonBlank(
                context["page"],
                context["xml"],
                context["observation_xml"],
                context["observationXml"],
            )

        private fun actionNameForStep(step: Map<String, Any?>): String {
            val sourceContext = sourceContextForStep(step)
            val sourceAction = mapArg(sourceContext["action"])
            val raw = firstNonBlank(
                step["omniflow_action"],
                step["local_action"],
                step["tool"],
                step["callable_tool"],
                step["type"],
                sourceAction["tool"],
            )
            return RunLogReplayPolicy.omniflowActionForToolName(raw)
                ?: RunLogReplayPolicy.normalizeToolName(raw).ifBlank { "unknown" }
        }

        private fun annotatedActionRole(
            functionSpec: Map<String, Any?>,
            originalSpec: Map<String, Any?>,
            step: Map<String, Any?>,
            stepIndex: Int,
        ): String? {
            listOf(
                mapArg(step["raw_action_edge"]),
                mapArg(step["udeg_edge"]),
                mapArg(step["cleanup_annotation"]),
                mapArg(mapArg(step["metadata"])["raw_action_edge"]),
            ).forEach { annotation ->
                normalizeActionRole(
                    firstNonBlank(
                        annotation["role"],
                        annotation["raw_edge_role"],
                        annotation["udeg_edge_role"],
                        annotation["action_role"],
                        annotation["cleanup_action"],
                        annotation["cleanupAction"],
                        annotation["usefulness"],
                        annotation["category"],
                        annotation["kind"],
                    )
                )?.let { return it }
            }

            for (spec in listOf(functionSpec, originalSpec)) {
                val agentReuse = mapArg(spec["agent_reuse"])
                val keyActions = listArg(agentReuse["key_actions"]) + listArg(agentReuse["keyActions"])
                if (keyActions.any { matchesStepReference(it, step, stepIndex) }) return "semantic"
                val checkerAssets = listArg(agentReuse["checker_assets"]) + listArg(agentReuse["checkerAssets"])
                if (checkerAssets.any { matchesStepReference(it, step, stepIndex) }) return "checker_candidate"
                val noiseActions = listArg(agentReuse["noise_actions"]) + listArg(agentReuse["noiseActions"])
                if (noiseActions.any { matchesStepReference(it, step, stepIndex) }) return "noise"
            }
            return null
        }

        private fun matchesStepReference(
            rawReference: Any?,
            step: Map<String, Any?>,
            stepIndex: Int,
        ): Boolean {
            when (rawReference) {
                is Number -> return rawReference.toInt() == stepIndex
                is String -> {
                    val value = rawReference.trim()
                    return value == stepIndex.toString() ||
                        value == firstNonBlank(step["id"], step["step_id"], step["stepId"])
                }
            }
            val reference = mapArg(rawReference)
            if (reference.isEmpty()) return false
            val index = intArg(reference["step_index"], reference["stepIndex"], reference["index"], defaultValue = -1)
            if (index == stepIndex) return true
            val stepId = firstNonBlank(reference["step_id"], reference["stepId"], reference["id"])
            return stepId.isNotBlank() && stepId == firstNonBlank(step["id"], step["step_id"], step["stepId"])
        }

        private fun normalizeActionRole(rawRole: String): String? =
            when (rawRole.trim().lowercase(Locale.US)) {
                "navigation", "navigate", "route", "route_safe" -> "navigation"
                "semantic", "key", "key_action", "key_function" -> "semantic"
                "checker",
                "checker_candidate",
                "runtime_checker",
                "optional_checker",
                "conditional_checker",
                "conditional_obstruction",
                "popup_checker",
                "ad_checker" -> "checker_candidate"
                "noise",
                "ignore",
                "ignored",
                "drop_candidate",
                "merge_candidate",
                "probably_useless",
                "redundant_candidate",
                "noop",
                "no_op" -> "noise"
                "unknown" -> "unknown"
                else -> null
            }

        private fun defaultActionRole(actionType: String): String =
            when (actionType) {
                "open_app", "press_back", "press_home" -> "navigation"
                else -> "unknown"
            }

        private fun mapArg(value: Any?): Map<String, Any?> =
            when (value) {
                is Map<*, *> -> value.entries.associate { (key, raw) -> key.toString() to raw }
                else -> emptyMap()
            }

        private fun listArg(value: Any?): List<Any?> =
            when (value) {
                is List<*> -> value
                is Array<*> -> value.toList()
                else -> emptyList()
            }

        private fun intArg(vararg values: Any?, defaultValue: Int): Int {
            values.forEach { value ->
                when (value) {
                    is Number -> return value.toInt()
                    is String -> value.trim().toIntOrNull()?.let { return it }
                }
            }
            return defaultValue
        }

        private fun firstNonBlank(vararg values: Any?): String {
            values.forEach { value ->
                val text = value?.toString()?.trim().orEmpty()
                if (text.isNotEmpty()) return text
            }
            return ""
        }
    }
}
