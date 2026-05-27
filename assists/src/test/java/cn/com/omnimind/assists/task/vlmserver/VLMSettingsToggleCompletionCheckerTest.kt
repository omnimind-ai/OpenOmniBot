package cn.com.omnimind.assists.task.vlmserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VLMSettingsToggleCompletionCheckerTest {
    @Test
    fun `completes when wifi off and bluetooth on satisfy goal`() {
        val result = VLMSettingsToggleCompletionChecker.check(
            goal = "Turn off Wi-Fi, then enable Bluetooth",
            currentXml = null,
            stateSnapshot = VLMSettingsToggleCompletionChecker.StateSnapshot(
                wifiEnabled = false,
                bluetoothEnabled = true
            ),
            currentPackageName = "com.android.settings"
        )

        assertTrue(result.complete)
        assertEquals("settings_toggle_state_satisfied", result.reason)
        assertTrue(result.summary.contains("Wi-Fi is off"))
        assertTrue(result.summary.contains("Bluetooth is on"))
    }

    @Test
    fun `does not complete when one requested toggle state is missing`() {
        val result = VLMSettingsToggleCompletionChecker.check(
            goal = "Turn off WiFi and turn on bluetooth",
            currentXml = null,
            stateSnapshot = VLMSettingsToggleCompletionChecker.StateSnapshot(
                wifiEnabled = false,
                bluetoothEnabled = false
            ),
            currentPackageName = "com.android.settings"
        )

        assertFalse(result.complete)
        assertTrue(result.reason.startsWith("state_mismatch"))
    }

    @Test
    fun `does not infer bluetooth pairing as toggle completion`() {
        val result = VLMSettingsToggleCompletionChecker.check(
            goal = "Pair a new Bluetooth device",
            currentXml = null,
            stateSnapshot = VLMSettingsToggleCompletionChecker.StateSnapshot(
                bluetoothEnabled = true
            ),
            currentPackageName = "com.android.settings"
        )

        assertFalse(result.complete)
        assertEquals("no_settings_toggle_intent", result.reason)
    }

    @Test
    fun `does not treat opening bluetooth settings as enabling bluetooth`() {
        val result = VLMSettingsToggleCompletionChecker.check(
            goal = "Open Bluetooth settings",
            currentXml = null,
            stateSnapshot = VLMSettingsToggleCompletionChecker.StateSnapshot(
                bluetoothEnabled = true
            ),
            currentPackageName = "com.android.settings"
        )

        assertFalse(result.complete)
        assertEquals("no_settings_toggle_intent", result.reason)
    }

    @Test
    fun `treats Chinese open bluetooth as enable bluetooth toggle`() {
        val result = VLMSettingsToggleCompletionChecker.check(
            goal = "当前在设置首页。打开蓝牙。如果蓝牙已经开启，就直接完成。",
            currentXml = null,
            stateSnapshot = VLMSettingsToggleCompletionChecker.StateSnapshot(
                bluetoothEnabled = true
            ),
            currentPackageName = "com.android.settings"
        )

        assertTrue(result.complete)
        assertEquals("settings_toggle_state_satisfied", result.reason)
        assertTrue(result.summary.contains("Bluetooth is on"))
    }

    @Test
    fun `does not treat Chinese opening bluetooth settings page as enabling bluetooth`() {
        val result = VLMSettingsToggleCompletionChecker.check(
            goal = "打开蓝牙设置页面",
            currentXml = null,
            stateSnapshot = VLMSettingsToggleCompletionChecker.StateSnapshot(
                bluetoothEnabled = true
            ),
            currentPackageName = "com.android.settings"
        )

        assertFalse(result.complete)
        assertEquals("non_toggle_bluetooth_intent", result.reason)
    }

    @Test
    fun `uses xml switch state when device state reader is unavailable`() {
        val result = VLMSettingsToggleCompletionChecker.check(
            goal = "Disable wifi and enable bluetooth",
            currentXml = SWITCH_XML,
            stateSnapshot = VLMSettingsToggleCompletionChecker.StateSnapshot(),
            currentPackageName = "com.android.settings"
        )

        assertTrue(result.complete)
        assertEquals("xml_checked", result.stateSource)
    }

    @Test
    fun `does not infer future bluetooth prompt as current enabled state`() {
        val result = VLMSettingsToggleCompletionChecker.check(
            goal = "打开蓝牙",
            currentXml = FUTURE_BLUETOOTH_PROMPT_XML,
            stateSnapshot = VLMSettingsToggleCompletionChecker.StateSnapshot(),
            currentPackageName = "com.android.settings"
        )

        assertFalse(result.complete)
        assertEquals("unknown_state:bluetooth", result.reason)
        assertEquals("unknown", result.stateSource)
    }

    @Test
    fun `device mismatch wins over misleading xml state text`() {
        val result = VLMSettingsToggleCompletionChecker.check(
            goal = "打开蓝牙",
            currentXml = FUTURE_BLUETOOTH_PROMPT_XML,
            stateSnapshot = VLMSettingsToggleCompletionChecker.StateSnapshot(
                bluetoothEnabled = false
            ),
            currentPackageName = "com.android.settings"
        )

        assertFalse(result.complete)
        assertTrue(result.reason.startsWith("state_mismatch"))
        assertEquals("state_snapshot", result.stateSource)
    }

    companion object {
        private const val SWITCH_XML =
            """
            <hierarchy>
              <node text="Network &amp; internet" bounds="[0,0][720,120]">
                <node text="Wi-Fi" checked="false" checkable="true" bounds="[40,20][680,100]" />
              </node>
              <node text="Connected devices" bounds="[0,120][720,240]">
                <node text="Bluetooth" checked="true" checkable="true" bounds="[40,140][680,220]" />
              </node>
            </hierarchy>
            """

        private const val FUTURE_BLUETOOTH_PROMPT_XML =
            """
            <hierarchy>
              <node text="Connected devices" bounds="[0,0][720,120]">
                <node text="Pair new device" bounds="[40,140][680,220]" />
                <node text="Bluetooth will turn on to pair" bounds="[40,240][680,320]" />
              </node>
            </hierarchy>
            """
    }
}
