package cn.com.omnimind.assists.task.vlmserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VLMPostActionObservationTest {
    @Test
    fun `summarizes after page state for action history`() {
        val summary = VLMPostActionObservation.summarize(
            UIStep(
                observation = "before",
                thought = "open settings",
                action = ClickAction(targetDescription = "Settings", x = 100f, y = 100f),
                observationXml = BEFORE_XML,
                afterObservationXml = AFTER_XML,
                packageName = "com.android.launcher",
                afterPackageName = "com.android.settings"
            )
        )

        requireNotNull(summary)
        assertTrue(summary.screenChanged)
        assertTrue(summary.packageChanged)
        assertEquals("com.android.settings", summary.afterPackageName)
        assertTrue(summary.afterVisibleTexts.contains("Network & internet"))
        assertEquals("Search settings", summary.afterFocusedEditable)
        assertTrue(summary.summaryText.contains("after action screen changed"))
    }

    companion object {
        private const val BEFORE_XML =
            """
            <hierarchy>
              <node bounds="[0,0][720,1280]">
                <node text="Settings" bounds="[20,20][120,80]" clickable="true" />
              </node>
            </hierarchy>
            """

        private const val AFTER_XML =
            """
            <hierarchy>
              <node bounds="[0,0][720,1280]">
                <node text="Settings" bounds="[48,256][312,353]" />
                <node text="Search settings" hintText="Search settings" bounds="[152,426][421,480]" editable="true" focused="true" />
                <node text="Network &amp; internet" bounds="[144,579][475,633]" clickable="true" />
              </node>
            </hierarchy>
            """
    }
}
