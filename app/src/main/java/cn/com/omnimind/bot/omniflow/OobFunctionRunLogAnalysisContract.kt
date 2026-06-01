package cn.com.omnimind.bot.omniflow

/**
 * Agent-facing RunLog evidence analysis vocabulary for update_function.
 *
 * This is a prompt/schema contract only. It does not classify runtime replay
 * steps; replay/UDEG step-role policy stays in OobStepRoleClassifier.
 */
internal object OobFunctionRunLogAnalysisContract {
    const val FIELD_SUMMARY = "summary"
    const val FIELD_STEP_FINDINGS = "step_findings"
    const val FIELD_FUNCTION_STEP_INDEX = "function_step_index"
    const val FIELD_RUNLOG_CARD_INDEX = "runlog_card_index"
    const val FIELD_LABEL = "label"
    const val FIELD_ROLE = "role"
    const val FIELD_REASON = "reason"
    const val FIELD_FAILURE_REASON = "failure_reason"
    const val FIELD_CODE = "code"
    const val FIELD_MESSAGE = "message"
    const val FIELD_RECOMMENDED_PATCH = "recommended_patch"
    const val FIELD_OPS = "ops"

    const val ROLE_REQUIRED_ACTION = "required_action"
    const val ROLE_OPTIONAL_CHECKER = "optional_checker"
    const val ROLE_NOISE = "noise"
    const val ROLE_DUPLICATE = "duplicate"
    const val ROLE_FAILED_ACTION = "failed_action"
    const val ROLE_SUCCESS_EVIDENCE = "success_evidence"

    const val FAILURE_WRONG_TARGET = "wrong_target"
    const val FAILURE_TARGET_MISSING = "target_missing"
    const val FAILURE_AD_INTERRUPTION = "ad_interruption"
    const val FAILURE_REPEATED_INPUT = "repeated_input"
    const val FAILURE_UNSTABLE_COORDINATE = "unstable_coordinate"
    const val FAILURE_UNKNOWN = "unknown"

    val roles: List<String> = listOf(
        ROLE_REQUIRED_ACTION,
        ROLE_OPTIONAL_CHECKER,
        ROLE_NOISE,
        ROLE_DUPLICATE,
        ROLE_FAILED_ACTION,
        ROLE_SUCCESS_EVIDENCE,
    )

    val failureCodes: List<String> = listOf(
        FAILURE_WRONG_TARGET,
        FAILURE_TARGET_MISSING,
        FAILURE_AD_INTERRUPTION,
        FAILURE_REPEATED_INPUT,
        FAILURE_UNSTABLE_COORDINATE,
        FAILURE_UNKNOWN,
    )

    val roleChoiceText: String = roles.joinToString(" | ")
    val failureCodeChoiceText: String = failureCodes.joinToString(" | ")
}
