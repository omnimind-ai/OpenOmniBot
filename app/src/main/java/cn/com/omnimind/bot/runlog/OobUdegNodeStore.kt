package cn.com.omnimind.bot.runlog

import android.content.Context
import com.google.gson.GsonBuilder
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.security.MessageDigest
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

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
    data class ObservedPage(
        val pageXml: String,
        val packageName: String = "",
        val activityName: String = "",
        val screenshotBase64: String? = null,
        val goal: String = "",
        val stepIndex: Int = 0,
    )

    data class PageObservationResult(
        val node: Map<String, Any?>,
        val pageSimilarity: Float,
        val firstSeen: Boolean,
        val reason: String,
    ) {
        fun toMap(): Map<String, Any?> = linkedMapOf(
            "success" to true,
            "node_id" to node["node_id"],
            "package_name" to node["package_name"],
            "page_similarity" to pageSimilarity,
            "first_seen" to firstSeen,
            "reason" to reason,
            "page_analysis" to mapArg(node["page_analysis"]).takeIf { it.isNotEmpty() },
            "decision_context" to mapArg(node["decision_context"]).takeIf { it.isNotEmpty() },
            "node_skill_context" to nodeSkillContext(
                node = node,
                pageSimilarity = pageSimilarity,
                reason = reason,
            ),
            "function_ids" to functionIds(node),
            "source" to "oob_udeg_node_store",
        ).filterValues { it != null }
    }

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
            "node_skill_context" to nodeSkillContext(
                node = node,
                pageSimilarity = pageSimilarity,
                reason = reason,
            ),
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
        val existingMatch = findBestNodeMatch(
            pageVector = pageVector,
            currentPackage = source.packageName,
            minScore = STRONG_PAGE_MATCH_SCORE,
        )
        val targetNodeId = existingMatch?.node?.get("node_id")?.toString()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: pageVector.nodeId
        val existing = existingMatch?.node ?: getNode(targetNodeId)
        val existingFunctions = functionSummaries(existing).toMutableList()
        val newSummary = functionSummary(functionSpec, normalizedFunctionId)
        val mergedFunctions = existingFunctions
            .filterNot { it["function_id"]?.toString() == normalizedFunctionId }
            .plus(newSummary)
        val existingPageAnalysis = mapArg(existing["page_analysis"]).takeIf { it.isNotEmpty() }
            ?: buildPageAnalysis(
                observedPage = ObservedPage(
                    pageXml = source.pageXml,
                    packageName = source.packageName,
                    activityName = source.activityName,
                ),
                pageVector = pageVector,
                existing = existing,
                now = System.currentTimeMillis(),
            )
        val node = linkedMapOf<String, Any?>(
            "schema_version" to NODE_SCHEMA_VERSION,
            "node_id" to targetNodeId,
            "package_name" to pageVector.packageName,
            "activity_name" to source.activityName.takeIf { it.isNotBlank() },
            "page_vector_set" to pageVector.toMap(),
            "page_analysis" to existingPageAnalysis,
            "skill" to buildNodeSkill(
                nodeId = targetNodeId,
                packageName = pageVector.packageName,
                functions = mergedFunctions,
                pageAnalysis = existingPageAnalysis,
            ),
            "decision_context" to buildDecisionContext(
                nodeId = targetNodeId,
                packageName = pageVector.packageName,
                functionCount = mergedFunctions.size,
                pageAnalysis = existingPageAnalysis,
            ),
            "functions" to mergedFunctions,
            "first_seen_at" to longArg(existing["first_seen_at"], defaultValue = System.currentTimeMillis()),
            "last_seen_at" to mapArg(existing["_oob_registry"])["last_seen_at"],
            "updated_at" to System.currentTimeMillis(),
            "source" to "oob_native_udeg",
        ).filterValues { it != null }.toMutableMap()
        val registry = mapArg(existing["_oob_registry"]).toMutableMap()
        node["_oob_registry"] = registry.apply {
            put("node_kind", "page")
            put("function_count", mergedFunctions.size)
            put("last_function_id", normalizedFunctionId)
            put("updated_at", System.currentTimeMillis())
        }

        saveNode(targetNodeId, node)
        return linkedMapOf(
            "success" to true,
            "indexed" to true,
            "function_id" to normalizedFunctionId,
            "node_id" to targetNodeId,
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
        return recallByPageVector(
            query = query,
            currentPackage = currentPackage,
            topK = topK,
            minScore = minScore,
        )
    }

    private fun findBestNodeMatch(
        pageVector: OobPageVectorSet.PageVector,
        currentPackage: String,
        minScore: Float,
    ): RecallMatch? {
        return recallByPageVector(
            query = pageVector,
            currentPackage = currentPackage,
            topK = 1,
            minScore = minScore,
        ).firstOrNull()
    }

    private fun recallByPageVector(
        query: OobPageVectorSet.PageVector,
        currentPackage: String,
        topK: Int,
        minScore: Float,
    ): List<RecallMatch> {
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

    private fun saveNode(nodeId: String, node: Map<String, Any?>) {
        val normalizedNodeId = nodeId.trim()
        prefs().edit()
            .putString(nodeKey(normalizedNodeId), gson.toJson(node))
            .putString(INDEX_KEY, gson.toJson((listNodeIds() - normalizedNodeId) + normalizedNodeId))
            .apply()
    }

    private fun buildNodeSkill(
        nodeId: String,
        packageName: String,
        functions: List<Map<String, Any?>>,
        pageAnalysis: Map<String, Any?> = emptyMap(),
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
                pageAnalysis = pageAnalysis,
            ),
            "capabilities" to capabilities,
            "function_count" to capabilities.size,
        )
    }

    private fun buildDecisionContext(
        nodeId: String,
        packageName: String,
        functionCount: Int,
        pageAnalysis: Map<String, Any?> = emptyMap(),
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
        "page_analysis" to compactPageAnalysisForDecision(pageAnalysis).takeIf { it.isNotEmpty() },
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
        pageAnalysis: Map<String, Any?> = emptyMap(),
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
        renderPageAnalysisForSkill(pageAnalysis).takeIf { it.isNotBlank() }?.let { analysis ->
            appendLine()
            appendLine("## Page Analysis")
            appendLine()
            appendLine(analysis)
        }
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

    private fun buildPageAnalysis(
        observedPage: ObservedPage,
        pageVector: OobPageVectorSet.PageVector,
        existing: Map<String, Any?>,
        now: Long,
    ): Map<String, Any?> {
        val previous = mapArg(existing["page_analysis"])
        val signals = parsePageSignals(observedPage.pageXml)
        val screenshotHash = observedPage.screenshotBase64
            ?.takeIf { it.isNotBlank() }
            ?.let(::sha256)
        val previousSummary = mapArg(previous["summary"])
        val visibleTexts = signals.visibleTexts.ifEmpty {
            listArg(previousSummary["visible_texts"]).mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
        }
        val actionables = signals.actionableLabels.ifEmpty {
            listArg(previousSummary["actionables"]).mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
        }
        val pageTitle = firstNonBlank(
            signals.title,
            previousSummary["title"],
            visibleTexts.firstOrNull(),
            pageVector.packageName,
        )
        val pageRole = when {
            signals.hasEditable && signals.hasScrollable -> "form_or_searchable_list"
            signals.hasEditable -> "form"
            signals.hasScrollable -> "scrollable_list"
            actionables.isNotEmpty() -> "actionable_page"
            else -> "static_page"
        }
        val suggestedPolicy = buildList {
            add("Ground the next action on the live screenshot/XML before acting.")
            if (signals.hasScrollable) {
                add("If the target text is not visible, prefer one deliberate scroll over repeated small scrolls.")
            }
            if (signals.hasEditable) {
                add("Use editable fields only when the user goal requires input; do not overwrite existing text without evidence.")
            }
            if (actionables.isNotEmpty()) {
                add("Prefer visible actionable labels that match the current goal.")
            }
        }
        return linkedMapOf(
            "schema_version" to PAGE_ANALYSIS_SCHEMA_VERSION,
            "analysis_kind" to "xml_screenshot_first_seen",
            "node_id" to pageVector.nodeId,
            "summary" to linkedMapOf(
                "title" to pageTitle.takeIf { it.isNotBlank() },
                "page_role" to pageRole,
                "visible_texts" to visibleTexts.take(MAX_ANALYSIS_TEXTS),
                "actionables" to actionables.take(MAX_ANALYSIS_ACTIONABLES),
                "has_scrollable" to signals.hasScrollable,
                "has_editable" to signals.hasEditable,
                "has_focused_input" to signals.hasFocusedInput,
            ).filterValues { it != null },
            "decision_hints" to suggestedPolicy,
            "page_vector_stats" to linkedMapOf(
                "schema_version" to OobPageVectorSet.SCHEMA_VERSION,
                "page_vector_dim" to pageVector.vector.size,
                "element_count" to pageVector.elementCount,
                "actionable_count" to pageVector.actionableCount,
                "focus_target_count" to pageVector.focusTargetCount,
                "display_text_count" to pageVector.displayTextCount,
            ),
            "visual_observation" to linkedMapOf(
                "screenshot_present" to (screenshotHash != null),
                "screenshot_sha256" to screenshotHash,
            ).filterValues { it != null },
            "privacy" to linkedMapOf(
                "raw_xml_stored" to false,
                "raw_screenshot_stored" to false,
                "editable_text_stored" to false,
                "screenshot_encoding" to "sha256",
            ),
            "created_at" to longArg(previous["created_at"], defaultValue = now),
            "updated_at" to now,
        )
    }

    private fun compactPageAnalysisForDecision(pageAnalysis: Map<String, Any?>): Map<String, Any?> {
        if (pageAnalysis.isEmpty()) return emptyMap()
        val summary = mapArg(pageAnalysis["summary"])
        return linkedMapOf(
            "title" to summary["title"],
            "page_role" to summary["page_role"],
            "visible_texts" to listArg(summary["visible_texts"]).take(8),
            "actionables" to listArg(summary["actionables"]).take(8),
            "has_scrollable" to summary["has_scrollable"],
            "has_editable" to summary["has_editable"],
            "decision_hints" to listArg(pageAnalysis["decision_hints"]).take(4),
        ).filterValues { it != null }
    }

    private fun renderPageAnalysisForSkill(pageAnalysis: Map<String, Any?>): String {
        val compact = compactPageAnalysisForDecision(pageAnalysis)
        if (compact.isEmpty()) return ""
        val visibleTexts = listArg(compact["visible_texts"])
            .joinToString(" / ") { it.toString() }
            .take(MAX_RENDERED_TEXT_CHARS)
        val actionables = listArg(compact["actionables"])
            .joinToString(" / ") { it.toString() }
            .take(MAX_RENDERED_TEXT_CHARS)
        val hints = listArg(compact["decision_hints"]).joinToString(" ")
        return buildString {
            firstNonBlank(compact["title"]).takeIf { it.isNotBlank() }?.let {
                appendLine("Title: $it")
            }
            firstNonBlank(compact["page_role"]).takeIf { it.isNotBlank() }?.let {
                appendLine("Role: $it")
            }
            if (visibleTexts.isNotBlank()) appendLine("Visible texts: $visibleTexts")
            if (actionables.isNotBlank()) appendLine("Actionables: $actionables")
            if (hints.isNotBlank()) appendLine("Decision hints: $hints")
        }.trim()
    }

    private fun parsePageSignals(xml: String): PageSignals {
        if (xml.isBlank()) return PageSignals()
        val document = runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                isExpandEntityReferences = false
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            }
            factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        }.getOrNull() ?: return PageSignals()

        val visibleTexts = linkedSetOf<String>()
        val actionables = linkedSetOf<String>()
        var hasScrollable = false
        var hasEditable = false
        var hasFocusedInput = false
        val nodeList = document.getElementsByTagName("node")
        for (index in 0 until nodeList.length) {
            val element = nodeList.item(index) as? Element ?: continue
            val editable = element.boolAttr("editable") || element.attr("class").contains("EditText", ignoreCase = true)
            val focused = element.boolAttr("focused")
            val scrollable = element.boolAttr("scrollable")
            val sensitive = editable || element.boolAttr("password")
            val label = normalizeLabel(
                if (sensitive) {
                    firstNonBlank(element.attr("hint"), element.attr("hintText"), element.attr("content-desc"))
                } else {
                    firstNonBlank(element.attr("text"), element.attr("content-desc"), element.attr("hint"), element.attr("hintText"))
                }
            )
            if (label.isNotBlank() && !isOverlayLabel(label)) {
                visibleTexts += label
            }
            val primaryActionable = element.boolAttr("clickable") ||
                element.boolAttr("long-clickable") ||
                element.boolAttr("focusable") ||
                editable
            val actionable = primaryActionable ||
                scrollable
            if (primaryActionable) {
                val actionLabel = normalizeLabel(
                    label.ifBlank {
                        descendantLabels(element)
                            .take(MAX_DESCENDANT_LABELS_FOR_ACTION)
                            .joinToString(" / ")
                    }
                )
                if (actionLabel.isNotBlank() && !isOverlayLabel(actionLabel)) {
                    actionables += actionLabel
                }
            }
            hasScrollable = hasScrollable || scrollable
            hasEditable = hasEditable || editable
            hasFocusedInput = hasFocusedInput || (editable && focused)
        }
        return PageSignals(
            title = visibleTexts.firstOrNull().orEmpty(),
            visibleTexts = visibleTexts.take(MAX_ANALYSIS_TEXTS),
            actionableLabels = actionables.take(MAX_ANALYSIS_ACTIONABLES),
            hasScrollable = hasScrollable,
            hasEditable = hasEditable,
            hasFocusedInput = hasFocusedInput,
        )
    }

    private fun descendantLabels(element: Element): List<String> {
        val labels = linkedSetOf<String>()
        fun visit(current: Element) {
            val editable = current.boolAttr("editable") || current.attr("class").contains("EditText", ignoreCase = true)
            val sensitive = editable || current.boolAttr("password")
            val label = normalizeLabel(
                if (sensitive) {
                    firstNonBlank(current.attr("hint"), current.attr("hintText"), current.attr("content-desc"))
                } else {
                    firstNonBlank(current.attr("text"), current.attr("content-desc"), current.attr("hint"), current.attr("hintText"))
                }
            )
            if (label.isNotBlank() && !isOverlayLabel(label)) {
                labels += label
            }
            val children = current.childNodes
            for (index in 0 until children.length) {
                val child = children.item(index) as? Element ?: continue
                visit(child)
            }
        }
        val children = element.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index) as? Element ?: continue
            visit(child)
            if (labels.size >= MAX_DESCENDANT_LABELS_FOR_ACTION) break
        }
        return labels.toList()
    }

    private data class PageSignals(
        val title: String = "",
        val visibleTexts: List<String> = emptyList(),
        val actionableLabels: List<String> = emptyList(),
        val hasScrollable: Boolean = false,
        val hasEditable: Boolean = false,
        val hasFocusedInput: Boolean = false,
    )

    private fun Element.attr(name: String): String {
        val direct = getAttribute(name).trim()
        if (direct.isNotEmpty()) return direct
        return when (name) {
            "hint", "hintText" -> getAttribute("hint-text").trim()
            "long-clickable" -> getAttribute("longClickable").trim()
            else -> ""
        }
    }

    private fun Element.boolAttr(name: String): Boolean {
        val value = attr(name).lowercase(Locale.US)
        return value == "true" || value == "1"
    }

    private fun normalizeLabel(value: String): String {
        return value
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_LABEL_CHARS)
    }

    private fun isOverlayLabel(value: String): Boolean {
        val normalized = value.lowercase(Locale.US)
        return normalized == "继续执行" ||
            normalized == "接管" ||
            normalized == "已接管控制，完成操作后点击继续" ||
            normalized.contains("omnibot") ||
            normalized.contains("oob")
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun intArg(vararg values: Any?, defaultValue: Int): Int {
        for (value in values) {
            when (value) {
                is Number -> return value.toInt()
                is String -> value.trim().toIntOrNull()?.let { return it }
            }
        }
        return defaultValue
    }

    private fun longArg(vararg values: Any?, defaultValue: Long): Long {
        for (value in values) {
            when (value) {
                is Number -> return value.toLong()
                is String -> value.trim().toLongOrNull()?.let { return it }
            }
        }
        return defaultValue
    }

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

    fun observePage(observedPage: ObservedPage): PageObservationResult? {
        val pageVector = OobPageVectorSet.encode(
            xml = observedPage.pageXml,
            packageName = observedPage.packageName,
        ) ?: return null
        val existingMatch = findBestNodeMatch(
            pageVector = pageVector,
            currentPackage = observedPage.packageName,
            minScore = STRONG_PAGE_MATCH_SCORE,
        )
        val now = System.currentTimeMillis()
        val targetNodeId = existingMatch?.node?.get("node_id")?.toString()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: pageVector.nodeId
        val existing = if (targetNodeId == pageVector.nodeId && existingMatch == null) {
            getNode(targetNodeId)
        } else {
            existingMatch?.node ?: emptyMap()
        }
        val existingRegistry = mapArg(existing["_oob_registry"])
        val existingFirstSeen = longArg(existing["first_seen_at"], existingRegistry["first_seen_at"], defaultValue = now)
        val firstSeen = existing.isEmpty()
        val functions = functionSummaries(existing)
        val pageAnalysis = buildPageAnalysis(
            observedPage = observedPage,
            pageVector = pageVector,
            existing = existing,
            now = now,
        )
        val node = linkedMapOf<String, Any?>(
            "schema_version" to NODE_SCHEMA_VERSION,
            "node_id" to targetNodeId,
            "package_name" to pageVector.packageName,
            "activity_name" to firstNonBlank(observedPage.activityName, existing["activity_name"]).takeIf { it.isNotBlank() },
            "page_vector_set" to pageVector.toMap(),
            "page_analysis" to pageAnalysis,
            "skill" to buildNodeSkill(
                nodeId = targetNodeId,
                packageName = pageVector.packageName,
                functions = functions,
                pageAnalysis = pageAnalysis,
            ),
            "decision_context" to buildDecisionContext(
                nodeId = targetNodeId,
                packageName = pageVector.packageName,
                functionCount = functions.size,
                pageAnalysis = pageAnalysis,
            ),
            "functions" to functions,
            "first_seen_at" to existingFirstSeen,
            "last_seen_at" to now,
            "updated_at" to now,
            "source" to "oob_native_udeg",
        ).filterValues { it != null }.toMutableMap()

        val registry = existingRegistry.toMutableMap()
        val seenCount = intArg(registry["seen_count"], existing["seen_count"], defaultValue = 0) + 1
        node["_oob_registry"] = registry.apply {
            put("node_kind", "page")
            put("function_count", functions.size)
            put("seen_count", seenCount)
            put("first_seen_at", existingFirstSeen)
            put("last_seen_at", now)
            put("last_goal_hash", observedPage.goal.takeIf { it.isNotBlank() }?.let(::sha256))
            put("updated_at", now)
        }.filterValues { it != null }

        saveNode(targetNodeId, node)
        val similarity = existingMatch?.pageSimilarity ?: 1.0f
        val reason = when {
            firstSeen -> "page_vector_first_seen"
            existingMatch != null -> existingMatch.reason
            else -> "page_vector_exact_node_update"
        }
        return PageObservationResult(
            node = node,
            pageSimilarity = roundScore(similarity),
            firstSeen = firstSeen,
            reason = reason,
        )
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
        private const val PAGE_ANALYSIS_SCHEMA_VERSION = "oob.udeg.page_analysis.v1"
        const val UDEG_DECISION_PATH =
            "page match -> UDEG node -> node skill-like decision context -> VLM/tool decision"
        const val MIN_PAGE_MATCH_SCORE = 0.30f
        const val STRONG_PAGE_MATCH_SCORE = 0.87f
        private const val MAX_NODE_SCAN = 1_000
        private const val MAX_ANALYSIS_TEXTS = 16
        private const val MAX_ANALYSIS_ACTIONABLES = 16
        private const val MAX_LABEL_CHARS = 80
        private const val MAX_RENDERED_TEXT_CHARS = 400
        private const val MAX_DESCENDANT_LABELS_FOR_ACTION = 3
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

        fun nodeSkillContext(
            node: Map<String, Any?>,
            pageSimilarity: Float,
            reason: String,
        ): Map<String, Any?> = linkedMapOf(
            "schema_version" to "oob.udeg.node_skill_context.v1",
            "role" to "decision",
            "context_kind" to "udeg_node_skill_like_decision_context",
            "decision_path" to UDEG_DECISION_PATH,
            "entry_policy" to "page_match_to_udeg_node",
            "page_match" to linkedMapOf(
                "node_id" to node["node_id"],
                "page_similarity" to pageSimilarity,
                "reason" to reason,
            ).filterValues { it != null },
            "udeg_node" to linkedMapOf(
                "node_id" to node["node_id"],
                "package_name" to node["package_name"],
                "activity_name" to node["activity_name"],
            ).filterValues { it != null },
            "skill" to mapArg(node["skill"]).takeIf { it.isNotEmpty() },
            "decision_context" to mapArg(node["decision_context"]).takeIf { it.isNotEmpty() },
            "attached_functions" to functionSummaries(node),
        ).filterValues { it != null }

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
