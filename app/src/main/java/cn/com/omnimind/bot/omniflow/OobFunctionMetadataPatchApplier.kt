package cn.com.omnimind.bot.omniflow

/**
 * Applies non-structural metadata updates for update_function.
 *
 * Structural execution edits stay in OobFunctionUpdateService. This class owns
 * Function header updates, step labels, evidence metadata, checker metadata,
 * parameters, agent reuse hints, and update audit metadata.
 */
class OobFunctionMetadataPatchApplier(
    private val checkerPatchService: OobFunctionCheckerPatchService = OobFunctionCheckerPatchService(),
) {
    fun applyPatch(
        spec: MutableMap<String, Any?>,
        patch: Map<String, Any?>,
    ): List<Map<String, Any?>> {
        val changes = mutableListOf<Map<String, Any?>>()
        setStringFieldIfChanged(spec, "name", patch["name"], changes, "header")
        setStringFieldIfChanged(spec, "description", patch["description"], changes, "header")

        applyStepLabelPatches(spec, patch, changes)
        applyParameterPatch(spec, patch, changes)
        applyAgentReusePatch(spec, patch, changes)
        applyMetadataPatch(spec, patch, changes)
        applyTopLevelCheckerRulesPatch(spec, patch, changes)

        changes += checkerPatchService.applyOptionalCheckerMetadataFromSteps(spec)
        return changes
    }

    fun applyRunLogEvidenceAnalysis(
        spec: MutableMap<String, Any?>,
        runId: String,
        analysis: Map<String, Any?>,
    ): List<Map<String, Any?>> {
        val metadata = mutableJsonMap(mapArg(spec["metadata"]))
        val existingEvidence = mutableJsonMap(mapArg(metadata["oob_function_evidence"]))
        val sourceRunIds = listArg(existingEvidence["source_run_ids"])
            .mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            .toMutableList()
        if (runId.isNotBlank() && sourceRunIds.none { it == runId }) {
            sourceRunIds += runId
        }
        val evidence = linkedMapOf<String, Any?>().apply {
            putAll(existingEvidence)
            put("schema_version", "oob.function_evidence.v1")
            put("source", "update_function.runlog_analysis")
            put("latest_run_id", runId.takeIf { it.isNotBlank() })
            put("source_run_ids", sourceRunIds)
            put("latest_analysis", mutableJsonValue(analysis))
            put("updated_at_ms", System.currentTimeMillis())
        }.filterValues { it != null }
        if (existingEvidence == evidence) return emptyList()
        metadata["oob_function_evidence"] = evidence
        spec["metadata"] = metadata
        return listOf(
            changeMap(
                part = "metadata",
                field = "oob_function_evidence",
                old = existingEvidence.takeIf { it.isNotEmpty() },
                new = evidence,
            )
        )
    }

    fun appendUpdateAudit(
        spec: MutableMap<String, Any?>,
        mode: String,
        instruction: String,
        changed: Boolean,
        dryRun: Boolean,
        changes: List<Map<String, Any?>>,
    ) {
        val metadata = mutableJsonMap(mapArg(spec["metadata"]))
        metadata["oob_function_update"] = linkedMapOf(
            "schema_version" to "oob.function_update.v1",
            "tool" to "update_function",
            "mode" to mode,
            "status" to if (changed) "updated" else "unchanged",
            "changed" to changed,
            "dry_run" to dryRun,
            "instruction" to instruction.takeIf { it.isNotBlank() },
            "change_count" to changes.size,
            "updated_at_ms" to System.currentTimeMillis(),
        ).filterValues { it != null }
        if (mode == "enhance" || metadata["oob_enhancement"] != null) {
            metadata["oob_enhancement"] = linkedMapOf(
                "schema_version" to "oob.function_enhancement.v1",
                "source" to "update_function",
                "status" to if (changed) "enhanced" else "unchanged",
                "changed" to changed,
                "message" to if (changed) {
                    "Agent enhancement applied through update_function."
                } else {
                    "No safe useful enhancement was applied."
                },
                "updated_at_ms" to System.currentTimeMillis(),
            )
        }
        spec["metadata"] = metadata
    }

    private fun applyStepLabelPatches(
        spec: MutableMap<String, Any?>,
        patch: Map<String, Any?>,
        changes: MutableList<Map<String, Any?>>,
    ) {
        val stepPatches = listArg(patch["steps"])
            .mapNotNull { mapArg(it).takeIf { stepPatch -> stepPatch.isNotEmpty() } }
        if (stepPatches.isEmpty()) return

        val execution = mutableJsonMap(mapArg(spec["execution"]))
        val steps = mutableJsonList(listArg(execution["steps"]))
        stepPatches.forEach { stepPatch ->
            val index = intArg(
                stepPatch["index"],
                stepPatch["step_index"],
                stepPatch["stepIndex"],
                defaultValue = -1
            )
            val stepIndex = if (index >= 0) {
                index
            } else {
                val stepId = firstNonBlank(stepPatch["id"], stepPatch["step_id"], stepPatch["stepId"])
                steps.indexOfFirst { raw -> firstNonBlank(mapArg(raw)["id"]) == stepId }
            }
            if (stepIndex !in steps.indices) return@forEach
            val step = mutableJsonMap(mapArg(steps[stepIndex]))
            setStringFieldIfChanged(step, "title", stepPatch["title"], changes, "step_label", stepIndex)
            setStringFieldIfChanged(step, "summary", stepPatch["summary"], changes, "step_label", stepIndex)
            setStringFieldIfChanged(step, "description", stepPatch["description"], changes, "step_label", stepIndex)
            val cleanupAnnotation = mapArg(stepPatch["cleanup_annotation"])
                .ifEmpty { mapArg(stepPatch["cleanupAnnotation"]) }
            if (cleanupAnnotation.isNotEmpty()) {
                val old = mapArg(step["cleanup_annotation"])
                if (old != cleanupAnnotation) {
                    step["cleanup_annotation"] = cleanupAnnotation
                    changes += changeMap(
                        part = "step_cleanup",
                        field = "cleanup_annotation",
                        old = old.takeIf { it.isNotEmpty() },
                        new = cleanupAnnotation,
                        stepIndex = stepIndex,
                    )
                }
            }
            steps[stepIndex] = step
        }
        execution["steps"] = steps
        execution["step_count"] = steps.size
        spec["execution"] = execution
    }

    private fun applyParameterPatch(
        spec: MutableMap<String, Any?>,
        patch: Map<String, Any?>,
        changes: MutableList<Map<String, Any?>>,
    ) {
        val parameters = listArg(patch["parameters"])
            .mapNotNull { mapArg(it).takeIf { parameter -> parameter.isNotEmpty() } }
            .filter(::isSafeParameterPatch)
        if (parameters.isNotEmpty() && spec["parameters"] != parameters) {
            val old = spec["parameters"]
            spec["parameters"] = parameters
            changes += changeMap("parameters", "parameters", old, parameters)
        }
    }

    private fun applyAgentReusePatch(
        spec: MutableMap<String, Any?>,
        patch: Map<String, Any?>,
        changes: MutableList<Map<String, Any?>>,
    ) {
        val agentReuse = mapArg(patch["agent_reuse"])
            .ifEmpty { mapArg(patch["agentReuse"]) }
        if (agentReuse.isEmpty()) return

        val old = mutableJsonMap(mapArg(spec["agent_reuse"]))
        val merged = linkedMapOf<String, Any?>().apply {
            putAll(old)
            putAll(agentReuse)
        }
        if (old != merged) {
            spec["agent_reuse"] = merged
            changes += changeMap("agent_reuse", "agent_reuse", old.takeIf { it.isNotEmpty() }, merged)
        }
    }

    private fun applyMetadataPatch(
        spec: MutableMap<String, Any?>,
        patch: Map<String, Any?>,
        changes: MutableList<Map<String, Any?>>,
    ) {
        val metadataPatch = mapArg(patch["metadata"])
        if (metadataPatch.isEmpty()) return

        val metadata = mutableJsonMap(mapArg(spec["metadata"]))
        metadataPatch.forEach { (key, value) ->
            if (key == "function_id" || key == "execution") return@forEach
            val metadataKey = if (key == "checkerRules") "checker_rules" else key
            if (metadataKey == "checker_rules") {
                changes += checkerPatchService.applyCheckerRulesPatch(metadata, value)
                return@forEach
            }
            val safeValue = mutableJsonValue(value)
            if (metadata[metadataKey] != safeValue) {
                changes += changeMap("metadata", metadataKey, metadata[metadataKey], safeValue)
                metadata[metadataKey] = safeValue
            }
        }
        spec["metadata"] = metadata
    }

    private fun applyTopLevelCheckerRulesPatch(
        spec: MutableMap<String, Any?>,
        patch: Map<String, Any?>,
        changes: MutableList<Map<String, Any?>>,
    ) {
        val topLevelCheckerRules = listArg(patch["checker_rules"])
            .ifEmpty { listArg(patch["checkerRules"]) }
        if (topLevelCheckerRules.isEmpty()) return

        val metadata = mutableJsonMap(mapArg(spec["metadata"]))
        changes += checkerPatchService.applyCheckerRulesPatch(metadata, topLevelCheckerRules)
        spec["metadata"] = metadata
    }

    private fun isSafeParameterPatch(parameter: Map<String, Any?>): Boolean {
        val bindings = listArg(parameter["bindings"])
            .mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
        if (bindings.isEmpty()) return true
        return bindings.all { binding ->
            val normalized = binding.lowercase()
            val forbidden = listOf(
                ".x",
                ".y",
                "bounds",
                "center_x",
                "center_y",
                "width",
                "height",
                "screenshot",
                "xml",
                "source_context",
            )
            forbidden.none { token -> normalized.contains(token) }
        }
    }

    private fun setStringFieldIfChanged(
        target: MutableMap<String, Any?>,
        field: String,
        rawValue: Any?,
        changes: MutableList<Map<String, Any?>>,
        part: String,
        stepIndex: Int? = null,
    ) {
        val value = rawValue?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val old = target[field]?.toString()
        if (old == value) return
        target[field] = value
        changes += changeMap(part, field, old, value, stepIndex)
    }

    private fun changeMap(
        part: String,
        field: String,
        old: Any?,
        new: Any?,
        stepIndex: Int? = null,
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
        "part" to part,
        "field" to field,
        "step_index" to stepIndex,
        "old" to old,
        "new" to new,
    ).filterValues { it != null }

    private fun mutableJsonMap(value: Map<String, Any?>): LinkedHashMap<String, Any?> =
        linkedMapOf<String, Any?>().apply {
            value.forEach { (key, item) ->
                put(key, mutableJsonValue(item))
            }
        }

    private fun mutableJsonList(value: List<Any?>): MutableList<Any?> =
        value.map { mutableJsonValue(it) }.toMutableList()

    private fun mutableJsonValue(value: Any?): Any? =
        when (value) {
            is Map<*, *> -> linkedMapOf<String, Any?>().apply {
                value.forEach { (key, item) ->
                    if (key != null) put(key.toString(), mutableJsonValue(item))
                }
            }
            is List<*> -> value.map { mutableJsonValue(it) }.toMutableList()
            is Array<*> -> value.map { mutableJsonValue(it) }.toMutableList()
            else -> value
        }

    private fun firstNonBlank(vararg values: Any?): String {
        for (value in values) {
            val text = value?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) return text
        }
        return ""
    }

    private fun mapArg(value: Any?): Map<String, Any?> =
        when (value) {
            is Map<*, *> -> linkedMapOf<String, Any?>().apply {
                value.forEach { (key, item) ->
                    if (key != null) put(key.toString(), item)
                }
            }
            else -> emptyMap()
        }

    private fun listArg(value: Any?): List<Any?> =
        when (value) {
            is List<*> -> value
            is Array<*> -> value.toList()
            else -> emptyList()
        }

    private fun intArg(vararg values: Any?, defaultValue: Int): Int {
        values.forEach { value ->
            when (value) {
                is Number -> return value.toInt()
                is String -> value.trim().toIntOrNull()?.let { return it }
            }
        }
        return defaultValue
    }
}
