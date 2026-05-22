package cn.com.omnimind.bot.runlog

import android.content.Context
import com.google.gson.GsonBuilder
import java.util.Locale

/**
 * OOB-native UDEG node store.
 *
 * Each node is keyed by PageVectorSet page-match evidence and stores a
 * skill-like decision context plus outgoing reusable Functions. Online VLM
 * recall should first localize the live page to one of these nodes, then use
 * the node skill and attached Functions as decision context.
 */
class OobUdegNodeStore(
    private val context: Context,
) {
    data class RecallMatch(
        val node: Map<String, Any?>,
        val pageSimilarity: Float,
        val reason: String,
    ) {
        fun toMap(): Map<String, Any?> = linkedMapOf(
            "node_id" to node["node_id"],
            "package_name" to node["package_name"],
            "page_similarity" to pageSimilarity,
            "reason" to reason,
            "skill" to node["skill"],
            "decision_context" to mapArg(node["decision_context"]).takeIf { it.isNotEmpty() },
            "function_ids" to functionIds(node),
            "page_vector_set" to mapArg(node["page_vector_set"]).let { vectorSet ->
                linkedMapOf(
                    "schema_version" to vectorSet["schema_version"],
                    "page_vector_dim" to vectorSet["page_vector_dim"],
                    "element_count" to vectorSet["element_count"],
                    "actionable_count" to vectorSet["actionable_count"],
                    "privacy" to vectorSet["privacy"],
                ).filterValues { it != null }
            },
        ).filterValues { it != null }
    }

    fun upsertFunction(functionId: String, functionSpec: Map<String, Any?>): Map<String, Any?> {
        val normalizedFunctionId = functionId.trim()
        if (normalizedFunctionId.isEmpty()) {
            return error("FUNCTION_ID_EMPTY", "function_id is required")
        }
        val source = extractSourcePage(functionSpec)
            ?: return linkedMapOf(
                "success" to true,
                "indexed" to false,
                "function_id" to normalizedFunctionId,
                "reason" to "missing_source_page",
                "source" to "oob_udeg_node_store",
            )
        val pageVector = OobPageVectorSet.encode(
            xml = source.pageXml,
            packageName = source.packageName,
        ) ?: return linkedMapOf(
            "success" to false,
            "indexed" to false,
            "function_id" to normalizedFunctionId,
            "reason" to "invalid_source_page",
            "source" to "oob_udeg_node_store",
        )
        val existing = getNode(pageVector.nodeId)
        val existingFunctions = functionSummaries(existing).toMutableList()
        val newSummary = functionSummary(functionSpec, normalizedFunctionId)
        val mergedFunctions = existingFunctions
            .filterNot { it["function_id"]?.toString() == normalizedFunctionId }
            .plus(newSummary)
        val node = linkedMapOf<String, Any?>(
            "schema_version" to NODE_SCHEMA_VERSION,
            "node_id" to pageVector.nodeId,
            "package_name" to pageVector.packageName,
            "activity_name" to source.activityName.takeIf { it.isNotBlank() },
            "page_vector_set" to pageVector.toMap(),
            "skill" to buildNodeSkill(
                nodeId = pageVector.nodeId,
                packageName = pageVector.packageName,
                functions = mergedFunctions,
            ),
            "decision_context" to buildDecisionContext(
                nodeId = pageVector.nodeId,
                packageName = pageVector.packageName,
                functionCount = mergedFunctions.size,
            ),
            "functions" to mergedFunctions,
            "updated_at" to System.currentTimeMillis(),
            "source" to "oob_native_udeg",
        ).filterValues { it != null }.toMutableMap()
        val registry = mapArg(existing["_oob_registry"]).toMutableMap()
        node["_oob_registry"] = registry.apply {
            put("function_count", mergedFunctions.size)
            put("last_function_id", normalizedFunctionId)
            put("updated_at", System.currentTimeMillis())
        }

        val prefs = prefs()
        prefs.edit()
            .putString(nodeKey(pageVector.nodeId), gson.toJson(node))
            .putString(INDEX_KEY, gson.toJson((listNodeIds() - pageVector.nodeId) + pageVector.nodeId))
            .apply()
        return linkedMapOf(
            "success" to true,
            "indexed" to true,
            "function_id" to normalizedFunctionId,
            "node_id" to pageVector.nodeId,
            "page_vector_set" to linkedMapOf(
                "schema_version" to OobPageVectorSet.SCHEMA_VERSION,
                "page_vector_dim" to pageVector.vector.size,
                "element_count" to pageVector.elementCount,
                "actionable_count" to pageVector.actionableCount,
            ),
            "skill_id" to mapArg(node["skill"])["id"],
            "source" to "oob_udeg_node_store",
        )
    }

    fun recall(
        currentXml: String,
        currentPackage: String = "",
        topK: Int = 5,
        minScore: Float = MIN_PAGE_MATCH_SCORE,
    ): List<RecallMatch> {
        val query = OobPageVectorSet.encode(currentXml, currentPackage) ?: return emptyList()
        return listNodes(limit = MAX_NODE_SCAN)
            .mapNotNull { node ->
                val nodePackage = node["package_name"]?.toString()?.trim().orEmpty()
                if (currentPackage.isNotBlank() && nodePackage.isNotBlank() && currentPackage != nodePackage) {
                    return@mapNotNull null
                }
                val vector = OobPageVectorSet.vectorFrom(
                    mapArg(node["page_vector_set"])["page_vector"]
                )
                val score = OobPageVectorSet.cosine(query.vector, vector)
                if (score < minScore) return@mapNotNull null
                RecallMatch(
                    node = node,
                    pageSimilarity = roundScore(score),
                    reason = if (score >= STRONG_PAGE_MATCH_SCORE) {
                        "page_vector_strong_match"
                    } else {
                        "page_vector_candidate"
                    },
                )
            }
            .sortedByDescending { it.pageSimilarity }
            .take(topK.coerceIn(1, 50))
    }

    fun listNodes(limit: Int = 100): List<Map<String, Any?>> {
        return listNodeIds()
            .asReversed()
            .take(limit.coerceIn(1, MAX_NODE_SCAN))
            .mapNotNull(::getNode)
    }

    fun getNode(nodeId: String): Map<String, Any?> {
        val raw = prefs().getString(nodeKey(nodeId.trim()), null)?.takeIf { it.isNotBlank() }
            ?: return emptyMap()
        return runCatching { decodeMap(raw) }.getOrDefault(emptyMap())
    }

    private fun buildNodeSkill(
        nodeId: String,
        packageName: String,
        functions: List<Map<String, Any?>>,
    ): Map<String, Any?> {
        val capabilities = functions.mapNotNull { function ->
            val functionId = function["function_id"]?.toString()?.trim().orEmpty()
            if (functionId.isEmpty()) return@mapNotNull null
            linkedMapOf(
                "function_id" to functionId,
                "name" to function["name"],
                "description" to function["description"],
                "input_schema" to function["input_schema"],
                "step_summaries" to function["step_summaries"],
            ).filterValues { it != null }
        }
        val guidance = buildString {
            append("UDEG page-match localized the current screen to node ")
            append(nodeId)
            if (packageName.isNotBlank()) append(" in ").append(packageName)
            append(". Use this node skill as decision context. ")
            append("Prefer an attached Function only when its description and boundary fit the user's goal; ")
            append("otherwise continue with live VLM screen actions.")
        }
        return linkedMapOf(
            "schema_version" to NODE_SKILL_SCHEMA_VERSION,
            "id" to "udeg_node_skill_$nodeId",
            "kind" to "udeg_node_skill",
            "name" to "UDEG node context",
            "description" to "Skill-like decision context attached to a page-matched UDEG node.",
            "frontmatter" to linkedMapOf(
                "name" to "udeg-node-$nodeId",
                "description" to "Decision context for a page-matched UDEG node.",
            ),
            "activation" to linkedMapOf(
                "type" to "page_match",
                "min_page_similarity" to MIN_PAGE_MATCH_SCORE,
                "strong_page_similarity" to STRONG_PAGE_MATCH_SCORE,
            ),
            "role" to "decision_context",
            "decision_path" to UDEG_DECISION_PATH,
            "decision_rules" to NODE_DECISION_RULES,
            "decision_guidance" to guidance,
            "body" to renderNodeSkillBody(
                nodeId = nodeId,
                packageName = packageName,
                guidance = guidance,
                capabilities = capabilities,
            ),
            "capabilities" to capabilities,
            "function_count" to capabilities.size,
        )
    }

    private fun buildDecisionContext(
        nodeId: String,
        packageName: String,
        functionCount: Int,
    ): Map<String, Any?> = linkedMapOf(
        "schema_version" to NODE_DECISION_CONTEXT_SCHEMA_VERSION,
        "role" to "decision",
        "context_kind" to "udeg_node_skill_decision_context",
        "entry_policy" to "page_match_to_udeg_node",
        "decision_path" to UDEG_DECISION_PATH,
        "node_id" to nodeId,
        "package_name" to packageName.takeIf { it.isNotBlank() },
        "skill_id" to "udeg_node_skill_$nodeId",
        "function_count" to functionCount,
        "usage" to listOf(
            "choose the next live VLM action from the current page",
            "select an attached Function only when the node, boundary, and goal match",
            "fall back to normal VLM execution when the node skill does not fit",
        ),
        "constraints" to listOf(
            "do not scan the Function store directly",
            "do not treat a recalled Function as task completion",
            "do not execute weak or parameterized Functions without live grounding",
        ),
        "instruction" to "Use this UDEG node's skill-like context as VLM decision context after page match localizes the current screen to this node.",
    ).filterValues { it != null }

    private fun renderNodeSkillBody(
        nodeId: String,
        packageName: String,
        guidance: String,
        capabilities: List<Map<String, Any?>>,
    ): String = buildString {
        appendLine("# UDEG Node Skill")
        appendLine()
        appendLine("Use only after page match localizes the current page to `$nodeId`.")
        if (packageName.isNotBlank()) {
            appendLine("Package: `$packageName`.")
        }
        appendLine()
        appendLine("## Decision Context")
        appendLine()
        appendLine("Decision path: `page match -> UDEG node -> node skill-like decision context -> VLM/tool decision`.")
        appendLine()
        appendLine(guidance)
        appendLine()
        appendLine("## Decision Rules")
        NODE_DECISION_RULES.forEach { rule ->
            appendLine("- $rule")
        }
        if (capabilities.isNotEmpty()) {
            appendLine()
            appendLine("## Attached Functions")
            capabilities.forEach { capability ->
                val functionId = capability["function_id"]?.toString()?.trim().orEmpty()
                if (functionId.isBlank()) return@forEach
                val description = firstNonBlank(capability["description"], capability["name"], functionId)
                appendLine("- `$functionId`: $description")
            }
        }
    }.trim()

    private fun functionSummary(
        functionSpec: Map<String, Any?>,
        functionId: String,
    ): Map<String, Any?> {
        return linkedMapOf(
            "function_id" to functionId,
            "name" to firstNonBlank(functionSpec["name"], functionId),
            "description" to firstNonBlank(functionSpec["description"], functionSpec["name"], functionId),
            "input_schema" to OobFunctionSchemaBuilder.inputSchema(functionSpec),
            "step_summaries" to stepSummaries(functionSpec),
            "source" to mapArg(functionSpec["source"]),
        )
    }

    private fun stepSummaries(functionSpec: Map<String, Any?>): List<Map<String, Any?>> {
        val execution = mapArg(functionSpec["execution"])
        return listArg(execution["steps"]).mapIndexedNotNull { index, raw ->
            val step = mapArg(raw)
            if (step.isEmpty()) return@mapIndexedNotNull null
            val tool = firstNonBlank(
                step["omniflow_action"],
                step["local_action"],
                step["callable_tool"],
                step["tool"],
            )
            linkedMapOf(
                "index" to index,
                "id" to firstNonBlank(step["id"], "step_${index + 1}"),
                "title" to firstNonBlank(step["title"], step["summary"], tool),
                "tool" to tool,
            )
        }
    }

    private fun extractSourcePage(functionSpec: Map<String, Any?>): SourcePage? {
        val execution = mapArg(functionSpec["execution"])
        listArg(execution["steps"]).forEach { raw ->
            val step = mapArg(raw)
            val sourceContext = mapArg(step["source_context"])
            val srcCtx = mapArg(sourceContext["src_ctx"])
                .ifEmpty { mapArg(sourceContext["source_context"]) }
                .ifEmpty { mapArg(sourceContext["page_context"]) }
            val page = firstNonBlank(
                srcCtx["page"],
                srcCtx["xml"],
                srcCtx["observation_xml"],
                sourceContext["page"],
                sourceContext["xml"],
                sourceContext["observation_xml"],
            )
            if (page.isNotBlank()) {
                return SourcePage(
                    pageXml = page,
                    packageName = firstNonBlank(
                        srcCtx["package_name"],
                        srcCtx["packageName"],
                        sourceContext["package_name"],
                        sourceContext["packageName"],
                    ),
                    activityName = firstNonBlank(srcCtx["activity_name"], srcCtx["activityName"]),
                )
            }
        }
        return null
    }

    private fun prefs() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun listNodeIds(): List<String> {
        val raw = prefs().getString(INDEX_KEY, "[]").orEmpty()
        return runCatching {
            gson.fromJson(raw, List::class.java)
                .mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
        }.getOrDefault(emptyList())
    }

    private fun decodeMap(raw: String): Map<String, Any?> {
        val decoded = gson.fromJson(raw, Map::class.java) ?: return emptyMap()
        return sanitizeMap(decoded)
    }

    private fun nodeKey(nodeId: String): String = "$NODE_PREFIX$nodeId"

    private fun error(code: String, message: String): Map<String, Any?> = linkedMapOf(
        "success" to false,
        "indexed" to false,
        "error_code" to code,
        "error_message" to message,
        "source" to "oob_udeg_node_store",
    )

    private data class SourcePage(
        val pageXml: String,
        val packageName: String,
        val activityName: String,
    )

    companion object {
        private const val PREFS_NAME = "oob_udeg_nodes"
        private const val INDEX_KEY = "oob_udeg_node_index_v1"
        private const val NODE_PREFIX = "oob_udeg_node_v1:"
        private const val NODE_SCHEMA_VERSION = "oob.udeg.node.v1"
        private const val NODE_SKILL_SCHEMA_VERSION = "oob.udeg.node_skill.v1"
        private const val NODE_DECISION_CONTEXT_SCHEMA_VERSION = "oob.udeg.decision_context.v1"
        const val UDEG_DECISION_PATH =
            "page_match -> UDEG node -> node skill-like decision context -> VLM/tool decision"
        const val MIN_PAGE_MATCH_SCORE = 0.30f
        const val STRONG_PAGE_MATCH_SCORE = 0.87f
        private const val MAX_NODE_SCAN = 1_000
        private val NODE_DECISION_RULES = listOf(
            "Use the node skill only after page match localizes the current page to this node.",
            "Treat attached Functions as outgoing reusable transitions from this node.",
            "Use the skill as decision context; keep grounding actions on the live screenshot/XML.",
            "If no attached Function fits the user goal, continue with normal VLM actions.",
        )

        private val gson = GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create()

        fun functionIds(node: Map<String, Any?>): List<String> =
            functionSummaries(node).mapNotNull {
                it["function_id"]?.toString()?.trim()?.takeIf(String::isNotEmpty)
            }

        fun functionSummaries(node: Map<String, Any?>): List<Map<String, Any?>> =
            listArg(node["functions"]).mapNotNull { raw ->
                mapArg(raw).takeIf { it.isNotEmpty() }
            }

        fun mapArg(value: Any?): Map<String, Any?> {
            return when (value) {
                is Map<*, *> -> linkedMapOf<String, Any?>().apply {
                    value.forEach { (key, item) ->
                        if (key != null) put(key.toString(), sanitizeValue(item))
                    }
                }
                else -> emptyMap()
            }
        }

        fun listArg(value: Any?): List<Any?> =
            when (value) {
                is List<*> -> value
                is Array<*> -> value.toList()
                else -> emptyList()
            }

        fun firstNonBlank(vararg values: Any?): String {
            for (value in values) {
                val text = value?.toString()?.trim().orEmpty()
                if (text.isNotEmpty()) return text
            }
            return ""
        }

        private fun sanitizeMap(raw: Map<*, *>): Map<String, Any?> =
            linkedMapOf<String, Any?>().apply {
                raw.forEach { (key, value) ->
                    if (key != null) put(key.toString(), sanitizeValue(value))
                }
            }

        private fun sanitizeValue(value: Any?): Any? {
            return when (value) {
                is Map<*, *> -> sanitizeMap(value)
                is List<*> -> value.map(::sanitizeValue)
                is Array<*> -> value.map(::sanitizeValue)
                else -> value
            }
        }

        private fun roundScore(score: Float): Float =
            String.format(Locale.US, "%.4f", score).toFloat()
    }
}
