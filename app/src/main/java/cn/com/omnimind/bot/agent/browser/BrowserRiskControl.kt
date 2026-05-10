package cn.com.omnimind.bot.agent

import java.net.URI
import java.util.Locale

internal data class BrowserRiskChallenge(
    val kind: String,
    val recommendedNextAction: String
)

internal object BrowserRiskControl {
    private val searchHostPatterns = listOf(
        Regex("(^|\\.)google\\.[a-z.]+$", RegexOption.IGNORE_CASE),
        Regex("(^|\\.)bing\\.com$", RegexOption.IGNORE_CASE),
        Regex("(^|\\.)duckduckgo\\.com$", RegexOption.IGNORE_CASE),
        Regex("(^|\\.)yahoo\\.[a-z.]+$", RegexOption.IGNORE_CASE),
        Regex("(^|\\.)baidu\\.com$", RegexOption.IGNORE_CASE),
        Regex("(^|\\.)yandex\\.[a-z.]+$", RegexOption.IGNORE_CASE),
        Regex("(^|\\.)ecosia\\.org$", RegexOption.IGNORE_CASE),
        Regex("(^|\\.)search\\.brave\\.com$", RegexOption.IGNORE_CASE),
        Regex("(^|\\.)sogou\\.com$", RegexOption.IGNORE_CASE)
    )

    fun normalizedHost(rawUrl: String?): String? {
        val value = rawUrl?.trim().orEmpty()
        if (value.isBlank()) return null
        return runCatching {
            val uri = URI(value)
            uri.host
                ?.lowercase(Locale.ROOT)
                ?.removePrefix("www.")
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    fun isSearchHost(rawUrl: String?): Boolean {
        val host = normalizedHost(rawUrl) ?: return false
        return searchHostPatterns.any { pattern -> pattern.matches(host) }
    }

    fun shouldThrottle(action: BrowserUseAction): Boolean {
        return action == BrowserUseAction.NAVIGATE ||
            action == BrowserUseAction.CLICK ||
            action == BrowserUseAction.TYPE ||
            action == BrowserUseAction.PRESS_KEY ||
            action == BrowserUseAction.SCROLL_AND_COLLECT
    }

    fun baseThrottleDelayMs(
        action: BrowserUseAction,
        rawUrl: String?
    ): Long {
        val base = when (action) {
            BrowserUseAction.NAVIGATE -> 550L
            BrowserUseAction.CLICK -> 180L
            BrowserUseAction.TYPE -> 260L
            BrowserUseAction.PRESS_KEY -> 140L
            BrowserUseAction.SCROLL_AND_COLLECT -> 320L
            else -> 0L
        }
        if (base <= 0L || !isSearchHost(rawUrl)) {
            return base
        }
        val searchBonus = when (action) {
            BrowserUseAction.NAVIGATE -> 900L
            BrowserUseAction.CLICK -> 220L
            BrowserUseAction.TYPE -> 260L
            BrowserUseAction.PRESS_KEY -> 120L
            BrowserUseAction.SCROLL_AND_COLLECT -> 360L
            else -> 0L
        }
        return base + searchBonus
    }

    fun computeThrottleDelayMs(
        baseDelayMs: Long,
        elapsedSinceLastActionMs: Long?,
        jitterMs: Long
    ): Long {
        if (baseDelayMs <= 0L) return 0L
        val normalizedJitter = jitterMs.coerceAtLeast(0L)
        val deficit = elapsedSinceLastActionMs
            ?.let { (baseDelayMs - it).coerceAtLeast(0L) }
            ?: 0L
        return deficit + normalizedJitter
    }

    fun detectChallenge(
        statusCode: Int? = null,
        title: String? = null,
        bodyText: String? = null,
        currentUrl: String? = null
    ): BrowserRiskChallenge? {
        when (statusCode) {
            429 -> return BrowserRiskChallenge(
                kind = "rate_limited",
                recommendedNextAction = "wait_before_retrying_and_reduce_request_rate"
            )
            403 -> return BrowserRiskChallenge(
                kind = "access_denied",
                recommendedNextAction = "stop_automatic_retry_and_use_manual_access"
            )
        }

        val haystack = listOfNotNull(title, bodyText, currentUrl)
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .lowercase(Locale.ROOT)
        if (haystack.isBlank()) return null

        val searchHost = isSearchHost(currentUrl)
        return when {
            "cloudflare" in haystack &&
                ("challenge" in haystack || "attention required" in haystack) ->
                BrowserRiskChallenge(
                    kind = "cloudflare_challenge",
                    recommendedNextAction = "ask_user_to_complete_verification_manually"
                )

            searchHost &&
                ("unusual traffic" in haystack ||
                    "our systems have detected unusual traffic" in haystack ||
                    "automated queries" in haystack) ->
                BrowserRiskChallenge(
                    kind = "search_engine_challenge",
                    recommendedNextAction = "ask_user_to_complete_verification_manually"
                )

            "recaptcha" in haystack ||
                "hcaptcha" in haystack ||
                "turnstile" in haystack ||
                "captcha" in haystack ||
                "verify you are human" in haystack ||
                "security check" in haystack ->
                BrowserRiskChallenge(
                    kind = "captcha_challenge",
                    recommendedNextAction = "ask_user_to_complete_verification_manually"
                )

            "too many requests" in haystack || "rate limit" in haystack ->
                BrowserRiskChallenge(
                    kind = "rate_limited",
                    recommendedNextAction = "wait_before_retrying_and_reduce_request_rate"
                )

            "access denied" in haystack || "forbidden" in haystack ->
                BrowserRiskChallenge(
                    kind = "access_denied",
                    recommendedNextAction = "stop_automatic_retry_and_use_manual_access"
                )

            else -> null
        }
    }
}
