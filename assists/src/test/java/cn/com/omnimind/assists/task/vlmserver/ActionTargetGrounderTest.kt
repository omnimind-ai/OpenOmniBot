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

    @Test
    fun `grounds display option to clickable parent even when coordinate is on wallpaper row`() {
        val action = ClickAction(
            targetDescription = "Display settings option",
            x = 180f,
            y = 973f,
        )

        val result = ActionTargetGrounder.ground(action, SETTINGS_DISPLAY_WALLPAPER_XML)

        assertTrue(result.applied)
        val grounded = result.action as ClickAction
        assertEquals(360f, grounded.x, 0.01f)
        assertEquals(755f, grounded.y, 0.01f)
        assertTrue(result.targetLabel.contains("Display"))
        assertFalse(result.targetLabel.contains("Wallpaper"))
    }

    @Test
    fun `does not ground generic settings page description to large container`() {
        val action = ClickAction(
            targetDescription = "Settings app main screen",
            x = 360f,
            y = 640f,
        )

        val result = ActionTargetGrounder.ground(action, SETTINGS_XML)

        assertFalse(result.applied)
        assertEquals("generic_target_description", result.reason)
        assertEquals(action, result.action)
    }

    @Test
    fun `grounds exact numeric keypad target before trusting nearby wrong coordinate`() {
        val action = ClickAction(
            targetDescription = "digit 1",
            x = 240f,
            y = 516f,
        )

        val result = ActionTargetGrounder.ground(action, CLOCK_TIMER_KEYPAD_XML)

        assertTrue(result.applied)
        assertEquals("exact_key_target", result.reason)
        val grounded = result.action as ClickAction
        assertEquals(240f, grounded.x, 0.01f)
        assertEquals(396f, grounded.y, 0.01f)
        assertTrue(result.targetLabel.contains("timer_setup_digit_1"))
    }

    @Test
    fun `grounds add contact intent to create contact fab`() {
        val action = ClickAction(
            targetDescription = "Plus button to add a new contact",
            x = 433f,
            y = 1260f,
        )

        val result = ActionTargetGrounder.ground(action, CONTACTS_HOME_XML)

        assertTrue(result.applied)
        val grounded = result.action as ClickAction
        assertEquals(616f, grounded.x, 0.01f)
        assertEquals(984f, grounded.y, 0.01f)
        assertTrue(result.targetLabel.contains("Create contact"))
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

        private const val SETTINGS_DISPLAY_WALLPAPER_XML =
            """
            <hierarchy>
              <node bounds="[0,0][720,1280]" class="android.widget.FrameLayout" enabled="true">
                <node bounds="[0,216][720,1232]" class="android.widget.ScrollView" scrollable="true" focusable="true" enabled="true">
                  <node bounds="[0,491][720,667]" clickable="true" focusable="true" enabled="true" class="android.widget.LinearLayout">
                    <node text="Sound &amp; vibration" resource-id="android:id/title" bounds="[144,533][458,587]" enabled="true" class="android.widget.TextView"/>
                    <node text="Volume, haptics, Do Not Disturb" resource-id="android:id/summary" bounds="[144,587][538,625]" enabled="true" class="android.widget.TextView"/>
                  </node>
                  <node bounds="[0,667][720,843]" clickable="true" focusable="true" enabled="true" class="android.widget.LinearLayout">
                    <node text="Display" resource-id="android:id/title" bounds="[144,709][274,763]" enabled="true" class="android.widget.TextView"/>
                    <node text="Dark theme, font size, brightness" resource-id="android:id/summary" bounds="[144,763][549,801]" enabled="true" class="android.widget.TextView"/>
                  </node>
                  <node bounds="[0,843][720,1019]" clickable="true" focusable="true" enabled="true" class="android.widget.LinearLayout">
                    <node text="Wallpaper &amp; style" resource-id="android:id/title" bounds="[144,885][451,939]" enabled="true" class="android.widget.TextView"/>
                    <node text="Colors, themed icons, app grid" resource-id="android:id/summary" bounds="[144,939][521,977]" enabled="true" class="android.widget.TextView"/>
                  </node>
                  <node bounds="[0,1019][720,1195]" clickable="true" focusable="true" enabled="true" class="android.widget.LinearLayout">
                    <node text="Accessibility" resource-id="android:id/title" bounds="[144,1061][369,1115]" enabled="true" class="android.widget.TextView"/>
                    <node text="Display, interaction, audio" resource-id="android:id/summary" bounds="[144,1115][459,1153]" enabled="true" class="android.widget.TextView"/>
                  </node>
                </node>
              </node>
            </hierarchy>
            """

        private const val CLOCK_TIMER_KEYPAD_XML =
            """
            <hierarchy>
              <node bounds="[0,0][720,1280]" class="android.widget.FrameLayout" enabled="true">
                <node text="00h 00m 00s" content-desc="0 hours, 0 minutes, 0 seconds" resource-id="com.google.android.deskclock:id/timer_setup_time" bounds="[180,176][540,336]" enabled="true" class="android.widget.TextView"/>
                <node text="1" resource-id="com.google.android.deskclock:id/timer_setup_digit_1" bounds="[184,340][296,452]" enabled="true" clickable="true" focusable="true" class="android.widget.Button"/>
                <node text="2" resource-id="com.google.android.deskclock:id/timer_setup_digit_2" bounds="[304,340][416,452]" enabled="true" clickable="true" focusable="true" class="android.widget.Button"/>
                <node text="3" resource-id="com.google.android.deskclock:id/timer_setup_digit_3" bounds="[424,340][536,452]" enabled="true" clickable="true" focusable="true" class="android.widget.Button"/>
                <node text="4" resource-id="com.google.android.deskclock:id/timer_setup_digit_4" bounds="[184,460][296,572]" enabled="true" clickable="true" focusable="true" class="android.widget.Button"/>
                <node text="5" resource-id="com.google.android.deskclock:id/timer_setup_digit_5" bounds="[304,460][416,572]" enabled="true" clickable="true" focusable="true" class="android.widget.Button"/>
                <node text="6" resource-id="com.google.android.deskclock:id/timer_setup_digit_6" bounds="[424,460][536,572]" enabled="true" clickable="true" focusable="true" class="android.widget.Button"/>
                <node text="7" resource-id="com.google.android.deskclock:id/timer_setup_digit_7" bounds="[184,580][296,692]" enabled="true" clickable="true" focusable="true" class="android.widget.Button"/>
                <node text="8" resource-id="com.google.android.deskclock:id/timer_setup_digit_8" bounds="[304,580][416,692]" enabled="true" clickable="true" focusable="true" class="android.widget.Button"/>
                <node text="9" resource-id="com.google.android.deskclock:id/timer_setup_digit_9" bounds="[424,580][536,692]" enabled="true" clickable="true" focusable="true" class="android.widget.Button"/>
                <node text="00" resource-id="com.google.android.deskclock:id/timer_setup_digit_00" bounds="[184,700][296,812]" enabled="true" clickable="true" focusable="true" class="android.widget.Button"/>
                <node text="0" resource-id="com.google.android.deskclock:id/timer_setup_digit_0" bounds="[304,700][416,812]" enabled="true" clickable="true" focusable="true" class="android.widget.Button"/>
                <node text="⌫" content-desc="Backspace" resource-id="com.google.android.deskclock:id/timer_setup_delete" bounds="[424,700][536,812]" enabled="true" clickable="true" focusable="true" class="android.widget.Button"/>
              </node>
            </hierarchy>
            """

        private const val CONTACTS_HOME_XML =
            """
            <hierarchy>
              <node bounds="[0,0][720,1280]" class="android.widget.FrameLayout" enabled="true">
                <node text="Search contacts" resource-id="com.google.android.contacts:id/open_search_bar" bounds="[32,64][688,160]" enabled="true" clickable="true" focusable="true" class="android.widget.EditText" />
                <node text="No contacts yet" resource-id="android:id/text1" bounds="[189,834][532,895]" enabled="true" class="android.widget.TextView" />
                <node content-desc="Create contact" resource-id="com.google.android.contacts:id/floating_action_button" bounds="[560,928][672,1040]" enabled="true" clickable="true" focusable="true" class="android.widget.ImageButton" />
                <node content-desc="Contacts" resource-id="com.google.android.contacts:id/contacts" bounds="[0,1072][240,1232]" enabled="true" focusable="true" selected="true" class="android.widget.FrameLayout" />
                <node content-desc="Highlights" resource-id="com.google.android.contacts:id/highlights" bounds="[240,1072][480,1232]" enabled="true" clickable="true" focusable="true" class="android.widget.FrameLayout" />
                <node content-desc="Fix &amp; manage" resource-id="com.google.android.contacts:id/nav_manage" bounds="[480,1072][720,1232]" enabled="true" clickable="true" focusable="true" class="android.widget.FrameLayout" />
              </node>
            </hierarchy>
            """
    }
}
