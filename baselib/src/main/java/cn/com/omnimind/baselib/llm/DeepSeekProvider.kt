package cn.com.omnimind.baselib.llm

import java.net.URI

object DeepSeekProvider {
    const val OFFICIAL_PROFILE_ID = "deepseek-official"
    const val OFFICIAL_PROFILE_NAME = "DeepSeek"
    const val OFFICIAL_BASE_URL = "https://api.deepseek.com"
    const val PROTOCOL_TYPE = "deepseek"

    fun officialProfile(): ModelProviderProfile {
        return ModelProviderProfile(
            id = OFFICIAL_PROFILE_ID,
            name = OFFICIAL_PROFILE_NAME,
            baseUrl = OFFICIAL_BASE_URL,
            protocolType = PROTOCOL_TYPE
        )
    }

    fun isOfficialBaseUrl(value: String?): Boolean {
        val normalized = value
            ?.trim()
            ?.removeSuffix("#")
            ?.trim()
            ?.replace(Regex("/+$"), "")
            .orEmpty()
        if (normalized.isEmpty()) {
            return false
        }
        val uri = runCatching { URI(normalized) }.getOrNull() ?: return false
        if (uri.scheme?.equals("https", ignoreCase = true) != true) {
            return false
        }
        if (!uri.host.equals("api.deepseek.com", ignoreCase = true)) {
            return false
        }
        val path = uri.path.orEmpty().trimEnd('/')
        return path.isEmpty() || path == "/v1"
    }

    fun shouldUseOfficialAdapter(protocolType: String?, apiBase: String?): Boolean {
        return normalizeProtocolType(protocolType) != "anthropic" && isOfficialBaseUrl(apiBase)
    }

    fun normalizeProtocolType(value: String?): String {
        return when (value?.trim()?.lowercase().orEmpty()) {
            PROTOCOL_TYPE -> PROTOCOL_TYPE
            "anthropic" -> "anthropic"
            else -> "openai_compatible"
        }
    }

    fun mapReasoningEffortForOfficialApi(value: String?): String? {
        return when (value?.trim()?.lowercase().orEmpty()) {
            "high", "low", "medium" -> "high"
            "max", "xhigh" -> "max"
            else -> null
        }
    }
}
