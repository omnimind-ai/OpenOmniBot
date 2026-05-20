package cn.com.omnimind.bot.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentProfileRegistryTest {

    @Test
    fun `four built-in profiles registered`() {
        val ids = SubagentProfileRegistry.all().map { it.id }
        assertTrue(ids.containsAll(listOf("general", "explorer", "memory-curator", "planner")))
        assertEquals(4, ids.size)
    }

    @Test
    fun `unknown profileId falls back to general`() {
        assertEquals("general", SubagentProfileRegistry.get(null).id)
        assertEquals("general", SubagentProfileRegistry.get("").id)
        assertEquals("general", SubagentProfileRegistry.get("does-not-exist").id)
    }

    @Test
    fun `no profile exposes subagent_dispatch`() {
        for (profile in SubagentProfileRegistry.all()) {
            assertFalse(
                "profile=${profile.id} must not allow subagent_dispatch",
                "subagent_dispatch" in profile.allowedTools
            )
        }
    }

    @Test
    fun `no profile exposes privileged or terminal tools`() {
        val forbidden = listOf(
            "terminal_execute",
            "android_privileged_action",
            "android_privileged_session_start",
            "android_privileged_session_exec",
            "android_privileged_session_read",
            "android_privileged_session_stop",
            "terminal_session_start",
            "terminal_session_exec",
            "terminal_session_read",
            "terminal_session_stop"
        )
        for (profile in SubagentProfileRegistry.all()) {
            for (tool in forbidden) {
                assertFalse(
                    "profile=${profile.id} must not allow $tool",
                    tool in profile.allowedTools
                )
            }
        }
    }

    @Test
    fun `no profile exposes file write or delete tools`() {
        val forbidden = listOf("file_write", "file_edit", "file_move", "file_delete")
        for (profile in SubagentProfileRegistry.all()) {
            for (tool in forbidden) {
                assertFalse(
                    "profile=${profile.id} must not allow $tool",
                    tool in profile.allowedTools
                )
            }
        }
    }

    @Test
    fun `planner has no tools at all`() {
        val planner = SubagentProfileRegistry.get("planner")
        assertTrue(planner.allowedTools.isEmpty())
    }

    @Test
    fun `memory-curator can write memory`() {
        val curator = SubagentProfileRegistry.get("memory-curator")
        assertTrue("memory_search" in curator.allowedTools)
        assertTrue("memory_upsert_longterm" in curator.allowedTools)
        assertTrue("memory_write_daily" in curator.allowedTools)
    }

    @Test
    fun `explorer is read-only memory plus filesystem`() {
        val explorer = SubagentProfileRegistry.get("explorer")
        assertTrue("memory_search" in explorer.allowedTools)
        assertTrue("memory_load" in explorer.allowedTools)
        assertTrue("file_read" in explorer.allowedTools)
        assertFalse("memory_upsert_longterm" in explorer.allowedTools)
        assertFalse("memory_write_daily" in explorer.allowedTools)
    }

    @Test
    fun `each profile has distinct system prompt`() {
        val prompts = SubagentProfileRegistry.all().map { it.systemPrompt }
        assertEquals(prompts.size, prompts.toSet().size)
        for ((i, a) in prompts.withIndex()) {
            for (b in prompts.drop(i + 1)) {
                assertNotEquals(a, b)
            }
        }
    }

    @Test
    fun `isForbidden flags critical mutating tools`() {
        assertTrue(SubagentProfileRegistry.isForbidden("subagent_dispatch"))
        assertTrue(SubagentProfileRegistry.isForbidden("file_write"))
        assertTrue(SubagentProfileRegistry.isForbidden("terminal_execute"))
        assertFalse(SubagentProfileRegistry.isForbidden("memory_search"))
        assertFalse(SubagentProfileRegistry.isForbidden("file_read"))
    }
}
