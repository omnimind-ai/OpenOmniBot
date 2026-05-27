package cn.com.omnimind.assists.task.vlmserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VLMFirstStepOptimizerTest {
    @Test
    fun `injects live page signals for first step when target app is foreground`() {
        val context = UIContext(
            overallTask = "给张三发送消息",
            targetPackageName = "com.example.chat"
        )

        val enriched = VLMFirstStepOptimizer.enrichContext(
            context = context,
            currentXml = SAMPLE_XML,
            currentPackageName = "com.example.chat",
            stepIndex = 0
        )

        assertTrue(enriched.currentPageSummary.contains("前台包名: com.example.chat"))
        assertTrue(enriched.currentPageSummary.contains("发送"))
        assertTrue(enriched.currentPageSummary.contains("存在输入框"))
        assertTrue(enriched.currentPageSummary.contains("app_info: package_name=com.example.chat"))
        assertTrue(enriched.firstStepGuidance.contains("不要仅因前台包名和目标包名不一致就重复 open_app"))
        assertTrue(enriched.firstStepGuidance.contains("当前截图/XML"))
    }

    @Test
    fun `first step uses current page evidence when current package differs`() {
        val context = UIContext(
            overallTask = "打开聊天应用发送消息",
            targetPackageName = "com.example.chat"
        )

        val enriched = VLMFirstStepOptimizer.enrichContext(
            context = context,
            currentXml = SAMPLE_XML,
            currentPackageName = "com.android.launcher",
            stepIndex = 0
        )

        assertTrue(enriched.currentPageSummary.contains("app_info: package_name=com.android.launcher"))
        assertTrue(enriched.currentPageSummary.contains("app_info: target_package=com.example.chat"))
        assertTrue(enriched.currentPageSummary.contains("page/xml:"))
        assertTrue(enriched.firstStepGuidance.contains("不要仅因前台包名和目标包名不一致就重复 open_app"))
        assertTrue(enriched.firstStepGuidance.contains("当前截图/XML"))
    }

    @Test
    fun `first step highlights goal matched visible candidate`() {
        val context = UIContext(
            overallTask = "Open Connected devices settings",
            targetPackageName = "com.android.settings"
        )

        val enriched = VLMFirstStepOptimizer.enrichContext(
            context = context,
            currentXml = SETTINGS_XML,
            currentPackageName = "com.android.settings",
            stepIndex = 0
        )

        assertTrue(enriched.currentPageSummary.contains("任务相关首屏候选: Connected devices"))
        assertTrue(enriched.firstStepGuidance.contains("优先点击匹配候选"))
        assertTrue(enriched.firstStepGuidance.contains("不要默认点击列表第一项"))
    }

    @Test
    fun `first step honors explicit scroll request`() {
        val context = UIContext(
            overallTask = "Scroll down the settings list",
            targetPackageName = "com.android.settings"
        )

        val enriched = VLMFirstStepOptimizer.enrichContext(
            context = context,
            currentXml = SETTINGS_XML,
            currentPackageName = "com.android.settings",
            stepIndex = 0
        )

        assertTrue(enriched.firstStepGuidance.contains("优先对当前可滚动区域执行 scroll"))
        assertTrue(enriched.firstStepGuidance.contains("不要改点首个列表项"))
    }

    @Test
    fun `first step asks for larger scroll when target is not visible on scrollable page`() {
        val context = UIContext(
            overallTask = "Open Display settings",
            targetPackageName = "com.android.settings"
        )

        val enriched = VLMFirstStepOptimizer.enrichContext(
            context = context,
            currentXml = SETTINGS_XML,
            currentPackageName = "com.android.settings",
            stepIndex = 0
        )

        assertTrue(enriched.firstStepGuidance.contains("较大幅度 scroll"))
        assertTrue(enriched.firstStepGuidance.contains("不要短滑"))
    }

    @Test
    fun `first step treats brightness goal as display settings candidate`() {
        val context = UIContext(
            overallTask = "Turn brightness to the min value.",
            targetPackageName = "com.android.settings"
        )

        val enriched = VLMFirstStepOptimizer.enrichContext(
            context = context,
            currentXml = SETTINGS_WITH_DISPLAY_SUBTITLE_XML,
            currentPackageName = "com.android.settings",
            stepIndex = 0
        )

        assertTrue(enriched.currentPageSummary.contains("任务相关首屏候选"))
        assertTrue(enriched.currentPageSummary.contains("Display"))
        assertTrue(enriched.firstStepGuidance.contains("优先点击匹配候选"))
    }


    @Test
    fun `refreshes current page summary after first turn`() {
        val context = UIContext(
            overallTask = "给张三发送消息",
            targetPackageName = "com.example.chat",
            currentPageSummary = "old summary",
            firstStepGuidance = "old guidance",
            trace = listOf(
                UIStep(
                    observation = "done",
                    thought = "clicked",
                    action = ClickAction(targetDescription = "发送", x = 100f, y = 100f)
                )
            )
        )

        val enriched = VLMFirstStepOptimizer.enrichContext(
            context = context,
            currentXml = SAMPLE_XML,
            currentPackageName = "com.example.chat",
            stepIndex = 1
        )

        assertTrue(enriched.currentPageSummary.contains("app_info: package_name=com.example.chat"))
        assertTrue(enriched.currentPageSummary.contains("发送"))
        assertEquals("", enriched.firstStepGuidance)
        assertEquals("com.example.chat", enriched.currentPackageName)
    }

    companion object {
        private const val SAMPLE_XML =
            """
            <hierarchy>
              <node bounds="[0,0][1080,1920]">
                <node text="张三" bounds="[80,140][260,220]" clickable="true" />
                <node text="输入消息" bounds="[40,1700][820,1800]" focusable="true" editable="true" />
                <node text="发送" bounds="[880,1700][1040,1800]" clickable="true" />
                <node text="继续执行" bounds="[900,80][1060,160]" clickable="true" />
              </node>
            </hierarchy>
            """

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
                <node text="Apps" bounds="[144,931][235,985]" clickable="true" />
                <node text="Notifications" bounds="[144,1107][373,1161]" clickable="true" />
              </node>
            </hierarchy>
            """

        private const val SETTINGS_WITH_DISPLAY_SUBTITLE_XML =
            """
            <hierarchy>
              <node bounds="[0,0][720,1280]">
                <node scrollable="true" bounds="[0,216][720,1232]">
                  <node clickable="true" focusable="true" bounds="[0,957][720,1133]">
                    <node text="Display" bounds="[144,999][274,1053]" />
                    <node text="Dark theme, font size, brightness" bounds="[144,1053][549,1091]" />
                  </node>
                </node>
              </node>
            </hierarchy>
            """
    }
}
