package cn.com.omnimind.bot.runlog

import cn.com.omnimind.omniintelligence.models.ScrollDirection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    private class FakeBackend(
        private val beforeXml: String,
        private val afterXml: String,
    ) : OmniflowActionBackend {
        private var clicked = false

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
            clicked = true
        }

        override suspend fun pressHotKey(key: String) {
            clicked = true
        }

        override fun currentXml(): String = if (clicked) afterXml else beforeXml

        override fun currentPackageName(): String = "com.example"

        override fun currentActivityName(): String = "ExampleActivity"
    }

    companion object {
        private const val SOURCE_XML =
            "<hierarchy bounds=\"[0,0][1080,1920]\"><node bounds=\"[100,200][300,280]\" clickable=\"true\" enabled=\"true\" visible-to-user=\"true\" text=\"Open\" class=\"android.widget.Button\" resource-id=\"app:id/open\"/></hierarchy>"
        private const val AFTER_XML =
            "<hierarchy bounds=\"[0,0][1080,1920]\"><node bounds=\"[100,200][300,280]\" enabled=\"true\" visible-to-user=\"true\" text=\"Done\" class=\"android.widget.TextView\" resource-id=\"app:id/done\"/></hierarchy>"
    }
}
