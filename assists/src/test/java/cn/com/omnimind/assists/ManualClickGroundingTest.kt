package cn.com.omnimind.assists

import cn.com.omnimind.assists.task.vlmserver.ManualClickGrounding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ManualClickGroundingTest {
    @Test
    fun `source-less click uses after page transition to disambiguate same row buttons`() {
        val result = ManualClickGrounding.inferClickTarget(
            beforeXml = """
                <hierarchy bounds="[0,0][1260,2800]">
                  <node package="com.android.contacts" class="android.widget.Button" content-desc="选择" clickable="true" enabled="true" visible-to-user="true" bounds="[728,216][890,377]" />
                  <node package="com.android.contacts" class="android.widget.Button" content-desc="新建联系人" clickable="true" enabled="true" visible-to-user="true" bounds="[890,216][1052,377]" />
                  <node package="com.android.contacts" class="android.widget.Button" content-desc="更多" clickable="true" enabled="true" visible-to-user="true" bounds="[1052,216][1214,377]" />
                </hierarchy>
            """.trimIndent(),
            afterXml = """
                <hierarchy bounds="[0,0][1260,2800]">
                  <node package="com.android.contacts" class="android.widget.EditText" text="姓名" editable="true" enabled="true" visible-to-user="true" bounds="[245,903][1023,1064]" />
                  <node package="com.android.contacts" class="android.widget.EditText" text="电话" editable="true" enabled="true" visible-to-user="true" bounds="[565,1555][1023,1716]" />
                </hierarchy>
            """.trimIndent(),
            packageName = "com.android.contacts",
            eventLabel = "",
            eventClassName = "android.widget.Button",
            ignoredPackageName = "cn.com.omnimind.bot.debug",
            ignoredTextHints = emptyList()
        )

        assertNotNull(result.target)
        assertEquals("新建联系人", result.target?.label)
        assertEquals("xml_fallback_transition_inferred", result.resolution)
        assertEquals("after_page_transition_match", result.inference["reason"])
    }

    @Test
    fun `source-less click stays ambiguous when event text is absent from stale xml`() {
        val detailPageXml = """
            <hierarchy bounds="[0,0][1260,2800]">
              <node package="com.android.contacts" class="android.widget.Button" content-desc="分享" clickable="true" enabled="true" visible-to-user="true" bounds="[1124,2485][1474,2730]" />
              <node package="com.android.contacts" class="android.widget.Button" content-desc="更多" clickable="true" enabled="true" visible-to-user="true" bounds="[1052,216][1214,377]" />
            </hierarchy>
        """.trimIndent()

        val result = ManualClickGrounding.inferClickTarget(
            beforeXml = detailPageXml,
            afterXml = detailPageXml,
            packageName = "com.android.contacts",
            eventLabel = "完成",
            eventClassName = "android.widget.Button",
            ignoredPackageName = "cn.com.omnimind.bot.debug",
            ignoredTextHints = emptyList()
        )

        assertNull(result.target)
        assertEquals("xml_fallback_ambiguous", result.resolution)
        assertEquals("ambiguous_event_text_not_matched", result.inference["reason"])
    }
}
