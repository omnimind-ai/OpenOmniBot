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
            AgentToolNames.ANDROID_PRIVILEGED_ACTION,
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
    fun `no profile exposes file_delete`() {
        for (profile in SubagentProfileRegistry.all()) {
            assertFalse(
                "profile=${profile.id} must not allow file_delete",
                "file_delete" in profile.allowedTools
            )
        }
    }

    @Test
    fun `planner has no tools at all`() {
        val planner = SubagentProfileRegistry.get("planner")
        assertTrue(planner.allowedTools.isEmpty())
    }

    @Test
    fun `memory-curator can read files and write memory`() {
        val curator = SubagentProfileRegistry.get("memory-curator")
        assertTrue("memory_search" in curator.allowedTools)
        assertTrue("memory_load" in curator.allowedTools)
        assertTrue("memory_upsert_longterm" in curator.allowedTools)
        assertTrue("memory_write_daily" in curator.allowedTools)
        assertTrue("memory_rollup_day" in curator.allowedTools)
        assertTrue("file_read" in curator.allowedTools)
        // 记忆管理员不应能写文件
        assertFalse("file_write" in curator.allowedTools)
        assertFalse("file_edit" in curator.allowedTools)
    }

    @Test
    fun `explorer is read-only with browser access`() {
        val explorer = SubagentProfileRegistry.get("explorer")
        // 允许:文件只读 + 记忆只读 + 浏览
        assertTrue("file_read" in explorer.allowedTools)
        assertTrue("file_list" in explorer.allowedTools)
        assertTrue("memory_search" in explorer.allowedTools)
        assertTrue("memory_load" in explorer.allowedTools)
        assertTrue(AgentToolNames.BROWSER_USE in explorer.allowedTools)
        // 不允许:写文件 / 写记忆
        assertFalse("file_write" in explorer.allowedTools)
        assertFalse("file_edit" in explorer.allowedTools)
        assertFalse("memory_upsert_longterm" in explorer.allowedTools)
        assertFalse("memory_write_daily" in explorer.allowedTools)
        assertFalse("memory_rollup_day" in explorer.allowedTools)
    }

    @Test
    fun `general allows file_write and memory_upsert_longterm`() {
        val general = SubagentProfileRegistry.get("general")
        // 扩容后:可写文件 + 可写长期记忆 + 视觉自动化 + 媒体 + 浏览
        assertTrue("file_write" in general.allowedTools)
        assertTrue("file_edit" in general.allowedTools)
        assertTrue("file_move" in general.allowedTools)
        assertTrue("memory_upsert_longterm" in general.allowedTools)
        assertTrue("memory_write_daily" in general.allowedTools)
        assertTrue(AgentToolNames.VLM_TASK in general.allowedTools)
        assertTrue("music_playback_control" in general.allowedTools)
        assertTrue(AgentToolNames.BROWSER_USE in general.allowedTools)
    }

    @Test
    fun `general blocks privileged and recursive tools`() {
        val general = SubagentProfileRegistry.get("general")
        assertFalse("subagent_dispatch" in general.allowedTools)
        assertFalse("terminal_execute" in general.allowedTools)
        assertFalse(AgentToolNames.ANDROID_PRIVILEGED_ACTION in general.allowedTools)
        assertFalse("file_delete" in general.allowedTools)
        // 调度类:只读允许,写入不允许(留给主 Agent 显式审批)
        assertTrue("schedule_task_list" in general.allowedTools)
        assertFalse("schedule_task_create" in general.allowedTools)
        assertTrue("calendar_event_list" in general.allowedTools)
        assertFalse("calendar_event_create" in general.allowedTools)
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
    fun `no profile prompt enumerates forbidden tools or uses negative ban phrases`() {
        // 工具白名单层(SubagentToolCatalogView)已经做了真正的过滤。
        // 提示词不应再"剧透"被禁工具或用负面禁用语,否则会引导 LLM 联想到本不可见的工具。
        val negativePhrases = listOf(
            "subagent_dispatch",
            "已禁用",
            "你不能",
            "禁止调用",
            "no recursion",
            "must not call"
        )
        for (profile in SubagentProfileRegistry.all()) {
            for (phrase in negativePhrases) {
                assertFalse(
                    "profile=${profile.id} prompt should not contain ban phrase '$phrase' but found in: ${profile.systemPrompt}",
                    profile.systemPrompt.contains(phrase)
                )
            }
        }
    }

    @Test
    fun `isForbidden flags critical mutating tools`() {
        // file_write 不再 forbidden(general 允许写),仅保留真正系统不变量
        assertTrue(SubagentProfileRegistry.isForbidden("subagent_dispatch"))
        assertTrue(SubagentProfileRegistry.isForbidden("terminal_execute"))
        assertTrue(SubagentProfileRegistry.isForbidden(AgentToolNames.ANDROID_PRIVILEGED_ACTION))
        assertTrue(SubagentProfileRegistry.isForbidden("file_delete"))
        // file_write / file_edit / memory_search 现在不在 FORBIDDEN 里
        assertFalse(SubagentProfileRegistry.isForbidden("file_write"))
        assertFalse(SubagentProfileRegistry.isForbidden("memory_search"))
        assertFalse(SubagentProfileRegistry.isForbidden("file_read"))
    }
}
