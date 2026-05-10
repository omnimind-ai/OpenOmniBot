package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.i18n.PromptLocale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConversationModePolicyTest {

    @Test
    fun subagentModeFiltersRecursivePlanningTools() {
        val definitions = AgentToolDefinitions.staticTools(PromptLocale.ZH_CN) +
            AgentToolDefinitions.memoryTools(PromptLocale.ZH_CN) +
            AgentToolDefinitions.subagentTools(PromptLocale.ZH_CN)

        val filtered = AgentConversationModePolicy.filterToolDefinitionsForConversationMode(
            definitions = definitions,
            conversationMode = AgentConversationModePolicy.SUBAGENT_MODE
        )
        val toolNames = filtered.mapNotNull { definition ->
            ((definition["function"] as? JsonObject)
                ?.get("name")
                ?.jsonPrimitive
                ?.contentOrNull)
        }

        assertFalse(toolNames.contains("schedule_task_create"))
        assertFalse(toolNames.contains("alarm_reminder_create"))
        assertFalse(toolNames.contains("calendar_event_create"))
        assertFalse(toolNames.contains("subagent_dispatch"))
        assertTrue(toolNames.contains("vlm_task"))
        assertTrue(toolNames.contains("memory_search"))
    }
}
