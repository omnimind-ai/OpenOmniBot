package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.bot.omniflow.OobFunctionJson.firstNonBlank
import cn.com.omnimind.bot.omniflow.OobFunctionJson.mapArg
import cn.com.omnimind.bot.runlog.OmniflowStepExecutor
import cn.com.omnimind.bot.runlog.RunLogReplayPolicy

/**
 * Classifies reusable Function steps for replay routing.
 *
 * The replay handler owns execution sequencing. This classifier owns stable
 * step-shape decisions such as legacy skip detection, local OmniFlow
 * executable detection, and replayable agent-tool extraction.
 */
class OobFunctionStepClassifier(
    private val callRequestResolver: OobFunctionCallRequestResolver,
) {
    fun requiresAgentPlanning(step: Map<String, Any?>): Boolean {
        val reason = mapArg(step["agent_call"])["reason"]?.toString()
            ?: step["reason"]?.toString()
            ?: ""
        return RunLogReplayPolicy.requiresAgentPlanningReason(reason)
    }

    fun replayableAgentTool(step: Map<String, Any?>, callableTool: String): String {
        val agentCall = mapArg(step["agent_call"])
        val agentArgs = mapArg(agentCall["args"])
        val candidates = listOf(
            agentArgs["original_tool"],
            step["tool"],
            callableTool,
            agentCall["tool"]
        )
        return candidates.asSequence()
            .map { it?.toString()?.trim().orEmpty() }
            .firstOrNull { it.isNotEmpty() && it != "oob.agent.run" }
            .orEmpty()
    }

    fun isOmniflowExecutionStep(
        step: Map<String, Any?>,
        specExists: (String) -> Boolean,
    ): Boolean {
        val tool = omniflowExecutionToolForStep(step, executionToolName(step))
        return when {
            RunLogReplayPolicy.isOmniflowGraphTool(tool) -> true
            RunLogReplayPolicy.isOmniflowFunctionTool(tool) -> true
            RunLogReplayPolicy.isOmniflowToolCallTool(tool) -> {
                val args = callRequestResolver.stepArgs(step)
                firstNonBlank(
                    callRequestResolver.functionId(args, step),
                    callRequestResolver.targetTool(args, step).takeIf {
                        it.isNotEmpty() && specExists(it)
                    },
                ).isNotEmpty()
            }
            else -> false
        }
    }

    fun isSkippedLegacyStep(step: Map<String, Any?>): Boolean {
        val names = listOf(
            executionToolName(step),
            OmniflowStepExecutor.actionNameForStep(step),
            step["source_tool"]?.toString().orEmpty(),
        )
        return names.any { name ->
            name.isNotBlank() && RunLogReplayPolicy.shouldSkipTool(name)
        }
    }

    fun executionToolName(step: Map<String, Any?>): String =
        firstNonBlank(
            step["callable_tool"],
            step["tool"],
            step["omniflow_action"],
            step["local_action"],
            step["type"],
        )

    fun omniflowExecutionToolForStep(
        step: Map<String, Any?>,
        callableTool: String,
    ): String {
        val agentCall = mapArg(step["agent_call"])
        val agentArgs = mapArg(agentCall["args"])
        val candidates = listOf(
            callableTool,
            step["tool"],
            step["callable_tool"],
            step["omniflow_action"],
            step["local_action"],
            step["type"],
            agentArgs["original_tool"],
            agentCall["original_tool"],
        )
        return candidates.asSequence()
            .map { it?.toString()?.trim().orEmpty() }
            .map { RunLogReplayPolicy.normalizeToolName(it) }
            .firstOrNull { it.isNotEmpty() && RunLogReplayPolicy.isOmniflowExecutionTool(it) }
            .orEmpty()
    }

}
