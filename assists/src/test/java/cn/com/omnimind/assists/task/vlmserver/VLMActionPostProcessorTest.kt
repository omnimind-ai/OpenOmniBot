package cn.com.omnimind.assists.task.vlmserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VLMActionPostProcessorTest {
    @Test
    fun `first step does not force open app when foreground package differs`() {
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

        assertFalse(result.step.action is OpenAppAction)
        assertFalse(result.reason == "target_package_not_foreground")
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
    fun `redirects click on already satisfied form value to remaining selection field`() {
        val step = VLMStep(
            observation = "contact editor",
            thought = "Phone number is present; now set the phone label to Work.",
            action = ClickAction(
                targetDescription = "415-555-0130 EditText",
                x = 356f,
                y = 1111.5f
            ),
            summary = "First name, last name, and phone number are filled."
        )

        val result = VLMActionPostProcessor.correct(
            step = step,
            context = UIContext(
                overallTask = "Go to the new contact screen and enter the following details: First Name: Alice, Last Name: Smith, Phone: 415-555-0130, Phone Label: Work. Do NOT hit save.",
                targetPackageName = "com.google.android.contacts"
            ),
            currentXml = CONTACT_EDITOR_PHONE_FILLED_XML,
            currentPackageName = "com.google.android.contacts",
            stepIndex = 8,
            displayWidth = 720,
            displayHeight = 1280
        )

        assertTrue(result.applied)
        assertEquals("pending_form_goal_target", result.reason)
        val action = result.step.action as ClickAction
        assertEquals("Mobile Phone", action.targetDescription)
        assertEquals(275f, action.x, 0.01f)
        assertEquals(1213.5f, action.y, 0.01f)
    }

    @Test
    fun `redirects input text away from stale focused value to remaining selection field`() {
        val step = VLMStep(
            observation = "contact editor",
            thought = "Set the phone label to Work.",
            action = InputTextAction(
                targetDescription = "415-555-0130 EditText",
                content = "Work",
                x = 356f,
                y = 1111.5f
            ),
            summary = "Phone field remained focused after entering the number."
        )

        val result = VLMActionPostProcessor.correct(
            step = step,
            context = UIContext(
                overallTask = "Go to the new contact screen and enter the following details: First Name: Alice, Last Name: Smith, Phone: 415-555-0130, Phone Label: Work. Do NOT hit save.",
                targetPackageName = "com.google.android.contacts"
            ),
            currentXml = CONTACT_EDITOR_PHONE_FILLED_XML,
            currentPackageName = "com.google.android.contacts",
            stepIndex = 8,
            displayWidth = 720,
            displayHeight = 1280
        )

        assertTrue(result.applied)
        assertEquals("type_to_form_field_target", result.reason)
        val action = result.step.action as ClickAction
        assertEquals("Mobile Phone", action.targetDescription)
        assertEquals(275f, action.x, 0.01f)
        assertEquals(1213.5f, action.y, 0.01f)
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
    fun `redirects type to matching form selection row when editable focus is stale`() {
        val step = VLMStep(
            observation = "contact editor with phone field focused",
            thought = "The phone number is already entered. Open the Mobile Phone label selector so the label can be changed to Work.",
            action = TypeAction(content = "Work"),
            summary = "Next change the phone label to Work."
        )

        val result = VLMActionPostProcessor.correct(
            step = step,
            context = UIContext(
                overallTask = "Create a new contact with first name Alice and last name Smith.",
                targetPackageName = "com.google.android.contacts"
            ),
            currentXml = CONTACT_EDITOR_PHONE_FOCUSED_XML,
            currentPackageName = "com.google.android.contacts",
            stepIndex = 6,
            displayWidth = 720,
            displayHeight = 1280
        )

        assertTrue(result.applied)
        assertEquals("type_to_form_field_target", result.reason)
        val action = result.step.action as ClickAction
        assertEquals("Mobile Phone", action.targetDescription)
        assertEquals(275f, action.x, 0.01f)
        assertEquals(1213.5f, action.y, 0.01f)
    }

    @Test
    fun `redirects type to matching editable field when focus is stale`() {
        val step = VLMStep(
            observation = "contact editor with first name already focused",
            thought = "The next step is to enter the last name Smith in the Last name field.",
            action = TypeAction(content = "Smith"),
            summary = "Next change the last name to Smith."
        )

        val result = VLMActionPostProcessor.correct(
            step = step,
            context = UIContext(
                overallTask = "Create a new contact with first name Alice, last name Smith, phone number 415-555-0130, and phone label Work.",
                targetPackageName = "com.google.android.contacts"
            ),
            currentXml = CONTACT_EDITOR_PHONE_FOCUSED_XML,
            currentPackageName = "com.google.android.contacts",
            stepIndex = 7,
            displayWidth = 720,
            displayHeight = 1280
        )

        assertTrue(result.applied)
        assertEquals("type_to_form_field_target", result.reason)
        val action = result.step.action as InputTextAction
        assertEquals("Last name", action.targetDescription)
        assertEquals("Smith", action.content)
        assertEquals(356f, action.x, 0.01f)
        assertEquals(799.5f, action.y, 0.01f)
    }

    @Test
    fun `keeps type when focused form field matches narrative intent`() {
        val step = VLMStep(
            observation = "contact editor with phone field focused",
            thought = "Enter the phone number in the Phone field.",
            action = TypeAction(content = "415-555-0130")
        )

        val result = VLMActionPostProcessor.correct(
            step = step,
            context = UIContext(
                overallTask = "Create a new contact with first name Alice, last name Smith, phone number 415-555-0130, and phone label Work.",
                targetPackageName = "com.google.android.contacts"
            ),
            currentXml = CONTACT_EDITOR_PHONE_FOCUSED_XML,
            currentPackageName = "com.google.android.contacts",
            stepIndex = 5,
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
    fun `does not convert brightness level row horizontal scroll before slider dialog is open`() {
        val step = VLMStep(
            observation = "display settings",
            thought = "adjust the brightness level option to access the slider",
            action = ScrollAction(
                targetDescription = "Brightness level option to access the slider",
                x1 = 18f,
                y1 = 819f,
                x2 = 702f,
                y2 = 819f,
                duration = 0.6f
            )
        )

        val result = VLMActionPostProcessor.correct(
            step = step,
            context = UIContext(
                overallTask = "Turn brightness to the max value.",
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
    fun `does not convert ordered apps scroll into unrelated display click`() {
        val step = VLMStep(
            observation = "settings home",
            thought = "scroll to find Apps",
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
                overallTask = DEFAULT_APPS_VERIFY_TASK,
                targetPackageName = "com.android.settings"
            ),
            currentXml = SETTINGS_WITH_DISPLAY_SUBTITLE_XML,
            currentPackageName = "com.android.settings",
            stepIndex = 1,
            displayWidth = 720,
            displayHeight = 1280
        )

        assertFalse(result.applied)
        val action = result.step.action as ScrollAction
        assertEquals("Settings list", action.targetDescription)
    }

    @Test
    fun `does not convert display scroll into unrelated apps click when display is absent`() {
        val step = VLMStep(
            observation = "settings home",
            thought = "scroll to find Display",
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
                overallTask = "From the Settings home screen, open Display settings, verify the Display page is visible, then finish.",
                targetPackageName = "com.android.settings"
            ),
            currentXml = SETTINGS_WITH_APPS_NO_DISPLAY_XML,
            currentPackageName = "com.android.settings",
            stepIndex = 1,
            displayWidth = 720,
            displayHeight = 1280
        )

        assertFalse(result.applied)
        val action = result.step.action as ScrollAction
        assertEquals("Settings list", action.targetDescription)
    }

    @Test
    fun `does not apply settings domain scroll correction by default`() {
        val step = VLMStep(
            observation = "settings home",
            thought = "open Apps",
            action = ClickAction(
                targetDescription = "Apps Assistant, recent apps, default apps LinearLayout",
                x = 360f,
                y = 977f
            )
        )

        val result = VLMActionPostProcessor.correct(
            step = step,
            context = UIContext(
                overallTask = "From the Settings home screen, open Display settings, verify the Display page is visible, then finish.",
                targetPackageName = "com.android.settings"
            ),
            currentXml = SETTINGS_WITH_APPS_NO_DISPLAY_XML,
            currentPackageName = "com.android.settings",
            stepIndex = 1,
            displayWidth = 720,
            displayHeight = 1280
        )

        assertFalse(result.applied)
        val action = result.step.action as ClickAction
        assertEquals("Apps Assistant, recent apps, default apps LinearLayout", action.targetDescription)
    }

    @Test
    fun `converts repeated ordered apps scroll into settings search click when row is absent`() {
        val step = VLMStep(
            observation = "settings home",
            thought = "scroll to find Apps",
            action = ScrollAction(
                targetDescription = "Scroll down to locate the Apps option in the Settings menu",
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
                overallTask = DEFAULT_APPS_VERIFY_TASK,
                targetPackageName = "com.android.settings"
            ),
            currentXml = SETTINGS_WITH_SEARCH_AND_DISPLAY_SUBTITLE_XML,
            currentPackageName = "com.android.settings",
            stepIndex = 2,
            displayWidth = 720,
            displayHeight = 1280
        )

        assertTrue(result.applied)
        assertEquals("semantic_search:scroll_to_search_affordance", result.reason)
        val action = result.step.action as ClickAction
        assertTrue(action.targetDescription, action.targetDescription.contains("Search settings", ignoreCase = true))
        assertTrue(action.y in 80f..184f)
    }

    @Test
    fun `redirects search input click to matching visible search result`() {
        val step = VLMStep(
            observation = "search results",
            thought = "open the System result",
            action = ClickAction(
                targetDescription = "System open_search_view_edit_text EditText",
                x = 352f,
                y = 112f
            )
        )

        val result = VLMActionPostProcessor.correct(
            step = step,
            context = UIContext(
                overallTask = "From the app home screen, open System, open Languages and input, verify the Languages page is visible, then finish.",
                targetPackageName = "com.example"
            ),
            currentXml = GENERIC_SEARCH_RESULTS_XML,
            currentPackageName = "com.example.search",
            stepIndex = 5,
            displayWidth = 720,
            displayHeight = 1280
        )

        assertTrue(result.applied)
        assertEquals("semantic_search:click_input_to_visible_result", result.reason)
        assertTrue(VLMActionControllerRegistry.registeredControllerIds().contains("semantic_search"))
        val action = result.step.action as ClickAction
        assertTrue(action.targetDescription, action.targetDescription.contains("System", ignoreCase = true))
        assertTrue(action.y in 230f..270f)
    }

    @Test
    fun `converts ordered apps scroll only into pending apps click when visible`() {
        val step = VLMStep(
            observation = "settings home",
            thought = "scroll to find Apps",
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
                overallTask = DEFAULT_APPS_VERIFY_TASK,
                targetPackageName = "com.android.settings"
            ),
            currentXml = SETTINGS_WITH_APPS_AND_DISPLAY_XML,
            currentPackageName = "com.android.settings",
            stepIndex = 1,
            displayWidth = 720,
            displayHeight = 1280
        )

        assertTrue(result.applied)
        assertEquals("ordered_goal_target_before_scroll", result.reason)
        val action = result.step.action as ClickAction
        assertTrue(action.targetDescription, action.targetDescription.contains("Apps", ignoreCase = true))
        assertFalse(action.targetDescription, action.targetDescription.contains("Display", ignoreCase = true))
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
    fun `does not apply settings subpage back correction by default`() {
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

        assertFalse(result.applied)
        assertTrue(result.step.action is ClickAction)
    }

    @Test
    fun `does not apply wifi toggle retargeting by default`() {
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

        assertFalse(result.applied)
        val action = result.step.action as ClickAction
        assertEquals("Toggle switch to turn off WiFi", action.targetDescription)
        assertEquals(442f, action.x, 0.01f)
        assertEquals(800f, action.y, 0.01f)
    }

    @Test
    fun `does not convert get state into settings toggle click by default`() {
        val step = VLMStep(
            observation = "bluetooth settings",
            thought = "蓝牙可能仍在开启，刷新状态",
            action = GetStateAction(
                reason = "当前页面为蓝牙设置页，需确认蓝牙是否已开启或需要点击开启"
            )
        )

        val result = VLMActionPostProcessor.correct(
            step = step,
            context = UIContext(
                overallTask = "当前在设置首页。打开蓝牙。如果蓝牙已经开启，就直接完成。",
                targetPackageName = "com.android.settings"
            ),
            currentXml = BLUETOOTH_SUBPAGE_WITH_CONNECTION_PREFERENCES_XML,
            currentPackageName = "com.android.settings",
            stepIndex = 2,
            displayWidth = 720,
            displayHeight = 1280
        )

        assertFalse(result.applied)
        assertTrue(result.step.action is GetStateAction)
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
    fun `uses only generic visible target matching when settings domain correction is disabled`() {
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
        assertEquals("generic_click_to_search_scroll", result.reason)
        val action = result.step.action as ScrollAction
        assertFalse(action.targetDescription, action.targetDescription.contains("Connected devices", ignoreCase = true))
    }

    @Test
    fun `does not back out of stale settings domain by default`() {
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

        assertFalse(result.applied)
        assertTrue(result.step.action is ClickAction)
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
    fun `does not retarget settings subpage toggle by default`() {
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

        assertFalse(result.applied)
        val action = result.step.action as ClickAction
        assertEquals("Wi-Fi toggle switch to turn off WiFi", action.targetDescription)
        assertEquals(360f, action.x, 0.01f)
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
    fun `does not rewrite finished into pending ordered target click`() {
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

        assertFalse(result.applied)
        assertTrue(result.step.action is FinishedAction)
    }

    @Test
    fun `types pending ordered target instead of clicking unrelated focused search result`() {
        val step = VLMStep(
            observation = "Settings search results show a recent System result.",
            thought = "click System",
            action = ClickAction(
                targetDescription = "System recent search result",
                x = 360f,
                y = 410f
            )
        )

        val result = VLMActionPostProcessor.correct(
            step = step,
            context = UIContext(
                overallTask = KEYBOARD_LINEAR_VERIFY_TASK,
                targetPackageName = "com.android.settings",
                trace = listOf(systemSettingsStep())
            ),
            currentXml = SETTINGS_SEARCH_FOCUSED_WITH_SYSTEM_RESULT_XML,
            currentPackageName = "com.google.android.settings.intelligence",
            stepIndex = 2,
            displayWidth = 720,
            displayHeight = 1280
        )

        assertTrue(result.applied)
        assertEquals("ordered_goal_search_query", result.reason)
        val action = result.step.action as TypeAction
        assertEquals("Languages & input", action.content)
    }

    @Test
    fun `aborts repeated system not responding wait loop`() {
        val step = VLMStep(
            observation = "Omnibot isn't responding dialog with Close app and Wait.",
            thought = "wait for the app",
            action = ClickAction(
                targetDescription = "Wait",
                x = 360f,
                y = 740f
            )
        )

        val result = VLMActionPostProcessor.correct(
            step = step,
            context = UIContext(
                overallTask = KEYBOARD_LINEAR_VERIFY_TASK,
                targetPackageName = "com.android.settings",
                trace = listOf(
                    UIStep(
                        observation = "Omnibot isn't responding dialog.",
                        thought = "wait once for the app to recover",
                        action = ClickAction(targetDescription = "Wait", x = 360f, y = 740f),
                        result = "clicked Wait",
                        observationXml = SYSTEM_ANR_DIALOG_XML
                    )
                )
            ),
            currentXml = SYSTEM_ANR_DIALOG_XML,
            currentPackageName = "android",
            stepIndex = 2,
            displayWidth = 720,
            displayHeight = 1280
        )

        assertTrue(result.applied)
        assertEquals("repeated_system_anr_wait", result.reason)
        assertTrue(result.step.action is AbortAction)
    }

    @Test
    fun `does not rewrite finished into back when pending ordered target is not visible`() {
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

        assertFalse(result.applied)
        assertTrue(result.step.action is FinishedAction)
    }

    @Test
    fun `allows finished when completed milestones prove linear ordered targets`() {
        val step = VLMStep(
            observation = "The On-screen keyboard page is visible.",
            thought = "the requested keyboard page is verified",
            action = FinishedAction(content = "The On-screen keyboard page is visible.")
        )

        val result = VLMActionPostProcessor.correct(
            step = step,
            context = UIContext(
                overallTask = KEYBOARD_LINEAR_VERIFY_TASK,
                targetPackageName = "com.android.settings",
                trace = listOf(systemSettingsStep()),
                completedMilestones = listOf(
                    "Navigated to System settings",
                    "Reached Languages & input",
                    "Opened On-screen keyboard",
                    "Verified On-screen keyboard page was visible"
                )
            ),
            currentXml = ON_SCREEN_KEYBOARD_PAGE_XML,
            currentPackageName = "com.android.settings",
            stepIndex = 5,
            displayWidth = 720,
            displayHeight = 1280
        )

        assertFalse(result.applied)
        assertTrue(result.step.action is FinishedAction)
    }

    @Test
    fun `does not rewrite finished based on later milestone backtracking`() {
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
                trace = listOf(appsStep(), defaultAppsStep(), phoneAppStep()),
                completedMilestones = listOf("Verified the Phone app page is visible")
            ),
            currentXml = PHONE_APP_DETAIL_XML,
            currentPackageName = "com.google.android.permissioncontroller",
            stepIndex = 3,
            displayWidth = 1080,
            displayHeight = 2400
        )

        assertFalse(result.applied)
        assertTrue(result.step.action is FinishedAction)
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
    fun `does not treat platform qualifier in app launch as pending ordered target`() {
        val step = VLMStep(
            observation = "The Display page is visible with Brightness level and Dark theme options shown.",
            thought = "the requested page is verified",
            summary = "Display settings page is confirmed open.",
            action = FinishedAction(content = "Display page is visible.")
        )

        val result = VLMActionPostProcessor.correct(
            step = step,
            context = UIContext(
                overallTask = "Open Android Settings, open Display settings, verify the Display page is visible, then finish.",
                targetPackageName = "com.android.settings",
                trace = listOf(
                    UIStep(
                        observation = "launcher",
                        thought = "open Android Settings",
                        action = OpenAppAction(packageName = "com.android.settings"),
                        result = "opened com.android.settings"
                    ),
                    displaySettingsStep()
                )
            ),
            currentXml = DISPLAY_SETTINGS_PAGE_XML,
            currentPackageName = "com.android.settings",
            stepIndex = 2,
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
    fun `allows finished when child target implies searched settings parent`() {
        val step = VLMStep(
            observation = "Default apps list shows Browser app and Phone app rows.",
            thought = "the requested Default apps page is verified",
            summary = "Default apps page verified with Browser app and Phone app rows visible.",
            action = FinishedAction(content = "Default apps page is visible with Browser app and Phone app rows.")
        )

        val result = VLMActionPostProcessor.correct(
            step = step,
            context = UIContext(
                overallTask = DEFAULT_APPS_VERIFY_TASK,
                targetPackageName = "com.android.settings",
                trace = listOf(defaultAppsStep())
            ),
            currentXml = DEFAULT_APPS_LIST_XML,
            currentPackageName = "com.google.android.permissioncontroller",
            stepIndex = 9,
            displayWidth = 1080,
            displayHeight = 2400
        )

        assertFalse(result.applied)
        assertTrue(result.step.action is FinishedAction)
    }

    @Test
    fun `does not treat pronoun open it as pending ordered target`() {
        val step = VLMStep(
            observation = "The About phone page shows Android version 13.",
            thought = "Android version is visible, so the verification target is satisfied.",
            summary = "Verified Android version 13 on the About phone page.",
            action = FinishedAction(content = "Android version 13 is visible on the About phone page.")
        )

        val result = VLMActionPostProcessor.correct(
            step = step,
            context = UIContext(
                overallTask = ABOUT_PHONE_VERIFY_TASK,
                targetPackageName = "com.android.settings",
                trace = listOf(aboutPhoneStep(), aboutPhoneVersionScrollStep())
            ),
            currentXml = ABOUT_PHONE_ANDROID_VERSION_XML,
            currentPackageName = "com.android.settings",
            stepIndex = 7,
            displayWidth = 720,
            displayHeight = 1280
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

        private const val DEFAULT_APPS_VERIFY_TASK =
            "From the Settings home screen, open Apps, open Default apps, verify the Default apps page is visible with Browser app or Phone app rows, then finish."

        private const val ABOUT_PHONE_VERIFY_TASK =
            "From the Settings home screen, scroll to About phone, open it, verify the About phone page title or Android version is visible, then finish."

        private const val KEYBOARD_LINEAR_VERIFY_TASK =
            "Open Android Settings, go to System, open Languages & input, open On-screen keyboard, verify the On-screen keyboard page is visible, then finish."

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

        private fun systemSettingsStep(): UIStep =
            UIStep(
                observation = "settings home",
                thought = "go to System",
                action = ClickAction(targetDescription = "System settings option", x = 360f, y = 1040f),
                result = "clicked System"
            )

        private fun aboutPhoneStep(): UIStep =
            UIStep(
                observation = "settings home",
                thought = "open About phone",
                action = ClickAction(targetDescription = "About emulated device", x = 360f, y = 968f),
                result = "clicked About emulated device"
            )

        private fun aboutPhoneVersionScrollStep(): UIStep =
            UIStep(
                observation = "About phone page",
                thought = "scroll to Android version",
                action = ScrollAction(
                    targetDescription = "scroll down to reveal Android version",
                    x1 = 360f,
                    y1 = 1178f,
                    x2 = 360f,
                    y2 = 384f
                ),
                result = "scrolled"
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

        private const val ABOUT_PHONE_ANDROID_VERSION_XML =
            """
            <hierarchy>
              <node bounds="[0,0][720,1280]">
                <node content-desc="Navigate up" clickable="true" focusable="true" enabled="true" bounds="[0,48][112,160]" />
                <node class="androidx.recyclerview.widget.RecyclerView" scrollable="true" focusable="true" enabled="true" bounds="[0,180][720,1232]">
                  <node text="SIM status" enabled="true" bounds="[48,208][246,262]" />
                  <node text="Model" enabled="true" bounds="[48,362][170,416]" />
                  <node text="sdk_gphone64_arm64" enabled="true" bounds="[48,416][423,462]" />
                  <node text="Android version" enabled="true" bounds="[48,540][387,594]" />
                  <node text="13" enabled="true" bounds="[48,594][88,640]" />
                  <node text="Device identifiers" enabled="true" bounds="[48,718][444,772]" />
                </node>
              </node>
            </hierarchy>
            """

        private const val ON_SCREEN_KEYBOARD_PAGE_XML =
            """
            <hierarchy>
              <node bounds="[0,0][720,1280]">
                <node content-desc="Navigate up" clickable="true" focusable="true" enabled="true" bounds="[0,48][112,160]" />
                <node text="On-screen keyboard" enabled="true" bounds="[144,88][552,160]" />
                <node class="androidx.recyclerview.widget.RecyclerView" focusable="true" enabled="true" bounds="[0,180][720,1232]">
                  <node text="Gboard" enabled="true" bounds="[48,220][240,274]" />
                  <node text="Google voice typing" enabled="true" bounds="[48,360][420,414]" />
                </node>
              </node>
            </hierarchy>
            """

        private const val SETTINGS_SEARCH_FOCUSED_WITH_SYSTEM_RESULT_XML =
            """
            <hierarchy>
              <node bounds="[0,0][720,1280]">
                <node text="" content-desc="Back" class="android.widget.ImageButton" clickable="true" focusable="true" enabled="true" bounds="[0,48][112,160]" />
                <node text="Search settings" class="android.widget.EditText" clickable="true" focusable="true" focused="true" editable="true" enabled="true" bounds="[112,48][680,160]" />
                <node class="androidx.recyclerview.widget.RecyclerView" scrollable="true" focusable="true" enabled="true" bounds="[0,180][720,1232]">
                  <node clickable="true" focusable="true" enabled="true" bounds="[0,310][720,460]">
                    <node text="System" enabled="true" bounds="[144,334][320,388]" />
                  </node>
                  <node clickable="true" focusable="true" enabled="true" bounds="[0,460][720,610]">
                    <node text="Storage" enabled="true" bounds="[144,484][330,538]" />
                  </node>
                </node>
              </node>
            </hierarchy>
            """

        private const val SYSTEM_ANR_DIALOG_XML =
            """
            <hierarchy>
              <node bounds="[18,444][702,836]" package="android">
                <node text="Omnibot isn't responding" class="android.widget.TextView" resource-id="android:id/alertTitle" enabled="true" bounds="[98,512][622,566]" />
                <node text="Close app" class="android.widget.Button" resource-id="android:id/aerr_close" enabled="true" clickable="true" focusable="true" bounds="[50,596][670,692]" />
                <node text="Wait" class="android.widget.Button" resource-id="android:id/aerr_wait" enabled="true" clickable="true" focusable="true" bounds="[50,692][670,788]" />
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

        private const val CONTACT_EDITOR_PHONE_FOCUSED_XML =
            """
            <hierarchy>
              <node id="0" class="android.widget.FrameLayout" enabled="true" bounds="[0,0][720,1280]">
                <node id="15" class="android.widget.ScrollView" enabled="true" focusable="true" scrollable="true" bounds="[0,176][720,1232]">
                  <node id="33" text="Alice" hint="First name" class="android.widget.EditText" enabled="true" clickable="true" long-clickable="true" focusable="true" editable="true" bounds="[104,603][608,716]" />
                  <node id="36" text="Smith" hint="Last name" class="android.widget.EditText" enabled="true" clickable="true" long-clickable="true" focusable="true" editable="true" bounds="[104,743][608,856]" />
                  <node id="56" text="Phone" class="android.widget.EditText" enabled="true" clickable="true" long-clickable="true" focusable="true" focused="true" editable="true" bounds="[104,1055][608,1168]" />
                  <node id="59" text="Mobile" content-desc="Mobile Phone" class="android.widget.Spinner" enabled="true" clickable="true" long-clickable="true" focusable="true" editable="true" bounds="[104,1195][446,1232]" />
                </node>
              </node>
            </hierarchy>
            """

        private const val CONTACT_EDITOR_PHONE_FILLED_XML =
            """
            <hierarchy>
              <node id="0" class="android.widget.FrameLayout" enabled="true" bounds="[0,0][720,1280]">
                <node id="15" class="android.widget.ScrollView" enabled="true" focusable="true" scrollable="true" bounds="[0,176][720,1232]">
                  <node id="33" text="Alice" hint="First name" class="android.widget.EditText" enabled="true" clickable="true" long-clickable="true" focusable="true" editable="true" bounds="[104,603][608,716]" />
                  <node id="36" text="Smith" hint="Last name" class="android.widget.EditText" enabled="true" clickable="true" long-clickable="true" focusable="true" editable="true" bounds="[104,743][608,856]" />
                  <node id="56" text="415-555-0130" hint="Phone" class="android.widget.EditText" enabled="true" clickable="true" long-clickable="true" focusable="true" focused="true" editable="true" bounds="[104,1055][608,1168]" />
                  <node id="59" text="Mobile" content-desc="Mobile Phone" class="android.widget.Spinner" enabled="true" clickable="true" long-clickable="true" focusable="true" editable="true" bounds="[104,1195][446,1232]" />
                  <node id="62" content-desc="Show dropdown menu" resource-id="com.google.android.contacts:id/text_input_end_icon" class="android.widget.ImageButton" enabled="true" clickable="true" focusable="true" checkable="true" bounds="[350,1203][446,1232]" />
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

        private const val SETTINGS_WITH_SEARCH_AND_DISPLAY_SUBTITLE_XML =
            """
            <hierarchy>
              <node bounds="[0,0][720,1280]">
                <node class="android.view.ViewGroup" resource-id="com.android.settings:id/search_action_bar" enabled="true" clickable="true" focusable="true" bounds="[32,80][688,184]">
                  <node text="Search settings" class="android.widget.TextView" resource-id="com.android.settings:id/search_action_bar_title" enabled="true" bounds="[152,105][421,159]" />
                </node>
                <node scrollable="true" bounds="[0,216][720,1232]">
                  <node clickable="true" focusable="true" bounds="[0,593][720,769]">
                    <node text="Sound &amp; vibration" bounds="[144,635][458,689]" />
                    <node text="Volume, haptics, Do Not Disturb" bounds="[144,689][538,727]" />
                  </node>
                  <node clickable="true" focusable="true" bounds="[0,769][720,945]">
                    <node text="Display" bounds="[144,811][274,865]" />
                    <node text="Dark theme, font size, brightness" bounds="[144,865][549,903]" />
                  </node>
                  <node clickable="true" focusable="true" bounds="[0,945][720,1121]">
                    <node text="Wallpaper &amp; style" bounds="[144,987][451,1041]" />
                    <node text="Colors, themed icons, app grid" bounds="[144,1041][521,1079]" />
                  </node>
                </node>
              </node>
            </hierarchy>
            """

        private const val GENERIC_SEARCH_RESULTS_XML =
            """
            <hierarchy>
              <node bounds="[0,0][720,1280]">
                <node class="android.widget.EditText" resource-id="com.example:id/search_src_text" text="System" enabled="true" editable="true" focused="true" focusable="true" bounds="[40,64][680,156]" />
                <node class="androidx.recyclerview.widget.RecyclerView" resource-id="com.example:id/results" enabled="true" scrollable="true" bounds="[0,176][720,1280]">
                  <node class="android.widget.LinearLayout" enabled="true" clickable="true" focusable="true" bounds="[0,210][720,298]">
                    <node text="System" class="android.widget.TextView" enabled="true" bounds="[72,226][240,270]" />
                  </node>
                  <node class="android.widget.LinearLayout" enabled="true" clickable="true" focusable="true" bounds="[0,298][720,386]">
                    <node text="Display" class="android.widget.TextView" enabled="true" bounds="[72,314][260,358]" />
                  </node>
                </node>
              </node>
            </hierarchy>
            """

        private const val SETTINGS_WITH_APPS_AND_DISPLAY_XML =
            """
            <hierarchy>
              <node bounds="[0,0][720,1280]">
                <node scrollable="true" bounds="[0,216][720,1232]">
                  <node clickable="true" focusable="true" bounds="[0,429][720,605]">
                    <node text="Battery" bounds="[144,471][283,525]" />
                    <node text="100%" bounds="[144,525][225,563]" />
                  </node>
                  <node clickable="true" focusable="true" bounds="[0,605][720,781]">
                    <node text="Apps" bounds="[144,647][246,701]" />
                    <node text="Default apps, screen time" bounds="[144,701][508,739]" />
                  </node>
                  <node clickable="true" focusable="true" bounds="[0,957][720,1133]">
                    <node text="Display" bounds="[144,999][274,1053]" />
                    <node text="Dark theme, font size, brightness" bounds="[144,1053][549,1091]" />
                  </node>
                </node>
              </node>
            </hierarchy>
            """

        private const val SETTINGS_WITH_APPS_NO_DISPLAY_XML =
            """
            <hierarchy>
              <node bounds="[0,0][720,1280]">
                <node scrollable="true" bounds="[0,537][720,1232]">
                  <node clickable="true" focusable="true" bounds="[0,537][720,713]">
                    <node text="Network &amp; internet" bounds="[144,579][475,633]" />
                    <node text="Mobile, Wi-Fi, hotspot" bounds="[144,633][412,671]" />
                  </node>
                  <node clickable="true" focusable="true" bounds="[0,713][720,889]">
                    <node text="Connected devices" bounds="[144,755][482,809]" />
                    <node text="Bluetooth, pairing" bounds="[144,809][361,847]" />
                  </node>
                  <node clickable="true" focusable="true" bounds="[0,889][720,1065]">
                    <node text="Apps" bounds="[144,931][235,985]" />
                    <node text="Assistant, recent apps, default apps" bounds="[144,985][586,1023]" />
                  </node>
                  <node clickable="true" focusable="true" bounds="[0,1065][720,1232]">
                    <node text="Notifications" bounds="[144,1107][373,1161]" />
                    <node text="Notification history, conversations" bounds="[144,1161][565,1199]" />
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

        private const val BLUETOOTH_SUBPAGE_WITH_CONNECTION_PREFERENCES_XML =
            """
            <hierarchy>
              <node bounds="[0,0][720,1280]">
                <node content-desc="Navigate up" clickable="true" focusable="true" enabled="true" bounds="[0,48][112,160]" />
                <node bounds="[0,406][720,1150]" class="androidx.recyclerview.widget.RecyclerView" scrollable="true" focusable="true" enabled="true">
                  <node clickable="true" focusable="true" enabled="true" bounds="[0,406][720,562]">
                    <node text="Pair new device" enabled="true" bounds="[144,438][423,492]" />
                    <node text="Bluetooth will turn on to pair" enabled="true" bounds="[144,492][491,530]" />
                  </node>
                  <node text="Saved devices" enabled="true" bounds="[48,610][688,648]" />
                  <node clickable="true" focusable="true" enabled="true" bounds="[0,820][720,976]">
                    <node text="Connection preferences" enabled="true" bounds="[48,852][472,906]" />
                    <node text="Bluetooth, Android Auto" enabled="true" bounds="[48,906][342,944]" />
                  </node>
                  <node text="Turn on Bluetooth to connect to other devices." enabled="true" bounds="[48,1064][618,1150]" />
                </node>
              </node>
            </hierarchy>
            """
    }
}
