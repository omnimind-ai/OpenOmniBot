package cn.com.omnimind.bot.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserRiskControlTest {
    @Test
    fun userAgentProfilesUseAndroidChromeCompatibleStrings() {
        assertEquals(BrowserUserAgentProfile.MOBILE_SAFARI, BrowserUserAgentProfile.defaultProfile())

        val mobile = BrowserUserAgentProfile.MOBILE_SAFARI.userAgentString
        val desktop = BrowserUserAgentProfile.DESKTOP_SAFARI.userAgentString

        listOf(mobile, desktop).forEach { userAgent ->
            assertTrue(userAgent.contains("Android"))
            assertTrue(userAgent.contains("Chrome/"))
            assertFalse(userAgent.contains("Macintosh"))
            assertFalse(userAgent.contains("iPhone"))
            assertFalse(userAgent.contains("Version/17"))
        }
        assertTrue(mobile.contains("Mobile Safari"))
        assertFalse(desktop.contains(" Mobile "))
    }

    @Test
    fun searchHostsUseMoreConservativeThrottle() {
        val normalDelay = BrowserRiskControl.baseThrottleDelayMs(
            action = BrowserUseAction.NAVIGATE,
            rawUrl = "https://example.com/search?q=agent"
        )
        val searchDelay = BrowserRiskControl.baseThrottleDelayMs(
            action = BrowserUseAction.NAVIGATE,
            rawUrl = "https://www.google.com/search?q=agent"
        )

        assertTrue(searchDelay > normalDelay)
        assertTrue(BrowserRiskControl.isSearchHost("https://www.bing.com/search?q=agent"))
        assertFalse(BrowserRiskControl.isSearchHost("https://example.com/search?q=agent"))
    }

    @Test
    fun throttleDelayAddsDeficitAndJitter() {
        assertEquals(
            500L,
            BrowserRiskControl.computeThrottleDelayMs(
                baseDelayMs = 550L,
                elapsedSinceLastActionMs = 100L,
                jitterMs = 50L
            )
        )
        assertEquals(
            50L,
            BrowserRiskControl.computeThrottleDelayMs(
                baseDelayMs = 550L,
                elapsedSinceLastActionMs = 1000L,
                jitterMs = 50L
            )
        )
    }

    @Test
    fun detectsSearchEngineTrafficChallenge() {
        val challenge = BrowserRiskControl.detectChallenge(
            title = "Google",
            bodyText = "Our systems have detected unusual traffic from your computer network.",
            currentUrl = "https://www.google.com/sorry/index?continue=https://www.google.com/search"
        )

        assertEquals("search_engine_challenge", challenge?.kind)
        assertEquals(
            "ask_user_to_complete_verification_manually",
            challenge?.recommendedNextAction
        )
    }

    @Test
    fun detectsCloudflareCaptchaAndHttpRateLimits() {
        val cloudflare = BrowserRiskControl.detectChallenge(
            title = "Attention Required! | Cloudflare",
            bodyText = "Checking if the site connection is secure. Challenge platform.",
            currentUrl = "https://example.com/"
        )
        val captcha = BrowserRiskControl.detectChallenge(
            title = "Security check",
            bodyText = "Please verify you are human and complete the CAPTCHA.",
            currentUrl = "https://example.com/"
        )
        val rateLimited = BrowserRiskControl.detectChallenge(
            statusCode = 429,
            currentUrl = "https://example.com/"
        )
        val denied = BrowserRiskControl.detectChallenge(
            statusCode = 403,
            currentUrl = "https://example.com/"
        )

        assertEquals("cloudflare_challenge", cloudflare?.kind)
        assertEquals("captcha_challenge", captcha?.kind)
        assertEquals("rate_limited", rateLimited?.kind)
        assertEquals("access_denied", denied?.kind)
    }
}
