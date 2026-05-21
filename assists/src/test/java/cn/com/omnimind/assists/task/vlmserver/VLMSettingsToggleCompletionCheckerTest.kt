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
    fun `uses xml switch state when device state reader is unavailable`() {
        val result = VLMSettingsToggleCompletionChecker.check(
            goal = "Disable wifi and enable bluetooth",
            currentXml = SWITCH_XML,
            stateSnapshot = VLMSettingsToggleCompletionChecker.StateSnapshot(),
            currentPackageName = "com.android.settings"
        )

        assertTrue(result.complete)
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
    }
}
