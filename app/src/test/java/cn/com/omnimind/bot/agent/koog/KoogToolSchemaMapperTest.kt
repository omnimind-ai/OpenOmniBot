package cn.com.omnimind.bot.agent.koog

import ai.koog.agents.core.tools.ToolParameterType
import cn.com.omnimind.baselib.llm.ChatCompletionFunction
import cn.com.omnimind.baselib.llm.ChatCompletionTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KoogToolSchemaMapperTest {

    private fun schema(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun tool(name: String, parameters: String): ChatCompletionTool {
        return ChatCompletionTool(
            type = "function",
            function = ChatCompletionFunction(
                name = name,
                description = "$name description",
                parameters = schema(parameters)
            )
        )
    }

    @Test
    fun `simple string parameter is required`() {
        val descriptor = KoogToolSchemaMapper.convert(
            tool(
                name = "echo",
                parameters = """
                    {
                      "type": "object",
                      "properties": {
                        "text": { "type": "string", "description": "The text to echo back" }
                      },
                      "required": ["text"]
                    }
                """.trimIndent()
            )
        )

        assertEquals("echo", descriptor.name)
        assertEquals(1, descriptor.requiredParameters.size)
        assertTrue(descriptor.optionalParameters.isEmpty())
        val text = descriptor.requiredParameters.single()
        assertEquals("text", text.name)
        assertEquals("The text to echo back", text.description)
        assertEquals(ToolParameterType.String, text.type)
    }

    @Test
    fun `integer number boolean array map to correct primitives`() {
        val descriptor = KoogToolSchemaMapper.convert(
            tool(
                name = "scan",
                parameters = """
                    {
                      "type": "object",
                      "properties": {
                        "count":   { "type": "integer" },
                        "ratio":   { "type": "number" },
                        "enabled": { "type": "boolean" },
                        "tags":    { "type": "array", "items": { "type": "string" } }
                      }
                    }
                """.trimIndent()
            )
        )
        val byName = descriptor.optionalParameters.associateBy { it.name }
        assertEquals(ToolParameterType.Integer, byName.getValue("count").type)
        assertEquals(ToolParameterType.Float, byName.getValue("ratio").type)
        assertEquals(ToolParameterType.Boolean, byName.getValue("enabled").type)
        val tags = byName.getValue("tags").type
        assertTrue("tags is array", tags is ToolParameterType.List)
        assertEquals(ToolParameterType.String, (tags as ToolParameterType.List).itemsType)
    }

    @Test
    fun `enum strings become Enum type`() {
        val descriptor = KoogToolSchemaMapper.convert(
            tool(
                name = "set_mode",
                parameters = """
                    {
                      "type": "object",
                      "properties": {
                        "mode": { "type": "string", "enum": ["fast", "slow"] }
                      },
                      "required": ["mode"]
                    }
                """.trimIndent()
            )
        )
        val mode = descriptor.requiredParameters.single()
        assertTrue("mode is enum", mode.type is ToolParameterType.Enum)
        val entries = (mode.type as ToolParameterType.Enum).entries
        assertEquals(listOf("fast", "slow"), entries.toList())
    }

    @Test
    fun `nested object schema preserves required and properties`() {
        val descriptor = KoogToolSchemaMapper.convert(
            tool(
                name = "create_user",
                parameters = """
                    {
                      "type": "object",
                      "properties": {
                        "profile": {
                          "type": "object",
                          "properties": {
                            "name": { "type": "string" },
                            "age":  { "type": "integer" }
                          },
                          "required": ["name"]
                        }
                      },
                      "required": ["profile"]
                    }
                """.trimIndent()
            )
        )
        val profile = descriptor.requiredParameters.single()
        val obj = profile.type as ToolParameterType.Object
        assertEquals(listOf("name", "age"), obj.properties.map { it.name })
        assertEquals(listOf("name"), obj.requiredProperties)
        assertEquals(ToolParameterType.String, obj.properties[0].type)
        assertEquals(ToolParameterType.Integer, obj.properties[1].type)
    }

    @Test
    fun `properties without required end up in optional list`() {
        val descriptor = KoogToolSchemaMapper.convert(
            tool(
                name = "search",
                parameters = """
                    {
                      "type": "object",
                      "properties": {
                        "q":    { "type": "string" },
                        "page": { "type": "integer" }
                      }
                    }
                """.trimIndent()
            )
        )
        assertTrue("no required params", descriptor.requiredParameters.isEmpty())
        assertEquals(2, descriptor.optionalParameters.size)
        assertNotNull(descriptor.optionalParameters.firstOrNull { it.name == "q" })
        assertNotNull(descriptor.optionalParameters.firstOrNull { it.name == "page" })
    }

    @Test
    fun `unknown or missing type defaults to string`() {
        val type = KoogToolSchemaMapper.parameterTypeFromSchema(schema("""{ "type": "wat" }"""))
        assertEquals(ToolParameterType.String, type)
        val nullType = KoogToolSchemaMapper.parameterTypeFromSchema(schema("""{ }"""))
        assertEquals(ToolParameterType.String, nullType)
    }
}
