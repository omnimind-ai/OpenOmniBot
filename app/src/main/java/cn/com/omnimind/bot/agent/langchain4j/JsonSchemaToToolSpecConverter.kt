package cn.com.omnimind.bot.agent.langchain4j

import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.model.chat.request.json.JsonArraySchema
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema
import dev.langchain4j.model.chat.request.json.JsonEnumSchema
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema
import dev.langchain4j.model.chat.request.json.JsonNumberSchema
import dev.langchain4j.model.chat.request.json.JsonObjectSchema
import dev.langchain4j.model.chat.request.json.JsonSchemaElement
import dev.langchain4j.model.chat.request.json.JsonStringSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Convert the existing OpenAI function-calling `parameters` JSON schemas
 * (defined in [cn.com.omnimind.bot.agent.AgentToolDefinitions]) into LangChain4j
 * typed [ToolSpecification] objects.
 *
 * The legacy schemas are JSON Schema draft-2020-12-ish: each property is a
 * JsonObject with `type`, optional `description`, optional `enum`, optional
 * `items` (for arrays), optional nested `properties`+`required` (for objects).
 *
 * Unsupported / unknown schema fragments (e.g. `oneOf`, `anyOf`, `$ref`) become
 * untyped string properties — better than throwing, given we do server-side
 * validation downstream in [cn.com.omnimind.bot.agent.AgentToolRegistry.validateArguments].
 */
internal object JsonSchemaToToolSpecConverter {

    /**
     * Build a [ToolSpecification] from the function-call descriptor as it appears
     * inside a single `tools[]` entry's `function` object:
     *
     * ```json
     * { "name": "...", "description": "...", "parameters": { ... } }
     * ```
     */
    fun toToolSpecification(
        name: String,
        description: String,
        parameters: JsonObject
    ): ToolSpecification {
        return ToolSpecification.builder()
            .name(name)
            .description(description)
            .parameters(toJsonObjectSchema(parameters))
            .build()
    }

    fun toJsonObjectSchema(schema: JsonObject): JsonObjectSchema {
        val builder = JsonObjectSchema.builder()
        (schema["description"] as? JsonPrimitive)?.contentOrNull?.let(builder::description)
        val properties = schema["properties"] as? JsonObject
        properties?.entries?.forEach { (propName, value) ->
            val propSchema = value as? JsonObject ?: return@forEach
            builder.addProperty(propName, toSchemaElement(propSchema))
        }
        val required = (schema["required"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }
        if (!required.isNullOrEmpty()) {
            builder.required(required)
        }
        (schema["additionalProperties"] as? JsonPrimitive)?.let { prim ->
            if (prim.contentOrNull == "true" || prim.contentOrNull == "false") {
                builder.additionalProperties(prim.contentOrNull == "true")
            }
        }
        return builder.build()
    }

    private fun toSchemaElement(schema: JsonObject): JsonSchemaElement {
        val enumValues = (schema["enum"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
        if (!enumValues.isNullOrEmpty()) {
            return JsonEnumSchema.builder()
                .description((schema["description"] as? JsonPrimitive)?.contentOrNull)
                .enumValues(enumValues)
                .build()
        }
        val typeNode = schema["type"]
        val type = when (typeNode) {
            is JsonPrimitive -> typeNode.contentOrNull?.trim()?.lowercase().orEmpty()
            else -> ""
        }
        val description = (schema["description"] as? JsonPrimitive)?.contentOrNull
        return when (type) {
            "string" -> JsonStringSchema.builder().description(description).build()
            "integer" -> JsonIntegerSchema.builder().description(description).build()
            "number" -> JsonNumberSchema.builder().description(description).build()
            "boolean" -> JsonBooleanSchema.builder().description(description).build()
            "array" -> {
                val itemsObj = schema["items"] as? JsonObject
                val itemsSchema = if (itemsObj != null) toSchemaElement(itemsObj) else JsonStringSchema.builder().build()
                JsonArraySchema.builder()
                    .description(description)
                    .items(itemsSchema)
                    .build()
            }
            "object" -> toJsonObjectSchema(schema)
            else -> JsonStringSchema.builder().description(description).build()
        }
    }
}
