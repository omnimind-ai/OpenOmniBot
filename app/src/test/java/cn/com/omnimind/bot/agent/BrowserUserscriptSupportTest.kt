package cn.com.omnimind.bot.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserUserscriptSupportTest {
    @Test
    fun parseSourceCollectsMetadataAndBlockedGrants() {
        val preview = BrowserUserscriptSupport.parseSource(
            source = """
                // ==UserScript==
                // @name Demo Script
                // @description For tests
                // @version 1.2.3
                // @match https://example.com/*
                // @include https://demo.example/*
                // @exclude https://example.com/private/*
                // @grant GM_getValue
                // @grant GM_xmlhttpRequest
                // @run-at document-end
                // ==/UserScript==
                console.log('hello');
            """.trimIndent(),
            sourceUrl = "https://example.com/demo.user.js"
        )

        assertEquals("Demo Script", preview.metadata.name)
        assertEquals("1.2.3", preview.metadata.version)
        assertEquals(listOf("https://example.com/*"), preview.metadata.matches)
        assertEquals(listOf("GM_xmlhttpRequest"), preview.blockedGrants)
        assertTrue(BrowserUserscriptSupport.isSupportedRunAt(preview.metadata.runAt))
    }

    @Test
    fun matchesUrlHonorsMatchAndExcludeRules() {
        val script = BrowserUserscriptRecord(
            id = 1L,
            name = "Demo",
            source = "console.log('demo')",
            matches = listOf("https://example.com/*"),
            excludes = listOf("https://example.com/private/*"),
            createdAt = 1L,
            updatedAt = 1L
        )

        assertTrue(
            BrowserUserscriptSupport.matchesUrl(
                script,
                "https://example.com/home"
            )
        )
        assertFalse(
            BrowserUserscriptSupport.matchesUrl(
                script,
                "https://example.com/private/settings"
            )
        )
    }
}
