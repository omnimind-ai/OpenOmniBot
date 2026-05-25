package cn.com.omnimind.bot.runlog

import android.content.Context
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import com.google.gson.GsonBuilder
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.File
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
            "skill_artifact" to mapArg(node["skill_artifact"]).takeIf { it.isNotEmpty() },
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
            "skill_artifact" to mapArg(node["skill_artifact"]).takeIf { it.isNotEmpty() },
            "node_skill_context" to nodeSkillContext(
                node = node,
                pageSimilarity = pageSimilarity,
                reason = reason,
            ),
            "function_ids" to functionIds(node),
            "segment_count" to segmentSummaries(node).size,
            "segments" to segmentSummaries(node),
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
        val mergedSegments = segmentSummaries(existing)
            .filterNot { it["function_id"]?.toString() == normalizedFunctionId }
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
                segments = mergedSegments,
                pageAnalysis = existingPageAnalysis,
            ),
            "decision_context" to buildDecisionContext(
                nodeId = targetNodeId,
                packageName = pageVector.packageName,
                functionCount = mergedFunctions.size,
                segmentCount = mergedSegments.size,
                pageAnalysis = existingPageAnalysis,
            ),
            "functions" to mergedFunctions,
            "segments" to mergedSegments,
            "first_seen_at" to longArg(existing["first_seen_at"], defaultValue = System.currentTimeMillis()),
            "last_seen_at" to mapArg(existing["_oob_registry"])["last_seen_at"],
            "updated_at" to System.currentTimeMillis(),
            "source" to "oob_native_udeg",
        ).filterValues { it != null }.toMutableMap()
        val registry = mapArg(existing["_oob_registry"]).toMutableMap()
        node["_oob_registry"] = registry.apply {
            put("node_kind", "page")
            put("function_count", mergedFunctions.size)
            put("segment_count", mergedSegments.size)
            put("last_function_id", normalizedFunctionId)
            put("updated_at", System.currentTimeMillis())
        }

        saveNode(targetNodeId, node)
        upsertFunctionSegmentNodes(
            functionSpec = functionSpec,
            functionId = normalizedFunctionId,
            startNodeId = targetNodeId,
        )
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
            "skill_artifact" to mapArg(node["skill_artifact"]).takeIf { it.isNotEmpty() },
            "function_count" to mergedFunctions.size,
            "segment_count" to mergedSegments.size,
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
                val vector = OobPageVectorSet.vectorFrom(
                    mapArg(node["page_vector_set"])["page_vector"]
                )
                val rawScore = OobPageVectorSet.cosine(query.vector, vector)
                val packageMultiplier = packageMatchMultiplier(
                    queryPackage = query.packageName,
                    nodePackage = nodePackage,
                )
                val score = rawScore * packageMultiplier
                if (score < minScore) return@mapNotNull null
                RecallMatch(
                    node = node,
                    pageSimilarity = roundScore(score),
                    reason = pageMatchReason(score, rawScore, packageMultiplier),
                )
            }
            .sortedByDescending { it.pageSimilarity }
            .take(topK.coerceIn(1, 50))
    }

    fun listNodes(limit: Int = 100): List<Map<String, Any?>> {
        val normalizedLimit = limit.coerceIn(1, MAX_NODE_SCAN)
        val nodesById = linkedMapOf<String, Map<String, Any?>>()
        listNodeIds()
            .asReversed()
            .take(normalizedLimit)
            .mapNotNull(::getNodeFromPreferences)
            .forEach { node ->
                val nodeId = firstNonBlank(node["node_id"])
                if (nodeId.isNotBlank()) {
                    nodesById[nodeId] = node
                }
            }
        listArtifactNodes(normalizedLimit).forEach { node ->
            val nodeId = firstNonBlank(node["node_id"])
            if (nodeId.isNotBlank() && nodeId !in nodesById) {
                nodesById[nodeId] = node
            }
        }
        return nodesById.values
            .sortedByDescending { node ->
                val registry = mapArg(node["_oob_registry"])
                longArg(node["updated_at"], node["last_seen_at"], registry["updated_at"], defaultValue = 0L)
            }
            .take(normalizedLimit)
    }

    fun getNode(nodeId: String): Map<String, Any?> {
        return getNodeFromPreferences(nodeId).takeIf { it.isNotEmpty() }
            ?: getNodeFromArtifact(nodeId)
    }

    fun removeFunctionReferences(functionIds: Set<String>): Map<String, Any?> {
        val normalizedIds = functionIds.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        return rewriteFunctionReferences { functionId ->
            normalizedIds.isEmpty() || functionId in normalizedIds
        }
    }

    fun clearFunctionReferences(): Map<String, Any?> =
        rewriteFunctionReferences { true }

    private fun rewriteFunctionReferences(shouldRemove: (String) -> Boolean): Map<String, Any?> {
        var scanned = 0
        var updated = 0
        var removedFunctions = 0
        var removedSegments = 0
        listNodes(limit = MAX_NODE_SCAN).forEach { node ->
            scanned += 1
            val nodeId = firstNonBlank(node["node_id"])
            if (nodeId.isBlank()) return@forEach
            val currentFunctions = functionSummaries(node)
            val currentSegments = segmentSummaries(node)
            val nextFunctions = currentFunctions.filterNot { function ->
                val functionId = firstNonBlank(function["function_id"])
                functionId.isNotBlank() && shouldRemove(functionId)
            }
            val nextSegments = currentSegments.filterNot { segment ->
                val functionId = firstNonBlank(segment["function_id"])
                functionId.isNotBlank() && shouldRemove(functionId)
            }
            if (nextFunctions.size == currentFunctions.size &&
                nextSegments.size == currentSegments.size
            ) {
                return@forEach
            }
            removedFunctions += currentFunctions.size - nextFunctions.size
            removedSegments += currentSegments.size - nextSegments.size
            val packageName = firstNonBlank(node["package_name"])
            val pageAnalysis = mapArg(node["page_analysis"])
            val updatedNode = linkedMapOf<String, Any?>().apply {
                putAll(node)
                put("functions", nextFunctions)
                put("segments", nextSegments)
                put(
                    "skill",
                    buildNodeSkill(
                        nodeId = nodeId,
                        packageName = packageName,
                        functions = nextFunctions,
                        segments = nextSegments,
                        pageAnalysis = pageAnalysis,
                    )
                )
                put(
                    "decision_context",
                    buildDecisionContext(
                        nodeId = nodeId,
                        packageName = packageName,
                        functionCount = nextFunctions.size,
                        segmentCount = nextSegments.size,
                        pageAnalysis = pageAnalysis,
                    )
                )
                put("updated_at", System.currentTimeMillis())
                val registry = mapArg(node["_oob_registry"]).toMutableMap()
                put(
                    "_oob_registry",
                    registry.apply {
                        put("node_kind", "page")
                        put("function_count", nextFunctions.size)
                        put("segment_count", nextSegments.size)
                        put("updated_at", System.currentTimeMillis())
                        if (nextFunctions.isEmpty()) remove("last_function_id")
                    }
                )
            }.filterValues { it != null }.toMutableMap()
            saveNode(nodeId, updatedNode)
            updated += 1
        }
        return linkedMapOf(
            "success" to true,
            "scanned_node_count" to scanned,
            "updated_node_count" to updated,
            "removed_function_reference_count" to removedFunctions,
            "removed_segment_reference_count" to removedSegments,
            "source" to "oob_udeg_node_store",
        )
    }

    private fun getNodeFromPreferences(nodeId: String): Map<String, Any?> {
        val raw = prefs().getString(nodeKey(nodeId.trim()), null)?.takeIf { it.isNotBlank() }
            ?: return emptyMap()
        return runCatching { decodeMap(raw) }.getOrDefault(emptyMap())
    }

    private fun saveNode(nodeId: String, node: MutableMap<String, Any?>) {
        val normalizedNodeId = nodeId.trim()
        exportNodeSkillArtifact(node)?.let { artifact ->
            node["skill_artifact"] = artifact
        }
        prefs().edit()
            .putString(nodeKey(normalizedNodeId), gson.toJson(node))
            .putString(INDEX_KEY, gson.toJson((listNodeIds() - normalizedNodeId) + normalizedNodeId))
            .apply()
    }

    private fun exportNodeSkillArtifact(node: Map<String, Any?>): Map<String, Any?>? {
        val nodeId = firstNonBlank(node["node_id"])
        if (nodeId.isBlank()) return null
        val skill = mapArg(node["skill"])
        val body = firstNonBlank(skill["body"])
        if (body.isBlank()) return null
        val packageName = firstNonBlank(node["package_name"])
        val activityName = firstNonBlank(node["activity_name"])
        val skillId = firstNonBlank(skill["id"], "udeg_node_skill_$nodeId")
        val safeNodeId = safePathSegment(nodeId)
        val safePackage = safePathSegment(packageName.ifBlank { "unknown_package" })
        val artifactDir = File(udegSkillArtifactsRoot(), "$safePackage/$safeNodeId")
        val skillFile = File(artifactDir, "SKILL.md")
        val payloadFile = File(artifactDir, "skill.json")
        val updatedAt = longArg(node["updated_at"], defaultValue = System.currentTimeMillis())
        val artifact = linkedMapOf<String, Any?>(
            "schema_version" to NODE_SKILL_ARTIFACT_SCHEMA_VERSION,
            "kind" to "oob_udeg_node_skill_artifact",
            "skill_id" to skillId,
            "node_id" to nodeId,
            "package_name" to packageName.takeIf { it.isNotBlank() },
            "activity_name" to activityName.takeIf { it.isNotBlank() },
            "activation" to linkedMapOf(
                "type" to "page_match",
                "page_vector_set_schema" to OobPageVectorSet.SCHEMA_VERSION,
                "min_page_similarity" to MIN_PAGE_MATCH_SCORE,
                "strong_page_similarity" to STRONG_PAGE_MATCH_SCORE,
            ),
            "decision_path" to UDEG_DECISION_PATH,
            "paths" to linkedMapOf(
                "root_path" to artifactDir.absolutePath,
                "skill_file_path" to skillFile.absolutePath,
                "payload_path" to payloadFile.absolutePath,
            ),
            "indexed_by" to "AgentWorkspaceManager.skillsRoot",
            "updated_at" to updatedAt,
        ).filterValues { it != null }

        val payload = buildSkillArtifactPayload(
            node = node,
            artifact = artifact,
        )
        return runCatching {
            artifactDir.mkdirs()
            skillFile.writeText(renderSkillArtifactMarkdown(node, artifact, body))
            payloadFile.writeText(gson.toJson(payload))
            writeSkillArtifactIndex(artifact)
            artifact
        }.getOrNull()
    }

    private fun buildSkillArtifactPayload(
        node: Map<String, Any?>,
        artifact: Map<String, Any?>,
    ): Map<String, Any?> {
        val vectorSet = mapArg(node["page_vector_set"])
        val vectorStats = linkedMapOf(
            "schema_version" to vectorSet["schema_version"],
            "node_id" to vectorSet["node_id"],
            "package_name" to vectorSet["package_name"],
            "page_vector_dim" to vectorSet["page_vector_dim"],
            "element_count" to vectorSet["element_count"],
            "actionable_count" to vectorSet["actionable_count"],
            "focus_target_count" to vectorSet["focus_target_count"],
            "display_text_count" to vectorSet["display_text_count"],
            "signature" to vectorSet["signature"],
            "privacy" to vectorSet["privacy"],
        ).filterValues { it != null }
        return linkedMapOf(
            "schema_version" to NODE_SKILL_ARTIFACT_SCHEMA_VERSION,
            "artifact" to artifact,
            "node" to linkedMapOf(
                "schema_version" to node["schema_version"],
                "node_id" to node["node_id"],
                "package_name" to node["package_name"],
                "activity_name" to node["activity_name"],
                "first_seen_at" to node["first_seen_at"],
                "last_seen_at" to node["last_seen_at"],
                "updated_at" to node["updated_at"],
                "source" to node["source"],
            ).filterValues { it != null },
            "page_match" to linkedMapOf(
                "page_vector_set" to vectorStats,
                "page_vector" to OobPageVectorSet.vectorFrom(vectorSet["page_vector"]),
            ),
            "page_analysis" to mapArg(node["page_analysis"]).takeIf { it.isNotEmpty() },
            "skill" to mapArg(node["skill"]).takeIf { it.isNotEmpty() },
            "decision_context" to mapArg(node["decision_context"]).takeIf { it.isNotEmpty() },
            "functions" to functionSummaries(node),
            "segments" to segmentSummaries(node),
            "registry" to mapArg(node["_oob_registry"]).takeIf { it.isNotEmpty() },
            "privacy" to linkedMapOf(
                "raw_xml_stored" to false,
                "raw_screenshot_stored" to false,
                "editable_text_stored" to false,
                "artifact_contains_page_vector" to true,
            ),
        ).filterValues { it != null }
    }

    private fun renderSkillArtifactMarkdown(
        node: Map<String, Any?>,
        artifact: Map<String, Any?>,
        body: String,
    ): String {
        val skill = mapArg(node["skill"])
        val nodeId = firstNonBlank(node["node_id"])
        val skillName = firstNonBlank(mapArg(skill["frontmatter"])["name"], "udeg-node-$nodeId")
        val description = firstNonBlank(
            mapArg(skill["frontmatter"])["description"],
            skill["description"],
            "Decision context for a page-matched UDEG node."
        )
        val packageName = firstNonBlank(node["package_name"])
        val activityName = firstNonBlank(node["activity_name"])
        val payloadPath = firstNonBlank(mapArg(artifact["paths"])["payload_path"])
        val metadata = buildList {
            add("  kind: oob_udeg_node_skill")
            add("  schema_version: $NODE_SKILL_ARTIFACT_SCHEMA_VERSION")
            add("  node_id: $nodeId")
            if (packageName.isNotBlank()) add("  package_name: $packageName")
            if (activityName.isNotBlank()) add("  activity_name: $activityName")
            add("  activation: page_match")
            add("  decision_path: $UDEG_DECISION_PATH")
            if (payloadPath.isNotBlank()) add("  structured_payload: $payloadPath")
        }.joinToString("\n")
        return buildString {
            appendLine("---")
            appendLine("name: $skillName")
            appendLine("description: $description")
            appendLine("compatibility: android,oob,udeg")
            appendLine("metadata:")
            appendLine(metadata)
            appendLine("---")
            appendLine()
            appendLine(body)
            appendLine()
            appendLine("## Structured Payload")
            appendLine()
            appendLine("This UDEG node skill is backed by `skill.json`. The online VLM should receive it only after page match localizes the current screen to this node.")
        }.trim() + "\n"
    }

    private fun writeSkillArtifactIndex(artifact: Map<String, Any?>) {
        val root = udegSkillArtifactsRoot()
        val indexFile = File(root, ARTIFACT_INDEX_FILE)
        val existing = runCatching {
            gson.fromJson(indexFile.readText(), List::class.java)
                ?.mapNotNull { raw -> mapArg(raw).takeIf { it.isNotEmpty() } }
        }.getOrNull().orEmpty()
        val nodeId = firstNonBlank(artifact["node_id"])
        val merged = existing
            .filterNot { firstNonBlank(it["node_id"]) == nodeId }
            .plus(artifact)
            .sortedByDescending { longArg(it["updated_at"], defaultValue = 0L) }
        root.mkdirs()
        indexFile.writeText(gson.toJson(merged))
    }

    private fun listArtifactNodes(limit: Int): List<Map<String, Any?>> {
        val root = udegSkillArtifactsRoot()
        if (!root.exists()) return emptyList()
        return root.walkTopDown()
            .onEnter { directory -> directory.name != ".git" }
            .filter { file -> file.isFile && file.name == "skill.json" }
            .take(limit.coerceIn(1, MAX_NODE_SCAN))
            .mapNotNull { file ->
                runCatching {
                    val payload = decodeMap(file.readText())
                    nodeFromArtifactPayload(payload)
                }.getOrNull()?.takeIf { it.isNotEmpty() }
            }
            .toList()
    }

    private fun getNodeFromArtifact(nodeId: String): Map<String, Any?> {
        val safeNodeId = safePathSegment(nodeId.trim())
        if (safeNodeId.isBlank()) return emptyMap()
        val root = udegSkillArtifactsRoot()
        if (!root.exists()) return emptyMap()
        return root.walkTopDown()
            .onEnter { directory -> directory.name != ".git" }
            .filter { file -> file.isFile && file.name == "skill.json" && file.parentFile?.name == safeNodeId }
            .mapNotNull { file ->
                runCatching {
                    nodeFromArtifactPayload(decodeMap(file.readText()))
                }.getOrNull()
            }
            .firstOrNull()
            .orEmpty()
    }

    private fun nodeFromArtifactPayload(payload: Map<String, Any?>): Map<String, Any?> {
        val node = mapArg(payload["node"])
        val pageMatch = mapArg(payload["page_match"])
        val pageVectorSet = mapArg(pageMatch["page_vector_set"]).toMutableMap()
        val pageVector = OobPageVectorSet.vectorFrom(pageMatch["page_vector"])
        if (pageVector.isNotEmpty()) {
            pageVectorSet["page_vector"] = pageVector
        }
        val nodeId = firstNonBlank(node["node_id"], pageVectorSet["node_id"])
        if (nodeId.isBlank()) return emptyMap()
        return linkedMapOf<String, Any?>(
            "schema_version" to firstNonBlank(node["schema_version"], NODE_SCHEMA_VERSION),
            "node_id" to nodeId,
            "package_name" to firstNonBlank(node["package_name"], pageVectorSet["package_name"]).takeIf { it.isNotBlank() },
            "activity_name" to firstNonBlank(node["activity_name"]).takeIf { it.isNotBlank() },
            "page_vector_set" to pageVectorSet.takeIf { it.isNotEmpty() },
            "page_analysis" to mapArg(payload["page_analysis"]).takeIf { it.isNotEmpty() },
            "skill" to mapArg(payload["skill"]).takeIf { it.isNotEmpty() },
            "decision_context" to mapArg(payload["decision_context"]).takeIf { it.isNotEmpty() },
            "functions" to listArg(payload["functions"]).mapNotNull { mapArg(it).takeIf(Map<String, Any?>::isNotEmpty) },
            "segments" to listArg(payload["segments"]).mapNotNull { mapArg(it).takeIf(Map<String, Any?>::isNotEmpty) },
            "skill_artifact" to mapArg(payload["artifact"]).takeIf { it.isNotEmpty() },
            "_oob_registry" to mapArg(payload["registry"]).takeIf { it.isNotEmpty() },
            "first_seen_at" to node["first_seen_at"],
            "last_seen_at" to node["last_seen_at"],
            "updated_at" to node["updated_at"],
            "source" to firstNonBlank(node["source"], "oob_native_udeg_artifact"),
        ).filterValues { it != null }
    }

    private fun udegSkillArtifactsRoot(): File {
        return runCatching {
            File(AgentWorkspaceManager(context).skillsRoot(), UDEG_SKILL_ARTIFACTS_DIR)
        }.getOrElse {
            File(context.filesDir, "workspace/.omnibot/skills/$UDEG_SKILL_ARTIFACTS_DIR")
        }
    }

    private fun buildNodeSkill(
        nodeId: String,
        packageName: String,
        functions: List<Map<String, Any?>>,
        segments: List<Map<String, Any?>> = emptyList(),
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
        val segmentCapabilities = segments.mapNotNull { segment ->
            val functionId = segment["function_id"]?.toString()?.trim().orEmpty()
            if (functionId.isEmpty()) return@mapNotNull null
            linkedMapOf(
                "function_id" to functionId,
                "name" to segment["name"],
                "description" to segment["description"],
                "input_schema" to segment["input_schema"],
                "matched_boundary" to segment["matched_boundary"],
                "matched_step_index" to segment["matched_step_index"],
                "start_step_index" to segment["start_step_index"],
                "remaining_step_count" to segment["remaining_step_count"],
                "step_summaries" to segment["step_summaries"],
            ).filterValues { it != null }
        }
        val guidance = buildString {
            append("UDEG page-match localized the current screen to node ")
            append(nodeId)
            if (packageName.isNotBlank()) append(" in ").append(packageName)
            append(". Use this node skill as decision context. ")
            append("Prefer an attached Function or segment only when its description and boundary fit the user's goal; ")
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
                segmentCapabilities = segmentCapabilities,
                pageAnalysis = pageAnalysis,
            ),
            "capabilities" to capabilities,
            "segment_capabilities" to segmentCapabilities,
            "function_count" to capabilities.size,
            "segment_count" to segmentCapabilities.size,
        )
    }

    private fun buildDecisionContext(
        nodeId: String,
        packageName: String,
        functionCount: Int,
        segmentCount: Int = 0,
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
        "segment_count" to segmentCount,
        "page_analysis" to compactPageAnalysisForDecision(pageAnalysis).takeIf { it.isNotEmpty() },
        "usage" to listOf(
            "choose the next live VLM action from the current page",
            "select an attached Function or Function segment only when the node, boundary, and goal match",
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
        segmentCapabilities: List<Map<String, Any?>> = emptyList(),
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
        if (segmentCapabilities.isNotEmpty()) {
            appendLine()
            appendLine("## Attached Function Segments")
            segmentCapabilities.forEach { capability ->
                val functionId = capability["function_id"]?.toString()?.trim().orEmpty()
                if (functionId.isBlank()) return@forEach
                val description = firstNonBlank(capability["description"], capability["name"], functionId)
                val startStepIndex = firstNonBlank(capability["start_step_index"])
                appendLine("- `$functionId` from step `$startStepIndex`: $description")
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

    private fun safePathSegment(value: String): String {
        return value.trim()
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('_')
            .take(MAX_ARTIFACT_SEGMENT_CHARS)
            .ifBlank { "unknown" }
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

    private fun segmentSummaries(
        functionSpec: Map<String, Any?>,
        functionId: String,
    ): List<Map<String, Any?>> {
        val steps = materializedSteps(functionSpec)
        if (steps.size < 2) return emptyList()
        val summaries = stepSummaries(functionSpec)
        return steps.flatMapIndexed { index, step ->
            segmentBoundaryContexts(step, index).mapNotNull { boundary ->
                if (boundary.startStepIndex <= 0 || boundary.startStepIndex >= steps.size) {
                    return@mapNotNull null
                }
                val remainingSummaries = summaries.drop(boundary.startStepIndex)
                if (remainingSummaries.isEmpty()) return@mapNotNull null
                linkedMapOf(
                    "function_id" to functionId,
                    "name" to firstNonBlank(functionSpec["name"], functionId),
                    "description" to firstNonBlank(functionSpec["description"], functionSpec["name"], functionId),
                    "input_schema" to OobFunctionSchemaBuilder.inputSchema(functionSpec),
                    "matched_boundary" to boundary.boundary,
                    "matched_step_index" to boundary.matchedStepIndex,
                    "start_step_index" to boundary.startStepIndex,
                    "remaining_step_count" to remainingSummaries.size,
                    "step_summaries" to remainingSummaries,
                    "source" to mapArg(functionSpec["source"]),
                )
            }
        }
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

    private fun materializedSteps(functionSpec: Map<String, Any?>): List<Map<String, Any?>> =
        listArg(mapArg(functionSpec["execution"])["steps"]).mapNotNull { raw ->
            mapArg(raw).takeIf { it.isNotEmpty() }
        }

    private fun segmentBoundaryContexts(
        step: Map<String, Any?>,
        stepIndex: Int,
    ): List<SegmentBoundaryContext> {
        val sourceContext = mapArg(step["source_context"])
            .ifEmpty { mapArg(mapArg(step["args"])["source_context"]) }
        if (sourceContext.isEmpty()) return emptyList()
        val output = mutableListOf<SegmentBoundaryContext>()
        val srcCtx = mapArg(sourceContext["src_ctx"])
        val srcPage = firstNonBlank(
            srcCtx["page"],
            srcCtx["xml"],
            srcCtx["observation_xml"],
            srcCtx["observationXml"],
        )
        if (srcPage.isNotBlank()) {
            output += SegmentBoundaryContext(
                boundary = "src_ctx",
                pageXml = srcPage,
                packageName = firstNonBlank(srcCtx["package_name"], srcCtx["packageName"]),
                matchedStepIndex = stepIndex,
                startStepIndex = stepIndex,
            )
        }
        val dstCtx = mapArg(sourceContext["dst_ctx"])
        val dstPage = firstNonBlank(
            dstCtx["page"],
            dstCtx["xml"],
            dstCtx["observation_xml"],
            dstCtx["observationXml"],
        )
        if (dstPage.isNotBlank()) {
            output += SegmentBoundaryContext(
                boundary = "dst_ctx",
                pageXml = dstPage,
                packageName = firstNonBlank(dstCtx["package_name"], dstCtx["packageName"]),
                matchedStepIndex = stepIndex,
                startStepIndex = stepIndex + 1,
            )
        }
        return output
    }

    private fun upsertFunctionSegmentNodes(
        functionSpec: Map<String, Any?>,
        functionId: String,
        startNodeId: String,
    ) {
        val steps = materializedSteps(functionSpec)
        if (steps.size < 2) return
        val summaries = stepSummaries(functionSpec)
        steps.forEachIndexed { index, step ->
            segmentBoundaryContexts(step, index).forEach { boundary ->
                if (boundary.startStepIndex <= 0 || boundary.startStepIndex >= steps.size) {
                    return@forEach
                }
                val pageVector = OobPageVectorSet.encode(
                    xml = boundary.pageXml,
                    packageName = boundary.packageName,
                ) ?: return@forEach
                val existingMatch = findBestNodeMatch(
                    pageVector = pageVector,
                    currentPackage = boundary.packageName,
                    minScore = MIN_PAGE_MATCH_SCORE,
                )
                val targetNodeId = existingMatch?.node?.get("node_id")?.toString()?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: pageVector.nodeId
                if (targetNodeId == startNodeId && boundary.startStepIndex == 0) return@forEach
                val existing = existingMatch?.node ?: getNode(targetNodeId)
                val existingFunctions = functionSummaries(existing)
                val existingSegments = segmentSummaries(existing).toMutableList()
                val newSegment = functionSegmentSummary(
                    functionSpec = functionSpec,
                    functionId = functionId,
                    boundary = boundary,
                    remainingStepSummaries = summaries.drop(boundary.startStepIndex),
                ) ?: return@forEach
                val mergedSegments = existingSegments
                    .filterNot {
                        it["function_id"]?.toString() == functionId &&
                            intArg(it["start_step_index"], defaultValue = -1) == boundary.startStepIndex
                    }
                    .plus(newSegment)
                val now = System.currentTimeMillis()
                val pageAnalysis = mapArg(existing["page_analysis"]).takeIf { it.isNotEmpty() }
                    ?: buildPageAnalysis(
                        observedPage = ObservedPage(
                            pageXml = boundary.pageXml,
                            packageName = boundary.packageName,
                        ),
                        pageVector = pageVector,
                        existing = existing,
                        now = now,
                    )
                val registry = mapArg(existing["_oob_registry"]).toMutableMap()
                val node = linkedMapOf<String, Any?>(
                    "schema_version" to NODE_SCHEMA_VERSION,
                    "node_id" to targetNodeId,
                    "package_name" to pageVector.packageName,
                    "activity_name" to firstNonBlank(existing["activity_name"]).takeIf { it.isNotBlank() },
                    "page_vector_set" to pageVector.toMap(),
                    "page_analysis" to pageAnalysis,
                    "skill" to buildNodeSkill(
                        nodeId = targetNodeId,
                        packageName = pageVector.packageName,
                        functions = existingFunctions,
                        segments = mergedSegments,
                        pageAnalysis = pageAnalysis,
                    ),
                    "decision_context" to buildDecisionContext(
                        nodeId = targetNodeId,
                        packageName = pageVector.packageName,
                        functionCount = existingFunctions.size,
                        segmentCount = mergedSegments.size,
                        pageAnalysis = pageAnalysis,
                    ),
                    "functions" to existingFunctions,
                    "segments" to mergedSegments,
                    "first_seen_at" to longArg(existing["first_seen_at"], registry["first_seen_at"], defaultValue = now),
                    "last_seen_at" to longArg(existing["last_seen_at"], registry["last_seen_at"], defaultValue = now),
                    "updated_at" to now,
                    "source" to "oob_native_udeg",
                ).filterValues { it != null }.toMutableMap()
                node["_oob_registry"] = registry.apply {
                    put("node_kind", "page")
                    put("function_count", existingFunctions.size)
                    put("segment_count", mergedSegments.size)
                    put("last_function_id", functionId)
                    put("updated_at", now)
                }.filterValues { it != null }
                saveNode(targetNodeId, node)
            }
        }
    }

    private fun functionSegmentSummary(
        functionSpec: Map<String, Any?>,
        functionId: String,
        boundary: SegmentBoundaryContext,
        remainingStepSummaries: List<Map<String, Any?>>,
    ): Map<String, Any?>? {
        if (remainingStepSummaries.isEmpty()) return null
        return linkedMapOf(
            "function_id" to functionId,
            "name" to firstNonBlank(functionSpec["name"], functionId),
            "description" to firstNonBlank(functionSpec["description"], functionSpec["name"], functionId),
            "input_schema" to OobFunctionSchemaBuilder.inputSchema(functionSpec),
            "matched_boundary" to boundary.boundary,
            "matched_step_index" to boundary.matchedStepIndex,
            "start_step_index" to boundary.startStepIndex,
            "remaining_step_count" to remainingStepSummaries.size,
            "step_summaries" to remainingStepSummaries,
            "source" to mapArg(functionSpec["source"]),
        )
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
        val segments = segmentSummaries(existing)
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
                segments = segments,
                pageAnalysis = pageAnalysis,
            ),
            "decision_context" to buildDecisionContext(
                nodeId = targetNodeId,
                packageName = pageVector.packageName,
                functionCount = functions.size,
                segmentCount = segments.size,
                pageAnalysis = pageAnalysis,
            ),
            "functions" to functions,
            "segments" to segments,
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
            put("segment_count", segments.size)
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

    private fun packageMatchMultiplier(queryPackage: String, nodePackage: String): Float {
        if (queryPackage.isBlank() || nodePackage.isBlank()) return 1.0f
        if (queryPackage == nodePackage) return 1.0f
        return PACKAGE_MISMATCH_MULTIPLIER
    }

    private fun pageMatchReason(score: Float, rawScore: Float, packageMultiplier: Float): String {
        val base = if (score >= STRONG_PAGE_MATCH_SCORE) {
            "page_vector_strong_match"
        } else {
            "page_vector_candidate"
        }
        if (packageMultiplier >= 1.0f) return base
        return "$base;package_soft_mismatch;raw_page_similarity=${roundScore(rawScore)}"
    }

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

    private data class SegmentBoundaryContext(
        val boundary: String,
        val pageXml: String,
        val packageName: String,
        val matchedStepIndex: Int,
        val startStepIndex: Int,
    )

    companion object {
        private const val PREFS_NAME = "oob_udeg_nodes"
        private const val INDEX_KEY = "oob_udeg_node_index_v1"
        private const val NODE_PREFIX = "oob_udeg_node_v1:"
        private const val NODE_SCHEMA_VERSION = "oob.udeg.node.v1"
        private const val NODE_SKILL_SCHEMA_VERSION = "oob.udeg.node_skill.v1"
        private const val NODE_SKILL_ARTIFACT_SCHEMA_VERSION = "oob.udeg.node_skill_artifact.v1"
        private const val NODE_DECISION_CONTEXT_SCHEMA_VERSION = "oob.udeg.decision_context.v1"
        private const val PAGE_ANALYSIS_SCHEMA_VERSION = "oob.udeg.page_analysis.v1"
        private const val UDEG_SKILL_ARTIFACTS_DIR = "oob-udeg-node-skills"
        private const val ARTIFACT_INDEX_FILE = "index.json"
        const val UDEG_DECISION_PATH =
            "page match -> UDEG node -> node skill-like decision context -> VLM/tool decision"
        const val MIN_PAGE_MATCH_SCORE = 0.30f
        const val STRONG_PAGE_MATCH_SCORE = 0.87f
        private const val PACKAGE_MISMATCH_MULTIPLIER = 0.82f
        private const val MAX_NODE_SCAN = 1_000
        private const val MAX_ANALYSIS_TEXTS = 16
        private const val MAX_ANALYSIS_ACTIONABLES = 16
        private const val MAX_LABEL_CHARS = 80
        private const val MAX_RENDERED_TEXT_CHARS = 400
        private const val MAX_DESCENDANT_LABELS_FOR_ACTION = 3
        private const val MAX_ARTIFACT_SEGMENT_CHARS = 96
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

        fun segmentSummaries(node: Map<String, Any?>): List<Map<String, Any?>> =
            listArg(node["segments"]).mapNotNull { raw ->
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
            "skill_artifact" to mapArg(node["skill_artifact"]).takeIf { it.isNotEmpty() },
            "skill" to mapArg(node["skill"]).takeIf { it.isNotEmpty() },
            "decision_context" to mapArg(node["decision_context"]).takeIf { it.isNotEmpty() },
            "attached_functions" to functionSummaries(node),
            "attached_segments" to segmentSummaries(node),
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
