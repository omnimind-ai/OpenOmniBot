package cn.com.omnimind.bot.agent

internal object AgentTextSanitizer {
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
