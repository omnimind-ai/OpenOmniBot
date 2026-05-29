package cn.com.omnimind.assists.task.vlmserver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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

        assertTrue(rendered.contains("OOB Accessibility tree / indexed page evidence"))
        assertTrue(rendered.contains("label=\"Network & internet\""))
        assertTrue(rendered.contains("center=(430,473)"))
        assertTrue(rendered.contains("flags=click"))
        assertFalse(rendered.contains("bounds="))
        assertTrue(rendered.contains("Scrollable regions:"))
        assertTrue(rendered.contains("vertical_down="))
    }

    @Test
    fun `indexed context caps visible candidates for compact per turn prompt`() {
        val rows = buildString {
            repeat(30) { index ->
                append("""<node text="Row $index" bounds="[0,${index * 40}][720,${index * 40 + 36}]" clickable="true" />""")
            }
        }
        val rendered = VLMIndexedPageContext.render(
            currentXml = "<hierarchy><node bounds=\"[0,0][720,1280]\">$rows</node></hierarchy>",
            displayWidth = 720,
            displayHeight = 1280
        )

        assertTrue(rendered.contains("#0 center="))
        assertTrue(rendered.contains("#17 center="))
        assertFalse(rendered.contains("#18 center="))
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
        assertTrue(enriched.currentPageSummary.contains("OOB Accessibility tree / indexed page evidence"))
        assertTrue(enriched.currentPageSummary.contains("Connected devices"))
    }

    @Test
    fun `renders form anchors for editable fields and selection rows`() {
        val rendered = VLMIndexedPageContext.render(
            currentXml = CONTACT_FORM_XML,
            displayWidth = 720,
            displayHeight = 1280
        )

        assertTrue(rendered.contains("Form anchors:"))
        assertTrue(rendered.contains("role=focused_editable label=\"First name\""))
        assertTrue(rendered.contains("role=editable label=\"Last name\""))
        assertTrue(rendered.contains("role=selection_row label=\"Mobile Phone\""))
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

    @Test
    fun `resolves indexed element target to XML center`() {
        val target = VLMIndexedPageContext.elementTarget(
            currentXml = SETTINGS_XML,
            displayWidth = 720,
            displayHeight = 1280,
            index = 1
        )

        requireNotNull(target)
        assertEquals(1, target.index)
        assertEquals("Network & internet", target.label)
        assertEquals(309.5f, target.centerX, 0.01f)
        assertEquals(606f, target.centerY, 0.01f)
    }

    @Test
    fun `resolves indexed scroll target inside scrollable bounds`() {
        val target = VLMIndexedPageContext.scrollTarget(
            currentXml = SETTINGS_XML,
            displayWidth = 720,
            displayHeight = 1280,
            index = 0,
            direction = "down"
        )

        requireNotNull(target)
        assertEquals(0, target.index)
        assertEquals(360f, target.x1, 0.01f)
        assertEquals(360f, target.x2, 0.01f)
        assertTrue(target.y1 > target.y2)
        assertTrue(target.y1 in 1000f..1100f)
        assertTrue(target.y2 in 300f..330f)
    }

    @Test
    fun `resolves unique target description to indexed element`() {
        val target = VLMIndexedPageContext.uniqueElementTargetByDescription(
            currentXml = SETTINGS_XML,
            displayWidth = 720,
            displayHeight = 1280,
            targetDescription = "Connected devices"
        )

        requireNotNull(target)
        assertEquals("Connected devices", target.label)
        assertEquals(313f, target.centerX, 0.01f)
        assertEquals(782f, target.centerY, 0.01f)
    }

    @Test
    fun `resolves element target by stable node id`() {
        val target = VLMIndexedPageContext.elementTargetByNodeId(
            currentXml = CONTACT_FORM_XML,
            displayWidth = 720,
            displayHeight = 1280,
            nodeId = "36"
        )

        requireNotNull(target)
        assertEquals("Last name", target.label)
        assertEquals(356f, target.centerX, 0.01f)
        assertEquals(799.5f, target.centerY, 0.01f)
        assertEquals("36", target.nodeId)
    }

    @Test
    fun `prefers editable target for input text description`() {
        val target = VLMIndexedPageContext.uniqueElementTargetByDescription(
            currentXml = CONTACT_FORM_XML,
            displayWidth = 720,
            displayHeight = 1280,
            targetDescription = "Phone",
            preferEditable = true
        )

        requireNotNull(target)
        assertEquals("Phone", target.label)
        assertEquals("56", target.nodeId)
    }

    @Test
    fun `does not resolve ambiguous target description`() {
        val ambiguousXml =
            """
            <hierarchy>
              <node bounds="[0,0][720,1280]">
                <node text="Phone" class="android.widget.TextView" clickable="true" bounds="[80,100][300,180]" />
                <node text="Phone" class="android.widget.TextView" clickable="true" bounds="[80,220][300,300]" />
              </node>
            </hierarchy>
            """

        val target = VLMIndexedPageContext.uniqueElementTargetByDescription(
            currentXml = ambiguousXml,
            displayWidth = 720,
            displayHeight = 1280,
            targetDescription = "Phone"
        )

        assertNull(target)
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

        private const val CONTACT_FORM_XML =
            """
            <hierarchy>
              <node id="0" class="android.widget.FrameLayout" enabled="true" bounds="[0,0][720,1280]">
                <node id="15" class="android.widget.ScrollView" enabled="true" focusable="true" scrollable="true" bounds="[0,176][720,1232]">
                  <node id="33" text="First name" class="android.widget.EditText" enabled="true" clickable="true" long-clickable="true" focusable="true" focused="true" editable="true" bounds="[104,603][608,716]" />
                  <node id="36" text="Last name" class="android.widget.EditText" enabled="true" clickable="true" long-clickable="true" focusable="true" editable="true" bounds="[104,743][608,856]" />
                  <node id="56" text="Phone" class="android.widget.EditText" enabled="true" clickable="true" long-clickable="true" focusable="true" editable="true" bounds="[104,1055][608,1168]" />
                  <node id="59" text="Mobile" content-desc="Mobile Phone" class="android.widget.Spinner" enabled="true" clickable="true" long-clickable="true" focusable="true" editable="true" bounds="[104,1195][446,1232]" />
                </node>
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
