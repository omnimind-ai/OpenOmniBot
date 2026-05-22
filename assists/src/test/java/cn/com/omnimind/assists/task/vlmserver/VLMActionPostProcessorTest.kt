package cn.com.omnimind.assists.task.vlmserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VLMActionPostProcessorTest {
    @Test
    fun `first step opens target package when foreground app differs`() {
        val step = VLMStep(
            observation = "launcher",
            thought = "tap current page",
            action = ClickAction(targetDescription = "Settings", x = 100f, y = 100f)
        )

        val result = VLMActionPostProcessor.correct(
            step = step,
            context = UIContext(
                overallTask = "Open Display settings",
                targetPackageName = "com.android.settings"
            ),
            currentXml = SETTINGS_XML,
            currentPackageName = "com.android.launcher",
            stepIndex = 0,
            displayWidth = 720,
            displayHeight = 1280
        )

        assertTrue(result.applied)
        assertEquals("target_package_not_foreground", result.reason)
        val action = result.step.action as OpenAppAction
        assertEquals("com.android.settings", action.packageName)
    }

    @Test
    fun `first step redirects unrelated click to visible goal target`() {
        val step = VLMStep(
            observation = "settings home",
            thought = "tap first row",
            action = ClickAction(targetDescription = "Network & internet", x = 320f, y = 606f)
        )

        val result = VLMActionPostProcessor.correct(
            step = step,
            context = UIContext(
                overallTask = "Open Display settings",
                targetPackageName = "com.android.settings"
            ),
            currentXml = SETTINGS_WITH_DISPLAY_XML,
            currentPackageName = "com.android.settings",
            stepIndex = 0,
            displayWidth = 720,
            displayHeight = 1280
        )

        assertTrue(result.applied)
        assertEquals("visible_goal_target", result.reason)
        val action = result.step.action as ClickAction
        assertEquals("Display", action.targetDescription)
        assertTrue(action.x in 240f..320f)
        assertTrue(action.y in 880f..980f)
    }

    @Test
    fun `does not redirect when click is already inside visible goal target`() {
        val step = VLMStep(
            observation = "settings home",
            thought = "tap display",
            action = ClickAction(targetDescription = "Display", x = 360f, y = 923f)
        )

        val result = VLMActionPostProcessor.correct(
            step = step,
            context = UIContext(
                overallTask = "Open Display settings",
                targetPackageName = "com.android.settings"
            ),
            currentXml = SETTINGS_WITH_DISPLAY_XML,
            currentPackageName = "com.android.settings",
            stepIndex = 0,
            displayWidth = 720,
            displayHeight = 1280
        )

        assertFalse(result.applied)
    }

    @Test
    fun `uses current form field intent instead of whole task when correcting click target`() {
        val step = VLMStep(
            observation = "contact editor",
            thought = "The next step is to enter the last name 'Smith' in the 'Last name' field.",
            action = ClickAction(targetDescription = "First name EditText", x = 356f, y = 659.5f),
            summary = "The first name 'Alice' has been entered; now proceeding to enter the last name."
        )

        val result = VLMActionPostProcessor.correct(
            step = step,
            context = UIContext(
                overallTask = "Go to the new contact screen and enter the following details: First Name: Alice, Last Name: Smith, Phone: 415-555-0130, Phone Label: Work.",
                targetPackageName = "com.google.android.contacts"
            ),
            currentXml = CONTACT_EDITOR_XML,
            currentPackageName = "com.google.android.contacts",
            stepIndex = 3,
            displayWidth = 720,
            displayHeight = 1280
        )

        assertTrue(result.applied)
        assertEquals("visible_goal_target", result.reason)
        val action = result.step.action as ClickAction
        assertEquals("Last name EditText", action.targetDescription)
        assertEquals(356f, action.x, 0.01f)
        assertEquals(799.5f, action.y, 0.01f)
    }

    @Test
    fun `flips horizontal slider scroll toward minimum endpoint`() {
        val step = VLMStep(
            observation = "brightness slider",
            thought = "scroll left to minimum",
            action = ScrollAction(
                targetDescription = "Display brightness slider",
                x1 = 50f,
                y1 = 141f,
                x2 = 702f,
                y2 = 141f,
                duration = 0.6f
            )
        )

        val result = VLMActionPostProcessor.correct(
            step = step,
            context = UIContext(
                overallTask = "Set display brightness to minimum",
                targetPackageName = "com.android.settings"
            ),
            currentXml = BRIGHTNESS_XML,
            currentPackageName = "com.android.settings",
            stepIndex = 3,
            displayWidth = 720,
            displayHeight = 1280
        )

        assertTrue(result.applied)
        assertEquals("slider_endpoint_direction", result.reason)
        val action = result.step.action as ScrollAction
        assertTrue(action.x1 > action.x2)
        assertTrue(action.x2 <= 32f)
        assertEquals(action.y1, action.y2, 0.01f)
    }

    @Test
    fun `converts slider endpoint click into drag`() {
        val step = VLMStep(
            observation = "brightness slider",
            thought = "set maximum brightness",
            action = ClickAction(
                targetDescription = "Display brightness slider",
                x = 702f,
                y = 141f
            )
        )

        val result = VLMActionPostProcessor.correct(
            step = step,
            context = UIContext(
                overallTask = "Set display brightness to maximum",
                targetPackageName = "com.android.settings"
            ),
            currentXml = BRIGHTNESS_XML,
            currentPackageName = "com.android.settings",
            stepIndex = 2,
            displayWidth = 720,
            displayHeight = 1280
        )

        assertTrue(result.applied)
        assertEquals("slider_click_to_drag", result.reason)
        val action = result.step.action as ScrollAction
        assertTrue(action.x2 > action.x1)
        assertTrue(action.x2 >= 688f)
        assertEquals(action.y1, action.y2, 0.01f)
    }

    @Test
    fun `does not treat settings display row as brightness slider`() {
        val step = VLMStep(
            observation = "settings list",
            thought = "click Display to access brightness settings",
            action = ClickAction(
                targetDescription = "Click on Display to access brightness settings",
                x = 209f,
                y = 1026f
            )
        )

        val result = VLMActionPostProcessor.correct(
            step = step,
            context = UIContext(
                overallTask = "Turn brightness to the min value.",
                targetPackageName = "com.android.settings"
            ),
            currentXml = SETTINGS_WITH_DISPLAY_SUBTITLE_XML,
            currentPackageName = "com.android.settings",
            stepIndex = 2,
            displayWidth = 720,
            displayHeight = 1280
        )

        assertFalse(result.applied)
    }

    @Test
    fun `converts numeric keypad type into first digit click`() {
        val step = VLMStep(
            observation = "clock timer keypad",
            thought = "enter timer value",
            action = TypeAction(content = "130")
        )

        val result = VLMActionPostProcessor.correct(
            step = step,
            context = UIContext(
                overallTask = "Create a timer with 0 hours, 1 minutes, and 30 seconds.",
                targetPackageName = "com.google.android.deskclock"
            ),
            currentXml = CLOCK_TIMER_KEYPAD_XML,
            currentPackageName = "com.google.android.deskclock",
            stepIndex = 2,
            displayWidth = 720,
            displayHeight = 1280
        )

        assertTrue(result.applied)
        assertEquals("type_to_numeric_key_click", result.reason)
        val action = result.step.action as ClickAction
        assertEquals("digit 1", action.targetDescription)
        assertEquals(240f, action.x, 0.01f)
        assertEquals(396f, action.y, 0.01f)
        assertTrue(result.step.summary.contains("remaining=30"))
    }

    @Test
    fun `keeps type when editable field is focused`() {
        val step = VLMStep(
            observation = "focused edit field",
            thought = "enter text",
            action = TypeAction(content = "130")
        )

        val result = VLMActionPostProcessor.correct(
            step = step,
            context = UIContext(overallTask = "Enter 130"),
            currentXml = FOCUSED_EDITABLE_XML,
            currentPackageName = "example",
            stepIndex = 1,
            displayWidth = 720,
            displayHeight = 1280
        )

        assertFalse(result.applied)
        assertEquals(step.action, result.step.action)
    }

    @Test
    fun `does not convert brightness level row click before slider dialog is open`() {
        val step = VLMStep(
            observation = "display settings",
            thought = "click Brightness level to open adjustment dialog",
            action = ClickAction(
                targetDescription = "Click on the Brightness level option to adjust brightness",
                x = 189f,
                y = 567f
            )
        )

        val result = VLMActionPostProcessor.correct(
            step = step,
            context = UIContext(
                overallTask = "Turn brightness to the min value.",
                targetPackageName = "com.android.settings"
            ),
            currentXml = DISPLAY_BRIGHTNESS_ROW_XML,
            currentPackageName = "com.android.settings",
            stepIndex = 3,
            displayWidth = 720,
            displayHeight = 1280
        )

        assertFalse(result.applied)
    }

    @Test
    fun `converts repeated settings scroll into visible display row click for brightness task`() {
        val step = VLMStep(
            observation = "settings list",
            thought = "scroll down to find brightness",
            action = ScrollAction(
                targetDescription = "Settings list",
                x1 = 360f,
                y1 = 1152f,
                x2 = 360f,
                y2 = 384f,
                duration = 0.6f
            )
        )

        val result = VLMActionPostProcessor.correct(
            step = step,
            context = UIContext(
                overallTask = "Turn brightness to the min value.",
                targetPackageName = "com.android.settings"
            ),
            currentXml = SETTINGS_WITH_DISPLAY_SUBTITLE_XML,
            currentPackageName = "com.android.settings",
            stepIndex = 3,
            displayWidth = 720,
            displayHeight = 1280
        )

        assertTrue(result.applied)
        assertEquals("visible_goal_target_before_scroll", result.reason)
        val action = result.step.action as ClickAction
        assertTrue(
            action.targetDescription,
            action.targetDescription.contains("Display", ignoreCase = true) ||
                action.targetDescription.contains("brightness", ignoreCase = true)
        )
        assertTrue(action.y in 990f..1100f)
    }

    @Test
    fun `does not convert explicit scroll task into visible target click`() {
        val step = VLMStep(
            observation = "settings list",
            thought = "scroll down",
            action = ScrollAction(
                targetDescription = "Settings list",
                x1 = 360f,
                y1 = 1152f,
                x2 = 360f,
                y2 = 384f,
                duration = 0.6f
            )
        )

        val result = VLMActionPostProcessor.correct(
            step = step,
            context = UIContext(
                overallTask = "Scroll down the settings list.",
                targetPackageName = "com.android.settings"
            ),
            currentXml = SETTINGS_WITH_DISPLAY_SUBTITLE_XML,
            currentPackageName = "com.android.settings",
            stepIndex = 1,
            displayWidth = 720,
            displayHeight = 1280
        )

        assertFalse(result.applied)
    }

    @Test
    fun `converts generic settings page click into search scroll when target is not visible`() {
        val step = VLMStep(
            observation = "settings home",
            thought = "click the settings page",
            action = ClickAction(
                targetDescription = "Settings app main screen",
                x = 360f,
                y = 640f
            )
        )

        val result = VLMActionPostProcessor.correct(
            step = step,
            context = UIContext(
                overallTask = "Turn brightness to the min value.",
                targetPackageName = "com.android.settings"
            ),
            currentXml = SETTINGS_XML,
            currentPackageName = "com.android.settings",
            stepIndex = 1,
            displayWidth = 720,
            displayHeight = 1280
        )

        assertTrue(result.applied)
        assertEquals("generic_click_to_search_scroll", result.reason)
        val action = result.step.action as ScrollAction
        assertTrue(action.y1 > action.y2)
    }

    @Test
    fun `backs out of settings subpage when requested top level target is absent`() {
        val step = VLMStep(
            observation = "internet settings",
            thought = "enable bluetooth",
            action = ClickAction(
                targetDescription = "Bluetooth toggle switch to enable Bluetooth",
                x = 439f,
                y = 800f
            )
        )

        val result = VLMActionPostProcessor.correct(
            step = step,
            context = UIContext(
                overallTask = "Enable bluetooth",
                targetPackageName = "com.android.settings"
            ),
            currentXml = INTERNET_SUBPAGE_WITHOUT_BLUETOOTH_XML,
            currentPackageName = "com.android.settings",
            stepIndex = 6,
            displayWidth = 720,
            displayHeight = 1280
        )

        assertTrue(result.applied)
        assertEquals("missing_settings_target_go_back", result.reason)
        assertTrue(result.step.action is PressBackAction)
    }

    @Test
    fun `redirects wifi toggle click to wifi row instead of connected network`() {
        val step = VLMStep(
            observation = "wifi list",
            thought = "turn off wifi",
            action = ClickAction(
                targetDescription = "Toggle switch to turn off WiFi",
                x = 442f,
                y = 800f
            )
        )

        val result = VLMActionPostProcessor.correct(
            step = step,
            context = UIContext(
                overallTask = "Turn off WiFi, then enable bluetooth",
                targetPackageName = "com.android.settings"
            ),
            currentXml = WIFI_LIST_WITH_CONNECTED_NETWORK_XML,
            currentPackageName = "com.android.settings",
            stepIndex = 3,
            displayWidth = 720,
            displayHeight = 1280
        )

        assertTrue(result.applied)
        assertEquals("settings_toggle_target", result.reason)
        val action = result.step.action as ClickAction
        assertTrue(action.targetDescription, action.targetDescription.contains("Wi-Fi", ignoreCase = true))
        assertTrue(action.x >= 560f)
        assertTrue(action.y in 590f..660f)
    }

    @Test
    fun `state pending marker keeps ordered settings task on wifi domain`() {
        val step = VLMStep(
            observation = "settings home",
            thought = "continue with bluetooth",
            action = ClickAction(
                targetDescription = "Connected devices",
                x = 360f,
                y = 801f
            )
        )

        val result = VLMActionPostProcessor.correct(
            step = step,
            context = UIContext(
                overallTask = "Turn off WiFi, then enable bluetooth",
                targetPackageName = "com.android.settings",
                trace = listOf(wifiPendingStep())
            ),
            currentXml = SETTINGS_WITH_DISPLAY_XML,
            currentPackageName = "com.android.settings",
            stepIndex = 4,
            displayWidth = 720,
            displayHeight = 1280
        )

        assertTrue(result.applied)
        assertEquals("visible_goal_target", result.reason)
        val action = result.step.action as ClickAction
        assertTrue(action.targetDescription, action.targetDescription.contains("Network", ignoreCase = true))
        assertTrue(action.y in 570f..680f)
    }

    @Test
    fun `prefers remaining settings domain after earlier domain mutation`() {
        val step = VLMStep(
            observation = "settings home",
            thought = "continue with the next part of the task",
            action = ClickAction(
                targetDescription = "Settings app main screen",
                x = 360f,
                y = 625f
            )
        )

        val result = VLMActionPostProcessor.correct(
            step = step,
            context = UIContext(
                overallTask = "Turn off WiFi, then enable bluetooth",
                targetPackageName = "com.android.settings",
                trace = listOf(wifiToggleStep())
            ),
            currentXml = SETTINGS_WITH_DISPLAY_XML,
            currentPackageName = "com.android.settings",
            stepIndex = 6,
            displayWidth = 720,
            displayHeight = 1280
        )

        assertTrue(result.applied)
        assertEquals("visible_goal_target", result.reason)
        val action = result.step.action as ClickAction
        assertTrue(action.targetDescription, action.targetDescription.contains("Connected devices", ignoreCase = true))
        assertTrue(action.y in 740f..850f)
    }

    @Test
    fun `backs out of stale settings domain when next ordered domain is pending`() {
        val step = VLMStep(
            observation = "network preferences",
            thought = "tap the visible row",
            action = ClickAction(
                targetDescription = "Network preferences",
                x = 360f,
                y = 760f
            )
        )

        val result = VLMActionPostProcessor.correct(
            step = step,
            context = UIContext(
                overallTask = "Turn off WiFi, then enable bluetooth",
                targetPackageName = "com.android.settings",
                trace = listOf(wifiToggleStep())
            ),
            currentXml = INTERNET_SUBPAGE_WITHOUT_BLUETOOTH_XML,
            currentPackageName = "com.android.settings",
            stepIndex = 7,
            displayWidth = 720,
            displayHeight = 1280
        )

        assertTrue(result.applied)
        assertEquals("pending_settings_target_go_back", result.reason)
        assertTrue(result.step.action is PressBackAction)
    }

    @Test
    fun `does not advance ordered settings task before first domain mutation`() {
        val step = VLMStep(
            observation = "settings home",
            thought = "open network settings first",
            action = ClickAction(
                targetDescription = "Network & internet option",
                x = 360f,
                y = 625f
            )
        )

        val result = VLMActionPostProcessor.correct(
            step = step,
            context = UIContext(
                overallTask = "Turn off WiFi, then enable bluetooth",
                targetPackageName = "com.android.settings",
                trace = listOf(
                    UIStep(
                        observation = "",
                        thought = "open network settings",
                        action = ClickAction(targetDescription = "Network & internet option", x = 360f, y = 625f)
                    )
                )
            ),
            currentXml = SETTINGS_WITH_DISPLAY_XML,
            currentPackageName = "com.android.settings",
            stepIndex = 2,
            displayWidth = 720,
            displayHeight = 1280
        )

        assertFalse(result.applied)
    }

    @Test
    fun `does not back out when settings subpage contains requested target`() {
        val step = VLMStep(
            observation = "internet settings",
            thought = "turn off wifi",
            action = ClickAction(
                targetDescription = "Wi-Fi toggle switch to turn off WiFi",
                x = 360f,
                y = 627f
            )
        )

        val result = VLMActionPostProcessor.correct(
            step = step,
            context = UIContext(
                overallTask = "Turn off WiFi",
                targetPackageName = "com.android.settings"
            ),
            currentXml = INTERNET_SUBPAGE_WITHOUT_BLUETOOTH_XML,
            currentPackageName = "com.android.settings",
            stepIndex = 3,
            displayWidth = 720,
            displayHeight = 1280
        )

        assertTrue(result.applied)
        assertEquals("settings_toggle_target", result.reason)
        val action = result.step.action as ClickAction
        assertTrue(action.targetDescription, action.targetDescription.contains("Wi-Fi", ignoreCase = true))
        assertTrue(action.x >= 560f)
    }

    @Test
    fun `redirects nested ordered settings click before later sibling`() {
        val step = VLMStep(
            observation = "default apps list",
            thought = "open the Phone app settings",
            action = ClickAction(
                targetDescription = "Phone app Phone LinearLayout",
                x = 540f,
                y = 1525f
            )
        )

        val result = VLMActionPostProcessor.correct(
            step = step,
            context = UIContext(
                overallTask = DEFAULT_APPS_ORDERED_TASK,
                targetPackageName = "com.android.settings",
                trace = listOf(appsStep(), defaultAppsStep())
            ),
            currentXml = DEFAULT_APPS_LIST_XML,
            currentPackageName = "com.google.android.permissioncontroller",
            stepIndex = 2,
            displayWidth = 1080,
            displayHeight = 2400
        )

        assertTrue(result.applied)
        assertEquals("ordered_goal_target", result.reason)
        val action = result.step.action as ClickAction
        assertTrue(action.targetDescription, action.targetDescription.contains("Browser app", ignoreCase = true))
        assertTrue(action.y in 650f..760f)
    }

    @Test
    fun `keeps ordered target click from later visible goal correction`() {
        val step = VLMStep(
            observation = "default apps list",
            thought = "open the Browser app settings",
            action = ClickAction(
                targetDescription = "Browser app Chrome LinearLayout",
                x = 540f,
                y = 701f
            )
        )

        val result = VLMActionPostProcessor.correct(
            step = step,
            context = UIContext(
                overallTask = DEFAULT_APPS_ORDERED_TASK,
                targetPackageName = "com.android.settings",
                trace = listOf(appsStep(), defaultAppsStep())
            ),
            currentXml = DEFAULT_APPS_LIST_XML,
            currentPackageName = "com.google.android.permissioncontroller",
            stepIndex = 2,
            displayWidth = 1080,
            displayHeight = 2400
        )

        assertFalse(result.applied)
        val action = result.step.action as ClickAction
        assertTrue(action.targetDescription, action.targetDescription.contains("Browser app", ignoreCase = true))
    }

    @Test
    fun `does not treat narrative mention as ordered target completion`() {
        val step = VLMStep(
            observation = "default apps list",
            thought = "open the Phone app settings",
            action = ClickAction(
                targetDescription = "Phone app Phone LinearLayout",
                x = 540f,
                y = 1525f
            )
        )
        val defaultStepWithBrowserNarrative = defaultAppsStep().copy(
            summary = "Navigated to the Apps page; identified Browser app as the next target."
        )

        val result = VLMActionPostProcessor.correct(
            step = step,
            context = UIContext(
                overallTask = DEFAULT_APPS_ORDERED_TASK,
                targetPackageName = "com.android.settings",
                trace = listOf(appsStep(), defaultStepWithBrowserNarrative)
            ),
            currentXml = DEFAULT_APPS_LIST_XML,
            currentPackageName = "com.google.android.permissioncontroller",
            stepIndex = 2,
            displayWidth = 1080,
            displayHeight = 2400
        )

        assertTrue(result.applied)
        assertEquals("ordered_goal_target", result.reason)
        val action = result.step.action as ClickAction
        assertTrue(action.targetDescription, action.targetDescription.contains("Browser app", ignoreCase = true))
    }

    @Test
    fun `converts premature finished into pending ordered target click when visible`() {
        val step = VLMStep(
            observation = "default apps list",
            thought = "all done",
            action = FinishedAction(content = "Done")
        )

        val result = VLMActionPostProcessor.correct(
            step = step,
            context = UIContext(
                overallTask = DEFAULT_APPS_ORDERED_TASK,
                targetPackageName = "com.android.settings",
                trace = listOf(appsStep(), defaultAppsStep())
            ),
            currentXml = DEFAULT_APPS_LIST_XML,
            currentPackageName = "com.google.android.permissioncontroller",
            stepIndex = 2,
            displayWidth = 1080,
            displayHeight = 2400
        )

        assertTrue(result.applied)
        assertEquals("premature_finished_ordered_target", result.reason)
        val action = result.step.action as ClickAction
        assertTrue(action.targetDescription, action.targetDescription.contains("Browser app", ignoreCase = true))
    }

    @Test
    fun `converts premature finished into back when pending ordered target is not visible`() {
        val step = VLMStep(
            observation = "phone detail page",
            thought = "phone page is visible",
            action = FinishedAction(content = "The Phone app page is visible.")
        )

        val result = VLMActionPostProcessor.correct(
            step = step,
            context = UIContext(
                overallTask = DEFAULT_APPS_ORDERED_TASK,
                targetPackageName = "com.android.settings",
                trace = listOf(appsStep(), defaultAppsStep(), phoneAppStep(), pressBackStep(), phoneAppStep())
            ),
            currentXml = PHONE_APP_DETAIL_XML,
            currentPackageName = "com.google.android.permissioncontroller",
            stepIndex = 5,
            displayWidth = 1080,
            displayHeight = 2400
        )

        assertTrue(result.applied)
        assertEquals("premature_finished_ordered_target_go_back", result.reason)
        assertTrue(result.step.action is PressBackAction)
    }

    @Test
    fun `allows finished when observation satisfies pending verify target`() {
        val step = VLMStep(
            observation = "The Display page is visible with Brightness level and Dark theme options shown.",
            thought = "the requested page is verified",
            summary = "Display settings page is confirmed open with Brightness level and Dark theme visible.",
            action = FinishedAction(content = "Display page is visible with Brightness level and Dark theme.")
        )

        val result = VLMActionPostProcessor.correct(
            step = step,
            context = UIContext(
                overallTask = DISPLAY_VERIFY_TASK,
                targetPackageName = "com.android.settings",
                trace = listOf(displaySettingsStep())
            ),
            currentXml = DISPLAY_SETTINGS_PAGE_XML,
            currentPackageName = "com.android.settings",
            stepIndex = 1,
            displayWidth = 720,
            displayHeight = 1280
        )

        assertFalse(result.applied)
        assertTrue(result.step.action is FinishedAction)
    }

    @Test
    fun `allows finished when repeated terms appear in pending verify target`() {
        val step = VLMStep(
            observation = "The Network & internet page is visible with options like Internet, SIMs, and others.",
            thought = "the network page is verified",
            summary = "Network & internet page verified with Internet and SIMs options visible.",
            action = FinishedAction(content = "Network & internet page is visible with Internet and SIMs.")
        )

        val result = VLMActionPostProcessor.correct(
            step = step,
            context = UIContext(
                overallTask = NETWORK_VERIFY_TASK,
                targetPackageName = "com.android.settings",
                trace = listOf(networkSettingsStep())
            ),
            currentXml = NETWORK_SETTINGS_PAGE_XML,
            currentPackageName = "com.android.settings",
            stepIndex = 1,
            displayWidth = 1080,
            displayHeight = 2400
        )

        assertFalse(result.applied)
        assertTrue(result.step.action is FinishedAction)
    }

    @Test
    fun `allows finished after every ordered target was clicked in order`() {
        val step = VLMStep(
            observation = "phone detail page",
            thought = "phone page is visible",
            action = FinishedAction(content = "The Phone app page is visible.")
        )

        val result = VLMActionPostProcessor.correct(
            step = step,
            context = UIContext(
                overallTask = DEFAULT_APPS_ORDERED_TASK,
                targetPackageName = "com.android.settings",
                trace = listOf(appsStep(), defaultAppsStep(), browserAppStep(), pressBackStep(), phoneAppStep())
            ),
            currentXml = PHONE_APP_DETAIL_XML,
            currentPackageName = "com.google.android.permissioncontroller",
            stepIndex = 5,
            displayWidth = 1080,
            displayHeight = 2400
        )

        assertFalse(result.applied)
    }

    companion object {
        private const val DISPLAY_VERIFY_TASK =
            "From the Settings home screen, open Display settings, verify the Display page is visible with Brightness level or Dark theme, then finish."

        private const val NETWORK_VERIFY_TASK =
            "From the Settings home screen, open Network & internet settings, verify the Network & internet page is visible with Internet or SIMs, then finish."

        private const val DEFAULT_APPS_ORDERED_TASK =
            "From Settings home, open Apps, open Default apps, open Browser app, verify the Browser app page is visible, go back to Default apps, open Phone app, verify the Phone app page is visible, then finish."

        private fun wifiToggleStep(): UIStep =
            UIStep(
                observation = "internet settings",
                thought = "turn off Wi-Fi",
                action = ClickAction(
                    targetDescription = "Wi-Fi toggle switch",
                    x = 360f,
                    y = 627f
                ),
                result = "点击坐标 (360.0, 627.0) 成功"
            )

        private fun wifiPendingStep(): UIStep =
            wifiToggleStep().copy(
                summary = "[settings_state_pending:wifi=off actual=on] [settings_state_pending:bluetooth=on actual=off]"
            )

        private fun appsStep(): UIStep =
            UIStep(
                observation = "settings home",
                thought = "open Apps",
                action = ClickAction(targetDescription = "Apps option to open Apps settings", x = 540f, y = 1347.5f),
                result = "clicked Apps"
            )

        private fun defaultAppsStep(): UIStep =
            UIStep(
                observation = "apps settings",
                thought = "open Default apps",
                action = ClickAction(targetDescription = "Default apps option to open default app settings", x = 620f, y = 1538f),
                result = "clicked Default apps"
            )

        private fun browserAppStep(): UIStep =
            UIStep(
                observation = "default apps list",
                thought = "open Browser app",
                action = ClickAction(targetDescription = "Browser app Chrome LinearLayout", x = 540f, y = 701f),
                result = "clicked Browser app"
            )

        private fun phoneAppStep(): UIStep =
            UIStep(
                observation = "default apps list",
                thought = "open Phone app",
                action = ClickAction(targetDescription = "Phone app Phone LinearLayout", x = 540f, y = 1525f),
                result = "clicked Phone app"
            )

        private fun displaySettingsStep(): UIStep =
            UIStep(
                observation = "settings home",
                thought = "open Display settings",
                action = ClickAction(targetDescription = "click on Display settings", x = 360f, y = 857f),
                result = "clicked Display settings"
            )

        private fun networkSettingsStep(): UIStep =
            UIStep(
                observation = "settings home",
                thought = "open Network & internet settings",
                action = ClickAction(targetDescription = "Network & internet option", x = 540f, y = 885.5f),
                result = "clicked Network & internet"
            )

        private fun pressBackStep(): UIStep =
            UIStep(
                observation = "detail page",
                thought = "go back to Default apps",
                action = PressBackAction(),
                result = "pressed back"
            )

        private const val SETTINGS_XML =
            """
            <hierarchy>
              <node bounds="[0,0][720,1280]" scrollable="true">
                <node text="Settings" bounds="[48,256][312,353]" />
                <node text="Network &amp; internet" bounds="[144,579][475,633]" clickable="true" />
              </node>
            </hierarchy>
            """

        private const val WIFI_LIST_WITH_CONNECTED_NETWORK_XML =
            """
            <hierarchy>
              <node bounds="[0,0][720,1280]">
                <node content-desc="Navigate up" clickable="true" focusable="true" enabled="true" bounds="[0,48][112,160]" />
                <node bounds="[0,412][720,1232]" class="androidx.recyclerview.widget.RecyclerView" scrollable="true" focusable="true" enabled="true">
                  <node clickable="true" focusable="true" enabled="true" bounds="[0,568][720,686]">
                    <node text="Wi-Fi" enabled="true" bounds="[48,600][136,654]" />
                  </node>
                  <node content-desc="AndroidWifi,Connected,Wifi signal full.,Open network" clickable="true" long-clickable="true" focusable="true" enabled="true" bounds="[0,686][720,842]">
                    <node text="AndroidWifi" enabled="true" bounds="[144,718][352,772]" />
                    <node text="Connected" enabled="true" bounds="[144,772][278,810]" />
                  </node>
                  <node clickable="true" focusable="true" enabled="true" bounds="[0,1072][720,1228]">
                    <node text="Network preferences" enabled="true" bounds="[48,1104][419,1158]" />
                    <node text="Wi-Fi turns back on automatically" enabled="true" bounds="[48,1158][459,1196]" />
                  </node>
                </node>
              </node>
            </hierarchy>
            """

        private const val SETTINGS_WITH_DISPLAY_XML =
            """
            <hierarchy>
              <node bounds="[0,0][720,1280]" scrollable="true">
                <node text="Settings" bounds="[48,256][312,353]" />
                <node text="Network &amp; internet" bounds="[144,579][475,633]" clickable="true" />
                <node text="Connected devices" bounds="[144,755][482,809]" clickable="true" />
                <node text="Display" bounds="[144,887][418,959]" clickable="true" />
              </node>
            </hierarchy>
            """

        private const val DEFAULT_APPS_LIST_XML =
            """
            <hierarchy>
              <node bounds="[0,0][1080,2400]">
                <node content-desc="Navigate up" clickable="true" focusable="true" enabled="true" bounds="[0,128][147,275]" />
                <node class="androidx.recyclerview.widget.RecyclerView" scrollable="true" focusable="true" enabled="true" bounds="[0,598][1080,1989]">
                  <node clickable="true" focusable="true" enabled="true" bounds="[0,598][1080,804]">
                    <node text="Browser app" enabled="true" bounds="[189,640][485,711]" />
                    <node text="Chrome" enabled="true" bounds="[189,711][319,762]" />
                  </node>
                  <node clickable="true" focusable="true" enabled="true" bounds="[0,804][1080,1010]">
                    <node text="Caller ID &amp; spam app" enabled="true" bounds="[189,846][680,917]" />
                    <node text="None" enabled="true" bounds="[189,917][276,968]" />
                  </node>
                  <node clickable="true" focusable="true" enabled="true" bounds="[0,1216][1080,1422]">
                    <node text="Home app" enabled="true" bounds="[189,1258][433,1329]" />
                    <node text="Pixel Launcher" enabled="true" bounds="[189,1329][429,1380]" />
                  </node>
                  <node clickable="true" focusable="true" enabled="true" bounds="[0,1422][1080,1628]">
                    <node text="Phone app" enabled="true" bounds="[189,1464][440,1535]" />
                    <node text="Phone" enabled="true" bounds="[189,1535][293,1586]" />
                  </node>
                </node>
              </node>
            </hierarchy>
            """

        private const val PHONE_APP_DETAIL_XML =
            """
            <hierarchy>
              <node bounds="[0,0][1080,2400]">
                <node content-desc="Navigate up" clickable="true" focusable="true" enabled="true" bounds="[0,128][147,275]" />
                <node class="androidx.recyclerview.widget.RecyclerView" focusable="true" enabled="true" bounds="[0,598][1080,1077]">
                  <node clickable="true" focusable="true" enabled="true" bounds="[0,598][1080,804]">
                    <node text="Phone" enabled="true" bounds="[316,640][465,711]" />
                    <node text="(System default)" enabled="true" bounds="[316,711][996,762]" />
                  </node>
                  <node text="Apps that allow you to make and receive telephone calls on your device" enabled="true" bounds="[63,920][1038,1077]" />
                </node>
              </node>
            </hierarchy>
            """

        private const val BRIGHTNESS_XML =
            """
            <hierarchy>
              <node bounds="[0,0][720,1280]">
                <node text="Display brightness" bounds="[48,96][400,176]" />
                <node class="android.widget.SeekBar" resource-id="com.android.systemui:id/brightness_slider" bounds="[24,112][704,176]" clickable="true" focusable="true" />
              </node>
            </hierarchy>
            """

        private const val CLOCK_TIMER_KEYPAD_XML =
            """
            <hierarchy>
              <node bounds="[0,0][720,1280]">
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
              </node>
            </hierarchy>
            """

        private const val FOCUSED_EDITABLE_XML =
            """
            <hierarchy>
              <node bounds="[0,0][720,1280]">
                <node text="" bounds="[48,120][672,220]" enabled="true" editable="true" focused="true" class="android.widget.EditText"/>
              </node>
            </hierarchy>
            """

        private const val CONTACT_EDITOR_XML =
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

        private const val SETTINGS_WITH_DISPLAY_SUBTITLE_XML =
            """
            <hierarchy>
              <node bounds="[0,0][720,1280]">
                <node scrollable="true" bounds="[0,216][720,1232]">
                  <node clickable="true" focusable="true" bounds="[0,781][720,957]">
                    <node text="Sound &amp; vibration" bounds="[144,823][458,877]" />
                    <node text="Volume, haptics, Do Not Disturb" bounds="[144,877][538,915]" />
                  </node>
                  <node clickable="true" focusable="true" bounds="[0,957][720,1133]">
                    <node text="Display" bounds="[144,999][274,1053]" />
                    <node text="Dark theme, font size, brightness" bounds="[144,1053][549,1091]" />
                  </node>
                </node>
              </node>
            </hierarchy>
            """

        private const val DISPLAY_BRIGHTNESS_ROW_XML =
            """
            <hierarchy>
              <node bounds="[0,0][720,1280]">
                <node scrollable="true" bounds="[0,406][720,1232]">
                  <node text="Brightness" bounds="[48,454][688,492]" />
                  <node clickable="true" focusable="true" bounds="[0,508][720,664]">
                    <node text="Brightness level" bounds="[48,540][330,594]" />
                    <node text="100%" bounds="[48,594][117,632]" />
                  </node>
                </node>
              </node>
            </hierarchy>
            """

        private const val DISPLAY_SETTINGS_PAGE_XML =
            """
            <hierarchy>
              <node bounds="[0,0][720,1280]">
                <node content-desc="Navigate up" clickable="true" focusable="true" enabled="true" bounds="[0,48][112,160]" />
                <node scrollable="true" bounds="[0,406][720,1232]">
                  <node text="Brightness" bounds="[48,454][688,492]" />
                  <node clickable="true" focusable="true" bounds="[0,508][720,664]">
                    <node text="Brightness level" bounds="[48,540][330,594]" />
                    <node text="100%" bounds="[48,594][117,632]" />
                  </node>
                  <node text="Appearance" bounds="[48,1126][688,1164]" />
                  <node clickable="true" focusable="true" bounds="[0,1180][720,1232]">
                    <node text="Dark theme" bounds="[48,1212][252,1232]" />
                  </node>
                </node>
              </node>
            </hierarchy>
            """

        private const val NETWORK_SETTINGS_PAGE_XML =
            """
            <hierarchy>
              <node bounds="[0,0][1080,2400]">
                <node content-desc="Navigate up" clickable="true" focusable="true" enabled="true" bounds="[80,128][227,275]" />
                <node scrollable="true" bounds="[80,598][1160,2337]">
                  <node clickable="true" focusable="true" bounds="[80,598][1160,804]">
                    <node text="Internet" bounds="[269,640][449,711]" />
                  </node>
                  <node clickable="true" focusable="true" bounds="[80,1010][1160,1216]">
                    <node text="SIMs" bounds="[269,1052][387,1123]" />
                  </node>
                </node>
              </node>
            </hierarchy>
            """

        private const val INTERNET_SUBPAGE_WITHOUT_BLUETOOTH_XML =
            """
            <hierarchy>
              <node bounds="[0,0][720,1280]">
                <node content-desc="Navigate up" clickable="true" focusable="true" enabled="true" bounds="[0,48][112,160]" />
                <node bounds="[0,412][720,1232]" class="androidx.recyclerview.widget.RecyclerView" scrollable="true" focusable="true" enabled="true">
                  <node clickable="true" focusable="true" enabled="true" bounds="[0,412][720,568]">
                    <node text="T-Mobile" enabled="true" bounds="[144,444][296,498]" />
                    <node text="Connected / LTE" enabled="true" bounds="[144,498][349,536]" />
                  </node>
                  <node clickable="true" focusable="true" enabled="true" bounds="[0,568][720,686]">
                    <node text="Wi-Fi" enabled="true" bounds="[48,600][136,654]" />
                  </node>
                  <node clickable="true" focusable="true" enabled="true" bounds="[0,686][720,842]">
                    <node text="Network preferences" enabled="true" bounds="[48,718][419,772]" />
                    <node text="Wi-Fi turns back on automatically" enabled="true" bounds="[48,772][459,810]" />
                  </node>
                </node>
              </node>
            </hierarchy>
            """
    }
}
