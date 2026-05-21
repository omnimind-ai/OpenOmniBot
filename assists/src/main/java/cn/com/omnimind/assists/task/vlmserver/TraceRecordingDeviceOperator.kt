package cn.com.omnimind.assists.task.vlmserver

import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-task raw trace session for one OOB-local `vlm_task`.
 *
 * It only records host-side raw I/O and lifecycle breadcrumbs so the provider
 * can later materialize the canonical run log. It must not interpret UTG
 * semantics locally.
 *
 * @param taskId Stable task identifier used by the app-side task manager.
 * @param goal User-visible goal string for this task session.
 * @param startedAtMs Original task start timestamp in milliseconds. This value
 *   is kept stable even if the local task pauses and resumes.
 */
class VlmTaskTraceSession(
    val taskId: String,
    val goal: String,
    val startedAtMs: Long,
) {
    private val nextActionStepIndex = AtomicInteger(0)
    private val recordedEvents =
        Collections.synchronizedList(mutableListOf<Map<String, Any?>>())

    /**
     * Record one lifecycle-side trace event that is not a concrete device act.
     *
     * @param eventType Stable raw event name such as `run_started`,
     *   `compile_decided`, or `waiting_input`.
     * @param message Optional human-readable context attached to the event.
     * @param status Optional task status snapshot for this lifecycle event.
     * @param extra Additional JSON-safe payload fields that should travel to the
     *   provider unchanged.
     * @param stepIndex Optional step index when this lifecycle event belongs to a
     *   specific device step. Pass `null` for task-global events.
     */
    fun recordLifecycleEvent(
        eventType: String,
        message: String? = null,
        status: String? = null,
        extra: Map<String, Any?> = emptyMap(),
        stepIndex: Int? = null,
    ) {
        val event = linkedMapOf<String, Any?>(
            "event_type" to eventType,
            "ts_ms" to System.currentTimeMillis(),
            "step_index" to stepIndex,
            "message" to message,
            "status" to status,
        )
        if (extra.isNotEmpty()) {
            event["extra"] = LinkedHashMap(extra)
        }
        recordedEvents.add(event)
    }

    /**
     * Record one raw device I/O event around the actual host operation.
     *
     * @param eventType Stable low-level event name such as `click`,
     *   `capture_screenshot`, or `press_hotkey`.
     * @param request JSON-safe request payload emitted before calling the host.
     * @param response JSON-safe response payload captured after the host call.
     * @param advanceStepIndex Whether this event represents a concrete action
     *   step that should advance the session step counter for the next device
     *   action.
     */
    fun recordDeviceEvent(
        eventType: String,
        request: Map<String, Any?>,
        response: Map<String, Any?>,
        advanceStepIndex: Boolean = false,
    ) {
        val stepIndex = nextActionStepIndex.get()
        recordedEvents.add(
            linkedMapOf(
                "event_type" to eventType,
                "ts_ms" to System.currentTimeMillis(),
                "step_index" to if (advanceStepIndex) stepIndex else stepIndex,
                "request" to LinkedHashMap(request),
                "response" to LinkedHashMap(response),
            )
        )
        if (advanceStepIndex) {
            nextActionStepIndex.incrementAndGet()
        }
    }

    /**
     * Return a stable copy of all collected raw events for provider upload.
     */
    fun snapshotEvents(): List<Map<String, Any?>> {
        synchronized(recordedEvents) {
            return recordedEvents.map { LinkedHashMap(it) }
        }
    }
}

/**
 * Thin `DeviceOperator` wrapper that records raw host I/O without changing the
 * existing execution behavior.
 *
 * @param delegate Existing device operator that performs the real Accessibility
 *   and screenshot work.
 * @param traceSession Mutable per-task recorder that owns the raw event buffer.
 */
class TraceRecordingDeviceOperator(
    private val delegate: DeviceOperator,
    private val traceSession: VlmTaskTraceSession,
) : DeviceOperator {
    override suspend fun clickCoordinate(x: Float, y: Float): OperationResult {
        val result = delegate.clickCoordinate(x, y)
        traceSession.recordDeviceEvent(
            eventType = "click",
            request = mapOf(
                "action" to mapOf(
                    "type" to "click",
                    "params" to mapOf("x" to x, "y" to y),
                )
            ),
            response = operationResultMap(result),
            advanceStepIndex = true,
        )
        return result
    }

    override suspend fun longClickCoordinate(x: Float, y: Float, duration: Long): OperationResult {
        val result = delegate.longClickCoordinate(x, y, duration)
        traceSession.recordDeviceEvent(
            eventType = "long_click",
            request = mapOf(
                "action" to mapOf(
                    "type" to "long_press",
                    "params" to mapOf(
                        "x" to x,
                        "y" to y,
                        "duration_ms" to duration,
                    ),
                )
            ),
            response = operationResultMap(result),
            advanceStepIndex = true,
        )
        return result
    }

    override suspend fun inputText(text: String): OperationResult {
        val result = delegate.inputText(text)
        traceSession.recordDeviceEvent(
            eventType = "input_text",
            request = mapOf(
                "action" to mapOf(
                    "type" to "input_text",
                    "params" to mapOf("text" to text),
                )
            ),
            response = operationResultMap(result),
            advanceStepIndex = true,
        )
        return result
    }

    override suspend fun pressHotKey(key: String): OperationResult {
        val result = delegate.pressHotKey(key)
        traceSession.recordDeviceEvent(
            eventType = "press_hotkey",
            request = mapOf(
                "action" to mapOf(
                    "type" to "press_key",
                    "params" to mapOf("key" to key),
                )
            ),
            response = operationResultMap(result),
            advanceStepIndex = true,
        )
        return result
    }

    override suspend fun copyToClipboard(text: String): OperationResult {
        return delegate.copyToClipboard(text)
    }

    override suspend fun getClipboard(): String? {
        return delegate.getClipboard()
    }

    override suspend fun slideCoordinate(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        duration: Long,
    ): OperationResult {
        return recordSlide(
            result = delegate.slideCoordinate(x1, y1, x2, y2, duration),
            x1 = x1,
            y1 = y1,
            x2 = x2,
            y2 = y2,
            duration = duration,
            targetDescription = null,
        )
    }

    override suspend fun slideCoordinateWithContext(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        duration: Long,
        targetDescription: String,
    ): OperationResult {
        return recordSlide(
            result = delegate.slideCoordinateWithContext(
                x1 = x1,
                y1 = y1,
                x2 = x2,
                y2 = y2,
                duration = duration,
                targetDescription = targetDescription,
            ),
            x1 = x1,
            y1 = y1,
            x2 = x2,
            y2 = y2,
            duration = duration,
            targetDescription = targetDescription,
        )
    }

    private fun recordSlide(
        result: OperationResult,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        duration: Long,
        targetDescription: String?,
    ): OperationResult {
        val params = linkedMapOf<String, Any?>(
            "x1" to x1,
            "y1" to y1,
            "x2" to x2,
            "y2" to y2,
            "duration_ms" to duration,
        )
        if (!targetDescription.isNullOrBlank()) {
            params["target_description"] = targetDescription
        }
        traceSession.recordDeviceEvent(
            eventType = "slide",
            request = mapOf(
                "action" to mapOf(
                    "type" to "swipe",
                    "params" to params,
                )
            ),
            response = operationResultMap(result),
            advanceStepIndex = true,
        )
        return result
    }

    override suspend fun goHome(): OperationResult {
        val result = delegate.goHome()
        traceSession.recordDeviceEvent(
            eventType = "go_home",
            request = mapOf(
                "action" to mapOf(
                    "type" to "press_key",
                    "params" to mapOf("key" to "HOME"),
                )
            ),
            response = operationResultMap(result),
            advanceStepIndex = true,
        )
        return result
    }

    override suspend fun goBack(): OperationResult {
        val result = delegate.goBack()
        traceSession.recordDeviceEvent(
            eventType = "go_back",
            request = mapOf(
                "action" to mapOf(
                    "type" to "press_key",
                    "params" to mapOf("key" to "BACK"),
                )
            ),
            response = operationResultMap(result),
            advanceStepIndex = true,
        )
        return result
    }

    override suspend fun launchApplication(packageName: String): OperationResult {
        val result = delegate.launchApplication(packageName)
        traceSession.recordDeviceEvent(
            eventType = "launch_application",
            request = mapOf(
                "action" to mapOf(
                    "type" to "open_app",
                    "params" to mapOf("package_name" to packageName),
                )
            ),
            response = operationResultMap(result),
            advanceStepIndex = false,
        )
        return result
    }

    override suspend fun captureScreenshot(): String {
        val screenshot = delegate.captureScreenshot()
        traceSession.recordDeviceEvent(
            eventType = "capture_screenshot",
            request = mapOf("mode" to "base64"),
            response = mapOf(
                "success" to screenshot.isNotBlank(),
                "base64_length" to screenshot.length,
            ),
            advanceStepIndex = false,
        )
        return screenshot
    }

    override fun getLastScreenshotWidth(): Int {
        return delegate.getLastScreenshotWidth()
    }

    override fun getLastScreenshotHeight(): Int {
        return delegate.getLastScreenshotHeight()
    }

    override fun getDisplayWidth(): Int {
        return delegate.getDisplayWidth()
    }

    override fun getDisplayHeight(): Int {
        return delegate.getDisplayHeight()
    }

    override suspend fun showInfo(message: String) {
        delegate.showInfo(message)
        traceSession.recordLifecycleEvent(
            eventType = "show_info",
            message = message,
        )
    }

    /**
     * Convert one low-level operation result into the JSON-safe payload expected
     * by the provider raw-trace ingest endpoint.
     */
    private fun operationResultMap(result: OperationResult): Map<String, Any?> {
        return linkedMapOf(
            "success" to result.success,
            "message" to result.message,
            "data" to result.data,
            "provider_run_log_json" to result.providerRunLogJson,
            "provider_run_log_path" to result.providerRunLogPath,
            "canonical_run_log_path" to result.canonicalRunLogPath,
        )
    }
}
