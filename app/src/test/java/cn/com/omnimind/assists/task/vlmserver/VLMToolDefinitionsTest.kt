package cn.com.omnimind.assists.task.vlmserver

import cn.com.omnimind.baselib.i18n.PromptLocale
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        assertTrue(toolNames.contains("oob_function_run"))
        assertTrue(toolNames.contains("finished"))
    }

    @Test
    fun `oob function run is exposed as callable candidate tool with open arguments`() {
        val tool = VLMToolDefinitions.tools(PromptLocale.EN_US)
            .single { it.function.name == "oob_function_run" }
        val parameters = tool.function.parameters
        val properties = parameters["properties"]!!.jsonObject
        val argumentsSchema = properties["arguments"]!!.jsonObject

        assertTrue(tool.function.description.contains("current page context"))
        assertTrue(properties.containsKey("function_id"))
        assertTrue(argumentsSchema["additionalProperties"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `prompt guide does not teach the model to call wait`() {
        val promptGuide = VLMToolDefinitions.renderPromptGuide(PromptLocale.EN_US)

        assertFalse(promptGuide.contains("wait("))
        assertFalse(promptGuide.contains("waiting actions"))
        assertTrue(promptGuide.contains("input_text(target_description, content, element_index?, x, y)"))
        assertTrue(promptGuide.contains("oob_function_run(function_id, arguments?)"))
        assertTrue(promptGuide.contains("Coordinates are fallback only"))
        assertTrue(promptGuide.contains("page settling and stability detection are handled internally"))
    }
}
