package cn.com.omnimind.bot.runlog

import cn.com.omnimind.baselib.runlog.InternalRunLogRecord
import cn.com.omnimind.bot.omniflow.OobFunctionSpecVocabulary
import cn.com.omnimind.bot.runlog.RunLogCardAccessors.asBoolean
import cn.com.omnimind.bot.runlog.RunLogCardAccessors.asMap

object RunLogReusableFunctionCompiler {
    fun compile(record: InternalRunLogRecord): Map<String, Any?>? {
        val replayableCards = record.cards.filter(::isSuccessfulCard)
        val compileCards = RunLogStartupBridgeCleaner.dropTransientStartupBridgeCards(replayableCards)
        val droppedStartupBridgeCount = replayableCards.size - compileCards.size
        val hasRecordedReplayStep = compileCards.any(RunLogStartupBridgeCleaner::hasRecordedReplayStep)
        val rawSteps = compileCards
            .mapIndexedNotNull { index, card ->
                RunLogReplayStepCompiler.compileCard(
                    card = card,
                    skipPerceptionTools = hasRecordedReplayStep,
                    nextReplayableCard = compileCards
                        .asSequence()
                        .drop(index + 1)
                        .firstOrNull(RunLogStartupBridgeCleaner::hasRecordedReplayStep),
                )
            }
        val replaySteps = RunLogReplayStepNoiseNormalizer.normalize(rawSteps)
        val initialStepCleanup = RunLogStartupBridgeCleaner.normalizeInitialOpenAppStep(
            replayableCards,
            replaySteps,
        )
        val injectedLaunchBridgeDroppedCount = initialStepCleanup.injectedLaunchBridgeDroppedCount
        val steps = initialStepCleanup.steps
            .mapIndexed { index, rawStep ->
                linkedMapOf<String, Any?>().apply {
                    putAll(rawStep)
                    put("id", "step_${index + 1}")
                    put("index", index)
                }
            }

        if (steps.isEmpty()) return null

        val parameterization = RunLogReusableFunctionParameterizer.parameterize(steps)
        val now = System.currentTimeMillis().toString()
        val functionId = deriveFunctionId(record)
        val goal = record.goal.ifBlank { record.operationDescription }
        val name = record.operationDescription.ifBlank { goal }.ifBlank { functionId }.take(80)
        val capabilities = executionCapabilities(steps)
        return linkedMapOf<String, Any?>(
            "schema_version" to OobFunctionSpecVocabulary.SCHEMA_VERSION_V1,
            "function_id" to functionId,
            "name" to name,
            "description" to goal.ifBlank { name },
            "parameters" to parameterization.parameters,
            "actions" to parameterization.actions,
            "metadata" to linkedMapOf(
                "source" to "run_log_import",
                "run_id" to record.runId,
                "source_run_ids" to listOf(record.runId),
                "original_goal" to record.goal,
                "goal" to goal.ifBlank { name },
                "step_count" to parameterization.actions.size,
                "oob_parameter_bindings" to parameterization.parameterBindings,
                "oob_legacy_parameters" to parameterization.legacyParameters.takeIf { it.isNotEmpty() },
                "oob_compat_execution" to true,
            ).filterValues { it != null },
            "source" to linkedMapOf(
                "kind" to "run_log",
                "run_id" to record.runId,
                "goal" to record.goal,
                "tool_name" to record.toolName,
                "card_count" to record.cards.size,
                "replayable_card_count" to replayableCards.size,
                "compiled_replayable_card_count" to compileCards.size,
                "transient_startup_bridge_dropped_count" to droppedStartupBridgeCount,
                "injected_launch_bridge_step_dropped_count" to injectedLaunchBridgeDroppedCount,
                "converted_at" to now,
                "converter" to "native_run_log_reusable_function_builder",
                "parameter_inference" to linkedMapOf(
                    "strategy" to "deterministic_input_text_bindings",
                    "parameter_count" to parameterization.legacyParameters.size,
                ),
            ),
            "execution" to linkedMapOf(
                "kind" to OobFunctionSpecVocabulary.EXECUTION_KIND_TOOL_SEQUENCE,
                "runner" to OobFunctionSpecVocabulary.EXECUTION_RUNNER_TOOL_SEQUENCE,
                "entrypoint" to "execute",
                "capabilities" to capabilities,
                "steps" to steps,
                "step_count" to steps.size,
                "omniflow_step_count" to capabilities["omniflow_step_count"],
                "agent_step_count" to capabilities["agent_step_count"],
                "has_agent_steps" to capabilities["has_agent_steps"],
            ),
            "_oob_registry" to linkedMapOf(
                "registered_at" to now,
                "updated_at" to now,
                "runner" to OobFunctionSpecVocabulary.REGISTRY_RUNNER_AGENT_REUSABLE_FUNCTION,
                "storage" to "workspace",
            ),
        )
    }

    private fun executionCapabilities(steps: List<Map<String, Any?>>): Map<String, Any?> {
        return linkedMapOf(
            "scriptable_step_count" to steps.count { it["scriptable"] == true },
            "model_free_step_count" to steps.count { it["model_free"] == true },
            "omniflow_step_count" to steps.count { it["executor"] == RunLogReplayPolicy.EXECUTOR_OMNIFLOW },
            "agent_step_count" to steps.count { it["executor"] == RunLogReplayPolicy.EXECUTOR_AGENT },
            "has_agent_steps" to steps.any { it["executor"] == RunLogReplayPolicy.EXECUTOR_AGENT },
        )
    }

    private fun isSuccessfulCard(card: Map<String, Any?>): Boolean {
        val header = asMap(card["header"])
        return asBoolean(card["success"]) != false && asBoolean(header["success"]) != false
    }

    private fun deriveFunctionId(record: InternalRunLogRecord): String {
        val base = record.toolName.ifBlank { record.goal }
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(40)
            .ifBlank { "cmd" }
        val suffix = record.runId.takeLast(8).replace(Regex("[^A-Za-z0-9]"), "")
        return "oob_cmd_${base}_$suffix"
    }

}
