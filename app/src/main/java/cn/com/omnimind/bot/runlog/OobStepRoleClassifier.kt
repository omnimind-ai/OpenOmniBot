package cn.com.omnimind.bot.runlog

import java.util.Locale

/**
 * Offline step annotation classifier used by RunLog analysis, checker mining,
 * and UDEG metadata. Runtime replay decisions should use OobActionCodec or
 * replay policy instead of branching on these roles.
 */
object OobStepRoleClassifier {
    const val ROLE_NAVIGATION = "navigation"
    const val ROLE_SEMANTIC = "semantic"
    const val ROLE_CHECKER_CANDIDATE = "checker_candidate"
    const val ROLE_NOISE = "noise"
    const val ROLE_UNKNOWN = "unknown"

    data class Classification(
        val role: String,
        val explicit: Boolean,
    )

    fun classify(
        functionSpec: Map<String, Any?>,
        step: Map<String, Any?>,
        stepIndex: Int,
        originalSpec: Map<String, Any?> = emptyMap(),
        actionType: String = OobActionCodec.actionNameForStep(step),
        actionSummary: Map<String, Any?> = OobActionCodec.actionArgsSummary(step),
    ): Classification {
        explicitRole(listOf(functionSpec, originalSpec), step, stepIndex)?.let {
            return Classification(it, explicit = true)
        }
        return Classification(defaultRole(actionType, actionSummary), explicit = false)
    }

    fun explicitRole(
        specs: List<Map<String, Any?>>,
        step: Map<String, Any?>,
        stepIndex: Int,
    ): String? {
        listOf(
            OobActionCodec.mapArg(step["raw_action_edge"]),
            OobActionCodec.mapArg(step["udeg_edge"]),
            OobActionCodec.mapArg(step["cleanup_annotation"]),
            OobActionCodec.mapArg(OobActionCodec.mapArg(step["metadata"])["raw_action_edge"]),
        ).forEach { annotation ->
            normalizeRole(
                OobActionCodec.firstNonBlank(
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

        for (spec in specs) {
            val agentReuse = OobActionCodec.mapArg(spec["agent_reuse"])
            val keyActions = OobActionCodec.listArg(agentReuse["key_actions"]) +
                OobActionCodec.listArg(agentReuse["keyActions"])
            if (keyActions.any { matchesStepReference(it, step, stepIndex) }) {
                return ROLE_SEMANTIC
            }
            val checkerAssets = OobActionCodec.listArg(agentReuse["checker_assets"]) +
                OobActionCodec.listArg(agentReuse["checkerAssets"])
            if (checkerAssets.any { matchesStepReference(it, step, stepIndex) }) {
                return ROLE_CHECKER_CANDIDATE
            }
            val noiseActions = OobActionCodec.listArg(agentReuse["noise_actions"]) +
                OobActionCodec.listArg(agentReuse["noiseActions"])
            if (noiseActions.any { matchesStepReference(it, step, stepIndex) }) {
                return ROLE_NOISE
            }
        }
        return null
    }

    fun defaultRole(actionType: String, actionSummary: Map<String, Any?> = emptyMap()): String {
        return when {
            OobActionCodec.isRouteAction(actionType, actionSummary) -> ROLE_NAVIGATION
            else -> ROLE_UNKNOWN
        }
    }

    fun normalizeRole(rawRole: String): String? =
        when (rawRole.trim().lowercase(Locale.US)) {
            "navigation", "navigate", "route", "route_safe" -> ROLE_NAVIGATION
            "semantic", "key", "key_action", "key_function", "main", "main_action" -> ROLE_SEMANTIC
            "checker",
            "checker_candidate",
            "runtime_checker",
            "optional_checker",
            "conditional_checker",
            "conditional_obstruction",
            "popup_checker",
            "ad_checker" -> ROLE_CHECKER_CANDIDATE
            "noise",
            "ignore",
            "ignored",
            "drop_candidate",
            "drop_noise",
            "merge_candidate",
            "merge_duplicate",
            "probably_useless",
            "redundant_candidate",
            "noop",
            "no_op" -> ROLE_NOISE
            "unknown" -> ROLE_UNKNOWN
            else -> null
        }

    fun isCheckerCandidateRole(rawRole: String): Boolean {
        val normalized = rawRole.trim().lowercase(Locale.US).replace('-', '_')
        return normalizeRole(normalized) == ROLE_CHECKER_CANDIDATE ||
            normalized in setOf("optional", "conditional")
    }

    fun matchesStepReference(
        rawReference: Any?,
        step: Map<String, Any?>,
        stepIndex: Int,
    ): Boolean {
        when (rawReference) {
            is Number -> return rawReference.toInt() == stepIndex
            is String -> {
                val value = rawReference.trim()
                return value == stepIndex.toString() ||
                    value == OobActionCodec.firstNonBlank(step["id"], step["step_id"], step["stepId"])
            }
        }
        val reference = OobActionCodec.mapArg(rawReference)
        if (reference.isEmpty()) return false
        val index = OobActionCodec.intArg(reference["step_index"], reference["stepIndex"], reference["index"], defaultValue = -1)
        if (index == stepIndex) return true
        val stepId = OobActionCodec.firstNonBlank(reference["step_id"], reference["stepId"], reference["id"])
        return stepId.isNotBlank() && stepId == OobActionCodec.firstNonBlank(step["id"], step["step_id"], step["stepId"])
    }
}
