package cn.com.omnimind.bot.omniflow

import cn.com.omnimind.bot.omniflow.OobFunctionJson.boolArgOrDefault
import cn.com.omnimind.bot.omniflow.OobFunctionJson.firstNonBlank
import cn.com.omnimind.bot.omniflow.OobFunctionJson.intArg
import cn.com.omnimind.bot.omniflow.OobFunctionJson.listArg
import cn.com.omnimind.bot.omniflow.OobFunctionJson.mapArg
import cn.com.omnimind.bot.omniflow.OobFunctionJson.mutableJsonMap
import cn.com.omnimind.bot.omniflow.OobFunctionJson.mutableJsonValue
import cn.com.omnimind.bot.runlog.OmniflowCheckerRule

/**
 * Owns Function checker metadata normalization.
 *
 * update_function can add checker rules directly or mark unstable/noisy steps as
 * optional checker candidates. This service keeps those candidates as metadata
 * instead of turning popup/ad dismissal into required execution steps.
 */
class OobFunctionCheckerPatchService {
    fun applyCheckerRulesPatch(
        metadata: MutableMap<String, Any?>,
        rawRules: Any?,
    ): List<Map<String, Any?>> {
        val additions = listArg(rawRules)
            .mapNotNull { sanitizeCheckerRule(mapArg(it)) }
        if (additions.isEmpty()) return emptyList()
        val existing = listArg(metadata["checker_rules"])
        val merged = mergeCheckerRules(existing, additions)
        if (existing == merged) return emptyList()
        metadata["checker_rules"] = merged
        return listOf(
            changeMap(
                "metadata",
                "checker_rules",
                existing.takeIf { it.isNotEmpty() },
                merged,
            )
        )
    }

    fun applyOptionalCheckerMetadataFromSteps(
        spec: MutableMap<String, Any?>,
    ): List<Map<String, Any?>> {
        val execution = mapArg(spec["execution"])
        val steps = listArg(execution["steps"])
            .mapNotNull { mapArg(it).takeIf { step -> step.isNotEmpty() } }
        if (steps.isEmpty()) return emptyList()

        val changes = mutableListOf<Map<String, Any?>>()
        val metadata = mutableJsonMap(mapArg(spec["metadata"]))
        val existingRules = listArg(metadata["checker_rules"])
        val mergedRules = mergeCheckerRules(
            existingRules,
            steps.mapIndexedNotNull { index, step -> optionalCheckerRuleForStep(step, index) }
        )
        val signatureToId = checkerRuleSignatureToId(mergedRules)
        val checkerAssets = steps.mapIndexedNotNull { index, step ->
            val rule = optionalCheckerRuleForStep(step, index) ?: return@mapIndexedNotNull null
            val checkerId = signatureToId[checkerRuleSignature(rule)] ?: firstNonBlank(rule["id"])
            checkerAssetForStep(checkerId, step, index)
        }

        if (existingRules != mergedRules) {
            changes += changeMap(
                "metadata",
                "checker_rules",
                existingRules.takeIf { it.isNotEmpty() },
                mergedRules,
            )
            metadata["checker_rules"] = mergedRules
            spec["metadata"] = metadata
        }

        if (checkerAssets.isNotEmpty()) {
            val agentReuse = mutableJsonMap(mapArg(spec["agent_reuse"]))
            val existingAssets = listArg(agentReuse["checker_assets"])
            val mergedAssets = mergeCheckerAssets(existingAssets, checkerAssets)
            if (existingAssets != mergedAssets) {
                changes += changeMap(
                    "agent_reuse",
                    "checker_assets",
                    existingAssets.takeIf { it.isNotEmpty() },
                    mergedAssets,
                )
                agentReuse["checker_assets"] = mergedAssets
                spec["agent_reuse"] = agentReuse
            }
        }
        return changes
    }

    private fun optionalCheckerRuleForStep(
        step: Map<String, Any?>,
        stepIndex: Int,
    ): Map<String, Any?>? {
        val annotation = mapArg(step["cleanup_annotation"])
        if (!isOptionalCheckerAnnotation(annotation)) return null
        val text = checkerInferenceText(step, annotation)
        val condition = when {
            containsAny(text, listOf("resolver", "chooser", "open with", "always open", "始终打开", "打开方式")) ->
                OmniflowCheckerRule.COND_RESOLVER_DIALOG
            containsAny(text, listOf("keyboard", "ime", "键盘", "输入法")) ->
                OmniflowCheckerRule.COND_KEYBOARD_OBSCURING
            containsAny(text, listOf("permission", "allow", "authorize", "grant", "权限", "授权", "允许")) ->
                OmniflowCheckerRule.COND_PERMISSION_DIALOG
            else -> OmniflowCheckerRule.COND_OVERLAY_BLOCKING
        }
        val action = checkerActionForCondition(condition)
        return linkedMapOf(
            "id" to "optional_checker_step_${stepIndex}_$condition",
            "phase" to checkerPhaseForCondition(condition),
            "condition" to condition,
            "action" to action,
            "enabled" to true,
            "params" to emptyMap<String, Any?>(),
        )
    }

    private fun isOptionalCheckerAnnotation(annotation: Map<String, Any?>): Boolean {
        val action = normalizeCleanupAction(
            firstNonBlank(
                annotation["cleanup_action"],
                annotation["cleanupAction"],
                annotation["action"],
            )
        )
        val usefulness = firstNonBlank(annotation["usefulness"]).lowercase()
        val category = firstNonBlank(annotation["category"]).lowercase()
        return action == "optional_checker" ||
            usefulness == "conditional_checker" ||
            category in setOf("conditional_obstruction", "runtime_checker", "checker_candidate")
    }

    private fun normalizeCleanupAction(raw: String): String =
        when (raw.trim().lowercase().replace('-', '_')) {
            "optional",
            "optional_checker",
            "conditional",
            "conditional_checker",
            "conditional_obstruction",
            "popup_checker",
            "ad_checker" -> "optional_checker"
            else -> raw.trim().lowercase().replace('-', '_')
        }

    private fun checkerInferenceText(
        step: Map<String, Any?>,
        annotation: Map<String, Any?>,
    ): String {
        val args = mapArg(step["args"])
        return listOf(
            step["title"],
            step["summary"],
            step["description"],
            annotation["optional_condition"],
            annotation["optionalCondition"],
            annotation["reason"],
            annotation["action_purpose"],
            args["target_description"],
            args["text"],
            args["content"],
        ).joinToString(" ") { it?.toString().orEmpty() }.lowercase()
    }

    private fun sanitizeCheckerRule(raw: Map<String, Any?>): Map<String, Any?>? {
        if (raw.isEmpty()) return null
        val condition = normalizeCheckerCondition(firstNonBlank(raw["condition"], raw["when"], raw["type"]))
        if (condition.isBlank()) return null
        val action = normalizeCheckerAction(firstNonBlank(raw["action"], raw["then"], raw["effect"]), condition)
        if (action.isBlank() || !isSupportedCheckerPair(condition, action)) return null
        val params = mutableMapOf<String, Any?>()
        val rawParams = mapArg(raw["params"])
        val packageName = firstNonBlank(
            rawParams["package_name"],
            rawParams["packageName"],
            raw["package_name"],
            raw["packageName"],
        )
        if (condition == OmniflowCheckerRule.COND_PACKAGE_MISMATCH &&
            Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$").matches(packageName)
        ) {
            params["package_name"] = packageName
        }
        return linkedMapOf(
            "id" to safeCheckerRuleId(firstNonBlank(raw["id"], "function_checker")),
            "phase" to checkerPhaseForCondition(condition),
            "condition" to condition,
            "action" to action,
            "enabled" to boolArgOrDefault(raw["enabled"], true),
            "params" to params,
        )
    }

    private fun normalizeCheckerCondition(raw: String): String =
        when (raw.trim().lowercase().replace('-', '_')) {
            "overlay_blocking",
            "blocking_overlay",
            "popup_blocking",
            "popup",
            "banner",
            "coupon",
            "obstruction",
            "conditional_obstruction" -> OmniflowCheckerRule.COND_OVERLAY_BLOCKING
            "ad_blocking",
            "blocking_ad",
            "ad_popup",
            "ad",
            "ads",
            "splash_ad",
            "interstitial_ad",
            "skip_ad",
            "advertising" -> OmniflowCheckerRule.COND_AD_BLOCKING
            "permission_dialog",
            "permission",
            "permission_prompt",
            "permission_nudge" -> OmniflowCheckerRule.COND_PERMISSION_DIALOG
            "resolver_dialog",
            "open_with_dialog",
            "chooser_dialog",
            "intent_resolver",
            "intent_resolver_dialog",
            "default_app_dialog",
            "always_open_dialog" -> OmniflowCheckerRule.COND_RESOLVER_DIALOG
            "keyboard_obscuring",
            "keyboard",
            "ime_obscuring",
            "soft_keyboard" -> OmniflowCheckerRule.COND_KEYBOARD_OBSCURING
            "package_mismatch",
            "wrong_app",
            "app_mismatch",
            "foreground_package_mismatch" -> OmniflowCheckerRule.COND_PACKAGE_MISMATCH
            else -> ""
        }

    private fun normalizeCheckerAction(raw: String, condition: String): String {
        val text = raw.trim().lowercase().replace('-', '_')
        if (text.isBlank()) return checkerActionForCondition(condition)
        return when (text) {
            "dismiss",
            "close",
            "close_popup",
            "click_close",
            "click_dismiss",
            "skip" -> OmniflowCheckerRule.ACTION_DISMISS
            "allow",
            "grant",
            "grant_permission",
            "click_allow" -> OmniflowCheckerRule.ACTION_ALLOW
            "confirm_resolver_always",
            "always_open",
            "open_always",
            "click_always_open",
            "click_always",
            "confirm_default",
            "set_default" -> OmniflowCheckerRule.ACTION_CONFIRM_RESOLVER_ALWAYS
            "hide_keyboard",
            "dismiss_keyboard",
            "close_keyboard" -> OmniflowCheckerRule.ACTION_HIDE_KEYBOARD
            "open_app",
            "launch_app",
            "start_app" -> OmniflowCheckerRule.ACTION_OPEN_APP
            "click" -> when (condition) {
                OmniflowCheckerRule.COND_OVERLAY_BLOCKING -> OmniflowCheckerRule.ACTION_DISMISS
                OmniflowCheckerRule.COND_AD_BLOCKING -> OmniflowCheckerRule.ACTION_DISMISS
                OmniflowCheckerRule.COND_PERMISSION_DIALOG -> OmniflowCheckerRule.ACTION_ALLOW
                OmniflowCheckerRule.COND_RESOLVER_DIALOG -> OmniflowCheckerRule.ACTION_CONFIRM_RESOLVER_ALWAYS
                else -> ""
            }
            else -> ""
        }
    }

    private fun checkerActionForCondition(condition: String): String =
        when (condition) {
            OmniflowCheckerRule.COND_KEYBOARD_OBSCURING -> OmniflowCheckerRule.ACTION_HIDE_KEYBOARD
            OmniflowCheckerRule.COND_PERMISSION_DIALOG -> OmniflowCheckerRule.ACTION_ALLOW
            OmniflowCheckerRule.COND_RESOLVER_DIALOG -> OmniflowCheckerRule.ACTION_CONFIRM_RESOLVER_ALWAYS
            OmniflowCheckerRule.COND_PACKAGE_MISMATCH -> OmniflowCheckerRule.ACTION_OPEN_APP
            else -> OmniflowCheckerRule.ACTION_DISMISS
        }

    private fun checkerPhaseForCondition(condition: String): String =
        when (condition) {
            OmniflowCheckerRule.COND_KEYBOARD_OBSCURING -> OmniflowCheckerRule.PHASE_PRE_ACTION
            OmniflowCheckerRule.COND_RESOLVER_DIALOG -> OmniflowCheckerRule.PHASE_POST_ACTION
            else -> OmniflowCheckerRule.PHASE_PRE_TRANSFER
        }

    private fun isSupportedCheckerPair(condition: String, action: String): Boolean =
        (condition == OmniflowCheckerRule.COND_OVERLAY_BLOCKING &&
            action == OmniflowCheckerRule.ACTION_DISMISS) ||
            (condition == OmniflowCheckerRule.COND_AD_BLOCKING &&
                action == OmniflowCheckerRule.ACTION_DISMISS) ||
            (condition == OmniflowCheckerRule.COND_PERMISSION_DIALOG &&
                action == OmniflowCheckerRule.ACTION_ALLOW) ||
            (condition == OmniflowCheckerRule.COND_RESOLVER_DIALOG &&
                action == OmniflowCheckerRule.ACTION_CONFIRM_RESOLVER_ALWAYS) ||
            (condition == OmniflowCheckerRule.COND_KEYBOARD_OBSCURING &&
                action == OmniflowCheckerRule.ACTION_HIDE_KEYBOARD) ||
            (condition == OmniflowCheckerRule.COND_PACKAGE_MISMATCH &&
                action == OmniflowCheckerRule.ACTION_OPEN_APP)

    private fun mergeCheckerRules(
        existing: List<Any?>,
        additions: List<Map<String, Any?>>,
    ): List<Any?> {
        if (additions.isEmpty()) return existing
        val output = existing.map { mutableJsonValue(it) }.toMutableList()
        val signatures = existing.mapNotNull { raw ->
            sanitizeCheckerRule(mapArg(raw))?.let(::checkerRuleSignature)
        }.toMutableSet()
        val usedIds = existing.mapNotNull {
            firstNonBlank(mapArg(it)["id"]).takeIf(String::isNotBlank)
        }.toMutableSet()
        additions.forEach { rawRule ->
            val signature = checkerRuleSignature(rawRule)
            if (!signatures.add(signature)) return@forEach
            val rule = mutableJsonMap(rawRule)
            val id = uniqueCheckerRuleId(firstNonBlank(rule["id"], "function_checker"), usedIds)
            rule["id"] = id
            output += rule
        }
        return output
    }

    private fun checkerRuleSignatureToId(rules: List<Any?>): Map<String, String> =
        rules.mapNotNull { raw ->
            val sanitized = sanitizeCheckerRule(mapArg(raw)) ?: return@mapNotNull null
            checkerRuleSignature(sanitized) to firstNonBlank(mapArg(raw)["id"], sanitized["id"])
        }.toMap()

    private fun checkerRuleSignature(rule: Map<String, Any?>): String {
        val params = mapArg(rule["params"])
        return listOf(
            rule["phase"],
            rule["condition"],
            rule["action"],
            firstNonBlank(params["package_name"], params["packageName"]),
        ).joinToString("|") { it?.toString().orEmpty() }
    }

    private fun checkerAssetForStep(
        checkerId: String,
        step: Map<String, Any?>,
        stepIndex: Int,
    ): Map<String, Any?>? {
        if (checkerId.isBlank()) return null
        val annotation = mapArg(step["cleanup_annotation"])
        val reason = firstNonBlank(
            annotation["optional_condition"],
            annotation["reason"],
            annotation["action_purpose"],
            step["description"],
            step["summary"],
            step["title"],
        )
        return linkedMapOf(
            "checker_id" to checkerId,
            "step_index" to stepIndex,
            "step_id" to firstNonBlank(step["id"], "step_${stepIndex + 1}"),
            "role" to "checker_candidate",
            "materialization" to "metadata_checker_rule",
            "reason" to reason.takeIf { it.isNotBlank() },
        ).filterValues { it != null }
    }

    private fun mergeCheckerAssets(
        existing: List<Any?>,
        additions: List<Map<String, Any?>>,
    ): List<Any?> {
        if (additions.isEmpty()) return existing
        val output = existing.map { mutableJsonValue(it) }.toMutableList()
        val seen = existing.mapNotNull { raw ->
            val asset = mapArg(raw)
            checkerAssetSignature(asset).takeIf { it.isNotBlank() }
        }.toMutableSet()
        additions.forEach { asset ->
            val signature = checkerAssetSignature(asset)
            if (signature.isNotBlank() && seen.add(signature)) {
                output += mutableJsonMap(asset)
            }
        }
        return output
    }

    private fun checkerAssetSignature(asset: Map<String, Any?>): String {
        val checkerId = firstNonBlank(asset["checker_id"], asset["checkerId"])
        val stepIndex = intArg(asset["step_index"], asset["stepIndex"], asset["index"], defaultValue = -1)
        val stepId = firstNonBlank(asset["step_id"], asset["stepId"])
        if (checkerId.isBlank() || stepIndex < 0) return ""
        return "$checkerId|$stepIndex|$stepId"
    }

    private fun safeCheckerRuleId(raw: String): String {
        val normalized = raw
            .replace(Regex("([a-z])([A-Z])"), "$1_$2")
            .replace(Regex("[^A-Za-z0-9_]+"), "_")
            .lowercase()
            .replace(Regex("_+"), "_")
            .trim('_')
            .take(80)
            .trim('_')
        return normalized.ifBlank { "function_checker" }
    }

    private fun uniqueCheckerRuleId(raw: String, usedIds: MutableSet<String>): String {
        val base = safeCheckerRuleId(raw)
        var candidate = base
        var suffix = 2
        while (candidate in usedIds) {
            val suffixText = "_$suffix"
            candidate = base.take((80 - suffixText.length).coerceAtLeast(1)).trimEnd('_') + suffixText
            suffix += 1
        }
        usedIds += candidate
        return candidate
    }

    private fun containsAny(text: String, needles: List<String>): Boolean =
        needles.any { text.contains(it) }

    private fun changeMap(
        part: String,
        field: String,
        old: Any?,
        new: Any?,
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
        "part" to part,
        "field" to field,
        "old" to old,
        "new" to new,
    ).filterValues { it != null }

}
