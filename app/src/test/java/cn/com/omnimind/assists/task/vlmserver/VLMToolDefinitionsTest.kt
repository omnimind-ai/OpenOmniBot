package cn.com.omnimind.assists.task.vlmserver

import cn.com.omnimind.baselib.i18n.PromptLocale
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VLMToolDefinitionsTest {
    @Test
    fun `model visible VLM tools do not expose legacy wait action`() {
        val toolNames = VLMToolDefinitions.tools(PromptLocale.EN_US)
            .map { it.function.name }
            .toSet()

        assertFalse(toolNames.contains("wait"))
        assertTrue(toolNames.contains("click"))
        assertTrue(toolNames.contains("input_text"))
        assertTrue(toolNames.contains("type"))
        assertTrue(toolNames.contains("scroll"))
        assertTrue(toolNames.contains("finished"))
    }

    @Test
    fun `prompt guide does not teach the model to call wait`() {
        val promptGuide = VLMToolDefinitions.renderPromptGuide(PromptLocale.EN_US)

        assertFalse(promptGuide.contains("wait("))
        assertFalse(promptGuide.contains("waiting actions"))
        assertTrue(promptGuide.contains("input_text(target_description, content, x, y)"))
        assertTrue(promptGuide.contains("page settling and stability detection are handled internally"))
    }
}
