package cn.com.omnimind.bot.agent

internal object AgentTextSanitizer {
    // Matches complete <function=name>...</function> blocks (including multi-line).
    private val completeFunctionBlockRegex = Regex(
        "<function=[^>]*>[\\s\\S]*?</function>",
        setOf(RegexOption.IGNORE_CASE)
    )

    /**
     * Strips text-based function call syntax from LLM output before displaying to the user.
     *
     * Some models emit tool calls as `<function=name>args</function>` inside content text
     * in addition to (or instead of) structured tool_calls. This content must not be shown
     * as chat text — only structured tool cards should represent those actions.
     *
     * Handles two cases:
     * - Complete blocks: `<function=name>args</function>` → removed entirely
     * - Incomplete streaming blocks: trailing `<function=` with no closing tag yet → trimmed
     */
    fun stripTextFunctionCalls(text: String): String {
        if (!text.contains("<function=", ignoreCase = true)) return text
        // Remove complete blocks first.
        var result = completeFunctionBlockRegex.replace(text, "")
        // If an incomplete block starts anywhere in the remaining text, drop it from that
        // point onward (the model is still streaming the arguments).
        val incompleteStart = result.indexOf("<function=", ignoreCase = true)
        if (incompleteStart >= 0) {
            result = result.substring(0, incompleteStart)
        }
        return result.trim()
    }

    fun sanitizeUtf16(text: String): String {
        if (text.isEmpty()) {
            return text
        }

        var index = 0
        var needsSanitization = false
        while (index < text.length) {
            val current = text[index]
            when {
                Character.isHighSurrogate(current) -> {
                    if (index + 1 < text.length && Character.isLowSurrogate(text[index + 1])) {
                        index += 2
                    } else {
                        needsSanitization = true
                        index = text.length
                    }
                }

                Character.isLowSurrogate(current) -> {
                    needsSanitization = true
                    index = text.length
                }

                else -> index += 1
            }
        }

        if (!needsSanitization) {
            return text
        }

        val sanitized = StringBuilder(text.length)
        index = 0
        while (index < text.length) {
            val current = text[index]
            when {
                Character.isHighSurrogate(current) -> {
                    if (index + 1 < text.length && Character.isLowSurrogate(text[index + 1])) {
                        sanitized.append(current)
                        sanitized.append(text[index + 1])
                        index += 2
                    } else {
                        index += 1
                    }
                }

                Character.isLowSurrogate(current) -> {
                    index += 1
                }

                else -> {
                    sanitized.append(current)
                    index += 1
                }
            }
        }
        return sanitized.toString()
    }
}
