package cn.com.omnimind.bot.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import cn.com.omnimind.accessibility.service.AssistsService
import cn.com.omnimind.accessibility.service.AssistsServiceListener
import cn.com.omnimind.assists.controller.accessibility.AccessibilityController
import cn.com.omnimind.assists.task.vlmserver.ManualVlmTraceRecorder
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.util.AssistsUtil
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class DebugManualTraceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        val durationMs = intent?.getLongExtra("durationMs", DEFAULT_DURATION_MS)
            ?.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
            ?: DEFAULT_DURATION_MS
        val sessionLabel = intent?.getStringExtra("sessionLabel")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "debug_manual_trace"

        scope.launch {
            val result = runCatching {
                runManualTrace(appContext, sessionLabel, durationMs)
            }.getOrElse { error ->
                linkedMapOf<String, Any?>(
                    "success" to false,
                    "phase" to "exception",
                    "error_message" to error.message.orEmpty(),
                    "error_type" to error.javaClass.name,
                    "token_usage_total" to 0,
                )
            }
            val json = gson.toJson(result)
            File(appContext.filesDir, RESULT_FILE).writeText(json)
            OmniLog.i(TAG, json)
        }
    }

    private suspend fun runManualTrace(
        context: Context,
        sessionLabel: String,
        durationMs: Long,
    ): Map<String, Any?> {
        val timing = DebugTiming()
        timing.measure("wait_accessibility_ms") {
            waitForAccessibility(context)
        }
        val recorder = ManualVlmTraceRecorder(context, sessionLabel)
        val started = timing.measure("start_recorder_ms") {
            recorder.start()
        }
        val eventProbe = DebugEventProbe()
        if (started) {
            AssistsService.addListener(eventProbe.listener)
        }
        File(context.filesDir, STARTED_FILE).writeText(
            gson.toJson(
                linkedMapOf(
                    "started" to started,
                    "session_label" to sessionLabel,
                    "duration_ms" to durationMs,
                    "started_at_ms" to System.currentTimeMillis(),
                )
            )
        )
        if (!started) {
            return linkedMapOf<String, Any?>(
                "success" to false,
                "phase" to "recorder_not_started",
                "session_label" to sessionLabel,
                "duration_ms" to durationMs,
                "action_count" to 0,
                "actions" to emptyList<Map<String, Any?>>(),
                "token_usage_total" to 0,
                "timing" to timing.finish(),
                "event_probe" to eventProbe.snapshot(),
                "source" to "debug_manual_trace",
            )
        }
        timing.measure("recording_window_ms") {
            delay(durationMs)
        }
        val traceResult = timing.measure("stop_recorder_ms") {
            try {
                recorder.stop()
            } finally {
                AssistsService.removeListener(eventProbe.listener)
            }
        }
        val actionNames = traceResult.actions.map { it.actionName }
        return linkedMapOf<String, Any?>(
            "success" to traceResult.actions.isNotEmpty(),
            "phase" to "validated",
            "session_label" to sessionLabel,
            "duration_ms" to durationMs,
            "action_count" to traceResult.actionCount,
            "action_names" to actionNames,
            "has_click" to actionNames.contains("click"),
            "has_swipe" to actionNames.contains("swipe"),
            "summary" to traceResult.summary,
            "actions" to traceResult.actions.map { action ->
                linkedMapOf<String, Any?>(
                    "action_name" to action.actionName,
                    "title" to action.title,
                    "params" to action.params,
                    "package_name" to action.packageName,
                    "started_at_ms" to action.startedAtMs,
                    "finished_at_ms" to action.finishedAtMs,
                    "duration_ms" to (action.finishedAtMs - action.startedAtMs).coerceAtLeast(0L),
                    "before_xml_present" to !action.beforeXml.isNullOrBlank(),
                    "after_xml_present" to !action.afterXml.isNullOrBlank(),
                    "summary" to action.summary,
                )
            },
            "token_usage_total" to 0,
            "timing" to timing.finish(
                counts = mapOf(
                    "action_count" to traceResult.actionCount,
                    "click_count" to actionNames.count { it == "click" },
                    "swipe_count" to actionNames.count { it == "swipe" },
                )
            ),
            "event_probe" to eventProbe.snapshot(),
            "source" to "debug_manual_trace",
        )
    }

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

    private class DebugTiming {
        private val startedAtMs = System.currentTimeMillis()
        private val startedAtNanos = System.nanoTime()
        private val phases = linkedMapOf<String, Long>()

        suspend fun <T> measure(name: String, block: suspend () -> T): T {
            val phaseStarted = System.nanoTime()
            return try {
                block()
            } finally {
                phases[name] = elapsedMs(phaseStarted)
            }
        }

        fun finish(counts: Map<String, Any?> = emptyMap()): Map<String, Any?> {
            val finishedAtMs = System.currentTimeMillis()
            return linkedMapOf(
                "source" to "debug_manual_trace",
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

    private class DebugEventProbe {
        private val lock = Any()
        private val typeCounts = linkedMapOf<String, Int>()
        private val packageCounts = linkedMapOf<String, Int>()
        private val samples = mutableListOf<Map<String, Any?>>()
        private var totalEvents = 0
        private var eventsWithSource = 0
        private var eventsWithoutSource = 0

        val listener = object : AssistsServiceListener {
            override fun onAccessibilityEvent(event: AccessibilityEvent) {
                record(event)
            }
        }

        fun snapshot(): Map<String, Any?> = synchronized(lock) {
            linkedMapOf(
                "total_events" to totalEvents,
                "events_with_source" to eventsWithSource,
                "events_without_source" to eventsWithoutSource,
                "type_counts" to typeCounts.toMap(),
                "package_counts" to packageCounts.toMap(),
                "samples" to samples.toList(),
            )
        }

        private fun record(event: AccessibilityEvent) {
            val eventType = eventTypeName(event.eventType)
            val packageName = event.packageName?.toString().orEmpty().ifBlank { "<empty>" }
            val source = runCatching { event.source }.getOrNull()
            synchronized(lock) {
                totalEvents += 1
                typeCounts[eventType] = (typeCounts[eventType] ?: 0) + 1
                packageCounts[packageName] = (packageCounts[packageName] ?: 0) + 1
                if (source == null) {
                    eventsWithoutSource += 1
                } else {
                    eventsWithSource += 1
                }
                if (samples.size < MAX_EVENT_SAMPLES) {
                    samples += eventSample(event, eventType, packageName, source)
                }
            }
        }

        private fun eventSample(
            event: AccessibilityEvent,
            eventType: String,
            packageName: String,
            source: AccessibilityNodeInfo?,
        ): Map<String, Any?> {
            val bounds = source?.let {
                val rect = Rect()
                it.getBoundsInScreen(rect)
                if (rect.isEmpty) null else "${rect.left},${rect.top},${rect.right},${rect.bottom}"
            }
            return linkedMapOf(
                "event_type" to eventType,
                "package_name" to packageName,
                "class_name" to event.className?.toString(),
                "text" to event.text.joinToString(" ").take(120),
                "has_source" to (source != null),
                "source_class" to source?.className?.toString(),
                "source_view_id" to source?.viewIdResourceName,
                "source_text" to source?.text?.toString()?.take(120),
                "source_content_description" to source?.contentDescription?.toString()?.take(120),
                "source_bounds" to bounds,
                "scroll_x" to event.scrollX,
                "scroll_y" to event.scrollY,
                "scroll_delta_x" to event.scrollDeltaX,
                "scroll_delta_y" to event.scrollDeltaY,
                "from_index" to event.fromIndex,
                "to_index" to event.toIndex,
                "item_count" to event.itemCount,
            )
        }

        private fun eventTypeName(eventType: Int): String = when (eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> "TYPE_VIEW_CLICKED"
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> "TYPE_VIEW_LONG_CLICKED"
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "TYPE_VIEW_TEXT_CHANGED"
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "TYPE_WINDOW_STATE_CHANGED"
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "TYPE_WINDOW_CONTENT_CHANGED"
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> "TYPE_VIEW_SCROLLED"
            else -> "TYPE_$eventType"
        }

        private companion object {
            private const val MAX_EVENT_SAMPLES = 24
        }
    }

    companion object {
        private const val TAG = "DebugManualTraceReceiver"
        private const val STARTED_FILE = "debug-manual-trace-started.json"
        private const val RESULT_FILE = "debug-manual-trace-result.json"
        private const val DEFAULT_DURATION_MS = 5_000L
        private const val MIN_DURATION_MS = 1_000L
        private const val MAX_DURATION_MS = 30_000L
        private val gson = GsonBuilder().disableHtmlEscaping().create()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
