package cn.com.omnimind.assists.task.vlmserver

import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.baselib.llm.ChatCompletionFunction
import cn.com.omnimind.baselib.llm.ChatCompletionTool
import cn.com.omnimind.baselib.i18n.PromptLocale
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

object VLMToolDefinitions {
    data class ToolSpec(
        val name: String,
        val description: String,
        val parameters: JsonObject,
        val promptGuide: String
    )

    private fun currentLocale(): PromptLocale = AppLocaleManager.currentPromptLocale()

    private fun t(locale: PromptLocale, zh: String, en: String): String {
        return when (locale) {
            PromptLocale.ZH_CN -> zh
            PromptLocale.EN_US -> en
        }
    }

    private fun buildToolSpecs(locale: PromptLocale): List<ToolSpec> = listOf(
        ToolSpec(
            name = "click",
            description = t(locale, "点击一个可见目标。", "Tap a visible target."),
            parameters = objectSchema(
                properties = linkedMapOf(
                    "target_description" to stringSchema(
                        t(locale, "要点击的目标描述。", "Description of the target to tap.")
                    ),
                    "x" to coordinateNumberSchema(
                        t(locale, "点击位置的 X 坐标。", "X coordinate of the tap target.")
                    ),
                    "y" to coordinateNumberSchema(
                        t(locale, "点击位置的 Y 坐标。", "Y coordinate of the tap target.")
                    )
                ),
                required = listOf("target_description", "x", "y")
            ),
            promptGuide = t(
                locale,
                "- click(target_description, x, y): 点击一个可见目标。",
                "- click(target_description, x, y): Tap a visible target."
            )
        ),
        ToolSpec(
            name = "type",
            description = t(locale, "在当前输入焦点中输入文本。", "Type text into the current focused input."),
            parameters = objectSchema(
                properties = linkedMapOf(
                    "content" to stringSchema(
                        t(locale, "要输入的文本内容。", "Text content to type.")
                    )
                ),
                required = listOf("content")
            ),
            promptGuide = t(
                locale,
                "- type(content): 在当前输入框输入文本。",
                "- type(content): Type text into the current input box."
            )
        ),
        ToolSpec(
            name = "scroll",
            description = t(locale, "从起点滑动到终点。", "Swipe from the start point to the end point."),
            parameters = objectSchema(
                properties = linkedMapOf(
                    "target_description" to stringSchema(
                        t(locale, "本次滚动想浏览或定位的目标描述。", "Description of what this scroll action is trying to browse or locate.")
                    ),
                    "x1" to coordinateNumberSchema(t(locale, "起点 X 坐标。", "Start X coordinate.")),
                    "y1" to coordinateNumberSchema(t(locale, "起点 Y 坐标。", "Start Y coordinate.")),
                    "x2" to coordinateNumberSchema(t(locale, "终点 X 坐标。", "End X coordinate.")),
                    "y2" to coordinateNumberSchema(t(locale, "终点 Y 坐标。", "End Y coordinate.")),
                    "duration" to numberSchema(t(locale, "滑动时长，单位秒。", "Swipe duration in seconds."))
                ),
                required = listOf("target_description", "x1", "y1", "x2", "y2")
            ),
            promptGuide = t(
                locale,
                "- scroll(target_description, x1, y1, x2, y2, duration?): 在屏幕上滑动。",
                "- scroll(target_description, x1, y1, x2, y2, duration?): Swipe on the screen."
            )
        ),
        ToolSpec(
            name = "long_press",
            description = t(locale, "长按一个目标。", "Long-press a target."),
            parameters = objectSchema(
                properties = linkedMapOf(
                    "target_description" to stringSchema(
                        t(locale, "要长按的目标描述。", "Description of the target to long-press.")
                    ),
                    "x" to coordinateNumberSchema(t(locale, "长按位置的 X 坐标。", "X coordinate of the long press.")),
                    "y" to coordinateNumberSchema(t(locale, "长按位置的 Y 坐标。", "Y coordinate of the long press."))
                ),
                required = listOf("target_description", "x", "y")
            ),
            promptGuide = t(
                locale,
                "- long_press(target_description, x, y): 长按一个目标。",
                "- long_press(target_description, x, y): Long-press a target."
            )
        ),
        ToolSpec(
            name = "open_app",
            description = t(locale, "打开指定应用。", "Open a specific app."),
            parameters = objectSchema(
                properties = linkedMapOf(
                    "package_name" to stringSchema(
                        t(locale, "目标应用的 Android package name。", "Android package name of the target app.")
                    )
                ),
                required = listOf("package_name")
            ),
            promptGuide = t(
                locale,
                "- open_app(package_name): 打开指定应用。",
                "- open_app(package_name): Open a specific app."
            )
        ),
        ToolSpec(
            name = "press_home",
            description = t(locale, "回到桌面。", "Go to the home screen."),
            parameters = objectSchema(),
            promptGuide = t(locale, "- press_home(): 回到桌面。", "- press_home(): Go to the home screen.")
        ),
        ToolSpec(
            name = "press_back",
            description = t(locale, "返回上一级。", "Go back one level."),
            parameters = objectSchema(),
            promptGuide = t(locale, "- press_back(): 返回上一级。", "- press_back(): Go back one level.")
        ),
        ToolSpec(
            name = "hot_key",
            description = t(locale, "发送一个受支持的快捷键。", "Send a supported hot key."),
            parameters = objectSchema(
                properties = linkedMapOf(
                    "key" to enumSchema(
                        description = t(locale, "当前受支持的快捷键。", "Supported hot keys."),
                        values = listOf("ENTER", "BACK", "HOME")
                    )
                ),
                required = listOf("key")
            ),
            promptGuide = t(
                locale,
                "- hot_key(key): 兼容 ENTER / BACK / HOME，但系统导航优先使用 press_back 或 press_home。",
                "- hot_key(key): Supports ENTER / BACK / HOME, but prefer press_back or press_home for system navigation."
            )
        ),
        ToolSpec(
            name = "finished",
            description = t(locale, "任务真正完成时结束。", "End the task only when it is truly complete."),
            parameters = objectSchema(
                properties = linkedMapOf(
                    "content" to stringSchema(
                        t(locale, "给用户的最终完成说明，可为空。", "Final completion note for the user. May be empty.")
                    )
                )
            ),
            promptGuide = t(
                locale,
                "- finished(content?): 仅在任务真正完成时调用。",
                "- finished(content?): Call only when the task is truly complete."
            )
        ),
        ToolSpec(
            name = "info",
            description = t(locale, "向用户询问或请求手动协助。", "Ask the user a question or request manual help."),
            parameters = objectSchema(
                properties = linkedMapOf(
                    "value" to stringSchema(
                        t(locale, "你要问用户的问题或需要用户执行的说明。", "Question to ask the user or instructions for the user to perform.")
                    )
                ),
                required = listOf("value")
            ),
            promptGuide = t(
                locale,
                "- info(value): 询问用户或请求用户协助。",
                "- info(value): Ask the user for information or manual assistance."
            )
        ),
        ToolSpec(
            name = "feedback",
            description = t(locale, "反馈当前上下文与目标不匹配。", "Report that the current context does not match the goal."),
            parameters = objectSchema(
                properties = linkedMapOf(
                    "value" to stringSchema(t(locale, "反馈原因。", "Reason for the feedback."))
                ),
                required = listOf("value")
            ),
            promptGuide = t(
                locale,
                "- feedback(value): 请求上层重新规划。",
                "- feedback(value): Ask the upper layer to re-plan."
            )
        ),
        ToolSpec(
            name = "abort",
            description = t(locale, "任务无法继续时终止。", "Abort when the task cannot continue."),
            parameters = objectSchema(
                properties = linkedMapOf(
                    "value" to stringSchema(t(locale, "终止任务的原因。", "Reason for aborting the task."))
                )
            ),
            promptGuide = t(
                locale,
                "- abort(value?): 在任务无法继续时终止。",
                "- abort(value?): Abort when the task cannot continue."
            )
        ),
        ToolSpec(
            name = "require_user_choice",
            description = t(locale, "让用户在若干选项中选择一个。", "Ask the user to choose one option from a list."),
            parameters = objectSchema(
                properties = linkedMapOf(
                    "options" to stringArraySchema(
                        t(locale, "可供用户选择的选项列表。", "List of options the user can choose from.")
                    ),
                    "prompt" to stringSchema(
                        t(locale, "要求用户做选择的提示文案。", "Prompt shown to the user when asking for a choice.")
                    )
                ),
                required = listOf("options", "prompt")
            ),
            promptGuide = t(
                locale,
                "- require_user_choice(options, prompt): 让用户做互斥选择。",
                "- require_user_choice(options, prompt): Ask the user to make a mutually exclusive choice."
            )
        ),
        ToolSpec(
            name = "require_user_confirmation",
            description = t(locale, "让用户确认当前状态后继续。", "Ask the user to confirm the current state before continuing."),
            parameters = objectSchema(
                properties = linkedMapOf(
                    "prompt" to stringSchema(
                        t(locale, "要求用户确认的提示文案。", "Prompt asking the user for confirmation.")
                    )
                ),
                required = listOf("prompt")
            ),
            promptGuide = t(
                locale,
                "- require_user_confirmation(prompt): 让用户确认后继续。",
                "- require_user_confirmation(prompt): Ask the user to confirm before continuing."
            )
        )
    )

    private fun toolSpecs(locale: PromptLocale = currentLocale()): List<ToolSpec> {
        return buildToolSpecs(locale)
    }

    fun tools(locale: PromptLocale = currentLocale()): List<ChatCompletionTool> {
        return toolSpecs(locale).map { spec ->
            ChatCompletionTool(
                function = ChatCompletionFunction(
                    name = spec.name,
                    description = spec.description,
                    parameters = spec.parameters
                )
            )
        }
    }

    fun renderPromptGuide(locale: PromptLocale = currentLocale()): String {
        val guides = toolSpecs(locale).joinToString(separator = "\n") { it.promptGuide }
        return buildString {
            appendLine(guides)
            append(
                t(
                    locale,
                    "注意：所有 function.arguments 必须是严格合法的 JSON object。坐标必须分别写入 x / y / x1 / y1 / x2 / y2 字段，不要写成 \"x\": 827, 76 这类非法格式。不要返回停留、延时或空操作类动作；页面停留和稳定检测由系统内部处理。",
                    "Important: every function.arguments value must be a strictly valid JSON object. Coordinates must be written into x / y / x1 / y1 / x2 / y2 as separate scalar fields. Do not emit invalid forms such as \"x\": 827, 76. Do not return idle, delay, or no-op actions; page settling and stability detection are handled internally."
                )
            )
        }
    }

    fun responseContract(locale: PromptLocale = currentLocale()): String {
        return when (locale) {
            PromptLocale.ZH_CN ->
                """{"observation":"当前界面的关键状态","thought":"为什么要执行这个工具","summary":"执行完本步后新的历史总结"}"""
            PromptLocale.EN_US ->
                """{"observation":"key state of the current screen","thought":"why this tool should be executed","summary":"updated running summary after this step"}"""
        }
    }

    fun toolSpec(name: String, locale: PromptLocale = currentLocale()): ToolSpec? =
        toolSpecs(locale).firstOrNull { it.name == name }

    fun propertiesFor(toolName: String, locale: PromptLocale = currentLocale()): Map<String, JsonObject> {
        val properties = toolSpec(toolName, locale)?.parameters?.get("properties") as? JsonObject ?: return emptyMap()
        return properties.mapValues { (_, value) -> value as? JsonObject ?: JsonObject(emptyMap()) }
    }

    fun requiredFieldsFor(toolName: String, locale: PromptLocale = currentLocale()): List<String> {
        val required = toolSpec(toolName, locale)?.parameters?.get("required") as? JsonArray ?: return emptyList()
        return required.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
    }

    fun normalizeArguments(toolName: String, arguments: JsonObject): JsonObject {
        if (arguments.isEmpty()) return arguments
        val normalized = arguments.toMutableMap()
        when (toolName) {
            "click", "long_press" -> normalizePointArguments(normalized)
            "scroll" -> normalizeScrollArguments(normalized)
        }
        return JsonObject(normalized)
    }

    fun coerceArguments(toolName: String, arguments: JsonObject): JsonObject {
        val properties = propertiesFor(toolName)
        if (properties.isEmpty() || arguments.isEmpty()) return arguments

        val normalized = linkedMapOf<String, JsonElement>()
        arguments.entries.forEach { (field, value) ->
            val schema = properties[field]
            normalized[field] = if (schema != null) {
                coerceValue(value, schema)
            } else {
                value
            }
        }
        return JsonObject(normalized)
    }

    fun validateArguments(toolName: String, arguments: JsonObject) {
        val properties = propertiesFor(toolName)
        val requiredFields = requiredFieldsFor(toolName)
        requiredFields.forEach { field ->
            if (arguments[field] == null || arguments[field] is JsonNull) {
                throw IllegalArgumentException("Tool $toolName missing required argument: $field")
            }
        }

        arguments.entries.forEach { (field, value) ->
            val schema = properties[field] ?: return@forEach
            val expectedType = schema["type"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (expectedType.isNotEmpty() && !matchesType(expectedType, value)) {
                val coordinateHint = if (expectedType == "number" && isCoordinateField(field)) {
                    " Coordinate fields must be a single numeric scalar, not [x,y], objects, or tuples."
                } else {
                    ""
                }
                throw IllegalArgumentException(
                    "Tool $toolName argument $field expected $expectedType but got ${describeType(value)}.$coordinateHint"
                )
            }
            val enumValues = (schema["enum"] as? JsonArray).orEmpty()
            if (enumValues.isNotEmpty()) {
                val raw = (value as? JsonPrimitive)?.contentOrNull
                if (raw == null || enumValues.none { it.jsonPrimitive.contentOrNull == raw }) {
                    throw IllegalArgumentException(
                        "Tool $toolName argument $field must be one of ${
                            enumValues.joinToString(",") { it.toString() }
                        }"
                    )
                }
            }
        }
    }

    private fun normalizePointArguments(arguments: MutableMap<String, JsonElement>) {
        extractPoint(arguments["x"])?.let { (x, y) ->
            arguments["x"] = buildNumericPrimitive(x)
            if (extractScalarNumber(arguments["y"]) == null) {
                arguments["y"] = buildNumericPrimitive(y)
            }
        }
        extractPoint(arguments["y"])?.let { (x, y) ->
            if (extractScalarNumber(arguments["x"]) == null) {
                arguments["x"] = buildNumericPrimitive(x)
            }
            arguments["y"] = buildNumericPrimitive(y)
        }
        if (extractScalarNumber(arguments["x"]) != null && extractScalarNumber(arguments["y"]) != null) {
            return
        }

        POINT_ALIAS_FIELDS.firstNotNullOfOrNull { alias ->
            extractPoint(arguments[alias])
        }?.let { (x, y) ->
            if (extractScalarNumber(arguments["x"]) == null) {
                arguments["x"] = buildNumericPrimitive(x)
            }
            if (extractScalarNumber(arguments["y"]) == null) {
                arguments["y"] = buildNumericPrimitive(y)
            }
        }
    }

    private fun normalizeScrollArguments(arguments: MutableMap<String, JsonElement>) {
        extractPoint(arguments["x1"])?.let { (x, y) ->
            arguments["x1"] = buildNumericPrimitive(x)
            if (extractScalarNumber(arguments["y1"]) == null) {
                arguments["y1"] = buildNumericPrimitive(y)
            }
        }
        extractPoint(arguments["x2"])?.let { (x, y) ->
            arguments["x2"] = buildNumericPrimitive(x)
            if (extractScalarNumber(arguments["y2"]) == null) {
                arguments["y2"] = buildNumericPrimitive(y)
            }
        }

        if (hasCompleteScrollCoordinates(arguments)) {
            return
        }

        RANGE_ALIAS_FIELDS.firstNotNullOfOrNull { alias ->
            extractRange(arguments[alias])
        }?.let { (x1, y1, x2, y2) ->
            if (extractScalarNumber(arguments["x1"]) == null) {
                arguments["x1"] = buildNumericPrimitive(x1)
            }
            if (extractScalarNumber(arguments["y1"]) == null) {
                arguments["y1"] = buildNumericPrimitive(y1)
            }
            if (extractScalarNumber(arguments["x2"]) == null) {
                arguments["x2"] = buildNumericPrimitive(x2)
            }
            if (extractScalarNumber(arguments["y2"]) == null) {
                arguments["y2"] = buildNumericPrimitive(y2)
            }
        }

        if (hasCompleteScrollCoordinates(arguments)) {
            return
        }

        val startPoint = RANGE_START_ALIAS_FIELDS.firstNotNullOfOrNull { alias ->
            extractPoint(arguments[alias])
        }
        val endPoint = RANGE_END_ALIAS_FIELDS.firstNotNullOfOrNull { alias ->
            extractPoint(arguments[alias])
        }
        if (startPoint != null && endPoint != null) {
            if (extractScalarNumber(arguments["x1"]) == null) {
                arguments["x1"] = buildNumericPrimitive(startPoint.first)
            }
            if (extractScalarNumber(arguments["y1"]) == null) {
                arguments["y1"] = buildNumericPrimitive(startPoint.second)
            }
            if (extractScalarNumber(arguments["x2"]) == null) {
                arguments["x2"] = buildNumericPrimitive(endPoint.first)
            }
            if (extractScalarNumber(arguments["y2"]) == null) {
                arguments["y2"] = buildNumericPrimitive(endPoint.second)
            }
        }
    }

    private fun hasCompleteScrollCoordinates(arguments: Map<String, JsonElement>): Boolean {
        return extractScalarNumber(arguments["x1"]) != null &&
            extractScalarNumber(arguments["y1"]) != null &&
            extractScalarNumber(arguments["x2"]) != null &&
            extractScalarNumber(arguments["y2"]) != null
    }

    private fun extractPoint(value: JsonElement?): Pair<Double, Double>? {
        return when (value) {
            is JsonArray -> {
                if (value.size < 2) return null
                val x = extractScalarNumber(value[0]) ?: return null
                val y = extractScalarNumber(value[1]) ?: return null
                x to y
            }

            is JsonObject -> {
                val x = extractScalarNumber(value["x"]) ?: return null
                val y = extractScalarNumber(value["y"]) ?: return null
                x to y
            }

            is JsonPrimitive -> extractPointFromString(value.contentOrNull)
            else -> null
        }
    }

    private fun extractRange(value: JsonElement?): ScrollCoordinates? {
        return when (value) {
            is JsonArray -> {
                if (value.size >= 4) {
                    val x1 = extractScalarNumber(value[0]) ?: return null
                    val y1 = extractScalarNumber(value[1]) ?: return null
                    val x2 = extractScalarNumber(value[2]) ?: return null
                    val y2 = extractScalarNumber(value[3]) ?: return null
                    return ScrollCoordinates(x1, y1, x2, y2)
                }
                if (value.size >= 2) {
                    val start = extractPoint(value[0]) ?: return null
                    val end = extractPoint(value[1]) ?: return null
                    return ScrollCoordinates(start.first, start.second, end.first, end.second)
                }
                null
            }

            is JsonObject -> {
                val direct = buildScrollCoordinates(
                    x1 = extractScalarNumber(value["x1"]),
                    y1 = extractScalarNumber(value["y1"]),
                    x2 = extractScalarNumber(value["x2"]),
                    y2 = extractScalarNumber(value["y2"])
                )
                if (direct != null) return direct

                val start = RANGE_START_ALIAS_FIELDS.firstNotNullOfOrNull { alias ->
                    extractPoint(value[alias])
                }
                val end = RANGE_END_ALIAS_FIELDS.firstNotNullOfOrNull { alias ->
                    extractPoint(value[alias])
                }
                if (start != null && end != null) {
                    return ScrollCoordinates(start.first, start.second, end.first, end.second)
                }
                null
            }

            is JsonPrimitive -> extractRangeFromString(value.contentOrNull)
            else -> null
        }
    }

    private fun extractPointFromString(raw: String?): Pair<Double, Double>? {
        val normalized = raw?.trim().orEmpty()
        if (normalized.isEmpty()) return null
        if ((normalized.startsWith("[") && normalized.endsWith("]")) ||
            (normalized.startsWith("{") && normalized.endsWith("}"))
        ) {
            val parsed = runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(normalized)
            }.getOrNull()
            return extractPoint(parsed)
        }
        val numbers = NUMBER_REGEX.findAll(normalized).mapNotNull { it.value.toDoubleOrNull() }.toList()
        if (numbers.size < 2) return null
        return numbers[0] to numbers[1]
    }

    private fun extractRangeFromString(raw: String?): ScrollCoordinates? {
        val normalized = raw?.trim().orEmpty()
        if (normalized.isEmpty()) return null
        if ((normalized.startsWith("[") && normalized.endsWith("]")) ||
            (normalized.startsWith("{") && normalized.endsWith("}"))
        ) {
            val parsed = runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(normalized)
            }.getOrNull()
            return extractRange(parsed)
        }
        val numbers = NUMBER_REGEX.findAll(normalized).mapNotNull { it.value.toDoubleOrNull() }.toList()
        if (numbers.size < 4) return null
        return ScrollCoordinates(numbers[0], numbers[1], numbers[2], numbers[3])
    }

    private fun buildScrollCoordinates(
        x1: Double?,
        y1: Double?,
        x2: Double?,
        y2: Double?
    ): ScrollCoordinates? {
        if (x1 == null || y1 == null || x2 == null || y2 == null) return null
        return ScrollCoordinates(x1, y1, x2, y2)
    }

    private fun extractScalarNumber(value: JsonElement?): Double? {
        return when (value) {
            is JsonPrimitive -> value.contentOrNull?.trim()?.toDoubleOrNull()
            else -> null
        }
    }

    private fun isCoordinateField(field: String): Boolean {
        return field == "x" || field == "y" || field == "x1" || field == "y1" || field == "x2" || field == "y2"
    }

    private fun coerceValue(value: JsonElement, schema: JsonObject): JsonElement {
        val type = schema["type"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        return when (type) {
            "string" -> coerceString(value)
            "number" -> coerceNumber(value) ?: value
            "integer" -> coerceInteger(value) ?: value
            "array" -> coerceArray(value, schema) ?: value
            else -> value
        }
    }

    private fun coerceString(value: JsonElement): JsonElement {
        return when (value) {
            is JsonPrimitive -> {
                JsonPrimitive(value.contentOrNull ?: value.toString())
            }

            else -> JsonPrimitive(value.toString())
        }
    }

    private fun coerceNumber(value: JsonElement): JsonPrimitive? {
        val primitive = value as? JsonPrimitive ?: return null
        val raw = primitive.contentOrNull?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val number = raw.toDoubleOrNull() ?: return null
        val asLong = number.toLong()
        return if (number == asLong.toDouble()) JsonPrimitive(asLong) else JsonPrimitive(number)
    }

    private fun coerceInteger(value: JsonElement): JsonPrimitive? {
        val primitive = value as? JsonPrimitive ?: return null
        val raw = primitive.contentOrNull?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val longValue = raw.toLongOrNull()
            ?: raw.toDoubleOrNull()?.toLong()
            ?: return null
        return JsonPrimitive(longValue)
    }

    private fun coerceArray(value: JsonElement, schema: JsonObject): JsonArray? {
        if (value is JsonArray) return value
        val primitive = value as? JsonPrimitive ?: return null
        val raw = primitive.contentOrNull?.trim().orEmpty()
        if (raw.isEmpty()) return JsonArray(emptyList())
        val itemType = ((schema["items"] as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull
        if (itemType == "string") {
            val parts = raw.split(Regex("[,，、\\n]"))
                .map { it.trim().trim('"', '\'') }
                .filter { it.isNotEmpty() }
                .map(::JsonPrimitive)
            return JsonArray(parts)
        }
        return null
    }

    private fun matchesType(expectedType: String, value: JsonElement): Boolean {
        return when (expectedType) {
            "string" -> value is JsonPrimitive && value.isString
            "integer" -> value is JsonPrimitive && !value.isString && (value.longOrNull != null || value.intOrNull != null)
            "number" -> value is JsonPrimitive && !value.isString && value.doubleOrNull != null
            "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
            "object" -> value is JsonObject
            "array" -> value is JsonArray
            else -> true
        }
    }

    private fun describeType(value: JsonElement): String {
        return when (value) {
            is JsonObject -> "object"
            is JsonArray -> "array"
            is JsonNull -> "null"
            is JsonPrimitive -> when {
                value.isString -> "string"
                value.booleanOrNull != null -> "boolean"
                value.intOrNull != null || value.longOrNull != null -> "integer"
                value.doubleOrNull != null -> "number"
                else -> "primitive"
            }
        }
    }

    private fun objectSchema(
        properties: Map<String, JsonObject> = emptyMap(),
        required: List<String> = emptyList()
    ): JsonObject {
        return buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("additionalProperties", JsonPrimitive(false))
            put(
                "properties",
                JsonObject(properties)
            )
            if (required.isNotEmpty()) {
                put(
                    "required",
                    buildJsonArray {
                        required.forEach { add(JsonPrimitive(it)) }
                    }
                )
            }
        }
    }

    private fun stringSchema(description: String): JsonObject {
        return buildJsonObject {
            put("type", JsonPrimitive("string"))
            put("description", JsonPrimitive(description))
        }
    }

    private fun coordinateNumberSchema(description: String): JsonObject {
        return buildJsonObject {
            put("type", JsonPrimitive("number"))
            put(
                "description",
                JsonPrimitive("$description 单个数值，范围 0-1000；不要传数组、对象或坐标对。")
            )
            put("minimum", JsonPrimitive(0))
            put("maximum", JsonPrimitive(1000))
        }
    }

    private fun numberSchema(description: String): JsonObject {
        return buildJsonObject {
            put("type", JsonPrimitive("number"))
            put("description", JsonPrimitive(description))
        }
    }

    private fun integerSchema(description: String): JsonObject {
        return buildJsonObject {
            put("type", JsonPrimitive("integer"))
            put("description", JsonPrimitive(description))
        }
    }

    private fun stringArraySchema(description: String): JsonObject {
        return buildJsonObject {
            put("type", JsonPrimitive("array"))
            put("description", JsonPrimitive(description))
            put(
                "items",
                buildJsonObject {
                    put("type", JsonPrimitive("string"))
                }
            )
        }
    }

    private fun enumSchema(description: String, values: List<String>): JsonObject {
        return buildJsonObject {
            put("type", JsonPrimitive("string"))
            put("description", JsonPrimitive(description))
            put(
                "enum",
                JsonArray(values.map(::JsonPrimitive))
            )
        }
    }

    private data class ScrollCoordinates(
        val x1: Double,
        val y1: Double,
        val x2: Double,
        val y2: Double
    )

    private fun buildNumericPrimitive(number: Double): JsonPrimitive {
        val asLong = number.toLong()
        return if (number == asLong.toDouble()) JsonPrimitive(asLong) else JsonPrimitive(number)
    }

    private val POINT_ALIAS_FIELDS = listOf(
        "position",
        "point",
        "coord",
        "coords",
        "coordinate",
        "coordinates",
        "tap_point",
        "click_point"
    )

    private val RANGE_ALIAS_FIELDS = listOf(
        "path",
        "points",
        "coords",
        "coordinate",
        "coordinates",
        "positions",
        "range"
    )

    private val RANGE_START_ALIAS_FIELDS = listOf("start", "from", "begin", "start_point")
    private val RANGE_END_ALIAS_FIELDS = listOf("end", "to", "finish", "end_point")
    private val NUMBER_REGEX = Regex("""[-+]?\d+(?:\.\d+)?(?:[eE][-+]?\d+)?""")
}
