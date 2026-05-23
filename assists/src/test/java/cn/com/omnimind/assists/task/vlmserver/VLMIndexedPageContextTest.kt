package cn.com.omnimind.assists.task.vlmserver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VLMIndexedPageContextTest {
    @Test
    fun `renders indexed visible elements with normalized centers`() {
        val rendered = VLMIndexedPageContext.render(
            currentXml = SETTINGS_XML,
            displayWidth = 720,
            displayHeight = 1280
        )

        assertTrue(rendered.contains("OOB indexed page evidence"))
        assertTrue(rendered.contains("label=\"Network & internet\""))
        assertTrue(rendered.contains("center=(430,473)"))
        assertTrue(rendered.contains("flags=click"))
        assertTrue(rendered.contains("Scrollable regions:"))
        assertTrue(rendered.contains("vertical_down="))
    }

    @Test
    fun `filters OOB overlay controls from indexed context`() {
        val rendered = VLMIndexedPageContext.render(
            currentXml = OVERLAY_XML,
            displayWidth = 1080,
            displayHeight = 1920
        )

        assertTrue(rendered.contains("label=\"发送\""))
        assertFalse(rendered.contains("继续执行"))
        assertFalse(rendered.contains("小万"))
    }

    @Test
    fun `enrich appends indexed context without dropping existing page summary`() {
        val context = UIContext(
            overallTask = "Open Connected devices settings",
            currentPageSummary = "UDEG page-match context: node_id=abc"
        )

        val enriched = VLMIndexedPageContext.enrich(
            context = context,
            currentXml = SETTINGS_XML,
            displayWidth = 720,
            displayHeight = 1280
        )

        assertTrue(enriched.currentPageSummary.contains("UDEG page-match context"))
        assertTrue(enriched.currentPageSummary.contains("OOB indexed page evidence"))
        assertTrue(enriched.currentPageSummary.contains("Connected devices"))
    }

    @Test
    fun `marked screenshot renderer is optional when screenshot is absent`() {
        val marked = VLMIndexedPageContext.renderMarkedScreenshot(
            screenshotBase64 = null,
            currentXml = SETTINGS_XML,
            displayWidth = 720,
            displayHeight = 1280
        )

        assertNull(marked)
    }

    companion object {
        private const val SETTINGS_XML =
            """
            <hierarchy>
              <node bounds="[0,0][720,1280]" scrollable="true">
                <node text="Settings" bounds="[48,256][312,353]" />
                <node text="Search settings" bounds="[152,426][421,480]" clickable="true" />
                <node text="Network &amp; internet" bounds="[144,579][475,633]" clickable="true" />
                <node text="Mobile, Wi-Fi, hotspot" bounds="[144,633][412,671]" />
                <node text="Connected devices" bounds="[144,755][482,809]" clickable="true" />
                <node text="Bluetooth, pairing" bounds="[144,809][361,847]" />
              </node>
            </hierarchy>
            """

        private const val OVERLAY_XML =
            """
            <hierarchy>
              <node bounds="[0,0][1080,1920]">
                <node text="发送" bounds="[880,1700][1040,1800]" clickable="true" />
                <node text="继续执行" bounds="[900,80][1060,160]" clickable="true" />
                <node text="小万" bounds="[920,920][1060,1060]" clickable="true" />
              </node>
            </hierarchy>
            """
    }
}
