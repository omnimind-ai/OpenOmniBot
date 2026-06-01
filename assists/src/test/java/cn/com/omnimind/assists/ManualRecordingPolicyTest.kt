package cn.com.omnimind.assists

import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualRecordingPolicyTest {
    @Test
    fun `a11 only replay actions stay disabled`() {
        val source = readSource(
            "assists/src/main/java/cn/com/omnimind/assists/task/vlmserver/ManualVlmTraceRecorder.kt"
        )

        assertFalse(source.contains("private fun recordClick("))
        assertFalse(source.contains("private fun recordFocusedTextTarget("))
        assertFalse(source.contains("private fun recordScrolled("))
        assertFalse(source.contains("ManualClickGrounding"))
        assertFalse(source.contains("ManualScrollEventPolicy"))
        assertFalse(source.contains("\"recording_backend\" to \"accessibility_event\""))
        assertTrue(source.contains("\"records_replayable_actions\" to false"))
        assertTrue(source.contains("\"a11_replay_actions_enabled\" to false"))
        assertTrue(source.contains("\"a11_post_input_click_enabled\" to true"))
        assertTrue(source.contains("private fun hasPostInputActionWindowLocked(nowMs: Long): Boolean"))
        assertTrue(
            Regex(
                "AccessibilityEvent\\.TYPE_VIEW_CLICKED,\\s*" +
                    "AccessibilityEvent\\.TYPE_VIEW_LONG_CLICKED -> \\{\\s*" +
                    ".*if \\(hasPostInputActionWindowLocked\\(nowMs\\)\\) \\{\\s*" +
                    "recordPostInputActionLocked\\(event, packageName, nowMs\\)\\s*" +
                    "\\} else \\{\\s*" +
                    "suppressA11OnlyActionEvent\\(event\\)",
                RegexOption.DOT_MATCHES_ALL
            ).containsMatchIn(source)
        )
        assertTrue(source.contains("AccessibilityEvent.TYPE_VIEW_FOCUSED -> {"))
        assertTrue(source.contains("handleTextInputFocus(event, packageName)"))
        assertTrue(source.contains("AccessibilityEvent.TYPE_VIEW_SCROLLED -> suppressA11OnlyActionEvent(event)"))
    }

    @Test
    fun `a11 text input uses explicit text anchors only`() {
        val source = readSource(
            "assists/src/main/java/cn/com/omnimind/assists/task/vlmserver/ManualVlmTraceRecorder.kt"
        )

        assertTrue(source.contains("private data class TextInputAnchor("))
        assertTrue(source.contains("rememberTextInputAnchorFromRealTouch("))
        assertTrue(source.contains("rememberTextInputAnchorFromFocus("))
        assertTrue(source.contains("\"a11_text_input_anchor_policy\" to \"real_touch_or_text_focus\""))
        assertTrue(
            Regex(
                "var anchor = textInputAnchor\\s*" +
                    "if \\(anchor == null && source\\?\\.isTextEntryLike\\(\\) == true\\) \\{\\s*" +
                    "rememberTextInputAnchorFromFocus\\(source, packageName\\)\\s*" +
                    "anchor = textInputAnchor\\s*" +
                    "\\}\\s*" +
                    "if \\(anchor == null\\) \\{\\s*" +
                    "suppressA11OnlyActionEvent\\(event\\)",
                RegexOption.DOT_MATCHES_ALL
            ).containsMatchIn(source)
        )
        assertFalse(source.contains("recordFocusedTextTarget("))
    }

    @Test
    fun `manual recording does not collect after evidence`() {
        val source = readSource(
            "assists/src/main/java/cn/com/omnimind/assists/task/vlmserver/ManualVlmTraceRecorder.kt"
        )

        assertFalse(source.contains("recordWindowTransitionObservation"))
        assertFalse(source.contains("afterXml = afterXml"))
        assertFalse(source.contains("afterScreenshot = afterScreenshot"))
        assertFalse(source.contains("RAW_TOUCH_SETTLE_MS"))
        assertFalse(source.contains("\"after_fingerprint\""))
        assertFalse(source.contains("\"after_page\""))
        assertTrue(source.contains("afterXml = null"))
        assertTrue(source.contains("afterScreenshot = null"))
    }

    @Test
    fun `overlay replay result distinguishes execution from recording`() {
        assertFalse(ManualOverlayGestureReplayResult(executed = false).recorded)
        assertTrue(ManualOverlayGestureReplayResult(executed = true).recorded)
        assertFalse(ManualOverlayGestureReplayResult(executed = true, recorded = false).recorded)
    }

    @Test
    fun `touch overlay drains bounded while recording is unfinished`() {
        val source = readSource(
            "uikit/src/main/java/cn/com/omnimind/uikit/loader/ManualTouchRecordLoader.kt"
        )
        val recorderSource = readSource(
            "assists/src/main/java/cn/com/omnimind/assists/task/vlmserver/ManualVlmTraceRecorder.kt"
        )

        assertFalse(source.contains("PROCESSING_RESET_TIMEOUT_MS"))
        assertFalse(source.contains("withTimeoutOrNull"))
        assertTrue(source.contains("recorded = replayResult.recorded"))
        assertTrue(source.contains("executed && !recorded"))
        assertTrue(source.contains("lockTouchLocked()"))
        assertTrue(recorderSource.contains("while (overlayGestureActiveCount > 0)"))
        assertTrue(recorderSource.contains("OVERLAY_RECORD_DRAIN_TIMEOUT_MS"))
        assertTrue(recorderSource.contains("manual overlay drain timeout"))
        assertTrue(recorderSource.contains("if (!isStarted || isPaused)"))
        assertTrue(recorderSource.contains("recordingLock.wait(min(OVERLAY_RECORD_DRAIN_POLL_MS, remainingMs))"))
        assertTrue(recorderSource.contains("recordingLock.notifyAll()"))
    }

    @Test
    fun `ime opening keeps keyboard pass through while app area stays recorded`() {
        val source = readSource(
            "uikit/src/main/java/cn/com/omnimind/uikit/loader/ManualTouchRecordLoader.kt"
        )
        val recorderSource = readSource(
            "assists/src/main/java/cn/com/omnimind/assists/task/vlmserver/ManualVlmTraceRecorder.kt"
        )

        assertTrue(recorderSource.contains("onGestureReplayStarted(mayOpenIme, replayPassthroughMs)"))
        assertTrue(recorderSource.contains("onGestureReplayFinished(mayOpenIme)"))
        assertTrue(source.contains("val replayResult = HumanTrajectoryLearningSession.recordOverlayGesture("))
        assertTrue(source.contains("onGestureReplayStarted = { mayOpenIme, passthroughMs ->"))
        assertTrue(source.contains("scheduleReplayRelockLocked(mayOpenIme, passthroughMs)"))
        assertTrue(source.contains("onGestureReplayFinished = { mayOpenIme ->"))
        assertFalse(source.contains("!expectsIme"))
        assertTrue(source.contains("scheduleImeVisibilityProbeLocked()"))
        assertTrue(source.contains("overlayHeightForParamsLocked(touchable, displaySize.y)"))
        assertTrue(source.contains("private fun imeTopLocked(): Int?"))
        assertTrue(source.contains("window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD"))
        assertTrue(source.contains("private fun trustedImeTopLocked(top: Int, displayHeight: Int): Int?"))
        assertTrue(source.contains("fallbackImeTop(displayHeight)"))
        assertTrue(source.contains("if (visible) {\n                            enterImeBypassLocked()"))
        assertTrue(source.contains("scheduleImeRelockLocked()"))
        assertFalse(source.contains("awaitImeVisible"))
        assertTrue(recorderSource.contains("if (xml.isNullOrBlank()) return false"))
        assertTrue(recorderSource.contains("if (candidates.isEmpty()) return false"))
    }

    @Test
    fun `missing source xml records coordinate only without stale fallback`() {
        val source = readSource(
            "assists/src/main/java/cn/com/omnimind/assists/task/vlmserver/ManualVlmTraceRecorder.kt"
        )

        assertTrue(source.contains("val beforeXml = withTimeoutOrNull(BEFORE_XML_CAPTURE_TIMEOUT_MS)"))
        assertTrue(source.contains("}?.takeIf { it.isNotBlank() }"))
        assertFalse(source.contains("} ?: synchronized(recordingLock) { lastXmlSnapshot }"))
        assertFalse(source.contains("?: AccessibilityController.getCaptureScreenShotXml(true)"))
        assertTrue(source.contains("\"before_xml_present\" to !beforeXml.isNullOrBlank()"))
        assertTrue(source.contains("coordinateTextAnchorTarget("))
        assertTrue(source.contains("coordinate_text_anchor_unresolved"))
        assertTrue(source.contains("sourceTarget == null"))
        val sessionSource = readSource(
            "assists/src/main/java/cn/com/omnimind/assists/HumanTrajectoryLearningSession.kt"
        )
        assertTrue(sessionSource.contains("SOURCE_CONTEXT_MODE_COORDINATE_ONLY_NO_XML"))
        assertTrue(sessionSource.contains("\"source_context_mode\" to SOURCE_CONTEXT_MODE_COORDINATE_ONLY_NO_XML.takeIf"))
        assertTrue(sessionSource.contains("\"missing_source_xml\" to true.takeIf"))
    }

    @Test
    fun `manual overlays do not use accessibility overlay or per-step z order rebuild`() {
        val source = readSource(
            "uikit/src/main/java/cn/com/omnimind/uikit/loader/ManualTouchRecordLoader.kt"
        )
        val controlSource = readSource(
            "uikit/src/main/java/cn/com/omnimind/uikit/loader/ManualRecordingControlOverlay.kt"
        )

        assertFalse(source.contains("WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY"))
        assertFalse(controlSource.contains("WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY"))
        assertFalse(source.contains("ensureOnTop"))
        assertTrue(source.contains("WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY"))
        assertTrue(controlSource.contains("WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY"))
        assertFalse(controlSource.contains("fun ensureOnTop"))
        assertTrue(controlSource.contains("showTransientStatus(\"开启悬浮窗权限\""))
        assertTrue(controlSource.contains("keepControlsAboveTouchRecorderOnce()"))
        val dragSuppression = Regex(
            "private fun beginDragRecordingSuppression\\(\\) \\{(.*?)\\n    \\}",
            RegexOption.DOT_MATCHES_ALL
        ).find(controlSource)?.groupValues?.get(1).orEmpty()
        val dragResume = Regex(
            "private fun endDragRecordingSuppression\\(\\) \\{(.*?)\\n    \\}",
            RegexOption.DOT_MATCHES_ALL
        ).find(controlSource)?.groupValues?.get(1).orEmpty()
        assertFalse(dragSuppression.contains("pauseActive"))
        assertFalse(dragResume.contains("resumeActive"))
    }

    private fun readSource(relativePath: String): String {
        val candidates = listOf(
            Paths.get(relativePath),
            Paths.get("..").resolve(relativePath)
        )
        val path = candidates.firstOrNull { Files.exists(it) }
            ?: error("Missing source file: $relativePath from ${Paths.get("").toAbsolutePath()}")
        return String(Files.readAllBytes(path))
    }
}
