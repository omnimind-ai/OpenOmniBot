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
    }
}
