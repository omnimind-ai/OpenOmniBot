package cn.com.omnimind.bot.debug

import BaseApplication
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.com.omnimind.accessibility.service.AssistsService
import cn.com.omnimind.assists.controller.accessibility.AccessibilityController
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.runlog.OmniflowActionRuntime
import cn.com.omnimind.bot.runlog.RunLogPagePackageInference
import cn.com.omnimind.bot.runlog.OobOmniFlowToolkitService
import cn.com.omnimind.bot.util.AssistsUtil
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Debug-only validation for a reusable "segment" Function loaded inside one
 * parent Function run. The default child segment is a model-free open Settings
 * action; the parent calls it through call_tool(function_id=...).
 */
class DebugOobFunctionSegmentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        val childFunctionId = intent.firstNonBlankExtra(
            "child_function_id",
            "childFunctionId"
        ).ifBlank { DEFAULT_CHILD_FUNCTION_ID }
        val parentFunctionId = intent.firstNonBlankExtra(
            "parent_function_id",
            "parentFunctionId",
        ).ifBlank { DEFAULT_PARENT_FUNCTION_ID }
        val packageName = intent.firstNonBlankExtra(
            "package_name",
            "packageName",
        ).ifBlank { DEFAULT_PACKAGE_NAME }
        val goal = intent.firstNonBlankExtra("goal")
            .ifBlank { "Open Settings segment" }

        scope.launch {
            val result = runCatching {
                validateSegmentRun(
                    context = appContext,
                    childFunctionId = childFunctionId,
                    parentFunctionId = parentFunctionId,
                    packageName = packageName,
                    goal = goal,
                )
            }.getOrElse { error ->
                linkedMapOf<String, Any?>(
                    "success" to false,
                    "phase" to "exception",
                    "child_function_id" to childFunctionId,
                    "parent_function_id" to parentFunctionId,
                    "package_name" to packageName,
                    "error_message" to error.message.orEmpty(),
                    "error_type" to error.javaClass.name,
                )
            }
            val json = gson.toJson(result)
            File(appContext.filesDir, RESULT_FILE).writeText(json)
            OmniLog.i(TAG, json)
        }
    }

    private suspend fun validateSegmentRun(
        context: Context,
        childFunctionId: String,
        parentFunctionId: String,
        packageName: String,
        goal: String,
    ): Map<String, Any?> {
        val timing = ValidationTiming()
        val toolkit = OobOmniFlowToolkitService(context)
        timing.measure("wait_accessibility_ms") {
            waitForAccessibility(context)
        }
        val beforeObservation = timing.measure("observe_before_ms") {
            waitForPageObservation()
        }
        val beforePackage = beforeObservation.packageName
        val beforeXml = beforeObservation.xml
        val registerChild = timing.measure("register_child_ms") {
            toolkit.registerFunction(
                mapOf(
                    "functionSpec" to openSettingsChildSpec(
                        functionId = childFunctionId,
                        packageName = packageName,
                        sourcePageXml = beforeXml,
                        sourcePackageName = beforePackage,
                    )
                )
            )
        }
        val registerParent = timing.measure("register_parent_ms") {
            toolkit.registerFunction(
                mapOf("functionSpec" to parentCallsChildSpec(parentFunctionId, childFunctionId))
            )
        }
        val storedChild = timing.measure("load_child_ms") {
            toolkit.getFunction(mapOf("function_id" to childFunctionId))
        }
        val recall = timing.measure("recall_ms") {
            toolkit.recall(
                mapOf(
                    "goal" to goal,
                    "current_package" to beforePackage,
                    "current_xml" to beforeXml,
                    "k" to 3,
                    "auto_execute" to true,
                )
            )
        }
        val parentRun = timing.measure("parent_run_ms") {
            toolkit.callFunction(
                mapOf(
                    "function_id" to parentFunctionId,
                    "goal" to goal,
                    "arguments" to emptyMap<String, Any?>(),
                )
            )
        }
        timing.measure("post_run_settle_ms") {
            delay(POST_RUN_DEVICE_SETTLE_MS)
        }
        val afterObservation = timing.measure("observe_after_ms") {
            waitForPageObservation(expectedPackage = packageName)
        }
        val afterPackage = afterObservation.packageName
        val nestedSummary = nestedSummary(parentRun)
        val loaded = storedChild["function_id"] == childFunctionId
        val parentSucceeded = parentRun["success"] == true
        val nestedLoaded = nestedSummary["nested_function_id"] == childFunctionId
        val nestedOpenedApp = nestedSummary["nested_tools"] is List<*> &&
            (nestedSummary["nested_tools"] as List<*>).contains("open_app")
        val packageMatched = afterPackage == packageName
        return linkedMapOf<String, Any?>(
            "success" to (
                registerChild["success"] == true &&
                    registerParent["success"] == true &&
                    loaded &&
                    parentSucceeded &&
                    nestedLoaded &&
                    nestedOpenedApp &&
                    packageMatched
                ),
            "phase" to "validated",
            "child_function_id" to childFunctionId,
            "parent_function_id" to parentFunctionId,
            "package_name" to packageName,
            "before_package" to beforePackage,
            "before_xml_present" to beforeXml.isNotBlank(),
            "after_package" to afterPackage,
            "after_xml_present" to afterObservation.xml.isNotBlank(),
            "device_package_match" to packageMatched,
            "loaded_child" to loaded,
            "nested_loaded" to nestedLoaded,
            "nested_opened_app" to nestedOpenedApp,
            "register_child" to registerChild,
            "register_parent" to registerParent,
            "recall" to recall,
            "parent_run" to parentRun,
            "parent_nested_summary" to nestedSummary,
            "timing" to timing.finish(
                counts = mapOf(
                    "register_success_count" to listOf(
                        registerChild["success"],
                        registerParent["success"],
                    ).count { it == true },
                    "parent_step_count" to ((parentRun["step_count"] as? Number)?.toInt()
                        ?: (parentRun["step_results"] as? List<*>)?.size
                        ?: 0),
                    "nested_step_count" to ((nestedSummary["nested_step_count"] as? Number)?.toInt()
                        ?: 0),
                    "recall_candidate_count" to ((recall["candidates"] as? List<*>)?.size ?: 0),
                    "segment_candidate_count" to ((recall["segment_candidates"] as? List<*>)?.size
                        ?: 0),
                )
            ),
            "source" to "debug_oob_function_segment_validation",
        )
    }

    private class ValidationTiming {
        private val startedAtMs = System.currentTimeMillis()
        private val startedAtNanos = System.nanoTime()
        private val phases = linkedMapOf<String, Long>()

        suspend fun <T> measure(name: String, block: suspend () -> T): T {
            val startNanos = System.nanoTime()
            return try {
                block()
            } finally {
                phases[name] = elapsedMs(startNanos)
            }
        }

        fun finish(counts: Map<String, Any?> = emptyMap()): Map<String, Any?> {
            val finishedAtMs = System.currentTimeMillis()
            return linkedMapOf(
                "source" to "debug_oob_function_segment_validation",
                "started_at_ms" to startedAtMs,
                "finished_at_ms" to finishedAtMs,
                "duration_ms" to elapsedMs(startedAtNanos),
                "phase_ms" to phases.toMap(),
                "counts" to counts,
            )
        }

        private fun elapsedMs(startNanos: Long): Long =
            ((System.nanoTime() - startNanos) / 1_000_000L).coerceAtLeast(0L)
    }

    private data class PageObservation(
        val packageName: String,
        val xml: String,
        val rawPackageName: String = "",
        val xmlChars: Int = xml.length,
        val reason: String = "",
    )

    private suspend fun waitForAccessibility(context: Context) {
        if (!AssistsUtil.Core.isInitialized()) {
            AssistsUtil.Core.initCore(context)
        }
        repeat(50) {
            if (AssistsService.instance != null && AccessibilityController.initController()) return
            delay(200L)
        }
        error("OOB accessibility service is not bound")
    }

    private fun nestedSummary(parentRun: Map<String, Any?>): Map<String, Any?> {
        val stepResults = parentRun["step_results"] as? List<*> ?: emptyList<Any?>()
        val firstStep = stepResults.firstOrNull() as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val nestedSteps = firstStep["step_results"] as? List<*> ?: emptyList<Any?>()
        val nestedTools = nestedSteps.mapNotNull { raw ->
            (raw as? Map<*, *>)?.get("tool")?.toString()?.takeIf { it.isNotBlank() }
        }
        return linkedMapOf(
            "step_tool" to firstStep["tool"],
            "step_executor" to firstStep["executor"],
            "step_success" to firstStep["success"],
            "nested_function_id" to firstStep["nested_function_id"],
            "nested_run_id" to firstStep["nested_run_id"],
            "nested_step_count" to firstStep["nested_step_count"],
            "nested_success_step_count" to firstStep["nested_success_step_count"],
            "nested_tools" to nestedTools,
        )
    }

    private fun openSettingsChildSpec(
        functionId: String,
        packageName: String,
        sourcePageXml: String?,
        sourcePackageName: String,
    ): Map<String, Any?> =
        reusableFunctionSpec(
            functionId = functionId,
            name = "Open Settings segment",
            description = "Open Android Settings from the current UDEG-matched screen.",
            steps = listOf(
                linkedMapOf<String, Any?>(
                    "id" to "open_settings",
                    "index" to 0,
                    "title" to "Open Settings",
                    "kind" to "omniflow_action",
                    "executor" to "omniflow",
                    "omniflow_action" to "open_app",
                    "local_action" to "open_app",
                    "tool" to "open_app",
                    "callable_tool" to "open_app",
                    "model_free" to true,
                    "scriptable" to true,
                    "args" to mapOf("package_name" to packageName),
                    "source_context" to sourcePageXml?.trim()?.takeIf { it.isNotEmpty() }?.let { xml ->
                        mapOf(
                            "src_ctx" to mapOf(
                                "page" to xml,
                                "package_name" to sourcePackageName,
                            )
                        )
                    },
                ).filterValues { it != null }
            ),
        )

    private fun parentCallsChildSpec(parentFunctionId: String, childFunctionId: String): Map<String, Any?> =
        reusableFunctionSpec(
            functionId = parentFunctionId,
            name = "Call open Settings segment",
            description = "Parent Function that loads and calls the reusable open Settings segment.",
            steps = listOf(
                mapOf(
                    "id" to "call_open_settings_segment",
                    "index" to 0,
                    "title" to "Call open Settings segment",
                    "kind" to "omniflow_function",
                    "executor" to "omniflow",
                    "tool" to "call_tool",
                    "callable_tool" to "call_tool",
                    "model_free" to true,
                    "scriptable" to true,
                    "args" to mapOf(
                        "function_id" to childFunctionId,
                        "arguments" to emptyMap<String, Any?>(),
                    ),
                )
            ),
        )

    private fun reusableFunctionSpec(
        functionId: String,
        name: String,
        description: String,
        steps: List<Map<String, Any?>>,
    ): Map<String, Any?> = linkedMapOf(
        "schema_version" to "oob.reusable_function.v1",
        "function_id" to functionId,
        "name" to name,
        "description" to description,
        "parameters" to emptyList<Map<String, Any?>>(),
        "source" to mapOf(
            "source" to "debug_oob_function_segment_validation",
            "goal" to description,
            "tool_name" to "debug_oob_function_segment",
        ),
        "execution" to mapOf(
            "kind" to "tool_sequence",
            "runner" to "oob_tool_sequence",
            "entrypoint" to "execute",
            "capabilities" to mapOf(
                "scriptable_step_count" to steps.size,
                "model_free_step_count" to steps.size,
                "omniflow_step_count" to steps.size,
                "agent_step_count" to 0,
                "requires_agent_fallback" to false,
            ),
            "steps" to steps,
            "step_count" to steps.size,
            "omniflow_step_count" to steps.size,
            "agent_step_count" to 0,
            "requires_agent_fallback" to false,
        ),
    )

    private suspend fun waitForPageObservation(
        expectedPackage: String? = null,
    ): PageObservation {
        var last = PageObservation(packageName = "", xml = "", reason = "not_observed")
        repeat(PAGE_OBSERVE_ATTEMPTS) { attempt ->
            val observation = currentObservation()
            last = observation
            val packageReady = expectedPackage.isNullOrBlank() ||
                packageMatches(expectedPackage, observation.packageName)
            if (packageReady && observation.xml.isNotBlank()) {
                return observation
            }
            if (attempt < PAGE_OBSERVE_ATTEMPTS - 1) {
                delay(PAGE_OBSERVE_INTERVAL_MS)
            }
        }
        if (last.xml.isBlank()) {
            error(
                "OOB current page XML is unavailable; " +
                    "last_package=${last.packageName.ifBlank { last.rawPackageName }} " +
                    "last_xml_chars=${last.xmlChars} reason=${last.reason}"
            )
        }
        return last
    }

    private suspend fun currentObservation(): PageObservation {
        val accessibilityReady = AccessibilityController.initController()
        val xml = firstNonBlank(
            if (accessibilityReady) {
                captureXmlOnMain()
            } else {
                null
            },
            OmniflowActionRuntime.backend.currentXml(),
        ).orEmpty()
        val rawPackage = firstNonBlank(
            if (accessibilityReady) {
                packageNameOnMain()
            } else {
                null
            },
            OmniflowActionRuntime.backend.currentPackageName(),
        ).orEmpty()
        val effectivePackage = RunLogPagePackageInference.effectivePackage(rawPackage, xml)
        if (effectivePackage.isBlank() || isOobPackage(effectivePackage)) {
            return PageObservation(
                packageName = effectivePackage,
                xml = xml,
                rawPackageName = rawPackage,
                reason = if (!accessibilityReady) {
                    "accessibility_controller_not_ready"
                } else if (effectivePackage.isBlank()) {
                    "blank_effective_package"
                } else {
                    "oob_package_filtered"
                },
            )
        }
        return PageObservation(
            packageName = effectivePackage,
            xml = xml,
            rawPackageName = rawPackage,
            reason = "observed",
        )
    }

    private suspend fun captureXmlOnMain(): String? = withContext(Dispatchers.Main.immediate) {
        runCatching { AccessibilityController.getCaptureScreenShotXml(true) }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private suspend fun packageNameOnMain(): String? = withContext(Dispatchers.Main.immediate) {
        runCatching { AccessibilityController.getPackageName() }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun isOobPackage(packageName: String): Boolean =
        packageName == BaseApplication.instance.packageName ||
            packageName.startsWith("cn.com.omnimind.")

    private fun packageMatches(expectedPackage: String, currentPackage: String): Boolean =
        expectedPackage == currentPackage ||
            (
                expectedPackage.substringAfterLast('.') == currentPackage.substringAfterLast('.') &&
                    (expectedPackage.startsWith("com.android.") ||
                        expectedPackage.startsWith("com.google.android.")) &&
                    (currentPackage.startsWith("com.android.") ||
                        currentPackage.startsWith("com.google.android."))
                )

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstNotNullOfOrNull { value ->
            value?.trim()?.takeIf { it.isNotEmpty() }
        }

    companion object {
        private const val TAG = "DebugOobFunctionSegmentReceiver"
        private const val RESULT_FILE = "debug-oob-function-segment-result.json"
        private const val DEFAULT_CHILD_FUNCTION_ID = "debug_open_settings_segment"
        private const val DEFAULT_PARENT_FUNCTION_ID = "debug_parent_calls_open_settings_segment"
        private const val DEFAULT_PACKAGE_NAME = "com.android.settings"
        private const val POST_RUN_DEVICE_SETTLE_MS = 1_200L
        private const val PAGE_OBSERVE_ATTEMPTS = 80
        private const val PAGE_OBSERVE_INTERVAL_MS = 250L
        private val gson = GsonBuilder().disableHtmlEscaping().create()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    private fun Intent?.firstNonBlankExtra(vararg names: String): String {
        for (name in names) {
            val value = this?.getStringExtra(name)?.trim().orEmpty()
            if (value.isNotEmpty()) return value
        }
        return ""
    }
}
