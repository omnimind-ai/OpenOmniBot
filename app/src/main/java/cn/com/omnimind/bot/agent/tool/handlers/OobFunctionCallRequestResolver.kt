package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.bot.omniflow.OobFunctionJson.firstNonBlank
import cn.com.omnimind.bot.omniflow.OobFunctionJson.mapArg
import cn.com.omnimind.bot.runlog.RunLogReplayPolicy

/**
 * Resolves Function and call_tool arguments from model calls and replay steps.
 * The replay handler decides how to execute the request; this class only owns
 * argument shape compatibility across recorded RunLogs and current tool calls.
 */
class OobFunctionCallRequestResolver {
    fun stepArgs(step: Map<String, Any?>): Map<String, Any?> {
        val directArgs = mapArg(step["args"])
        val agentCall = mapArg(step["agent_call"])
        val agentArgs = mapArg(agentCall["args"])
        val originalArgs = mapArg(directArgs["original_args"])
            .ifEmpty { mapArg(directArgs["originalArgs"]) }
            .ifEmpty { mapArg(agentArgs["original_args"]) }
            .ifEmpty { mapArg(agentArgs["originalArgs"]) }
        val topLevelArgs = buildMap {
            for (key in EXECUTION_ARG_KEYS) {
                if (step.containsKey(key)) put(key, step[key])
            }
        }
        return when {
            directArgs.hasExecutionArgs() -> directArgs
            originalArgs.isNotEmpty() -> originalArgs
            topLevelArgs.isNotEmpty() -> topLevelArgs
            else -> directArgs
        }
    }

    fun nestedFunctionArguments(args: Map<String, Any?>): Map<String, Any?> {
        val nested = nestedArguments(args)
        if (nested.isNotEmpty()) return nested
        return linkedMapOf<String, Any?>().apply {
            args.forEach { (key, value) ->
                if (key !in FUNCTION_CALL_META_KEYS) put(key, value)
            }
        }
    }

    fun resolve(
        args: Map<String, Any?>,
        step: Map<String, Any?> = emptyMap(),
        isKnownFunction: (String) -> Boolean,
    ): Request {
        val targetTool = targetTool(args, step)
        val targetArgs = callToolArguments(args)
        val functionId = firstNonBlank(
            functionId(args, step),
            if (RunLogReplayPolicy.isOmniflowFunctionTool(targetTool)) {
                functionId(targetArgs, emptyMap())
            } else {
                null
            },
            targetTool.takeIf { it.isNotEmpty() && isKnownFunction(it) },
        )
        return Request(
            targetTool = targetTool,
            targetArgs = targetArgs,
            functionId = functionId,
        )
    }

    fun functionId(args: Map<String, Any?>, step: Map<String, Any?>): String = firstNonBlank(
        args["function_id"],
        args["functionId"],
        args["oob_function_id"],
        args["oobFunctionId"],
        step["function_id"],
        step["functionId"],
        step["oob_function_id"],
        step["oobFunctionId"],
    )

    fun targetTool(args: Map<String, Any?>, step: Map<String, Any?>): String = firstNonBlank(
        args["tool_name"],
        args["toolName"],
        args["target_tool"],
        args["targetTool"],
        args["tool"],
        step["tool_name"],
        step["toolName"],
        step["target_tool"],
        step["targetTool"],
    )

    private fun callToolArguments(args: Map<String, Any?>): Map<String, Any?> {
        val nested = nestedArguments(args)
        if (nested.isNotEmpty()) return nested
        return linkedMapOf<String, Any?>().apply {
            args.forEach { (key, value) ->
                if (key !in CALL_TOOL_META_KEYS) put(key, value)
            }
        }
    }

    private fun nestedArguments(args: Map<String, Any?>): Map<String, Any?> =
        mapArg(args["arguments"])
            .ifEmpty { mapArg(args["args"]) }
            .ifEmpty { mapArg(args["input"]) }

    private fun Map<String, Any?>.hasExecutionArgs(): Boolean =
        EXECUTION_ARG_KEYS.any { key -> this[key] != null }

    data class Request(
        val targetTool: String,
        val targetArgs: Map<String, Any?>,
        val functionId: String,
    )

    private companion object {
        val FUNCTION_CALL_META_KEYS = setOf(
            "function_id",
            "functionId",
            "id",
            "name",
            "tool_name",
            "toolName",
            "target_tool",
            "targetTool",
            "oob_function_id",
            "oobFunctionId",
            "goal",
            "tool_title",
            "tool",
            "callable_tool",
            "arguments",
            "args",
            "input",
        )

        val EXECUTION_ARG_KEYS = setOf(
            "function_id",
            "functionId",
            "id",
            "name",
            "tool_name",
            "toolName",
            "target_tool",
            "targetTool",
            "oob_function_id",
            "oobFunctionId",
            "node_id",
            "nodeId",
            "target_node_id",
            "targetNodeId",
            "edge_id",
            "edgeId",
            "action_id",
            "actionId",
            "path",
            "edges",
            "utg",
            "graph",
            "arguments",
            "args",
            "input",
        )

        val CALL_TOOL_META_KEYS = setOf(
            "function_id",
            "functionId",
            "oob_function_id",
            "oobFunctionId",
            "tool_name",
            "toolName",
            "target_tool",
            "targetTool",
            "tool",
            "callable_tool",
            "arguments",
            "args",
            "input",
            "goal",
            "tool_title",
        )
    }
}
