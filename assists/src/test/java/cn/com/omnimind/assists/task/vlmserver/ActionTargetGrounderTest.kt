package cn.com.omnimind.assists.task.vlmserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionTargetGrounderTest {
    @Test
    fun `grounds click to matching accessibility node center`() {
        val action = ClickAction(
            targetDescription = "发送",
            x = 1012f,
            y = 1852f,
        )

        val result = ActionTargetGrounder.ground(action, SAMPLE_XML)

        assertTrue(result.applied)
        val grounded = result.action as ClickAction
        assertEquals(990f, grounded.x, 0.01f)
        assertEquals(1840f, grounded.y, 0.01f)
        assertEquals("inside_target_centered", result.reason)
    }

    @Test
    fun `does not ground unrelated target description`() {
        val action = ClickAction(
            targetDescription = "删除",
            x = 1012f,
            y = 1852f,
        )

        val result = ActionTargetGrounder.ground(action, SAMPLE_XML)

        assertFalse(result.applied)
        assertEquals("no_semantic_target", result.reason)
        assertEquals(action, result.action)
    }

    @Test
    fun `grounds long press when target text matches nearby node`() {
        val action = LongPressAction(
            targetDescription = "张三",
            x = 145f,
            y = 235f,
        )

        val result = ActionTargetGrounder.ground(action, SAMPLE_XML)

        assertTrue(result.applied)
        val grounded = result.action as LongPressAction
        assertEquals(180f, grounded.x, 0.01f)
        assertEquals(240f, grounded.y, 0.01f)
    }

    @Test
    fun `grounds clickable parent using descendant text`() {
        val action = ClickAction(
            targetDescription = "确认支付",
            x = 702f,
            y = 1220f,
        )

        val result = ActionTargetGrounder.ground(action, NESTED_XML)

        assertTrue(result.applied)
        val grounded = result.action as ClickAction
        assertEquals(720f, grounded.x, 0.01f)
        assertEquals(1240f, grounded.y, 0.01f)
    }

    @Test
    fun `prefers direct text node over large scroll container`() {
        val action = ClickAction(
            targetDescription = "Network & internet option",
            x = 219f,
            y = 800f,
        )

        val result = ActionTargetGrounder.ground(action, SETTINGS_XML)

        assertTrue(result.applied)
        val grounded = result.action as ClickAction
        assertEquals(309.5f, grounded.x, 0.01f)
        assertEquals(606f, grounded.y, 0.01f)
        assertTrue(result.targetLabel.contains("Network"))
    }

    @Test
    fun `high confidence direct text match can beat nearby unrelated text`() {
        val action = ClickAction(
            targetDescription = "Apps setting option",
            x = 207f,
            y = 1249f,
        )

        val result = ActionTargetGrounder.ground(action, SETTINGS_XML)

        assertTrue(result.applied)
        val grounded = result.action as ClickAction
        assertEquals(189.5f, grounded.x, 0.01f)
        assertEquals(958f, grounded.y, 0.01f)
        assertTrue(result.targetLabel.contains("Apps"))
    }

    companion object {
        private const val SAMPLE_XML =
            """
            <hierarchy>
              <node bounds="[0,0][1080,1920]" class="android.widget.FrameLayout" enabled="true">
                <node text="张三" bounds="[100,200][260,280]" clickable="true" enabled="true" class="android.widget.TextView"/>
                <node text="发送" bounds="[940,1800][1040,1880]" clickable="true" enabled="true" class="android.widget.Button"/>
                <node text="取消" bounds="[40,1800][160,1880]" clickable="true" enabled="true" class="android.widget.Button"/>
              </node>
            </hierarchy>
            """

        private const val NESTED_XML =
            """
            <hierarchy>
              <node bounds="[0,0][1080,1920]" class="android.widget.FrameLayout" enabled="true">
                <node bounds="[560,1180][880,1300]" clickable="true" enabled="true" class="android.widget.LinearLayout">
                  <node text="确认支付" bounds="[620,1210][820,1270]" clickable="false" enabled="true" class="android.widget.TextView"/>
                </node>
                <node text="取消" bounds="[160,1180][480,1300]" clickable="true" enabled="true" class="android.widget.Button"/>
              </node>
            </hierarchy>
            """

        private const val SETTINGS_XML =
            """
            <hierarchy>
              <node bounds="[0,0][720,1280]" class="android.widget.FrameLayout" enabled="true">
                <node bounds="[0,537][720,1232]" class="android.widget.ScrollView" scrollable="true" focusable="true" enabled="true">
                  <node text="Network &amp; internet" bounds="[144,579][475,633]" clickable="false" enabled="true" class="android.widget.TextView"/>
                  <node text="Mobile, Wi-Fi, hotspot" bounds="[144,633][412,671]" clickable="false" enabled="true" class="android.widget.TextView"/>
                  <node text="Connected devices" bounds="[144,755][482,809]" clickable="false" enabled="true" class="android.widget.TextView"/>
                  <node text="Bluetooth, pairing" bounds="[144,809][361,847]" clickable="false" enabled="true" class="android.widget.TextView"/>
                  <node text="Apps" bounds="[144,931][235,985]" clickable="false" enabled="true" class="android.widget.TextView"/>
                  <node text="Notifications" bounds="[144,1107][373,1161]" clickable="false" enabled="true" class="android.widget.TextView"/>
                </node>
              </node>
            </hierarchy>
            """
    }
}
