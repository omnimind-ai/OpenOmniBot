package cn.com.omnimind.bot.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

object AgentConversationModePolicy {
    const val NORMAL_MODE = "normal"
    const val SUBAGENT_MODE = "subagent"

    private val subagentRestrictedToolNames = setOf(
        "schedule_task_create",
        "schedule_task_list",
        "schedule_task_update",
        "schedule_task_delete",
        "alarm_reminder_create",
        "alarm_reminder_list",
        "alarm_reminder_delete",
        "calendar_list",
        "calendar_event_create",
        "calendar_event_list",
        "calendar_event_update",
        "calendar_event_delete",
        "subagent_dispatch"
    )

    fun isSubagentMode(conversationMode: String?): Boolean {
        return conversationMode?.trim()?.equals(SUBAGENT_MODE, ignoreCase = true) == true
    }

    fun isToolRestrictedInConversationMode(
        toolName: String,
        conversationMode: String?
    ): Boolean {
        if (!isSubagentMode(conversationMode)) {
            return false
        }
        return subagentRestrictedToolNames.contains(toolName.trim())
    }

    fun restrictedToolNamesForConversationMode(conversationMode: String?): Set<String> {
        return if (isSubagentMode(conversationMode)) {
            subagentRestrictedToolNames
        } else {
            emptySet()
        }
    }

    fun filterToolDefinitionsForConversationMode(
        definitions: List<JsonObject>,
        conversationMode: String?
    ): List<JsonObject> {
        val restricted = restrictedToolNamesForConversationMode(conversationMode)
        if (restricted.isEmpty()) {
            return definitions
        }
        return definitions.filterNot { definition ->
            val toolName = (definition["function"] as? JsonObject)
                ?.get("name")
                ?.jsonPrimitive
                ?.contentOrNull
                ?.trim()
                .orEmpty()
            restricted.contains(toolName)
        }
    }
}
