package cn.com.omnimind.bot.runlog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OobActionCodecTest {
    @Test
    fun `normalizes legacy names to canonical actions`() {
        assertEquals("click", OobActionCodec.canonicalActionForName("tap"))
        assertEquals("input_text", OobActionCodec.canonicalActionForName("set_text"))
        assertEquals("swipe", OobActionCodec.canonicalActionForName("scroll_down"))
        assertEquals("press_key", OobActionCodec.canonicalActionForName("press_back"))
        assertEquals("open_app", OobActionCodec.canonicalActionForName("launch_app"))
        assertEquals("finished", OobActionCodec.canonicalActionForName("done"))
    }

    @Test
    fun `derives implicit key args for back and home aliases`() {
        assertEquals(
            "back",
            OobActionCodec.argsForStep(
                mapOf("tool" to "press_back", "args" to emptyMap<String, Any?>())
            )["key"],
        )
        assertEquals(
            "home",
            OobActionCodec.argsForStep(
                mapOf("tool" to "press_home", "args" to emptyMap<String, Any?>())
            )["key"],
        )
    }

    @Test
    fun `redacts input text in action summaries`() {
        val summary = OobActionCodec.actionArgsSummary(
            actionType = "input_text",
            args = mapOf("text" to "secret value", "target_description" to "search box"),
            sourceAction = emptyMap(),
        )

        assertEquals("search box", summary["target_description"])
        assertEquals(true, summary["text_present"])
        assertEquals(12, summary["text_length"])
        assertTrue(!summary.containsKey("text"))
    }
}
