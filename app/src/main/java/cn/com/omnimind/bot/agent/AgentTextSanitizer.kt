package cn.com.omnimind.bot.agent

internal object AgentTextSanitizer {
    private val thinkBlockPattern = Regex(
        pattern = "<think>.*?</think>",
        options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val thinkTagPattern = Regex(
        pattern = "</?think>",
        options = setOf(RegexOption.IGNORE_CASE)
    )

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

    fun sanitizeVisibleAssistantText(text: String): String {
        val utf16SafeText = sanitizeUtf16(text)
        if (
            !utf16SafeText.contains("<think", ignoreCase = true) &&
            !utf16SafeText.contains("</think", ignoreCase = true)
        ) {
            return utf16SafeText
        }
        return thinkTagPattern.replace(
            thinkBlockPattern.replace(utf16SafeText, ""),
            ""
        )
    }
}
