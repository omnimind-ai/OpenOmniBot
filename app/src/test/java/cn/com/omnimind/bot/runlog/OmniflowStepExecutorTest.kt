package cn.com.omnimind.bot.runlog

import cn.com.omnimind.omniintelligence.models.ScrollDirection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OmniflowStepExecutorTest {
    @Test
    fun `detects explicit omniflow steps`() {
        val step = mapOf(
            "executor" to "omniflow",
            "omniflow_action" to "click",
            "args" to mapOf("x" to 10, "y" to 20),
        )

        assertTrue(OmniflowStepExecutor.isOmniflowStep(step))
        assertEquals("click", OmniflowStepExecutor.actionNameForStep(step))
    }

    @Test
    fun `detects model free local actions without explicit executor`() {
        val step = mapOf(
            "model_free" to true,
            "tool" to "press_back",
            "args" to emptyMap<String, Any?>(),
        )

        assertTrue(OmniflowStepExecutor.isOmniflowStep(step))
        assertEquals("press_back", OmniflowStepExecutor.actionNameForStep(step))
    }

    @Test
    fun `does not classify unknown model free tool as omniflow`() {
        val step = mapOf(
            "model_free" to true,
            "tool" to "browser_use",
            "args" to emptyMap<String, Any?>(),
        )

        assertFalse(OmniflowStepExecutor.isOmniflowStep(step))
    }

    @Test
    fun `normalizes omniflow canonical aliases`() {
        assertEquals(
            "click",
            OmniflowStepExecutor.actionNameForStep(
                mapOf("model_free" to true, "tool" to "tap")
            )
        )
        assertEquals(
            "input_text",
            OmniflowStepExecutor.actionNameForStep(
                mapOf("model_free" to true, "tool" to "type_text")
            )
        )
        assertEquals(
            "finished",
            OmniflowStepExecutor.actionNameForStep(
                mapOf("model_free" to true, "tool" to "done")
            )
        )
    }

    @Test
    fun `detects omniflow canonical action names`() {
        val step = mapOf(
            "model_free" to true,
            "tool" to "input_text",
            "args" to mapOf("text" to "hello"),
        )

        assertTrue(OmniflowStepExecutor.isOmniflowStep(step))
        assertEquals("input_text", OmniflowStepExecutor.actionNameForStep(step))
    }

    @Test
    fun `identifies local replay actions that require accessibility`() {
        assertTrue(
            OmniflowStepExecutor.requiresAccessibility(
                mapOf("executor" to "omniflow", "tool" to "click")
            )
        )
        assertTrue(
            OmniflowStepExecutor.requiresAccessibility(
                mapOf("executor" to "omniflow", "tool" to "swipe")
            )
        )
        assertFalse(
            OmniflowStepExecutor.requiresAccessibility(
                mapOf("executor" to "omniflow", "tool" to "open_app")
            )
        )
        assertFalse(
            OmniflowStepExecutor.requiresAccessibility(
                mapOf("executor" to "omniflow", "tool" to "finished")
            )
        )
    }

    @Test
    fun `remap keeps non coordinate action args unchanged`() {
        val args = mapOf("content" to "hello")
        val result = OmniflowStepExecutor.remapStepArgs(
            mapOf(
                "executor" to "omniflow",
                "omniflow_action" to "type",
                "args" to args,
            )
        )

        assertEquals(args, result.args)
        assertTrue(result.meta.isEmpty())
    }

    @Test
    fun `execute verifies recorded after page postcondition`() = runBlocking {
        val backend = FakeBackend(beforeXml = SOURCE_XML, afterXml = AFTER_XML)
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = OmniflowStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "omniflow_action" to "click",
                    "coordinate_hook" to "omniflow",
                    "args" to mapOf("x" to 120, "y" to 240),
                    "source_context" to mapOf(
                        "src_ctx" to mapOf("page" to SOURCE_XML),
                        "dst_ctx" to mapOf(
                            "page" to AFTER_XML,
                            "package_name" to "com.example",
                        ),
                    ),
                    "postcondition" to mapOf(
                        "kind" to "recorded_after_page_similarity",
                        "min_score" to 0.1,
                    ),
                ),
                stepId = "step_1",
                stepTitle = "click open",
            )

            assertEquals(true, result["success"])
            val postcondition = result["postcondition"] as Map<*, *>
            assertEquals(true, postcondition["success"])
            assertEquals(true, postcondition["package_matched"])
        }
    }

    @Test
    fun `execute normalizes recorded package from expected xml`() = runBlocking {
        val backend = FakeBackend(
            beforeXml = SETTINGS_XML,
            afterXml = SETTINGS_APPS_XML,
            currentPackage = "com.android.settings",
        )
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = OmniflowStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "omniflow_action" to "click",
                    "args" to mapOf("x" to 360, "y" to 977),
                    "source_context" to mapOf(
                        "src_ctx" to mapOf("page" to SETTINGS_XML),
                        "dst_ctx" to mapOf(
                            "page" to SETTINGS_APPS_XML,
                            "package_name" to "cn.com.omnimind.bot.debug",
                        ),
                    ),
                    "postcondition" to mapOf(
                        "kind" to "recorded_after_page_similarity",
                        "min_score" to 0.1,
                    ),
                ),
                stepId = "step_package_infer",
                stepTitle = "click Apps",
            )

            assertEquals(true, result["success"])
            val postcondition = result["postcondition"] as Map<*, *>
            assertEquals(true, postcondition["success"])
            assertEquals("com.android.settings", postcondition["expected_package"])
            assertEquals("cn.com.omnimind.bot.debug", postcondition["recorded_expected_package"])
            assertEquals(true, postcondition["package_matched"])
        }
    }

    @Test
    fun `execute accepts android system package namespace alias`() = runBlocking {
        val backend = FakeBackend(
            beforeXml = SETTINGS_APPS_XML,
            afterXml = PERMISSION_CONTROLLER_XML,
            currentPackage = "com.google.android.permissioncontroller",
        )
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = OmniflowStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "omniflow_action" to "click",
                    "args" to mapOf("x" to 360, "y" to 640),
                    "source_context" to mapOf(
                        "src_ctx" to mapOf("page" to SETTINGS_APPS_XML),
                        "dst_ctx" to mapOf(
                            "page" to PERMISSION_CONTROLLER_XML,
                            "package_name" to "com.android.permissioncontroller",
                        ),
                    ),
                    "postcondition" to mapOf(
                        "kind" to "recorded_after_page_similarity",
                        "min_score" to 0.1,
                    ),
                ),
                stepId = "step_permission_controller",
                stepTitle = "click default apps",
            )

            assertEquals(true, result["success"])
            val postcondition = result["postcondition"] as Map<*, *>
            assertEquals(true, postcondition["success"])
            assertEquals(true, postcondition["package_matched"])
            assertEquals("com.android.permissioncontroller", postcondition["current_package"])
        }
    }

    @Test
    fun `execute skips click when current page already satisfies recorded after page`() = runBlocking {
        val backend = FakeBackend(
            beforeXml = SETTINGS_APPS_XML,
            afterXml = SETTINGS_APPS_XML,
            currentPackage = "com.android.settings",
        )
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = OmniflowStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "omniflow_action" to "click",
                    "coordinate_hook" to "omniflow",
                    "args" to mapOf("x" to 360, "y" to 977),
                    "source_context" to mapOf(
                        "src_ctx" to mapOf(
                            "page" to SETTINGS_XML,
                            "package_name" to "com.android.settings",
                        ),
                        "dst_ctx" to mapOf(
                            "page" to SETTINGS_APPS_XML,
                            "package_name" to "com.android.settings",
                        ),
                    ),
                    "postcondition" to mapOf(
                        "kind" to "recorded_after_page_similarity",
                        "min_score" to 0.1,
                    ),
                ),
                stepId = "step_already_done",
                stepTitle = "click Apps",
            )

            assertEquals(true, result["success"])
            assertEquals(true, result["skipped"])
            assertEquals("pre_action_postcondition_satisfied", result["skip_reason"])
            assertFalse(backend.clicked)
            val postcondition = result["postcondition"] as Map<*, *>
            assertEquals(true, postcondition["pre_action_satisfied"])
        }
    }

    @Test
    fun `execute allows package-only postcondition for transient settings search page`() = runBlocking {
        val backend = FakeBackend(
            beforeXml = SETTINGS_XML,
            afterXml = SETTINGS_SEARCH_CURRENT_XML,
            currentPackage = "com.google.android.settings.intelligence",
        )
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = OmniflowStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "omniflow_action" to "click",
                    "title" to "点击 Search settings search_action_bar ViewGroup",
                    "args" to mapOf(
                        "x" to 500,
                        "y" to 120,
                        "target_description" to "Search settings search_action_bar ViewGroup",
                    ),
                    "source_context" to mapOf(
                        "src_ctx" to mapOf("page" to SETTINGS_XML),
                        "dst_ctx" to mapOf(
                            "page" to SETTINGS_SEARCH_RECORDED_XML,
                            "package_name" to "com.google.android.settings.intelligence",
                        ),
                    ),
                    "postcondition" to mapOf(
                        "kind" to "recorded_after_page_similarity",
                        "min_score" to 0.95,
                        "allow_package_only_for_transient_search" to true,
                    ),
                ),
                stepId = "step_search",
                stepTitle = "click search settings",
            )

            assertEquals(true, result["success"])
            val postcondition = result["postcondition"] as Map<*, *>
            assertEquals(true, postcondition["success"])
            assertEquals("settings_search_transition", postcondition["semantic_fallback"])
        }
    }

    @Test
    fun `execute retries postcondition xml capture after app launch`() = runBlocking {
        val backend = FakeBackend(
            beforeXml = SOURCE_XML,
            afterXml = AFTER_XML,
            currentPackage = "com.example",
            missingXmlReadsAfterAction = 2,
        )
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = OmniflowStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "omniflow_action" to "open_app",
                    "args" to mapOf("package_name" to "com.example"),
                    "source_context" to mapOf(
                        "dst_ctx" to mapOf(
                            "page" to AFTER_XML,
                            "package_name" to "com.example",
                        ),
                    ),
                    "postcondition" to mapOf(
                        "kind" to "recorded_after_page_similarity",
                        "min_score" to 0.1,
                    ),
                ),
                stepId = "step_open_app",
                stepTitle = "open app",
            )

            assertEquals(true, result["success"])
            val postcondition = result["postcondition"] as Map<*, *>
            assertEquals(true, postcondition["success"])
        }
    }

    @Test
    fun `execute verifies open app from activity package when xml and package are blank`() = runBlocking {
        val backend = FakeBackend(
            beforeXml = "",
            afterXml = "",
            currentPackage = "",
            currentActivity = "com.android.settings/.Settings",
        )
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = OmniflowStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "omniflow_action" to "open_app",
                    "args" to mapOf("package_name" to "com.android.settings"),
                ),
                stepId = "step_open_app_activity_package",
                stepTitle = "open settings",
            )

            assertEquals(true, result["success"])
            val postcondition = result["postcondition"] as Map<*, *>
            assertEquals(true, postcondition["success"])
            assertEquals(true, postcondition["package_matched"])
            assertEquals("com.android.settings", postcondition["current_package"])
        }
    }

    @Test
    fun `execute passes reset task flag to fresh open app launch`() = runBlocking {
        val backend = FakeBackend(beforeXml = SOURCE_XML, afterXml = AFTER_XML)
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = OmniflowStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "omniflow_action" to "open_app",
                    "args" to mapOf(
                        "package_name" to "com.example",
                        "reset_task" to true,
                    ),
                ),
                stepId = "step_open_app_reset",
                stepTitle = "open app",
            )

            assertEquals(true, result["success"])
            assertEquals(listOf("com.example:true"), backend.launchRequests)
        }
    }

    @Test
    fun `execute retries coordinate remap when current xml is temporarily unavailable`() = runBlocking {
        val backend = FakeBackend(
            beforeXml = SCROLL_SOURCE_XML,
            afterXml = SCROLL_SOURCE_XML,
            missingXmlReadsBeforeAction = 2,
        )
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = OmniflowStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "omniflow_action" to "scroll",
                    "coordinate_hook" to "omniflow",
                    "args" to mapOf(
                        "x1" to 540,
                        "y1" to 1500,
                        "x2" to 540,
                        "y2" to 500,
                    ),
                    "source_context" to mapOf(
                        "src_ctx" to mapOf("page" to SCROLL_SOURCE_XML),
                    ),
                ),
                stepId = "step_scroll_after_launch",
                stepTitle = "scroll after open app",
            )

            assertEquals(true, result["success"])
        }
    }

    @Test
    fun `execute retries coordinate remap when current page is still settling`() = runBlocking {
        val backend = FakeBackend(
            beforeXml = SOURCE_XML,
            afterXml = SOURCE_XML,
            postActionXmls = listOf(STALE_DIALOG_XML, CLOCK_HOME_XML),
            postActionPackages = listOf("com.google.android.deskclock", "com.google.android.deskclock"),
        )
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = OmniflowStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "omniflow_action" to "click",
                    "coordinate_hook" to "omniflow",
                    "args" to mapOf("x" to 504, "y" to 1152),
                    "source_context" to mapOf(
                        "src_ctx" to mapOf("page" to CLOCK_HOME_XML),
                    ),
                ),
                stepId = "step_clock_tab",
                stepTitle = "click stopwatch tab",
            )

            assertEquals(true, result["success"])
        }
    }

    @Test
    fun `execute waits for matching postcondition instead of failing on stale foreground page`() = runBlocking {
        val backend = FakeBackend(
            beforeXml = SETTINGS_DISPLAY_XML,
            afterXml = SYSTEMUI_SLIDER_XML,
            currentPackage = "com.google.android.gms",
            postActionXmls = listOf(GMS_STALE_XML, SYSTEMUI_SLIDER_XML),
            postActionPackages = listOf("com.google.android.gms", "com.google.android.gms"),
        )
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = OmniflowStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "omniflow_action" to "click",
                    "args" to mapOf("x" to 360, "y" to 586),
                    "source_context" to mapOf(
                        "src_ctx" to mapOf("page" to SETTINGS_DISPLAY_XML),
                        "dst_ctx" to mapOf(
                            "page" to SYSTEMUI_SLIDER_XML,
                            "package_name" to "com.android.systemui",
                        ),
                    ),
                    "postcondition" to mapOf(
                        "kind" to "recorded_after_page_similarity",
                        "min_score" to 0.1,
                    ),
                ),
                stepId = "step_brightness_dialog",
                stepTitle = "click brightness level",
            )

            assertEquals(true, result["success"])
            val postcondition = result["postcondition"] as Map<*, *>
            assertEquals(true, postcondition["success"])
            assertEquals(true, postcondition["package_matched"])
            assertEquals("com.android.systemui", postcondition["current_package"])
        }
    }

    @Test
    fun `execute rejects settings page match based only on generic resource ids`() = runBlocking {
        val backend = FakeBackend(
            beforeXml = SETTINGS_XML,
            afterXml = SETTINGS_SECURITY_LIKE_XML,
            currentPackage = "com.android.settings",
        )
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            try {
                OmniflowStepExecutor.execute(
                    step = mapOf(
                        "executor" to "omniflow",
                        "omniflow_action" to "click",
                        "args" to mapOf("x" to 360, "y" to 749),
                        "source_context" to mapOf(
                            "src_ctx" to mapOf("page" to SETTINGS_XML),
                            "dst_ctx" to mapOf(
                                "page" to SETTINGS_DISPLAY_XML,
                                "package_name" to "com.android.settings",
                            ),
                        ),
                        "postcondition" to mapOf(
                            "kind" to "recorded_after_page_similarity",
                            "min_score" to 0.12,
                        ),
                    ),
                    stepId = "step_wrong_settings_page",
                    stepTitle = "click Display",
                )
                fail("Expected wrong Settings page postcondition to fail")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message.orEmpty().contains("Postcondition failed"))
            }
        }
    }

    @Test
    fun `execute still fails low similarity postcondition without transient search marker`() = runBlocking {
        val backend = FakeBackend(
            beforeXml = SETTINGS_XML,
            afterXml = SETTINGS_SEARCH_CURRENT_XML,
            currentPackage = "com.google.android.settings.intelligence",
        )
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            try {
                OmniflowStepExecutor.execute(
                    step = mapOf(
                        "executor" to "omniflow",
                        "omniflow_action" to "click",
                        "args" to mapOf("x" to 500, "y" to 120),
                        "source_context" to mapOf(
                            "src_ctx" to mapOf("page" to SETTINGS_XML),
                            "dst_ctx" to mapOf(
                                "page" to SETTINGS_SEARCH_RECORDED_XML,
                                "package_name" to "com.google.android.settings.intelligence",
                            ),
                        ),
                        "postcondition" to mapOf(
                            "kind" to "recorded_after_page_similarity",
                            "min_score" to 0.95,
                        ),
                    ),
                    stepId = "step_search",
                    stepTitle = "click search settings",
                )
                fail("Expected low-similarity postcondition to fail")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message.orEmpty().contains("Postcondition failed"))
            }
        }
    }

    private class FakeBackend(
        private val beforeXml: String,
        private val afterXml: String,
        private val currentPackage: String = "com.example",
        private val currentActivity: String = "ExampleActivity",
        private val missingXmlReadsBeforeAction: Int = 0,
        private val missingXmlReadsAfterAction: Int = 0,
        private val postActionXmls: List<String>? = null,
        private val postActionPackages: List<String>? = null,
    ) : OmniflowActionBackend {
        var clicked = false
            private set
        private var preActionXmlReadCount = 0
        private var actionXmlReadCount = 0
        val launchRequests = mutableListOf<String>()

        override fun isReady(): Boolean = true

        override suspend fun click(x: Float, y: Float) {
            clicked = true
        }

        override suspend fun longPress(x: Float, y: Float, durationMs: Long) {
            clicked = true
        }

        override suspend fun scroll(
            x: Float,
            y: Float,
            direction: ScrollDirection,
            distance: Float,
            durationMs: Long,
        ) {
            clicked = true
        }

        override suspend fun inputTextToFocusedNode(text: String) {
            clicked = true
        }

        override suspend fun launchApplication(packageName: String) {
            launchApplication(packageName, resetTask = false)
        }

        override suspend fun launchApplication(packageName: String, resetTask: Boolean) {
            clicked = true
            launchRequests += "$packageName:$resetTask"
        }

        override suspend fun pressHotKey(key: String) {
            clicked = true
        }

        override fun currentXml(): String? {
            if (!clicked) {
                if (preActionXmlReadCount < missingXmlReadsBeforeAction) {
                    preActionXmlReadCount += 1
                    return null
                }
                return beforeXml
            }
            if (actionXmlReadCount < missingXmlReadsAfterAction) {
                actionXmlReadCount += 1
                return null
            }
            postActionXmls?.let { xmls ->
                if (xmls.isNotEmpty()) {
                    val index = actionXmlReadCount.coerceAtMost(xmls.lastIndex)
                    actionXmlReadCount += 1
                    return xmls[index]
                }
            }
            return afterXml
        }

        override fun currentPackageName(): String {
            val packages = postActionPackages
            if (clicked && !packages.isNullOrEmpty()) {
                val index = (actionXmlReadCount - 1).coerceAtLeast(0).coerceAtMost(packages.lastIndex)
                return packages[index]
            }
            return currentPackage
        }

        override fun currentActivityName(): String = currentActivity
    }

    companion object {
        private const val SOURCE_XML =
            "<hierarchy bounds=\"[0,0][1080,1920]\"><node bounds=\"[100,200][300,280]\" clickable=\"true\" enabled=\"true\" visible-to-user=\"true\" text=\"Open\" class=\"android.widget.Button\" resource-id=\"app:id/open\"/></hierarchy>"
        private const val SCROLL_SOURCE_XML =
            "<hierarchy bounds=\"[0,0][1080,1920]\"><node bounds=\"[0,0][1080,1920]\" scrollable=\"true\" enabled=\"true\" visible-to-user=\"true\" class=\"androidx.recyclerview.widget.RecyclerView\" resource-id=\"app:id/list\"><node bounds=\"[100,300][900,380]\" enabled=\"true\" visible-to-user=\"true\" text=\"Network\" class=\"android.widget.TextView\" resource-id=\"android:id/title\"/></node></hierarchy>"
        private const val AFTER_XML =
            "<hierarchy bounds=\"[0,0][1080,1920]\"><node bounds=\"[100,200][300,280]\" enabled=\"true\" visible-to-user=\"true\" text=\"Done\" class=\"android.widget.TextView\" resource-id=\"app:id/done\"/></hierarchy>"
        private const val SETTINGS_XML =
            "<hierarchy bounds=\"[0,0][1080,1920]\"><node bounds=\"[30,40][1050,140]\" clickable=\"true\" enabled=\"true\" visible-to-user=\"true\" text=\"Search settings\" class=\"android.view.ViewGroup\" resource-id=\"com.android.settings:id/search_action_bar\"/></hierarchy>"
        private const val STALE_DIALOG_XML =
            "<hierarchy bounds=\"[0,0][720,1280]\"><node bounds=\"[199,66][640,157]\" clickable=\"true\" enabled=\"true\" visible-to-user=\"true\" class=\"android.view.ViewGroup\"><node bounds=\"[223,90][598,123]\" enabled=\"true\" visible-to-user=\"true\" text=\"Privacy\" class=\"android.widget.TextView\" resource-id=\"com.google.android.deskclock:id/body\"/></node></hierarchy>"
        private const val CLOCK_HOME_XML =
            "<hierarchy bounds=\"[0,0][720,1280]\"><node bounds=\"[0,0][720,1280]\" enabled=\"true\" visible-to-user=\"true\" class=\"android.widget.FrameLayout\"><node bounds=\"[0,176][720,1072]\" enabled=\"true\" focusable=\"true\" class=\"android.view.ViewGroup\" resource-id=\"com.google.android.deskclock:id/desk_clock_pager\"/><node bounds=\"[432,1072][576,1232]\" enabled=\"true\" clickable=\"true\" focusable=\"true\" content-desc=\"Stopwatch\" class=\"android.widget.FrameLayout\" resource-id=\"com.google.android.deskclock:id/tab_menu_stopwatch\"><node bounds=\"[433,1168][575,1209]\" enabled=\"true\" visible-to-user=\"true\" text=\"Stopwatch\" class=\"android.widget.TextView\" resource-id=\"com.google.android.deskclock:id/navigation_bar_item_small_label_view\"/></node></node></hierarchy>"
        private const val SETTINGS_APPS_XML =
            "<hierarchy bounds=\"[0,0][720,1280]\"><node bounds=\"[0,0][720,1232]\" enabled=\"true\" visible-to-user=\"true\" text=\"Apps\" class=\"android.widget.TextView\" resource-id=\"com.android.settings:id/content_parent\"/><node bounds=\"[48,594][273,648]\" enabled=\"true\" visible-to-user=\"true\" text=\"Default apps\" class=\"android.widget.TextView\" resource-id=\"android:id/title\"/></hierarchy>"
        private const val PERMISSION_CONTROLLER_XML =
            "<hierarchy bounds=\"[0,0][720,1280]\"><node bounds=\"[0,0][720,1232]\" enabled=\"true\" visible-to-user=\"true\" text=\"Default apps\" class=\"android.widget.TextView\" resource-id=\"com.android.permissioncontroller:id/content_parent\"/><node bounds=\"[144,438][368,492]\" enabled=\"true\" visible-to-user=\"true\" text=\"Browser app\" class=\"android.widget.TextView\" resource-id=\"android:id/title\"/></hierarchy>"
        private const val SETTINGS_SEARCH_RECORDED_XML =
            "<hierarchy bounds=\"[0,0][1080,1920]\"><node bounds=\"[20,40][1060,140]\" enabled=\"true\" visible-to-user=\"true\" text=\"Search settings\" class=\"android.widget.EditText\" resource-id=\"com.google.android.settings.intelligence:id/search_action_bar\"/></hierarchy>"
        private const val SETTINGS_SEARCH_CURRENT_XML =
            "<hierarchy bounds=\"[0,0][1080,1920]\"><node bounds=\"[40,220][1040,320]\" enabled=\"true\" visible-to-user=\"true\" text=\"No recent searches\" class=\"android.widget.TextView\" resource-id=\"com.google.android.settings.intelligence:id/empty\"/></hierarchy>"
        private const val SETTINGS_DISPLAY_XML =
            "<hierarchy bounds=\"[0,0][720,1280]\"><node bounds=\"[0,508][720,664]\" clickable=\"true\" enabled=\"true\" visible-to-user=\"true\" class=\"android.widget.LinearLayout\"><node bounds=\"[48,540][330,594]\" enabled=\"true\" visible-to-user=\"true\" text=\"Brightness level\" class=\"android.widget.TextView\" resource-id=\"android:id/title\"/><node bounds=\"[48,594][85,632]\" enabled=\"true\" visible-to-user=\"true\" text=\"0%\" class=\"android.widget.TextView\" resource-id=\"android:id/summary\"/></node></hierarchy>"
        private const val SYSTEMUI_SLIDER_XML =
            "<hierarchy bounds=\"[0,48][720,176]\"><node bounds=\"[32,64][688,160]\" enabled=\"true\" visible-to-user=\"true\" text=\"Display brightness\" class=\"android.widget.SeekBar\" resource-id=\"com.android.systemui:id/slider\"/></hierarchy>"
        private const val GMS_STALE_XML =
            "<hierarchy bounds=\"[0,0][720,1280]\"><node bounds=\"[48,540][688,594]\" enabled=\"true\" visible-to-user=\"true\" text=\"Google Play services\" class=\"android.widget.TextView\" resource-id=\"com.google.android.gms:id/title\"/></hierarchy>"
        private const val SETTINGS_SECURITY_LIKE_XML =
            "<hierarchy bounds=\"[0,0][720,1280]\"><node bounds=\"[0,406][720,1232]\" enabled=\"true\" visible-to-user=\"true\" class=\"androidx.recyclerview.widget.RecyclerView\" resource-id=\"com.android.settings:id/recycler_view\"><node bounds=\"[48,454][688,492]\" enabled=\"true\" visible-to-user=\"true\" text=\"Security status\" class=\"android.widget.TextView\" resource-id=\"android:id/title\"/><node bounds=\"[144,540][496,594]\" enabled=\"true\" visible-to-user=\"true\" text=\"Google Play Protect\" class=\"android.widget.TextView\" resource-id=\"android:id/title\"/><node bounds=\"[144,712][562,750]\" enabled=\"true\" visible-to-user=\"true\" text=\"No Google account on this device\" class=\"android.widget.TextView\" resource-id=\"android:id/summary\"/></node></hierarchy>"
    }
}
