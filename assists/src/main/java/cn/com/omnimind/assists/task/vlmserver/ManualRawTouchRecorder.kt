package cn.com.omnimind.assists.task.vlmserver

import android.content.Context
import cn.com.omnimind.baselib.shizuku.ShizukuCapabilityManager
import cn.com.omnimind.baselib.util.OmniLog
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.math.hypot

internal data class ManualRawTouchStatus(
    val available: Boolean,
    val backend: String,
    val accessMethod: String? = null,
    val devicePath: String? = null,
    val deviceName: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val command: String? = null
) {
    fun asMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
        "available" to available,
        "backend" to backend,
        "access_method" to accessMethod,
        "device_path" to devicePath,
        "device_name" to deviceName,
        "error_code" to errorCode,
        "error_message" to errorMessage,
        "command" to command
    ).filterValues { it != null }
}

internal data class ManualRawTouchStart(
    val gestureId: Long,
    val startedAtMs: Long,
    val x: Float,
    val y: Float,
    val rawX: Int,
    val rawY: Int
)

internal data class ManualRawTouchGesture(
    val gestureId: Long,
    val actionName: String,
    val startedAtMs: Long,
    val finishedAtMs: Long,
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val rawStartX: Int,
    val rawStartY: Int,
    val rawEndX: Int,
    val rawEndY: Int,
    val durationMs: Long,
    val distancePx: Float,
    val pointCount: Int,
    val backend: String,
    val devicePath: String,
    val deviceName: String
)

/**
 * In-app raw touch recorder backed by Android's `getevent`.
 *
 * Manual operation recording can use this stream as a best-effort enhancement.
 * Normal Android app UIDs usually cannot read `/dev/input/event*`, so the
 * recorder tries plain `getevent`, `su -c getevent`, then Shizuku. If all fail,
 * callers can continue with Accessibility event recording and keep the raw
 * failure in diagnostics.
 */
internal class ManualRawTouchRecorder(
    private val context: Context? = null,
    private val displayWidth: Int,
    private val displayHeight: Int,
    private val onGestureStarted: (ManualRawTouchStart) -> Unit,
    private val onGestureFinished: (ManualRawTouchGesture) -> Unit
) {
    private val running = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private var process: Process? = null
    private var readerThread: Thread? = null
    private var shizukuExecThread: Thread? = null
    private var shizukuPollThread: Thread? = null
    private var shizukuSessionId: String? = null
    private var selectedMode: ShellMode? = null
    private var selectedDevice: ManualRawTouchParser.TouchDevice? = null
    private var selectedStreamCommand: List<String>? = null
    private var lastStatus: ManualRawTouchStatus = unavailable("not_started", "Raw touch recorder not started")

    fun start(): ManualRawTouchStatus {
        if (running.get()) return lastStatus
        val probe = probeTouchDevice()
        if (!probe.status.available || probe.mode == null || probe.device == null) {
            lastStatus = probe.status
            return lastStatus
        }
        val mode = probe.mode
        val device = probe.device
        val parser = ManualRawTouchParser.StreamParser(
            device = device,
            displayWidth = displayWidth,
            displayHeight = displayHeight,
            backend = BACKEND,
            onGestureStarted = onGestureStarted
        )
        if (mode.accessMethod == ACCESS_SHIZUKU) {
            return startShizukuStream(mode, device, parser)
        }
        return runCatching {
            val command = streamCommandFor(mode, device)
            val startedProcess = ProcessBuilder(command).redirectErrorStream(true).start()
            process = startedProcess
            selectedMode = mode
            selectedDevice = device
            selectedStreamCommand = command
            running.set(true)
            paused.set(false)
            readerThread = Thread({
                readEventStream(startedProcess, parser)
            }, "oob_raw_getevent_reader").apply {
                isDaemon = true
                start()
            }
            lastStatus = ManualRawTouchStatus(
                available = true,
                backend = BACKEND,
                accessMethod = mode.accessMethod,
                devicePath = device.path,
                deviceName = device.name,
                command = command.joinToString(" ")
            )
            OmniLog.d(TAG, "raw touch recorder started: ${lastStatus.asMap()}")
            lastStatus
        }.getOrElse { error ->
            running.set(false)
            closeProcess()
            lastStatus = unavailable("stream_start_failed", error.message.orEmpty(), mode.streamCommand)
            lastStatus
        }
    }

    fun pause() {
        paused.set(true)
    }

    fun resume() {
        paused.set(false)
    }

    fun stop(): ManualRawTouchStatus {
        running.set(false)
        paused.set(false)
        closeProcess()
        readerThread?.join(STOP_JOIN_TIMEOUT_MS)
        shizukuExecThread?.join(STOP_JOIN_TIMEOUT_MS)
        shizukuPollThread?.join(STOP_JOIN_TIMEOUT_MS)
        readerThread = null
        shizukuExecThread = null
        shizukuPollThread = null
        val device = selectedDevice
        val mode = selectedMode
        val streamCommand = selectedStreamCommand
        selectedDevice = null
        selectedMode = null
        selectedStreamCommand = null
        return if (lastStatus.available) {
            lastStatus.copy(
                accessMethod = mode?.accessMethod ?: lastStatus.accessMethod,
                devicePath = device?.path ?: lastStatus.devicePath,
                deviceName = device?.name ?: lastStatus.deviceName,
                command = streamCommand?.joinToString(" ") ?: lastStatus.command
            )
        } else {
            lastStatus
        }
    }

    fun status(): ManualRawTouchStatus = lastStatus

    fun isActive(): Boolean = running.get() && lastStatus.available

    private fun readEventStream(
        process: Process,
        parser: ManualRawTouchParser.StreamParser
    ) {
        runCatching {
            BufferedReader(InputStreamReader(process.inputStream)).useLines { lines ->
                val iterator = lines.iterator()
                while (running.get() && iterator.hasNext()) {
                    val line = iterator.next()
                    val gesture = parser.acceptLine(line) ?: continue
                    if (!paused.get()) {
                        onGestureFinished(gesture)
                    }
                }
            }
        }.onFailure { error ->
            if (running.get()) {
                OmniLog.w(TAG, "raw touch event stream failed: ${error.message}")
            }
        }.also {
            running.set(false)
        }
    }

    private fun probeTouchDevice(): ProbeResult {
        val errors = mutableListOf<String>()
        for (mode in SHELL_MODES) {
            val result = runCommand(mode.probeCommand, PROBE_TIMEOUT_MS)
            if (result.timedOut) {
                errors += "${mode.name}: timeout"
                continue
            }
            val devices = ManualRawTouchParser.parseTouchDevices(result.output)
            val selected = devices.firstOrNull()
            if (selected != null) {
                return ProbeResult(
                    status = ManualRawTouchStatus(
                        available = true,
                        backend = BACKEND,
                        accessMethod = mode.accessMethod,
                        devicePath = selected.path,
                        deviceName = selected.name,
                        command = mode.probeCommand.joinToString(" ")
                    ),
                    mode = mode,
                    device = selected
                )
            }
            errors += "${mode.name}: exit=${result.exitCode} ${result.output.take(MAX_ERROR_CHARS)}"
        }
        probeShizukuTouchDevice(errors)?.let { return it }
        return ProbeResult(
            status = unavailable(
                errorCode = "raw_touch_permission_denied",
                errorMessage = errors.joinToString(" | ").ifBlank {
                    "No readable touch-capable getevent device"
                }
            ),
            mode = null,
            device = null
        )
    }

    private fun probeShizukuTouchDevice(errors: MutableList<String>): ProbeResult? {
        val appContext = context?.applicationContext ?: context
        if (appContext == null) {
            errors += "shizuku_session: no Android context"
            return null
        }
        val manager = runCatching { ShizukuCapabilityManager.get(appContext) }
            .getOrElse { error ->
                errors += "shizuku_session: ${error.message.orEmpty()}"
                return null
            }
        val status = manager.getStatus()
        if (!status.isGranted()) {
            errors += "shizuku_session: status=${status.code.name} ${status.message}"
            return null
        }
        val command = SHIZUKU_GETEVENT_PROBE_COMMAND
        val result = runCatching {
            runBlocking {
                manager.executeRawShell(
                    command = command,
                    timeoutSeconds = SHIZUKU_PROBE_TIMEOUT_SECONDS,
                    confirmed = true
                )
            }
        }.getOrElse { error ->
            errors += "shizuku_session: ${error.message.orEmpty()}"
            return null
        }
        val output = listOf(result.output, result.stdout, result.stderr)
            .filter { it.isNotBlank() }
            .joinToString("\n")
        val selected = ManualRawTouchParser.parseTouchDevices(output).firstOrNull()
        if (selected == null) {
            errors += "shizuku_session: code=${result.code} exit=${result.exitCode} ${output.take(MAX_ERROR_CHARS)}"
            return null
        }
        val mode = ShellMode(
            name = "shizuku_session",
            accessMethod = ACCESS_SHIZUKU,
            probeCommand = listOf("shizuku", "shell.exec", command),
            streamCommand = listOf("shizuku", "shell.session_exec", SHIZUKU_GETEVENT_STREAM_COMMAND)
        )
        return ProbeResult(
            status = ManualRawTouchStatus(
                available = true,
                backend = BACKEND,
                accessMethod = mode.accessMethod,
                devicePath = selected.path,
                deviceName = selected.name,
                command = mode.probeCommand.joinToString(" ")
            ),
            mode = mode,
            device = selected
        )
    }

    private fun startShizukuStream(
        mode: ShellMode,
        device: ManualRawTouchParser.TouchDevice,
        parser: ManualRawTouchParser.StreamParser
    ): ManualRawTouchStatus {
        val appContext = context?.applicationContext ?: context
        if (appContext == null) {
            lastStatus = unavailable("shizuku_no_context", "Android context is required for Shizuku raw touch", mode.streamCommand)
            return lastStatus
        }
        return runCatching {
            val manager = ShizukuCapabilityManager.get(appContext)
            val sessionId = "oob_raw_touch_${UUID.randomUUID()}"
            val startResult = runBlocking {
                manager.startPrivilegedSession(
                    sessionId = sessionId,
                    sessionName = "oob_raw_touch_recorder",
                    confirmed = true
                )
            }
            if (!startResult.success) {
                error("Shizuku raw touch session start failed: ${startResult.code} ${startResult.message}")
            }
            val activeSessionId = startResult.sessionId
                ?: error("Shizuku raw touch session start returned empty session id")
            val streamCommand = shizukuStreamCommandFor(device)
            shizukuSessionId = activeSessionId
            selectedMode = mode
            selectedDevice = device
            selectedStreamCommand = listOf("shizuku", "shell.session_exec", streamCommand)
            running.set(true)
            paused.set(false)
            shizukuExecThread = Thread({
                runCatching {
                    runBlocking {
                        manager.execPrivilegedSession(
                            sessionId = activeSessionId,
                            command = streamCommand,
                            timeoutSeconds = SHIZUKU_STREAM_TIMEOUT_SECONDS,
                            confirmed = true
                        )
                    }
                }.onFailure { error ->
                    if (running.get()) {
                        OmniLog.w(TAG, "Shizuku raw touch stream exec failed: ${error.message}")
                    }
                }.also {
                    if (running.get()) {
                        running.set(false)
                    }
                }
            }, "oob_raw_getevent_shizuku_exec").apply {
                isDaemon = true
                start()
            }
            shizukuPollThread = Thread({
                pollShizukuTranscript(manager, activeSessionId, parser)
            }, "oob_raw_getevent_shizuku_poll").apply {
                isDaemon = true
                start()
            }
            lastStatus = ManualRawTouchStatus(
                available = true,
                backend = BACKEND,
                accessMethod = mode.accessMethod,
                devicePath = device.path,
                deviceName = device.name,
                command = selectedStreamCommand?.joinToString(" ")
            )
            OmniLog.d(TAG, "raw touch recorder started through Shizuku: ${lastStatus.asMap()}")
            lastStatus
        }.getOrElse { error ->
            running.set(false)
            closeProcess()
            lastStatus = unavailable("shizuku_stream_start_failed", error.message.orEmpty(), mode.streamCommand)
            lastStatus
        }
    }

    private fun streamCommandFor(
        mode: ShellMode,
        device: ManualRawTouchParser.TouchDevice
    ): List<String> {
        return when (mode.accessMethod) {
            ACCESS_PROCESS -> mode.streamCommand + device.path
            ACCESS_SU -> {
                val shellCommand = mode.streamCommand.getOrNull(2).orEmpty()
                    .ifBlank { SHIZUKU_GETEVENT_STREAM_COMMAND }
                listOf("su", "-c", "$shellCommand ${device.path}")
            }
            ACCESS_SHIZUKU -> listOf("shizuku", "shell.session_exec", shizukuStreamCommandFor(device))
            else -> mode.streamCommand
        }
    }

    private fun shizukuStreamCommandFor(device: ManualRawTouchParser.TouchDevice): String =
        "$SHIZUKU_GETEVENT_STREAM_COMMAND ${device.path}"

    private fun pollShizukuTranscript(
        manager: ShizukuCapabilityManager,
        sessionId: String,
        parser: ManualRawTouchParser.StreamParser
    ) {
        var lastTranscript = ""
        while (running.get()) {
            val result = runCatching {
                runBlocking { manager.readPrivilegedSession(sessionId, maxChars = SHIZUKU_TRANSCRIPT_MAX_CHARS) }
            }.getOrElse { error ->
                if (running.get()) {
                    OmniLog.w(TAG, "Shizuku raw touch transcript read failed: ${error.message}")
                }
                Thread.sleep(SHIZUKU_POLL_INTERVAL_MS)
                null
            }
            if (result == null) continue
            val transcript = result.transcript.ifBlank { result.output }
            if (transcript.isNotBlank()) {
                val delta = if (transcript.startsWith(lastTranscript)) {
                    transcript.substring(lastTranscript.length)
                } else {
                    transcript
                }
                lastTranscript = transcript
                delta.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotBlank() && !it.startsWith("[stderr]") }
                    .forEach { line ->
                        val gesture = parser.acceptLine(line) ?: return@forEach
                        if (!paused.get()) {
                            onGestureFinished(gesture)
                        }
                    }
            }
            Thread.sleep(SHIZUKU_POLL_INTERVAL_MS)
        }
    }

    private fun closeProcess() {
        runCatching { process?.destroy() }
        runCatching {
            if (process?.waitFor(PROCESS_DESTROY_TIMEOUT_MS, TimeUnit.MILLISECONDS) == false) {
                process?.destroyForcibly()
            }
        }
        process = null
        val sessionId = shizukuSessionId
        shizukuSessionId = null
        if (!sessionId.isNullOrBlank()) {
            val appContext = context?.applicationContext ?: context
            runCatching {
                if (appContext != null) {
                    runBlocking { ShizukuCapabilityManager.get(appContext).stopPrivilegedSession(sessionId) }
                }
            }.onFailure { error ->
                OmniLog.w(TAG, "Failed to stop Shizuku raw touch session: ${error.message}")
            }
        }
    }

    private fun unavailable(
        errorCode: String,
        errorMessage: String,
        command: List<String>? = null
    ): ManualRawTouchStatus = ManualRawTouchStatus(
        available = false,
        backend = BACKEND,
        errorCode = errorCode,
        errorMessage = errorMessage,
        command = command?.joinToString(" ")
    )

    private data class ProbeResult(
        val status: ManualRawTouchStatus,
        val mode: ShellMode?,
        val device: ManualRawTouchParser.TouchDevice?
    )

    private data class ShellMode(
        val name: String,
        val accessMethod: String,
        val probeCommand: List<String>,
        val streamCommand: List<String>
    )

    private data class CommandResult(
        val exitCode: Int?,
        val output: String,
        val timedOut: Boolean
    )

    private fun runCommand(command: List<String>, timeoutMs: Long): CommandResult {
        return runCatching {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = StringBuilder()
            val reader = Thread {
                runCatching {
                    BufferedReader(InputStreamReader(process.inputStream)).useLines { lines ->
                        lines.forEach { line ->
                            if (output.length < MAX_PROBE_OUTPUT_CHARS) {
                                output.append(line).append('\n')
                            }
                        }
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                reader.join(READER_JOIN_TIMEOUT_MS)
                return@runCatching CommandResult(null, output.toString(), timedOut = true)
            }
            reader.join(READER_JOIN_TIMEOUT_MS)
            CommandResult(process.exitValue(), output.toString(), timedOut = false)
        }.getOrElse { error ->
            CommandResult(null, error.message.orEmpty(), timedOut = false)
        }
    }

    private companion object {
        private const val TAG = "ManualRawTouchRecorder"
        private const val BACKEND = "device_getevent"
        private const val ACCESS_PROCESS = "process"
        private const val ACCESS_SU = "su"
        private const val ACCESS_SHIZUKU = "shizuku_session"
        private const val PROBE_TIMEOUT_MS = 2_000L
        private const val SHIZUKU_PROBE_TIMEOUT_SECONDS = 5
        private const val SHIZUKU_STREAM_TIMEOUT_SECONDS = 600
        private const val SHIZUKU_TRANSCRIPT_MAX_CHARS = 64_000
        private const val SHIZUKU_POLL_INTERVAL_MS = 80L
        private const val SHIZUKU_GETEVENT_PROBE_COMMAND = "/system/bin/getevent -pl"
        private const val SHIZUKU_GETEVENT_STREAM_COMMAND = "/system/bin/getevent -lt"
        private const val STOP_JOIN_TIMEOUT_MS = 500L
        private const val PROCESS_DESTROY_TIMEOUT_MS = 250L
        private const val READER_JOIN_TIMEOUT_MS = 250L
        private const val MAX_ERROR_CHARS = 240
        private const val MAX_PROBE_OUTPUT_CHARS = 64_000
        private val SHELL_MODES = listOf(
            ShellMode(
                name = "plain_system_bin",
                accessMethod = ACCESS_PROCESS,
                probeCommand = listOf("/system/bin/getevent", "-pl"),
                streamCommand = listOf("/system/bin/getevent", "-lt")
            ),
            ShellMode(
                name = "plain_path",
                accessMethod = ACCESS_PROCESS,
                probeCommand = listOf("getevent", "-pl"),
                streamCommand = listOf("getevent", "-lt")
            ),
            ShellMode(
                name = "su_system_bin",
                accessMethod = ACCESS_SU,
                probeCommand = listOf("su", "-c", "/system/bin/getevent -pl"),
                streamCommand = listOf("su", "-c", "/system/bin/getevent -lt")
            ),
            ShellMode(
                name = "su_path",
                accessMethod = ACCESS_SU,
                probeCommand = listOf("su", "-c", "getevent -pl"),
                streamCommand = listOf("su", "-c", "getevent -lt")
            )
        )
    }
}

internal object ManualRawTouchParser {
    data class Axis(
        val min: Int,
        val max: Int
    )

    data class TouchDevice(
        val path: String,
        val name: String,
        val xAxis: Axis,
        val yAxis: Axis,
        val directInput: Boolean = false,
        val hasBtnTouch: Boolean = false,
        val hasTrackingId: Boolean = false,
        val hasSlot: Boolean = false
    )

    class StreamParser(
        private val device: TouchDevice,
        private val displayWidth: Int,
        private val displayHeight: Int,
        private val backend: String,
        private val onGestureStarted: (ManualRawTouchStart) -> Unit = {}
    ) {
        private var gestureSeq = 0L
        private var activeGestureId = 0L
        private var trackingActive = false
        private var finishOnNextSyn = false
        private var currentRawX: Int? = null
        private var currentRawY: Int? = null
        private var points = mutableListOf<RawPoint>()
        private var startNotified = false

        fun acceptLine(line: String): ManualRawTouchGesture? {
            val event = parseEventLine(line) ?: return null
            if (event.devicePath != device.path) return null
            when {
                event.isTrackingId -> {
                    if (event.value < 0) {
                        finishOnNextSyn = true
                    } else {
                        beginGestureIfNeeded(event)
                    }
                }
                event.isBtnTouch -> {
                    if (event.value > 0) {
                        beginGestureIfNeeded(event)
                    } else {
                        finishOnNextSyn = true
                    }
                }
                event.isPositionX -> {
                    currentRawX = event.value
                    if (!trackingActive) beginGestureIfNeeded(event)
                }
                event.isPositionY -> {
                    currentRawY = event.value
                    if (!trackingActive) beginGestureIfNeeded(event)
                }
                event.isSynReport -> {
                    appendPointIfReady(event.eventTimeMs)
                    if (finishOnNextSyn) {
                        return finishGesture(event.eventTimeMs)
                    }
                }
            }
            return null
        }

        private fun beginGestureIfNeeded(event: ParsedEvent) {
            if (trackingActive) return
            trackingActive = true
            finishOnNextSyn = false
            startNotified = false
            points = mutableListOf()
            activeGestureId = ++gestureSeq
        }

        private fun appendPointIfReady(eventTimeMs: Long) {
            if (!trackingActive) return
            val rawX = currentRawX ?: return
            val rawY = currentRawY ?: return
            val point = RawPoint(
                rawX = rawX,
                rawY = rawY,
                x = mapAxis(rawX, device.xAxis, displayWidth),
                y = mapAxis(rawY, device.yAxis, displayHeight),
                eventTimeMs = eventTimeMs
            )
            val last = points.lastOrNull()
            if (last == null || last.rawX != point.rawX || last.rawY != point.rawY) {
                points += point
                if (!startNotified) {
                    startNotified = true
                    onGestureStarted(
                        ManualRawTouchStart(
                            gestureId = activeGestureId,
                            startedAtMs = wallTimeForEvent(point.eventTimeMs),
                            x = point.x,
                            y = point.y,
                            rawX = point.rawX,
                            rawY = point.rawY
                        )
                    )
                }
            }
        }

        private fun finishGesture(eventTimeMs: Long): ManualRawTouchGesture? {
            appendPointIfReady(eventTimeMs)
            val finalPoints = points.toList()
            trackingActive = false
            finishOnNextSyn = false
            startNotified = false
            points = mutableListOf()
            if (finalPoints.isEmpty()) return null
            val start = finalPoints.first()
            val end = finalPoints.last()
            val durationMs = (end.eventTimeMs - start.eventTimeMs).coerceAtLeast(0L)
            val distance = hypot((end.x - start.x).toDouble(), (end.y - start.y).toDouble()).toFloat()
            val actionName = when {
                distance <= TAP_SLOP_PX && durationMs <= MAX_TAP_DURATION_MS -> "click"
                distance <= TAP_SLOP_PX && durationMs <= MAX_LONG_PRESS_DURATION_MS -> "long_press"
                distance >= MIN_SWIPE_DISTANCE_PX -> "swipe"
                else -> return null
            }
            return ManualRawTouchGesture(
                gestureId = activeGestureId,
                actionName = actionName,
                startedAtMs = wallTimeForEvent(start.eventTimeMs),
                finishedAtMs = wallTimeForEvent(end.eventTimeMs),
                startX = start.x,
                startY = start.y,
                endX = end.x,
                endY = end.y,
                rawStartX = start.rawX,
                rawStartY = start.rawY,
                rawEndX = end.rawX,
                rawEndY = end.rawY,
                durationMs = durationMs,
                distancePx = distance,
                pointCount = finalPoints.size,
                backend = backend,
                devicePath = device.path,
                deviceName = device.name
            )
        }

        private fun mapAxis(value: Int, axis: Axis, screenSize: Int): Float {
            val span = (axis.max - axis.min).coerceAtLeast(1)
            val normalized = ((value - axis.min).toFloat() / span.toFloat()).coerceIn(0f, 1f)
            return normalized * (screenSize - 1).coerceAtLeast(1)
        }
    }

    fun parseTouchDevices(text: String): List<TouchDevice> {
        val devices = mutableListOf<DeviceBuilder>()
        var current: DeviceBuilder? = null
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            val addMatch = ADD_DEVICE_REGEX.find(line)
            if (addMatch != null) {
                current?.let { devices += it }
                current = DeviceBuilder(path = addMatch.groupValues[1])
                return@forEach
            }
            val builder = current ?: return@forEach
            if (line.contains("INPUT_PROP_DIRECT", ignoreCase = true)) {
                builder.directInput = true
                return@forEach
            }
            if (line.contains("BTN_TOUCH", ignoreCase = true) || line.hasCode("BTN_TOUCH", "014a")) {
                builder.hasBtnTouch = true
            }
            NAME_REGEX.find(line)?.let { match ->
                builder.name = match.groupValues[1]
                return@forEach
            }
            val minMax = MIN_MAX_REGEX.find(line) ?: return@forEach
            val axis = Axis(
                min = minMax.groupValues[1].toIntOrNull() ?: return@forEach,
                max = minMax.groupValues[2].toIntOrNull() ?: return@forEach
            )
            when {
                line.hasCode("ABS_MT_POSITION_X", "0035") -> builder.xAxis = axis
                line.hasCode("ABS_MT_POSITION_Y", "0036") -> builder.yAxis = axis
                line.hasCode("ABS_MT_TRACKING_ID", "0039") -> builder.hasTrackingId = true
                line.hasCode("ABS_MT_SLOT", "002f") -> builder.hasSlot = true
            }
        }
        current?.let { devices += it }
        return devices.mapNotNull { builder ->
            val xAxis = builder.xAxis ?: return@mapNotNull null
            val yAxis = builder.yAxis ?: return@mapNotNull null
            TouchDevice(
                path = builder.path,
                name = builder.name.ifBlank { builder.path.substringAfterLast('/') },
                xAxis = xAxis,
                yAxis = yAxis,
                directInput = builder.directInput,
                hasBtnTouch = builder.hasBtnTouch,
                hasTrackingId = builder.hasTrackingId,
                hasSlot = builder.hasSlot
            )
        }.sortedWith(
            compareByDescending<TouchDevice> { it.directInput }
                .thenByDescending { it.hasBtnTouch }
                .thenByDescending { it.hasTrackingId }
                .thenByDescending { it.hasSlot }
                .thenByDescending { device ->
                val name = device.name.lowercase(Locale.US)
                TOUCH_NAME_HINTS.count { hint -> name.contains(hint) }
            }.thenBy { it.path }
        )
    }

    fun parseEventLine(line: String): ParsedEvent? {
        val match = EVENT_LINE_REGEX.find(line.trim()) ?: return null
        val value = parseEventValue(match.groupValues[5]) ?: return null
        val timeSeconds = match.groupValues[1].toDoubleOrNull() ?: return null
        return ParsedEvent(
            eventTimeMs = (timeSeconds * 1000.0).toLong(),
            devicePath = match.groupValues[2],
            eventType = match.groupValues[3],
            code = match.groupValues[4],
            value = value
        )
    }

    data class ParsedEvent(
        val eventTimeMs: Long,
        val devicePath: String,
        val eventType: String,
        val code: String,
        val value: Int
    ) {
        val isSynReport: Boolean get() = eventType == "EV_SYN" || code == "SYN_REPORT" || code == "0000"
        val isPositionX: Boolean get() = code == "ABS_MT_POSITION_X" || code == "0035"
        val isPositionY: Boolean get() = code == "ABS_MT_POSITION_Y" || code == "0036"
        val isTrackingId: Boolean get() = code == "ABS_MT_TRACKING_ID" || code == "0039"
        val isBtnTouch: Boolean get() = code == "BTN_TOUCH" || code == "014a"
    }

    private data class DeviceBuilder(
        val path: String,
        var name: String = "",
        var xAxis: Axis? = null,
        var yAxis: Axis? = null,
        var directInput: Boolean = false,
        var hasBtnTouch: Boolean = false,
        var hasTrackingId: Boolean = false,
        var hasSlot: Boolean = false
    )

    private data class RawPoint(
        val rawX: Int,
        val rawY: Int,
        val x: Float,
        val y: Float,
        val eventTimeMs: Long
    )

    private fun wallTimeForEvent(eventTimeMs: Long): Long {
        val nowWallMs = System.currentTimeMillis()
        val ageMs = (monotonicNowMs() - eventTimeMs).coerceAtLeast(0L)
        return (nowWallMs - ageMs).coerceAtMost(nowWallMs)
    }

    private fun monotonicNowMs(): Long {
        return runCatching { android.os.SystemClock.uptimeMillis() }
            .getOrElse { System.currentTimeMillis() }
    }

    private fun parseEventValue(value: String): Int? {
        val normalized = value.trim().lowercase(Locale.US)
        if (normalized.startsWith("-")) {
            normalized.toIntOrNull()?.let { return it }
        }
        val unsigned = normalized.toLongOrNull(16) ?: normalized.toLongOrNull() ?: return null
        val signed = if (normalized.length >= 8 && unsigned >= 0x80000000L) {
            unsigned - 0x1_0000_0000L
        } else {
            unsigned
        }
        return signed.toInt()
    }

    private fun String.hasCode(named: String, hex: String): Boolean =
        contains(named) || Regex("""(^|\s)$hex(\s|:)""", RegexOption.IGNORE_CASE).containsMatchIn(this)

    private const val TAP_SLOP_PX = 28f
    private const val MAX_TAP_DURATION_MS = 800L
    private const val MAX_LONG_PRESS_DURATION_MS = 2_500L
    private const val MIN_SWIPE_DISTANCE_PX = 80f
    private val TOUCH_NAME_HINTS = listOf("touch", "screen", "ts", "input")
    private val ADD_DEVICE_REGEX = Regex("""add device \d+:\s+(\S+)""")
    private val NAME_REGEX = Regex("name:\\s+\"([^\"]+)\"")
    private val MIN_MAX_REGEX = Regex("""min\s+(-?\d+),\s+max\s+(-?\d+)""")
    private val EVENT_LINE_REGEX = Regex("""^\[\s*([0-9]+(?:\.[0-9]+)?)\]\s+(\S+):\s+(\S+)\s+(\S+)\s+(\S+)""")
}
