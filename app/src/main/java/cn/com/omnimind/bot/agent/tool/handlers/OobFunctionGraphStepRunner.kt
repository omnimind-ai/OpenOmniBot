package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.bot.omniflow.OobFunctionJson.firstNonBlank
import cn.com.omnimind.bot.omniflow.OobFunctionJson.listArg
import cn.com.omnimind.bot.omniflow.OobFunctionJson.mapArg
import cn.com.omnimind.bot.runlog.OmniflowCheckerRule
import cn.com.omnimind.bot.runlog.OmniflowStepExecutor
import cn.com.omnimind.bot.runlog.RunLogReplayPolicy
import kotlinx.coroutines.CancellationException

/**
 * Executes OmniFlow graph/UTG steps by resolving them into primitive local
 * OmniFlow actions. The Function tool handler owns the main replay loop; this
 * class owns graph path selection and edge-to-step lowering.
 */
class OobFunctionGraphStepRunner {
    suspend fun execute(
        step: Map<String, Any?>,
        stepId: String,
        stepTitle: String,
        callableTool: String,
        checkerRules: List<OmniflowCheckerRule> = emptyList(),
    ): Map<String, Any?> {
        val path = resolveGraphPath(step, callableTool)
        if (path.isEmpty()) {
            return failureStepResult(
                stepId = stepId,
                tool = callableTool.ifEmpty { "go_to_node" },
                summary = "$stepTitle has no executable UTG path",
                errorCode = "OOB_UTG_PATH_EMPTY",
            )
        }

        val primitiveResults = mutableListOf<Map<String, Any?>>()
        for ((index, primitiveStep) in path.withIndex()) {
            val pathStepId = "${stepId}_path_${index + 1}"
            val pathTitle = primitiveStep["title"]?.toString()?.takeIf { it.isNotBlank() }
                ?: "$stepTitle path ${index + 1}"
            val startedAtMs = System.currentTimeMillis()
            val result = try {
                OmniflowStepExecutor.execute(primitiveStep, pathStepId, pathTitle, checkerRules)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failureStepResult(
                    stepId = pathStepId,
                    tool = OmniflowStepExecutor.actionNameForStep(primitiveStep),
                    executor = "omniflow",
                    summary = e.message ?: "UTG path action failed",
                    errorCode = "OOB_UTG_ACTION_FAILED",
                )
            }
            val finishedAtMs = System.currentTimeMillis()
            primitiveResults += LinkedHashMap<String, Any?>().apply {
                putAll(result)
                putIfAbsent("index", index)
                putIfAbsent("started_at_ms", startedAtMs)
                putIfAbsent("finished_at_ms", finishedAtMs)
                putIfAbsent("duration_ms", (finishedAtMs - startedAtMs).coerceAtLeast(0))
            }
            if (result["success"] == false) break
        }

        val success = primitiveResults.size == path.size &&
            primitiveResults.none { it["success"] == false }
        return linkedMapOf<String, Any?>(
            "step_id" to stepId,
            "tool" to callableTool.ifEmpty { "go_to_node" },
            "executor" to "omniflow_graph",
            "model_free" to true,
            "success" to success,
            "path_length" to path.size,
            "success_path_step_count" to primitiveResults.count { it["success"] != false },
            "step_results" to primitiveResults,
            "summary" to if (success) {
                "$stepTitle completed via local UTG path"
            } else {
                primitiveResults.lastOrNull()?.get("summary")?.toString()
                    ?: "$stepTitle failed in local UTG path"
            },
        )
    }

    private fun resolveGraphPath(
        step: Map<String, Any?>,
        callableTool: String,
    ): List<Map<String, Any?>> {
        val args = stepArgs(step)
        val directPath = listArg(args["path"]).ifEmpty { listArg(step["path"]) }
        val utg = mapArg(args["utg"])
            .ifEmpty { mapArg(step["utg"]) }
            .ifEmpty { mapArg(args["graph"]) }
            .ifEmpty { mapArg(step["graph"]) }
        val utgPathIds = listArg(utg["path"])
        val utgEdges = listArg(utg["edges"])
            .mapNotNull { mapArg(it).takeIf { edge -> edge.isNotEmpty() } }
        val edges = listArg(args["edges"])
            .ifEmpty { listArg(step["edges"]) }
            .mapNotNull { mapArg(it).takeIf { edge -> edge.isNotEmpty() } }
            .ifEmpty { utgEdges }

        val rawPath = when {
            directPath.isNotEmpty() -> directPath
            edges.isNotEmpty() && RunLogReplayPolicy.normalizeToolName(callableTool) in
                setOf("click_node", "node_click") -> selectClickNodeEdges(edges, args)
            utgPathIds.isNotEmpty() && utgEdges.isNotEmpty() -> {
                val edgeById = utgEdges.associateBy { firstNonBlank(it["edge_id"], it["edgeId"], it["id"]) }
                utgPathIds.mapNotNull { rawId -> edgeById[rawId?.toString().orEmpty()] }
            }
            edges.isNotEmpty() -> selectGoToNodeEdges(edges, args)
            else -> emptyList()
        }
        return rawPath.mapNotNull { raw ->
            val edge = mapArg(raw)
            edgeToOmniflowStep(edge)
        }
    }

    private fun selectClickNodeEdges(
        edges: List<Map<String, Any?>>,
        args: Map<String, Any?>,
    ): List<Map<String, Any?>> {
        val edgeId = firstNonBlank(args["edge_id"], args["edgeId"])
        val actionId = firstNonBlank(args["action_id"], args["actionId"])
        val targetNodeId = firstNonBlank(args["node_id"], args["nodeId"], args["target_node_id"], args["targetNodeId"])
        val selected = edges.firstOrNull { edge ->
            (edgeId.isNotEmpty() && firstNonBlank(edge["edge_id"], edge["edgeId"], edge["id"]) == edgeId) ||
                (actionId.isNotEmpty() && firstNonBlank(edge["action_id"], edge["actionId"]) == actionId) ||
                (targetNodeId.isNotEmpty() &&
                    firstNonBlank(edge["to_node_id"], edge["toNodeId"], edge["node_id"], edge["nodeId"]) == targetNodeId)
        } ?: edges.firstOrNull()
        return selected?.let { listOf(it) }.orEmpty()
    }

    private fun selectGoToNodeEdges(
        edges: List<Map<String, Any?>>,
        args: Map<String, Any?>,
    ): List<Map<String, Any?>> {
        val targetNodeId = firstNonBlank(args["node_id"], args["nodeId"], args["target_node_id"], args["targetNodeId"])
        if (targetNodeId.isEmpty()) return edges
        val targetIndex = edges.indexOfFirst { edge ->
            firstNonBlank(edge["to_node_id"], edge["toNodeId"], edge["node_id"], edge["nodeId"]) == targetNodeId
        }
        return if (targetIndex >= 0) edges.take(targetIndex + 1) else emptyList()
    }

    private fun edgeToOmniflowStep(edge: Map<String, Any?>): Map<String, Any?>? {
        val action = firstNonBlank(
            edge["action"],
            edge["tool"],
            edge["omniflow_action"],
            edge["local_action"],
            edge["type"],
        )
        val localAction = RunLogReplayPolicy.omniflowActionForToolName(action) ?: return null
        val edgeArgs = linkedMapOf<String, Any?>()
        edgeArgs.putAll(mapArg(edge["args"]))
        for (key in EDGE_ARG_KEYS) {
            if (edgeArgs[key] == null && edge.containsKey(key)) {
                edgeArgs[key] = edge[key]
            }
        }
        return linkedMapOf(
            "title" to firstNonBlank(edge["title"], edge["summary"], edge["target_description"], edge["targetDescription"], localAction),
            "kind" to "omniflow_action",
            "executor" to RunLogReplayPolicy.EXECUTOR_OMNIFLOW,
            "omniflow_action" to localAction,
            "local_action" to localAction,
            "model_free" to true,
            "scriptable" to true,
            "tool" to localAction,
            "callable_tool" to localAction,
            "args" to edgeArgs,
            "source_context" to mapArg(edge["source_context"]).takeIf { it.isNotEmpty() },
            "coordinate_hook" to edge["coordinate_hook"],
        ).filterValues { it != null }
    }

    private fun stepArgs(step: Map<String, Any?>): Map<String, Any?> {
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

    private fun Map<String, Any?>.hasExecutionArgs(): Boolean =
        EXECUTION_ARG_KEYS.any { key -> this[key] != null }

    private fun failureStepResult(
        stepId: String,
        tool: String,
        executor: String = "omniflow_graph",
        summary: String,
        errorCode: String,
    ): Map<String, Any?> = linkedMapOf(
        "step_id" to stepId,
        "tool" to tool,
        "executor" to executor,
        "model_free" to true,
        "success" to false,
        "needs_agent" to false,
        "fallback_available" to false,
        "error_code" to errorCode,
        "summary" to summary,
    )

    private companion object {
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

        val EDGE_ARG_KEYS = listOf(
            "x",
            "y",
            "x1",
            "y1",
            "x2",
            "y2",
            "direction",
            "distance",
            "distance_px",
            "distancePx",
            "duration",
            "duration_ms",
            "durationMs",
            "content",
            "text",
            "value",
            "package_name",
            "packageName",
            "key",
            "hotkey",
            "hot_key",
            "target_description",
            "targetDescription",
            "node_resource_id",
            "nodeResourceId",
            "resource_id",
            "resourceId",
        )
    }
}
