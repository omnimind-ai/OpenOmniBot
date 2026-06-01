package cn.com.omnimind.baselib.runlog

import android.content.Context
import android.os.Trace
import cn.com.omnimind.baselib.util.OmniLog
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class InternalRunLogRecord(
    val runId: String = "",
    val goal: String = "",
    val source: String = "internal_oob",
    val toolName: String = "",
    val operationDescription: String = "",
    val startedAtMs: Long = System.currentTimeMillis(),
    val finishedAtMs: Long? = null,
    val success: Boolean? = null,
    val doneReason: String = "",
    val errorMessage: String = "",
    val diagnostics: Map<String, Any?> = emptyMap(),
    val cards: List<Map<String, Any?>> = emptyList(),
    val eventSeq: Long = 0L
)

object InternalRunLogStore {
    private const val TAG = "InternalRunLogStore"
    private const val PROVIDER = "internal_oob"
    private const val STORAGE_DIR_NAME = "internal_run_logs"
    private const val MAX_RUN_COUNT = 200
    private const val RUN_LOG_EVENT_SCHEMA_VERSION = "oob.run_log_event.v1"
    private const val SNAPSHOT_MIN_INTERVAL_MS = 1_000L
    private const val REGISTERED_FUNCTION_BINDING_LIMIT = 20

    private val gson = GsonBuilder()
        .disableHtmlEscaping()
        .setPrettyPrinting()
        .create()
    private val compactGson = GsonBuilder()
        .disableHtmlEscaping()
        .create()
    private val mapType = object : TypeToken<Map<String, Any?>>() {}.type
    private val lastEventSeqByRun = mutableMapOf<String, Long>()
    private val lastSnapshotWriteMsByRun = mutableMapOf<String, Long>()

    @Synchronized
    fun beginRun(
        context: Context,
        runId: String,
        goal: String,
        source: String,
        toolName: String = "",
        operationDescription: String = goal
    ) {
        val normalizedRunId = runId.trim()
        if (normalizedRunId.isEmpty()) return
        val now = System.currentTimeMillis()
        val existing = readRunLocked(context, normalizedRunId)
        val record = (existing ?: InternalRunLogRecord(
            runId = normalizedRunId,
            startedAtMs = now
        )).copy(
            goal = goal.ifBlank { existing?.goal.orEmpty() },
            source = source.ifBlank { existing?.source ?: PROVIDER },
            toolName = toolName.ifBlank { existing?.toolName.orEmpty() },
            operationDescription = operationDescription.ifBlank {
                existing?.operationDescription.orEmpty()
            },
            finishedAtMs = null,
            success = null,
            doneReason = "",
            errorMessage = ""
        )
        val eventSeq = appendRunEventLocked(
            context = context,
            runId = normalizedRunId,
            eventType = "run_started",
            payload = linkedMapOf(
                "goal" to record.goal,
                "source" to record.source,
                "tool_name" to record.toolName,
                "operation_description" to record.operationDescription,
                "started_at_ms" to record.startedAtMs
            )
        )
        saveRunLocked(context, record.copy(eventSeq = eventSeq))
        pruneLocked(context)
    }

    @Synchronized
    fun appendCard(context: Context, runId: String, card: Map<String, Any?>) {
        val normalizedRunId = runId.trim()
        if (normalizedRunId.isEmpty()) return
        val record = readRunLocked(context, normalizedRunId)
            ?: InternalRunLogRecord(runId = normalizedRunId)
        val sanitizedCard = sanitizeMap(card)
        val eventSeq = appendRunEventLocked(
            context = context,
            runId = normalizedRunId,
            eventType = "card_appended",
            payload = linkedMapOf("card" to sanitizedCard)
        )
        saveRunLocked(context, record.copy(cards = record.cards + sanitizedCard, eventSeq = eventSeq))
        pruneLocked(context)
    }

    @Synchronized
    fun appendCards(
        context: Context,
        runId: String,
        cards: List<Map<String, Any?>>,
        saveSnapshot: Boolean = true
    ) {
        val normalizedRunId = runId.trim()
        if (normalizedRunId.isEmpty() || cards.isEmpty()) return
        val record = readRunLocked(context, normalizedRunId)
            ?: InternalRunLogRecord(runId = normalizedRunId)
        val sanitizedCards = cards.map(::sanitizeMap)
        val eventSeq = appendRunEventLocked(
            context = context,
            runId = normalizedRunId,
            eventType = "cards_appended",
            payload = linkedMapOf("cards" to sanitizedCards)
        )
        if (saveSnapshot) {
            saveRunLocked(context, record.copy(cards = record.cards + sanitizedCards, eventSeq = eventSeq))
        }
        pruneLocked(context)
    }

    @Synchronized
    fun updateDiagnostics(
        context: Context,
        runId: String,
        diagnostics: Map<String, Any?>,
        saveSnapshot: Boolean = true
    ) {
        val normalizedRunId = runId.trim()
        if (normalizedRunId.isEmpty() || diagnostics.isEmpty()) return
        val record = readRunLocked(context, normalizedRunId)
            ?: InternalRunLogRecord(runId = normalizedRunId)
        val sanitizedDiagnostics = sanitizeMap(diagnostics)
        val mergedDiagnostics = sanitizeMap(record.diagnostics + sanitizedDiagnostics)
        val eventSeq = appendRunEventLocked(
            context = context,
            runId = normalizedRunId,
            eventType = "diagnostics_updated",
            payload = linkedMapOf("diagnostics" to sanitizedDiagnostics)
        )
        if (saveSnapshot) {
            saveRunLocked(context, record.copy(diagnostics = mergedDiagnostics, eventSeq = eventSeq))
        }
        pruneLocked(context)
    }

    @Synchronized
    fun bindRegisteredFunction(
        context: Context,
        runId: String,
        functionId: String,
        functionSpec: Map<String, Any?> = emptyMap()
    ): Map<String, Any?> {
        val normalizedRunId = runId.trim()
        val normalizedFunctionId = functionId.trim()
        if (normalizedRunId.isEmpty() || normalizedFunctionId.isEmpty()) {
            return emptyMap()
        }
        val record = readRunLocked(context, normalizedRunId)
            ?: InternalRunLogRecord(runId = normalizedRunId)
        val binding = registeredFunctionBindingRecord(
            functionId = normalizedFunctionId,
            functionSpec = functionSpec,
            registeredAtMs = System.currentTimeMillis()
        )
        val mergedDiagnostics = upsertRegisteredFunctionBinding(
            diagnostics = record.diagnostics,
            binding = binding
        )
        val eventSeq = appendRunEventLocked(
            context = context,
            runId = normalizedRunId,
            eventType = "registered_function_bound",
            payload = linkedMapOf("binding" to binding)
        )
        val updatedRecord = record.copy(
            diagnostics = mergedDiagnostics,
            eventSeq = eventSeq
        )
        saveRunLocked(context, updatedRecord)
        pruneLocked(context)
        return registeredFunctionBinding(context, updatedRecord)
    }

    @Synchronized
    fun upsertCard(
        context: Context,
        runId: String,
        cardId: String,
        card: Map<String, Any?>
    ) {
        val normalizedRunId = runId.trim()
        val normalizedCardId = cardId.trim()
        if (normalizedRunId.isEmpty()) return
        val record = readRunLocked(context, normalizedRunId)
            ?: InternalRunLogRecord(runId = normalizedRunId)
        val sanitizedCard = sanitizeMap(
            if (normalizedCardId.isEmpty() || card.containsKey("card_id")) {
                card
            } else {
                linkedMapOf<String, Any?>("card_id" to normalizedCardId).apply {
                    putAll(card)
                }
            }
        )
        val updatedCards = record.cards.toMutableList()
        val replaceIndex = if (normalizedCardId.isEmpty()) {
            -1
        } else {
            updatedCards.indexOfFirst { existing ->
                val existingId = existing["card_id"]?.toString()?.trim().orEmpty()
                existingId == normalizedCardId
            }
        }
        if (replaceIndex >= 0) {
            updatedCards[replaceIndex] = sanitizedCard
        } else {
            updatedCards += sanitizedCard
        }
        val eventSeq = appendRunEventLocked(
            context = context,
            runId = normalizedRunId,
            eventType = "card_upserted",
            payload = linkedMapOf(
                "card_id" to normalizedCardId,
                "card" to sanitizedCard
            )
        )
        val updatedRecord = record.copy(cards = updatedCards, eventSeq = eventSeq)
        if (shouldSaveSnapshotLocked(context, normalizedRunId, sanitizedCard)) {
            saveRunLocked(context, updatedRecord)
        }
        pruneLocked(context)
    }

    @Synchronized
    fun finishRun(
        context: Context,
        runId: String,
        success: Boolean,
        doneReason: String,
        errorMessage: String? = null
    ) {
        val normalizedRunId = runId.trim()
        if (normalizedRunId.isEmpty()) return
        val record = readRunLocked(context, normalizedRunId)
            ?: InternalRunLogRecord(runId = normalizedRunId)
        val finishedAtMs = System.currentTimeMillis()
        val eventSeq = appendRunEventLocked(
            context = context,
            runId = normalizedRunId,
            eventType = "run_finished",
            payload = linkedMapOf(
                "finished_at_ms" to finishedAtMs,
                "success" to success,
                "done_reason" to doneReason,
                "error_message" to errorMessage.orEmpty()
            )
        )
        saveRunLocked(
            context,
            record.copy(
                finishedAtMs = finishedAtMs,
                success = success,
                doneReason = doneReason,
                errorMessage = errorMessage.orEmpty(),
                eventSeq = eventSeq
            )
        )
        pruneLocked(context)
    }

    @Synchronized
    fun listRuns(context: Context, limit: Int = 50, offset: Int = 0): Map<String, Any?> {
        val safeLimit = limit.coerceIn(1, MAX_RUN_COUNT)
        val safeOffset = offset.coerceAtLeast(0)
        val allRuns = readAllRunsLocked(context)
            .sortedByDescending { it.startedAtMs }
        val runs = allRuns
            .drop(safeOffset)
            .take(safeLimit)
        val dir = storageDir(context)
        return linkedMapOf(
            "success" to true,
            "count" to runs.size,
            "total_count" to allRuns.size,
            "limit" to safeLimit,
            "offset" to safeOffset,
            "next_offset" to (safeOffset + runs.size),
            "has_more" to (safeOffset + runs.size < allRuns.size),
            "provider" to PROVIDER,
            "run_index_path" to File(dir, "index.json").absolutePath,
            "run_storage_dir" to dir.absolutePath,
            "event_schema_version" to RUN_LOG_EVENT_SCHEMA_VERSION,
            "runs" to runs.map { record ->
                summaryMap(record, registeredFunctionBinding(context, record))
            }
        )
    }

    @Synchronized
    fun listRunRecords(
        context: Context,
        limit: Int = 50,
        offset: Int = 0
    ): List<InternalRunLogRecord> {
        val safeLimit = limit.coerceIn(1, MAX_RUN_COUNT)
        val safeOffset = offset.coerceAtLeast(0)
        return readAllRunsLocked(context)
            .sortedByDescending { it.startedAtMs }
            .drop(safeOffset)
            .take(safeLimit)
    }

    @Synchronized
    fun getRun(context: Context, runId: String): InternalRunLogRecord? {
        val normalizedRunId = runId.trim()
        if (normalizedRunId.isEmpty()) return null
        return readRunLocked(context, normalizedRunId)
    }

    @Synchronized
    fun timelinePayload(context: Context, runId: String): Map<String, Any?> {
        val normalizedRunId = runId.trim()
        if (normalizedRunId.isEmpty()) {
            return notFoundPayload(normalizedRunId)
        }
        val record = readRunLocked(context, normalizedRunId)
            ?: return notFoundPayload(normalizedRunId)
        val tokenUsage = tokenUsageSummary(record.cards)
        val registeredBinding = registeredFunctionBinding(context, record)
        return linkedMapOf(
            "success" to true,
            "provider" to PROVIDER,
            "run_id" to record.runId,
            "goal" to record.goal,
            "source" to record.source,
            "tool_name" to record.toolName,
            "operation_description" to record.operationDescription,
            "started_at_ms" to record.startedAtMs,
            "finished_at_ms" to record.finishedAtMs,
            "started_at" to formatTime(record.startedAtMs),
            "finished_at" to record.finishedAtMs?.let(::formatTime).orEmpty(),
            "run_finished" to (record.finishedAtMs != null),
            "run_success" to (record.success == true),
            "run_status" to runStatus(record),
            "duration_ms" to durationMs(record),
            "step_count" to record.cards.size,
            "event_seq" to record.eventSeq,
            "event_schema_version" to RUN_LOG_EVENT_SCHEMA_VERSION,
            "event_log_path" to runEventsFile(runFile(context, normalizedRunId)).absolutePath,
            "done_reason" to record.doneReason,
            "error_message" to record.errorMessage,
            "diagnostics" to record.diagnostics.takeIf { it.isNotEmpty() },
            "token_usage" to tokenUsage,
            "token_usage_total" to tokenUsage["total_tokens"],
            "token_usage_by_step" to tokenUsageByStep(record.cards),
            "token_usage_by_call" to tokenUsageByCall(record.cards),
            "cards" to record.cards
        ).apply {
            putAll(registeredBinding)
        }
    }

    private fun summaryMap(
        record: InternalRunLogRecord,
        registeredBinding: Map<String, Any?> = emptyMap()
    ): Map<String, Any?> {
        val tokenUsage = tokenUsageSummary(record.cards)
        return linkedMapOf(
            "run_id" to record.runId,
            "goal" to record.goal,
            "success" to (record.success == true),
            "run_finished" to (record.finishedAtMs != null),
            "run_success" to (record.success == true),
            "run_status" to runStatus(record),
            "done_reason" to record.doneReason,
            "step_count" to record.cards.size,
            "started_at_ms" to record.startedAtMs,
            "finished_at_ms" to record.finishedAtMs,
            "started_at" to formatTime(record.startedAtMs),
            "finished_at" to record.finishedAtMs?.let(::formatTime).orEmpty(),
            "duration_ms" to durationMs(record),
            "event_seq" to record.eventSeq,
            "tool_name" to record.toolName,
            "source" to record.source,
            "operation_description" to record.operationDescription,
            "error_message" to record.errorMessage,
            "diagnostics" to record.diagnostics.takeIf { it.isNotEmpty() },
            "token_usage" to tokenUsage,
            "token_usage_total" to tokenUsage["total_tokens"],
            "raw_run" to linkedMapOf(
                "run_id" to record.runId,
                "provider" to PROVIDER,
                "source" to record.source,
                "goal" to record.goal
            )
        ).apply {
            putAll(registeredBinding)
        }
    }

    private fun registeredFunctionBinding(
        context: Context,
        record: InternalRunLogRecord
    ): Map<String, Any?> {
        val persistedBindings = registeredFunctionBindings(record.diagnostics)
        val persistedFunctionIds = persistedBindings.mapNotNull { binding ->
            textValue(binding["function_id"]).takeIf { it.isNotEmpty() }
        }.distinct()
        val functions = runCatching {
            OobReusableFunctionStore.functionsForSourceRunId(
                context = context,
                runId = record.runId,
                limit = REGISTERED_FUNCTION_BINDING_LIMIT
            )
        }.onFailure {
            OmniLog.w(TAG, "load registered function binding failed: ${it.message}")
        }.getOrDefault(emptyList())

        val mergedFunctions = linkedMapOf<String, Map<String, Any?>>()
        functions.forEach { function ->
            val summary = stringMap(function["summary"])
            val spec = stringMap(function["function_spec"])
            val functionId = textValue(summary["function_id"]).ifBlank {
                textValue(spec["function_id"])
            }
            if (functionId.isNotEmpty()) {
                mergedFunctions[functionId] = sanitizeMap(function)
            }
        }
        persistedFunctionIds.forEach { functionId ->
            if (mergedFunctions.containsKey(functionId)) return@forEach
            val spec = runCatching {
                OobReusableFunctionStore.get(context, functionId)
            }.getOrNull() ?: return@forEach
            mergedFunctions[functionId] = linkedMapOf(
                "summary" to functionSummaryFromSpec(spec),
                "function_spec" to sanitizeMap(spec)
            )
        }

        val summariesById = linkedMapOf<String, Map<String, Any?>>()
        val specsById = linkedMapOf<String, Map<String, Any?>>()
        mergedFunctions.forEach { (functionId, function) ->
            val spec = stringMap(function["function_spec"])
            val summary = stringMap(function["summary"]).takeIf { it.isNotEmpty() }
                ?: spec.takeIf { it.isNotEmpty() }?.let(::functionSummaryFromSpec)
                ?: emptyMap()
            if (summary.isNotEmpty()) {
                summariesById[functionId] = sanitizeMap(summary)
            }
            if (spec.isNotEmpty()) {
                specsById[functionId] = sanitizeMap(spec)
            }
        }
        persistedBindings.forEach { binding ->
            val functionId = textValue(binding["function_id"])
            if (functionId.isNotEmpty() && !summariesById.containsKey(functionId)) {
                summariesById[functionId] = functionSummaryFromBinding(binding)
            }
        }
        val functionIds = (persistedFunctionIds + mergedFunctions.keys).distinct()
        val summaries = functionIds.mapNotNull { summariesById[it] }
        val specs = functionIds.mapNotNull { specsById[it] }
        val firstSummary = summaries.firstOrNull()
        val firstSpec = specs.firstOrNull()
        return linkedMapOf(
            "registered_as_function" to functionIds.isNotEmpty(),
            "is_registered_function" to functionIds.isNotEmpty(),
            "registered_function_count" to functionIds.size,
            "registered_function_ids" to functionIds,
            "registered_function_id" to functionIds.firstOrNull().orEmpty(),
            "registered_function_summary" to firstSummary?.let(::sanitizeMap),
            "registered_function_summaries" to summaries.map(::sanitizeMap),
            "registered_function_spec" to firstSpec?.let(::sanitizeMap),
            "has_registered_function_binding" to persistedFunctionIds.isNotEmpty(),
            "registered_function_binding_ids" to persistedFunctionIds,
            "registered_function_bindings" to persistedBindings.map(::sanitizeMap),
        )
    }

    private fun registeredFunctionBindingRecord(
        functionId: String,
        functionSpec: Map<String, Any?>,
        registeredAtMs: Long
    ): Map<String, Any?> {
        return linkedMapOf(
            "function_id" to functionId,
            "name" to textValue(functionSpec["name"]),
            "description" to textValue(functionSpec["description"]),
            "source_run_ids" to sourceRunIds(functionSpec),
            "registered_at_ms" to registeredAtMs,
            "registered_at" to formatTime(registeredAtMs),
            "source" to "oob_run_log_registration"
        ).filterValues { value ->
            when (value) {
                is String -> value.isNotBlank()
                is List<*> -> value.isNotEmpty()
                else -> true
            }
        }
    }

    private fun upsertRegisteredFunctionBinding(
        diagnostics: Map<String, Any?>,
        binding: Map<String, Any?>
    ): Map<String, Any?> {
        val functionId = textValue(binding["function_id"])
        if (functionId.isEmpty()) return sanitizeMap(diagnostics)
        val existing = registeredFunctionBindings(diagnostics)
        val merged = (listOf(binding) + existing.filter {
            textValue(it["function_id"]) != functionId
        }).take(REGISTERED_FUNCTION_BINDING_LIMIT)
        return sanitizeMap(
            diagnostics + linkedMapOf(
                "registered_function_id" to functionId,
                "registered_function_ids" to merged.mapNotNull {
                    textValue(it["function_id"]).takeIf(String::isNotEmpty)
                }.distinct(),
                "registered_function_bindings" to merged,
                "registered_as_function" to true,
                "registered_function_bound_at_ms" to binding["registered_at_ms"]
            )
        )
    }

    private fun registeredFunctionBindings(
        diagnostics: Map<String, Any?>
    ): List<Map<String, Any?>> {
        val multi = listOfMaps(diagnostics["registered_function_bindings"])
        if (multi.isNotEmpty()) return multi
        val single = stringMap(diagnostics["registered_function_binding"])
        if (single.isNotEmpty()) return listOf(single)
        val functionId = textValue(diagnostics["registered_function_id"])
        if (functionId.isEmpty()) return emptyList()
        return listOf(linkedMapOf("function_id" to functionId))
    }

    private fun functionSummaryFromSpec(spec: Map<String, Any?>): Map<String, Any?> {
        val execution = stringMap(spec["execution"])
        val steps = specSteps(spec)
        return linkedMapOf(
            "function_id" to textValue(spec["function_id"]),
            "name" to spec["name"],
            "description" to spec["description"],
            "step_count" to (numberToLong(execution["step_count"])?.toInt() ?: steps.size),
            "source_run_ids" to sourceRunIds(spec)
        )
    }

    private fun functionSummaryFromBinding(binding: Map<String, Any?>): Map<String, Any?> {
        return linkedMapOf(
            "function_id" to textValue(binding["function_id"]),
            "name" to textValue(binding["name"]),
            "description" to textValue(binding["description"]),
            "source_run_ids" to ((binding["source_run_ids"] as? List<*>)?.mapNotNull {
                it?.toString()?.trim()?.takeIf(String::isNotEmpty)
            } ?: emptyList<String>()),
            "registered_at_ms" to binding["registered_at_ms"],
            "registered_at" to textValue(binding["registered_at"]),
            "source" to textValue(binding["source"])
        ).filterValues { value ->
            when (value) {
                is String -> value.isNotBlank()
                is List<*> -> value.isNotEmpty()
                null -> false
                else -> true
            }
        }
    }

    private fun specSteps(spec: Map<String, Any?>): List<Map<String, Any?>> {
        val execution = stringMap(spec["execution"])
        return listOfMaps(execution["steps"])
    }

    private fun sourceRunIds(spec: Map<String, Any?>): List<String> {
        val source = stringMap(spec["source"])
        val metadata = stringMap(spec["metadata"])
        return buildList {
            textValue(source["run_id"]).takeIf(String::isNotEmpty)?.let(::add)
            textValue(metadata["run_id"]).takeIf(String::isNotEmpty)?.let(::add)
            (metadata["source_run_ids"] as? List<*>)?.forEach { raw ->
                raw?.toString()?.trim()?.takeIf(String::isNotEmpty)?.let(::add)
            }
        }.distinct()
    }

    private fun runStatus(record: InternalRunLogRecord): String {
        if (record.finishedAtMs == null) return "running"
        return if (record.success == true) "success" else "failed"
    }

    private fun notFoundPayload(runId: String): Map<String, Any?> {
        return linkedMapOf(
            "success" to false,
            "provider" to PROVIDER,
            "run_id" to runId,
            "error_code" to "NOT_FOUND",
            "error_message" to "Internal run log not found"
        )
    }

    private fun durationMs(record: InternalRunLogRecord): Long? {
        val finishedAt = record.finishedAtMs ?: return null
        return (finishedAt - record.startedAtMs).coerceAtLeast(0L)
    }

    private fun tokenUsageSummary(cards: List<Map<String, Any?>>): Map<String, Any?> {
        val usages = cards.mapNotNull(::extractTokenUsage)
        if (usages.isEmpty()) return emptyMap()
        val summary = linkedMapOf<String, Any?>()
        putSum(summary, "prompt_tokens", usages)
        putSum(summary, "completion_tokens", usages)
        putSum(summary, "total_tokens", usages)
        if (!summary.containsKey("total_tokens")) {
            val prompt = numberToLong(summary["prompt_tokens"])
            val completion = numberToLong(summary["completion_tokens"])
            if (prompt != null || completion != null) {
                summary["total_tokens"] = (prompt ?: 0L) + (completion ?: 0L)
            }
        }
        putSum(summary, "reasoning_tokens", usages)
        putSum(summary, "text_tokens", usages)
        putSum(summary, "cached_tokens", usages)
        putSum(summary, "attempt_count", usages)
        summary["step_count"] = usages.size
        val callCount = tokenUsageCallCount(cards)
        if (callCount > 0) {
            summary["call_count"] = callCount
        }
        return summary
    }

    private fun tokenUsageByStep(cards: List<Map<String, Any?>>): List<Map<String, Any?>> {
        return cards.mapIndexedNotNull { index, card ->
            val usage = extractTokenUsage(card) ?: return@mapIndexedNotNull null
            linkedMapOf<String, Any?>(
                "step_index" to (numberToLong(card["step_index"]) ?: index.toLong()).toInt(),
                "card_id" to textValue(card["card_id"]),
                "tool_name" to textValue(card["tool_name"]),
                "token_usage" to usage
            )
        }
    }

    private fun tokenUsageByCall(cards: List<Map<String, Any?>>): List<Map<String, Any?>> {
        val calls = mutableListOf<Map<String, Any?>>()
        cards.forEachIndexed { index, card ->
            val attempts = extractTokenUsageAttempts(card)
            val usages = attempts.ifEmpty {
                extractTokenUsage(card)?.let { listOf(it) } ?: emptyList()
            }
            usages.forEach { usage ->
                calls += linkedMapOf(
                    "call_index" to calls.size,
                    "step_index" to (numberToLong(card["step_index"]) ?: index.toLong()).toInt(),
                    "card_id" to textValue(card["card_id"]),
                    "tool_name" to textValue(card["tool_name"]),
                    "attempt_index" to numberToLong(usage["attempt_index"])?.toInt(),
                    "stability_attempt" to numberToLong(usage["stability_attempt"])?.toInt(),
                    "tool_retry_index" to numberToLong(usage["tool_retry_index"])?.toInt(),
                    "token_usage" to usage
                ).filterValues { it != null }
            }
        }
        return calls
    }

    private fun tokenUsageCallCount(cards: List<Map<String, Any?>>): Int {
        return cards.sumOf { card ->
            val attempts = extractTokenUsageAttempts(card)
            val usage = extractTokenUsage(card)
            when {
                attempts.isNotEmpty() -> attempts.size
                usage != null -> (numberToLong(usage["attempt_count"]) ?: 1L).coerceAtLeast(1L).toInt()
                else -> 0
            }
        }
    }

    private fun extractTokenUsage(card: Map<String, Any?>): Map<String, Any?>? {
        val direct = stringMap(card["token_usage"]).takeIf { it.isNotEmpty() }
        if (direct != null) return direct
        val header = stringMap(card["header"])
        return stringMap(header["token_usage"]).takeIf { it.isNotEmpty() }
    }

    private fun extractTokenUsageAttempts(card: Map<String, Any?>): List<Map<String, Any?>> {
        val direct = listOfMaps(card["token_usage_attempts"]).takeIf { it.isNotEmpty() }
        if (direct != null) return direct
        val header = stringMap(card["header"])
        return listOfMaps(header["token_usage_attempts"]).takeIf { it.isNotEmpty() }
            ?: emptyList()
    }

    private fun putSum(
        target: MutableMap<String, Any?>,
        key: String,
        usages: List<Map<String, Any?>>
    ) {
        var hasValue = false
        var total = 0L
        usages.forEach { usage ->
            val value = numberToLong(usage[key])
            if (value != null) {
                hasValue = true
                total += value
            }
        }
        if (hasValue) {
            target[key] = total
        }
    }

    private fun readAllRunsLocked(context: Context): List<InternalRunLogRecord> {
        val dir = storageDir(context)
        return dir.listFiles { file -> file.isFile && file.extension == "json" }
            ?.mapNotNull { file -> readRunFileLocked(file) }
            .orEmpty()
    }

    private fun readRunLocked(context: Context, runId: String): InternalRunLogRecord? {
        return readRunFileLocked(runFile(context, runId))
    }

    private fun readRunFileLocked(file: File): InternalRunLogRecord? {
        val snapshot = if (file.exists()) {
            runCatching {
                gson.fromJson(file.readText(), InternalRunLogRecord::class.java)
            }.getOrElse {
                OmniLog.w(TAG, "read run log failed: ${file.absolutePath}, ${it.message}")
                null
            }
        } else {
            null
        }
        val runId = snapshot?.runId ?: runIdFromRunFile(file)
        val events = readRunEventsLocked(
            file = runEventsFile(file),
            afterSeq = snapshot?.eventSeq ?: 0L
        )
        if (snapshot == null && events.isEmpty()) return null
        return runCatching {
            applyRunEventsLocked(
                base = snapshot ?: InternalRunLogRecord(runId = runId),
                events = events
            )
        }.getOrElse {
            OmniLog.w(TAG, "apply run log events failed: ${file.absolutePath}, ${it.message}")
            snapshot
        }
    }

    private fun saveRunLocked(context: Context, record: InternalRunLogRecord) {
        val file = runFile(context, record.runId)
        val tmp = File(file.parentFile, "${file.name}.tmp")
        runCatching {
            Trace.beginSection("InternalRunLogStore.saveSnapshot")
            try {
                tmp.writeText(gson.toJson(record))
                if (!tmp.renameTo(file)) {
                    file.writeText(gson.toJson(record))
                    tmp.delete()
                }
                lastSnapshotWriteMsByRun[record.runId] = System.currentTimeMillis()
            } finally {
                Trace.endSection()
            }
        }.onFailure {
            OmniLog.w(TAG, "save run log failed: ${file.absolutePath}, ${it.message}")
            tmp.delete()
        }
    }

    private fun pruneLocked(context: Context) {
        val dir = storageDir(context)
        val files = dir.listFiles { file -> file.isFile && file.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        files.drop(MAX_RUN_COUNT).forEach { file ->
            runCatching {
                runEventsFile(file).delete()
                file.delete()
            }
        }
    }

    private fun storageDir(context: Context): File {
        return File(context.applicationContext.filesDir, STORAGE_DIR_NAME).apply {
            if (!exists()) mkdirs()
        }
    }

    private fun runFile(context: Context, runId: String): File {
        return File(storageDir(context), "${sha256(runId).take(16)}_${safeFilePart(runId)}.json")
    }

    private fun runEventsFile(runFile: File): File {
        return File(runFile.parentFile, "${runFile.nameWithoutExtension}.events.ndjson")
    }

    private fun runIdFromRunFile(file: File): String {
        return file.nameWithoutExtension.substringAfter('_', file.nameWithoutExtension)
    }

    private fun appendRunEventLocked(
        context: Context,
        runId: String,
        eventType: String,
        payload: Map<String, Any?>
    ): Long {
        val file = runEventsFile(runFile(context, runId))
        val eventSeq = nextRunEventSeqLocked(file, runId)
        val event = linkedMapOf<String, Any?>(
            "schema_version" to RUN_LOG_EVENT_SCHEMA_VERSION,
            "provider" to PROVIDER,
            "run_id" to runId,
            "event_seq" to eventSeq,
            "event_type" to eventType,
            "created_at_ms" to System.currentTimeMillis(),
            "payload" to sanitizeMap(payload)
        )
        runCatching {
            Trace.beginSection("InternalRunLogStore.appendEvent")
            try {
                file.parentFile?.mkdirs()
                file.appendText(compactGson.toJson(event) + "\n")
            } finally {
                Trace.endSection()
            }
        }.onFailure {
            OmniLog.w(TAG, "append run log event failed: ${file.absolutePath}, ${it.message}")
        }
        return eventSeq
    }

    private fun nextRunEventSeqLocked(file: File, runId: String): Long {
        val cached = lastEventSeqByRun[runId]
        if (cached != null) {
            val next = cached + 1L
            lastEventSeqByRun[runId] = next
            return next
        }
        val current = readLastRunEventSeqLocked(file)
        val next = current + 1L
        lastEventSeqByRun[runId] = next
        return next
    }

    private fun readLastRunEventSeqLocked(file: File): Long {
        if (!file.exists()) return 0L
        return runCatching {
            var last = 0L
            file.forEachLine { line ->
                val event = parseEventLine(line) ?: return@forEachLine
                val seq = numberToLong(event["event_seq"]) ?: return@forEachLine
                if (seq > last) last = seq
            }
            last
        }.getOrElse {
            OmniLog.w(TAG, "read run log event seq failed: ${file.absolutePath}, ${it.message}")
            0L
        }
    }

    private fun readRunEventsLocked(
        file: File,
        afterSeq: Long
    ): List<Map<String, Any?>> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val events = mutableListOf<Map<String, Any?>>()
            file.forEachLine { line ->
                val event = parseEventLine(line) ?: return@forEachLine
                val seq = numberToLong(event["event_seq"]) ?: return@forEachLine
                if (seq > afterSeq) {
                    events += event
                }
            }
            events.sortedBy { numberToLong(it["event_seq"]) ?: 0L }
        }.getOrElse {
            OmniLog.w(TAG, "read run log events failed: ${file.absolutePath}, ${it.message}")
            emptyList()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseEventLine(line: String): Map<String, Any?>? {
        val normalized = line.trim()
        if (normalized.isEmpty()) return null
        return runCatching {
            compactGson.fromJson<Map<String, Any?>>(normalized, mapType)
        }.getOrNull()
    }

    private fun applyRunEventsLocked(
        base: InternalRunLogRecord,
        events: List<Map<String, Any?>>
    ): InternalRunLogRecord {
        var record = base
        var maxEventSeq = base.eventSeq
        for (event in events) {
            val eventSeq = numberToLong(event["event_seq"]) ?: continue
            if (eventSeq <= maxEventSeq) continue
            val payload = stringMap(event["payload"])
            record = when (event["event_type"]?.toString()) {
                "run_started" -> record.copy(
                    goal = textValue(payload["goal"]).ifBlank { record.goal },
                    source = textValue(payload["source"]).ifBlank { record.source },
                    toolName = textValue(payload["tool_name"]).ifBlank { record.toolName },
                    operationDescription = textValue(payload["operation_description"])
                        .ifBlank { record.operationDescription },
                    startedAtMs = numberToLong(payload["started_at_ms"]) ?: record.startedAtMs,
                    finishedAtMs = null,
                    success = null,
                    doneReason = "",
                    errorMessage = ""
                )
                "card_appended" -> record.copy(
                    cards = record.cards + sanitizeMap(stringMap(payload["card"]))
                )
                "cards_appended" -> record.copy(
                    cards = record.cards + listOfMaps(payload["cards"]).map(::sanitizeMap)
                )
                "card_upserted" -> record.copy(
                    cards = upsertCardList(
                        cards = record.cards,
                        cardId = textValue(payload["card_id"]),
                        card = sanitizeMap(stringMap(payload["card"]))
                    )
                )
                "run_finished" -> record.copy(
                    finishedAtMs = numberToLong(payload["finished_at_ms"]),
                    success = booleanValue(payload["success"]),
                    doneReason = textValue(payload["done_reason"]),
                    errorMessage = textValue(payload["error_message"])
                )
                "diagnostics_updated" -> record.copy(
                    diagnostics = sanitizeMap(
                        record.diagnostics + stringMap(payload["diagnostics"])
                    )
                )
                "registered_function_bound" -> record.copy(
                    diagnostics = upsertRegisteredFunctionBinding(
                        diagnostics = record.diagnostics,
                        binding = stringMap(payload["binding"])
                    )
                )
                else -> record
            }.copy(eventSeq = eventSeq)
            maxEventSeq = eventSeq
        }
        return record
    }

    private fun upsertCardList(
        cards: List<Map<String, Any?>>,
        cardId: String,
        card: Map<String, Any?>
    ): List<Map<String, Any?>> {
        if (cardId.isBlank()) {
            return cards + card
        }
        val updatedCards = cards.toMutableList()
        val replaceIndex = updatedCards.indexOfFirst { existing ->
            existing["card_id"]?.toString()?.trim() == cardId
        }
        if (replaceIndex >= 0) {
            updatedCards[replaceIndex] = card
        } else {
            updatedCards += card
        }
        return updatedCards
    }

    private fun shouldSaveSnapshotLocked(
        context: Context,
        runId: String,
        card: Map<String, Any?>
    ): Boolean {
        val status = textValue(card["status"]).ifBlank {
            textValue(stringMap(card["header"])["status"])
        }.lowercase(Locale.US)
        if (status.isNotEmpty() && status != "running") {
            return true
        }
        val now = System.currentTimeMillis()
        val lastWrite = lastSnapshotWriteMsByRun[runId]
            ?: runFile(context, runId).lastModified()
        return now - lastWrite >= SNAPSHOT_MIN_INTERVAL_MS
    }

    private fun safeFilePart(value: String): String {
        return value.replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(80)
            .ifBlank { "run" }
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun textValue(value: Any?): String {
        return value?.toString()?.trim().orEmpty()
    }

    private fun numberToLong(value: Any?): Long? {
        return when (value) {
            is Long -> value
            is Int -> value.toLong()
            is Number -> value.toLong()
            is String -> value.trim().toLongOrNull()
            else -> null
        }
    }

    private fun booleanValue(value: Any?): Boolean? {
        return when (value) {
            is Boolean -> value
            is String -> when (value.trim().lowercase(Locale.US)) {
                "true" -> true
                "false" -> false
                else -> null
            }
            else -> null
        }
    }

    private fun stringMap(value: Any?): Map<String, Any?> {
        if (value !is Map<*, *>) return emptyMap()
        return linkedMapOf<String, Any?>().apply {
            value.forEach { (key, item) ->
                if (key != null) {
                    put(key.toString(), item)
                }
            }
        }
    }

    private fun listOfMaps(value: Any?): List<Map<String, Any?>> {
        if (value !is List<*>) return emptyList()
        return value.mapNotNull { item ->
            stringMap(item).takeIf { it.isNotEmpty() }
        }
    }

    private fun sanitizeMap(value: Map<String, Any?>): Map<String, Any?> {
        return linkedMapOf<String, Any?>().apply {
            value.forEach { (key, item) ->
                put(key, sanitizeValue(item))
            }
        }
    }

    private fun sanitizeValue(value: Any?): Any? {
        return when (value) {
            null -> null
            is String, is Number, is Boolean -> value
            is Map<*, *> -> linkedMapOf<String, Any?>().apply {
                value.forEach { (key, item) ->
                    if (key != null) {
                        put(key.toString(), sanitizeValue(item))
                    }
                }
            }
            is List<*> -> value.map(::sanitizeValue)
            else -> value.toString()
        }
    }

    private fun formatTime(ms: Long): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
        return formatter.format(Date(ms))
    }
}
