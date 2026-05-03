package cn.com.omnimind.bot.codex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexAppServerProtocolPayloadTest {

    @Test
    fun sanitizeCodexAbsolutePathKeepsLastCleanAbsolutePath() {
        val path = sanitizeCodexAbsolutePath(
            """
            init-host: shell warmup
            /workspace
            warning: ignored trailing log
            """.trimIndent()
        )

        assertEquals("/workspace", path)
    }

    @Test
    fun sanitizeCodexAbsolutePathRejectsRelativeOutput() {
        assertNull(sanitizeCodexAbsolutePath("workspace"))
    }

    @Test
    fun buildCodexTextInputMatchesAppServerTextShape() {
        val input = buildCodexTextInput(" hello ")

        assertEquals(1, input.size)
        assertEquals("text", input[0]["type"])
        assertEquals("hello", input[0]["text"])
        assertTrue(input[0].containsKey("text_elements"))
    }

    @Test
    fun buildDefaultCodexSandboxPolicyUsesAbsoluteWritableRoot() {
        val policy = buildDefaultCodexSandboxPolicy("noise\n/workspace")

        assertEquals("workspaceWrite", policy["type"])
        assertEquals(listOf("/workspace"), policy["writableRoots"])
        assertEquals(true, policy["networkAccess"])
        assertEquals(false, policy["excludeTmpdirEnvVar"])
        assertEquals(false, policy["excludeSlashTmp"])
    }

    @Test
    fun addCodexOptionalRunParamsForwardsModelAndPlanMode() {
        val params = linkedMapOf<String, Any?>("threadId" to "thread-1")

        addCodexOptionalRunParams(
            params,
            mapOf(
                "model" to "gpt-5-codex",
                "effort" to "high",
                "collaborationMode" to "plan",
                "serviceTier" to "auto"
            )
        )

        assertEquals("gpt-5-codex", params["model"])
        assertEquals("high", params["effort"])
        assertEquals("plan", params["collaborationMode"])
        assertEquals("auto", params["serviceTier"])
    }

    @Test
    fun resolveCodexReviewTargetDefaultsToUncommittedChanges() {
        val target = resolveCodexReviewTarget(null)

        assertEquals("uncommittedChanges", target["type"])
    }

    @Test
    fun resolveCodexReviewTargetPreservesExplicitTarget() {
        val target = resolveCodexReviewTarget(
            mapOf(
                "type" to "baseBranch",
                "branch" to "main"
            )
        )

        assertEquals("baseBranch", target["type"])
        assertEquals("main", target["branch"])
    }
}
