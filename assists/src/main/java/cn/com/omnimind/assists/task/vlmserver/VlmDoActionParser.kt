package cn.com.omnimind.assists.task.vlmserver

import java.util.Locale
import kotlin.math.roundToLong

data class VlmDoActionParseResult(
    val step: VLMStep? = null,
    val error: String? = null,
    val normalizedText: String = ""
) {
    val success: Boolean get() = step != null && error == null
}

object VlmDoActionParser {
    private val thinkTagRegex = Regex("<think\\b[^>]*>.*?</think>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val answerTagRegex = Regex("<answer\\b[^>]*>(.*?)(?:</answer>|$)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val codeFenceRegex = Regex("```[a-zA-Z0-9_-]*\\s*(.*?)```", RegexOption.DOT_MATCHES_ALL)
    private val invocationStartRegex = Regex("\\b(do|finish)\\s*\\(", RegexOption.IGNORE_CASE)

    fun parse(response: String): VlmDoActionParseResult {
        val cleaned = normalize(response)
        val invocation = findInvocation(cleaned)
            ?: return VlmDoActionParseResult(
                error = "未找到 do(...) 或 finish(...) 动作",
                normalizedText = cleaned
            )

        return runCatching {
            when (invocation.name.lowercase(Locale.ROOT)) {
                "finish" -> parseFinish(invocation, cleaned)
                "do" -> parseDo(invocation, cleaned)
                else -> error("不支持的动作协议: ${invocation.name}")
            }
        }.getOrElse { error ->
            VlmDoActionParseResult(
                error = error.message ?: "DO_TEXT 动作解析失败",
                normalizedText = cleaned
            )
        }
    }

    private fun normalize(response: String): String {
        var cleaned = response.replace(thinkTagRegex, "").trim()
        val answer = answerTagRegex.find(cleaned)?.groupValues?.getOrNull(1)
        if (!answer.isNullOrBlank()) {
            cleaned = answer.trim()
        }

        val fencedBlocks = codeFenceRegex.findAll(cleaned).map { it.groupValues[1].trim() }.toList()
        if (fencedBlocks.isNotEmpty()) {
            cleaned = fencedBlocks.firstOrNull { invocationStartRegex.containsMatchIn(it) }
                ?: fencedBlocks.first()
        }

        return cleaned
            .replace("<answer>", "", ignoreCase = true)
            .replace("</answer>", "", ignoreCase = true)
            .trim()
    }

    private fun findInvocation(text: String): Invocation? {
        val match = invocationStartRegex.find(text) ?: return null
        val name = match.groupValues[1]
        val openIndex = text.indexOf('(', startIndex = match.range.first)
        if (openIndex < 0) return null
        val closeIndex = findMatchingParen(text, openIndex) ?: return null
        return Invocation(
            name = name,
            body = text.substring(openIndex + 1, closeIndex),
            raw = text.substring(match.range.first, closeIndex + 1),
            startIndex = match.range.first
        )
    }

    private fun findMatchingParen(text: String, openIndex: Int): Int? {
        var quote: Char? = null
        var escaping = false
        var depth = 0
        for (index in openIndex until text.length) {
            val ch = text[index]
            if (quote != null) {
                if (escaping) {
                    escaping = false
                } else if (ch == '\\') {
                    escaping = true
                } else if (ch == quote) {
                    quote = null
                }
                continue
            }
            when (ch) {
                '"', '\'' -> quote = ch
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return null
    }

    private fun parseFinish(invocation: Invocation, fullText: String): VlmDoActionParseResult {
        val params = parseParams(invocation.body)
        val message = params.string("message")
            ?: params.string("content")
            ?: fullText.removeSuffix(invocation.raw).trim().ifBlank { "任务已完成" }
        return VlmDoActionParseResult(
            step = VLMStep(
                observation = "",
                thought = thoughtBefore(fullText, invocation, fallback = "finish"),
                action = FinishedAction(content = message)
            ),
            normalizedText = fullText
        )
    }

    private fun parseDo(invocation: Invocation, fullText: String): VlmDoActionParseResult {
        val params = parseParams(invocation.body)
        val rawAction = params.string("action")
            ?: error("do(...) 缺少 action 参数")
        val normalizedAction = normalizeActionName(rawAction)
        val thought = thoughtBefore(fullText, invocation, fallback = "do(action=\"$rawAction\")")

        val action = when (normalizedAction) {
            "launch", "openapp", "openapplication" -> OpenAppAction(
                packageName = params.string("app")
                    ?: params.string("package")
                    ?: params.string("packageName")
                    ?: params.string("package_name")
                    ?: error("Launch 缺少 app 或 package 参数")
            )

            "tap", "click" -> {
                params.string("message")?.takeIf { it.isNotBlank() }?.let { message ->
                    return VlmDoActionParseResult(
                        step = VLMStep(
                            observation = "",
                            thought = thought,
                            action = RequireUserConfirmationAction(prompt = message)
                        ),
                        normalizedText = fullText
                    )
                }
                val point = params.point("element")
                    ?: params.point("position")
                    ?: params.point("coordinate")
                    ?: pointFromXY(params)
                    ?: error("Tap 缺少 element=[x,y] 坐标")
                ClickAction(
                    targetDescription = params.string("target") ?: params.string("description") ?: "tap",
                    x = point.first,
                    y = point.second
                )
            }

            "doubletap" -> {
                val point = params.point("element")
                    ?: params.point("position")
                    ?: params.point("coordinate")
                    ?: pointFromXY(params)
                    ?: error("Double Tap 缺少 element=[x,y] 坐标")
                DoubleTapAction(
                    targetDescription = params.string("target") ?: params.string("description") ?: "double tap",
                    x = point.first,
                    y = point.second
                )
            }

            "longpress" -> {
                val point = params.point("element")
                    ?: params.point("position")
                    ?: params.point("coordinate")
                    ?: pointFromXY(params)
                    ?: error("Long Press 缺少 element=[x,y] 坐标")
                LongPressAction(
                    targetDescription = params.string("target") ?: params.string("description") ?: "long press",
                    x = point.first,
                    y = point.second
                )
            }

            "type", "input", "inputtext" -> TypeAction(
                content = params.string("text")
                    ?: params.string("content")
                    ?: error("Type 缺少 text 参数")
            )

            "swipe", "scroll" -> {
                val start = params.point("start") ?: pointFromXY(params, "x1", "y1")
                    ?: error("Swipe 缺少 start=[x,y] 坐标")
                val end = params.point("end") ?: pointFromXY(params, "x2", "y2")
                    ?: error("Swipe 缺少 end=[x,y] 坐标")
                ScrollAction(
                    targetDescription = params.string("target") ?: params.string("description") ?: "swipe",
                    x1 = start.first,
                    y1 = start.second,
                    x2 = end.first,
                    y2 = end.second,
                    duration = params.durationMs("duration")?.let { it / 1000f } ?: 1.5f
                )
            }

            "back" -> PressBackAction()
            "home" -> PressHomeAction()

            "wait" -> WaitAction(
                durationMs = params.durationMs("duration")
                    ?: params.durationMs("duration_ms")
                    ?: params.durationMs("time")
                    ?: 1000L
            )

            "takeover", "takeoverrequest", "handoff" -> InfoAction(
                value = params.string("message")
                    ?: params.string("reason")
                    ?: "需要用户接管"
            )

            "finish", "finished" -> FinishedAction(
                content = params.string("message")
                    ?: params.string("content")
                    ?: "任务已完成"
            )

            else -> error("不支持的 DO_TEXT 动作: $rawAction")
        }

        return VlmDoActionParseResult(
            step = VLMStep(
                observation = "",
                thought = thought,
                action = action
            ),
            normalizedText = fullText
        )
    }

    private fun parseParams(body: String): Map<String, ParamValue> {
        val params = linkedMapOf<String, ParamValue>()
        var index = 0
        while (index < body.length) {
            index = skipDelimiters(body, index)
            if (index >= body.length) break

            val keyStart = index
            while (index < body.length && (body[index].isLetterOrDigit() || body[index] == '_')) {
                index++
            }
            val key = body.substring(keyStart, index).trim()
            if (key.isEmpty()) break
            index = skipWhitespace(body, index)
            if (index >= body.length || body[index] != '=') {
                break
            }
            index++
            index = skipWhitespace(body, index)
            val parsed = parseValue(body, index)
            params[key] = parsed.value
            index = parsed.nextIndex
        }
        return params
    }

    private fun parseValue(text: String, start: Int): ParsedValue {
        if (start >= text.length) return ParsedValue(ParamValue.StringValue(""), start)
        return when (val ch = text[start]) {
            '"', '\'' -> parseQuoted(text, start, ch)
            '[' -> parseArray(text, start)
            else -> parseBare(text, start)
        }
    }

    private fun parseQuoted(text: String, start: Int, quote: Char): ParsedValue {
        val builder = StringBuilder()
        var index = start + 1
        var escaping = false
        while (index < text.length) {
            val ch = text[index]
            if (escaping) {
                builder.append(
                    when (ch) {
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        else -> ch
                    }
                )
                escaping = false
            } else if (ch == '\\') {
                escaping = true
            } else if (ch == quote) {
                return ParsedValue(ParamValue.StringValue(builder.toString()), index + 1)
            } else {
                builder.append(ch)
            }
            index++
        }
        return ParsedValue(ParamValue.StringValue(builder.toString()), index)
    }

    private fun parseArray(text: String, start: Int): ParsedValue {
        var index = start + 1
        val values = mutableListOf<Float>()
        val current = StringBuilder()
        while (index < text.length) {
            val ch = text[index]
            if (ch == ']') {
                current.toString().trim().toFloatOrNull()?.let(values::add)
                return ParsedValue(ParamValue.ArrayValue(values), index + 1)
            }
            if (ch == ',') {
                current.toString().trim().toFloatOrNull()?.let(values::add)
                current.clear()
            } else {
                current.append(ch)
            }
            index++
        }
        return ParsedValue(ParamValue.ArrayValue(values), index)
    }

    private fun parseBare(text: String, start: Int): ParsedValue {
        var index = start
        while (index < text.length && text[index] != ',') {
            index++
        }
        val raw = text.substring(start, index).trim()
        val number = raw.toFloatOrNull()
        return ParsedValue(
            value = if (number != null) ParamValue.NumberValue(number) else ParamValue.StringValue(raw),
            nextIndex = index
        )
    }

    private fun skipWhitespace(text: String, start: Int): Int {
        var index = start
        while (index < text.length && text[index].isWhitespace()) index++
        return index
    }

    private fun skipDelimiters(text: String, start: Int): Int {
        var index = start
        while (index < text.length && (text[index].isWhitespace() || text[index] == ',')) index++
        return index
    }

    private fun normalizeActionName(value: String): String {
        return value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9\u4e00-\u9fa5]+"), "")
    }

    private fun thoughtBefore(fullText: String, invocation: Invocation, fallback: String): String {
        return fullText.substring(0, invocation.startIndex)
            .trim()
            .ifBlank { fallback }
    }

    private fun pointFromXY(params: Map<String, ParamValue>, xKey: String = "x", yKey: String = "y"): Pair<Float, Float>? {
        val x = params.number(xKey) ?: return null
        val y = params.number(yKey) ?: return null
        return x to y
    }

    private fun Map<String, ParamValue>.string(key: String): String? {
        return (this[key] as? ParamValue.StringValue)?.value?.trim()?.takeIf { it.isNotEmpty() }
            ?: (this[key] as? ParamValue.NumberValue)?.value?.toString()
    }

    private fun Map<String, ParamValue>.number(key: String): Float? {
        return when (val value = this[key]) {
            is ParamValue.NumberValue -> value.value
            is ParamValue.StringValue -> value.value.trim().toFloatOrNull()
            else -> null
        }
    }

    private fun Map<String, ParamValue>.point(key: String): Pair<Float, Float>? {
        val list = (this[key] as? ParamValue.ArrayValue)?.values ?: return null
        if (list.size < 2) return null
        return list[0] to list[1]
    }

    private fun Map<String, ParamValue>.durationMs(key: String): Long? {
        return when (val value = this[key]) {
            is ParamValue.NumberValue -> {
                val number = value.value
                if (number > 50f) number.roundToLong() else (number * 1000f).roundToLong()
            }
            is ParamValue.StringValue -> parseDurationMs(value.value)
            else -> null
        }?.coerceIn(1L, 60_000L)
    }

    private fun parseDurationMs(raw: String): Long? {
        val normalized = raw.trim().lowercase(Locale.ROOT)
        val number = Regex("([0-9]+(?:\\.[0-9]+)?)").find(normalized)?.groupValues?.getOrNull(1)
            ?.toFloatOrNull()
            ?: return null
        return when {
            "ms" in normalized || "毫秒" in normalized -> number.roundToLong()
            "sec" in normalized || "second" in normalized || "秒" in normalized || normalized.endsWith("s") ->
                (number * 1000f).roundToLong()
            else -> {
                if (number > 50f) number.roundToLong() else (number * 1000f).roundToLong()
            }
        }
    }

    private data class Invocation(
        val name: String,
        val body: String,
        val raw: String,
        val startIndex: Int
    )

    private data class ParsedValue(
        val value: ParamValue,
        val nextIndex: Int
    )

    private sealed class ParamValue {
        data class StringValue(val value: String) : ParamValue()
        data class NumberValue(val value: Float) : ParamValue()
        data class ArrayValue(val values: List<Float>) : ParamValue()
    }
}
