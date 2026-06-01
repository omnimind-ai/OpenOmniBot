package cn.com.omnimind.bot.runlog

/**
 * A single checker rule evaluated by OmniflowStepExecutor before each step.
 *
 * Rules are matched by [condition] against the current device state and the
 * pending replay action. When the condition is satisfied, [action] is executed.
 *
 * Three rule scopes compose in order — global rules run first, then
 * function-level rules, then node-level rules:
 *   - Global   : defined in [GLOBAL_PRE_TRANSFER] / [GLOBAL_PRE_ACTION] / [GLOBAL_POST_ACTION]
 *   - Function : loaded from Function spec metadata.checker_rules
 *   - Node     : loaded from UDEG node skill (future)
 *
 * Execution stops after the first rule whose action produces an effect
 * (i.e. runs a recovery action). Condition-only / continue rules do not stop
 * the chain.
 */
data class OmniflowCheckerRule(
    val id: String,
    val condition: String,
    val action: String,
    val params: Map<String, Any?> = emptyMap(),
    val phase: String = PHASE_PRE_TRANSFER,
    val enabled: Boolean = true,
) {
    companion object {

        // ── Phases ──────────────────────────────────────────────────────────
        /** Runs before anchor-based action transfer. */
        const val PHASE_PRE_TRANSFER = "pre_transfer"
        /** Runs after transfer, immediately before action dispatch. */
        const val PHASE_PRE_ACTION = "pre_action"
        /** Runs after action dispatch when the action itself may surface a system dialog. */
        const val PHASE_POST_ACTION = "post_action"

        // ── Conditions ──────────────────────────────────────────────────────
        /** Foreground package ≠ step's source-context package. */
        const val COND_PACKAGE_MISMATCH = "package_mismatch"
        /** A dismissible blocking overlay covers the target. */
        const val COND_OVERLAY_BLOCKING = "overlay_blocking"
        /** A dismissible ad/splash/interstitial blocks the current Function step. */
        const val COND_AD_BLOCKING = "ad_blocking"
        /** Soft keyboard overlaps the action target. */
        const val COND_KEYBOARD_OBSCURING = "keyboard_obscuring"
        /** An Android system permission request dialog is visible. */
        const val COND_PERMISSION_DIALOG = "permission_dialog"
        /** Android's "Open with" / resolver default-app dialog is visible. */
        const val COND_RESOLVER_DIALOG = "resolver_dialog"

        // ── Actions ─────────────────────────────────────────────────────────
        /** Launch the expected app (params: package_name overrides step inference). */
        const val ACTION_OPEN_APP = "open_app"
        /** Dismiss the blocking overlay by clicking its best dismiss candidate. */
        const val ACTION_DISMISS = "dismiss"
        /** Hide the soft keyboard. */
        const val ACTION_HIDE_KEYBOARD = "hide_keyboard"
        /** Click the Allow button on an Android permission request dialog. */
        const val ACTION_ALLOW = "allow"
        /** Click the "Always open" button on an Android resolver dialog. */
        const val ACTION_CONFIRM_RESOLVER_ALWAYS = "confirm_resolver_always"
        /** Select an app row in Android's resolver dialog before confirming. */
        const val ACTION_SELECT_RESOLVER_APP = "select_resolver_app"
        /** Wait [params.delay_ms] ms before continuing. */
        const val ACTION_WAIT = "wait"
        /** Emit a handoff signal so the host agent resumes. */
        const val ACTION_HANDOFF = "handoff"

        // ── Built-in global rules ────────────────────────────────────────────
        val GLOBAL_PRE_TRANSFER: List<OmniflowCheckerRule> = listOf(
            OmniflowCheckerRule(
                id = "confirm_resolver_always",
                condition = COND_RESOLVER_DIALOG,
                action = ACTION_CONFIRM_RESOLVER_ALWAYS,
                phase = PHASE_PRE_TRANSFER,
            ),
            OmniflowCheckerRule(
                id = "auto_grant_permission",
                condition = COND_PERMISSION_DIALOG,
                action = ACTION_ALLOW,
                phase = PHASE_PRE_TRANSFER,
            ),
            OmniflowCheckerRule(
                id = "dismiss_ad_blocking",
                condition = COND_AD_BLOCKING,
                action = ACTION_DISMISS,
                phase = PHASE_PRE_TRANSFER,
            ),
            OmniflowCheckerRule(
                id = "package_mismatch_recovery",
                condition = COND_PACKAGE_MISMATCH,
                action = ACTION_OPEN_APP,
                phase = PHASE_PRE_TRANSFER,
            ),
            OmniflowCheckerRule(
                id = "dismiss_blocking_overlay",
                condition = COND_OVERLAY_BLOCKING,
                action = ACTION_DISMISS,
                phase = PHASE_PRE_TRANSFER,
            ),
        )

        val GLOBAL_PRE_ACTION: List<OmniflowCheckerRule> = listOf(
            OmniflowCheckerRule(
                id = "hide_keyboard_if_obscuring",
                condition = COND_KEYBOARD_OBSCURING,
                action = ACTION_HIDE_KEYBOARD,
                phase = PHASE_PRE_ACTION,
            ),
        )

        val GLOBAL_POST_ACTION: List<OmniflowCheckerRule> = listOf(
            OmniflowCheckerRule(
                id = "confirm_resolver_always_after_open_app",
                condition = COND_RESOLVER_DIALOG,
                action = ACTION_CONFIRM_RESOLVER_ALWAYS,
                phase = PHASE_POST_ACTION,
            ),
        )

        // ── Factories ────────────────────────────────────────────────────────

        fun fromMap(map: Map<*, *>): OmniflowCheckerRule? {
            val id = map["id"]?.toString()?.trim().orEmpty().ifBlank { return null }
            val condition = map["condition"]?.toString()?.trim().orEmpty().ifBlank { return null }
            val action = map["action"]?.toString()?.trim().orEmpty().ifBlank { return null }
            val params = OobActionCodec.mapArg(map["params"])
            return OmniflowCheckerRule(
                id = id,
                condition = condition,
                action = action,
                params = params,
                phase = map["phase"]?.toString()?.trim() ?: PHASE_PRE_TRANSFER,
                enabled = map["enabled"]?.let(OobActionCodec::boolArg) ?: true,
            )
        }

        /** Extracts checker rules from a Function spec's metadata.checker_rules list. */
        fun fromSpec(spec: Map<*, *>): List<OmniflowCheckerRule> {
            val metadata = OobActionCodec.mapArg(spec["metadata"])
            val rules = OobActionCodec.listArg(metadata["checker_rules"])
            return rules.mapNotNull { OobActionCodec.mapArg(it).takeIf { rule -> rule.isNotEmpty() }?.let(::fromMap) }
        }
    }
}
