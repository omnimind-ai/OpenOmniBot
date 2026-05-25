package cn.com.omnimind.bot.agent.tool.handlers

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.AssistantToolCallFunction
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentRuntimeContextRepository
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.AgentWorkspaceDescriptor
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.agent.NoOpAgentCallback
import cn.com.omnimind.bot.agent.NoOpAgentRunControl
import cn.com.omnimind.bot.agent.ResolvedSkillContext
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.agent.WorkspaceMemoryService
import cn.com.omnimind.bot.runlog.OobRunLogReplayService
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkbenchToolHandlerOobFunctionToolsTest {
    @Test
    fun `agent workbench handler registers lists and deletes explicit oob functions`() = runBlocking {
        val context = TempFilesContext()
        try {
            val helper = SharedHelper(
                context = context,
                json = Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    encodeDefaults = false
                },
            )
            val handler = WorkbenchToolHandler(helper)
            val env = FakeEnv(context)
            val functionId = "agent_managed_function"
            val spec = functionSpec(functionId)

            val register = handler.execute(
                toolCall = toolCall("oob_function_register"),
                args = buildJsonObject {
                    put("functionSpec", mapToJson(spec))
                },
                runtimeDescriptor = descriptor("oob_function_register"),
                env = env,
                callback = NoOpAgentCallback,
                toolHandle = NoOpAgentRunControl.beginToolExecution(
                    "oob_function_register",
                    "register",
                ),
            )
            assertContextSuccess(register)
            assertNotNull(OobRunLogReplayService(context).getFunctionSpec(functionId))

            val list = handler.execute(
                toolCall = toolCall("oob_function_list"),
                args = buildJsonObject {},
                runtimeDescriptor = descriptor("oob_function_list"),
                env = env,
                callback = NoOpAgentCallback,
                toolHandle = NoOpAgentRunControl.beginToolExecution("oob_function_list", "list"),
            )
            val listPayload = payloadObject(list)
            assertEquals(true, listPayload["success"]?.jsonPrimitive?.booleanOrNull)
            assertTrue(
                listPayload["functions"]!!.jsonArray.any { raw ->
                    raw.jsonObject["function_id"]?.jsonPrimitive?.contentOrNull == functionId
                }
            )

            val delete = handler.execute(
                toolCall = toolCall("oob_function_delete"),
                args = buildJsonObject {
                    put("function_id", JsonPrimitive(functionId))
                },
                runtimeDescriptor = descriptor("oob_function_delete"),
                env = env,
                callback = NoOpAgentCallback,
                toolHandle = NoOpAgentRunControl.beginToolExecution(
                    "oob_function_delete",
                    "delete",
                ),
            )
            assertContextSuccess(delete)
            assertEquals(null, OobRunLogReplayService(context).getFunctionSpec(functionId))
        } finally {
            context.root.deleteRecursively()
        }
    }

    private fun toolCall(name: String): AssistantToolCall = AssistantToolCall(
        id = "$name-call",
        function = AssistantToolCallFunction(
            name = name,
            arguments = "{}",
        ),
    )

    private fun descriptor(name: String): AgentToolRegistry.RuntimeToolDescriptor =
        AgentToolRegistry.RuntimeToolDescriptor(
            name = name,
            displayName = name,
            toolType = "workbench",
        )

    private fun assertContextSuccess(result: ToolExecutionResult) {
        assertTrue(result is ToolExecutionResult.ContextResult)
        assertEquals(true, (result as ToolExecutionResult.ContextResult).success)
    }

    private fun payloadObject(result: ToolExecutionResult): JsonObject {
        assertTrue(result is ToolExecutionResult.ContextResult)
        return Json.parseToJsonElement(
            (result as ToolExecutionResult.ContextResult).rawResultJson,
        ).jsonObject
    }

    private fun mapToJson(value: Any?): JsonElement {
        return when (value) {
            null -> JsonNull
            is Map<*, *> -> JsonObject(
                value.entries.associate { (key, item) ->
                    key.toString() to mapToJson(item)
                }
            )
            is List<*> -> JsonArray(value.map(::mapToJson))
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        }
    }

    private fun functionSpec(functionId: String): Map<String, Any?> = linkedMapOf(
        "schema_version" to "oob.reusable_function.v1",
        "function_id" to functionId,
        "name" to "Agent managed function",
        "description" to "Reusable function managed from agent conversation",
        "parameters" to emptyList<Any?>(),
        "execution" to linkedMapOf(
            "kind" to "tool_sequence",
            "runner" to "oob_tool_sequence",
            "entrypoint" to "execute",
            "steps" to listOf(
                linkedMapOf(
                    "id" to "finished",
                    "index" to 0,
                    "title" to "Task completed",
                    "kind" to "omniflow_action",
                    "executor" to "omniflow",
                    "omniflow_action" to "finished",
                    "local_action" to "finished",
                    "tool" to "finished",
                    "callable_tool" to "finished",
                    "model_free" to true,
                    "scriptable" to true,
                    "args" to linkedMapOf("content" to "Done"),
                )
            ),
            "step_count" to 1,
        ),
    )

    private class TempFilesContext : ContextWrapper(null) {
        val root: File = Files.createTempDirectory("workbench-oob-function-tools-test").toFile()
        private val prefsByName = linkedMapOf<String, InMemorySharedPreferences>()
        private val appInfo = ApplicationInfo().apply {
            dataDir = root.absolutePath
            packageName = "cn.com.omnimind.bot.test"
        }

        override fun getApplicationContext(): Context = this

        override fun getApplicationInfo(): ApplicationInfo = appInfo

        override fun getFilesDir(): File = root

        override fun getPackageName(): String = appInfo.packageName

        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
            return prefsByName.getOrPut(name.orEmpty()) { InMemorySharedPreferences() }
        }
    }

    private class FakeEnv(
        private val context: Context
    ) : AgentExecutionEnvironment {
        override val agentRunId: String = "test-run"
        override val userMessage: String = "test"
        override val attachments: List<Map<String, Any?>> = emptyList()
        override val currentPackageName: String? = null
        override val runtimeContextRepository: AgentRuntimeContextRepository =
            AgentRuntimeContextRepository(context)
        override val workspaceDescriptor: AgentWorkspaceDescriptor =
            AgentWorkspaceDescriptor(
                id = "test",
                rootPath = context.filesDir.absolutePath,
                androidRootPath = context.filesDir.absolutePath,
                uriRoot = "content://test",
                currentCwd = context.filesDir.absolutePath,
                androidCurrentCwd = context.filesDir.absolutePath,
                shellRootPath = context.filesDir.absolutePath,
                retentionPolicy = "test",
            )
        override val resolvedSkills: List<ResolvedSkillContext> = emptyList()
        override val failureLearningSkill: ResolvedSkillContext? = null
        override val workspaceManager: AgentWorkspaceManager
            get() = throw UnsupportedOperationException("unused in test")
        override val workspaceMemoryService: WorkspaceMemoryService
            get() = throw UnsupportedOperationException("unused in test")
        override val conversationMode: String = "normal"
        override val reasoningEffort: String? = null
        override val terminalEnvironment: Map<String, String> = emptyMap()
        override val runControl = NoOpAgentRunControl
    }

    private class InMemorySharedPreferences : SharedPreferences {
        private val values = linkedMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(values)

        override fun getString(key: String?, defValue: String?): String? =
            values[key] as? String ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (values[key] as? Set<String>)?.toMutableSet() ?: defValues

        override fun getInt(key: String?, defValue: Int): Int =
            (values[key] as? Number)?.toInt() ?: defValue

        override fun getLong(key: String?, defValue: Long): Long =
            (values[key] as? Number)?.toLong() ?: defValue

        override fun getFloat(key: String?, defValue: Float): Float =
            (values[key] as? Number)?.toFloat() ?: defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            values[key] as? Boolean ?: defValue

        override fun contains(key: String?): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = Editor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val updates = linkedMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clear = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor =
                put(key, value)

            override fun putStringSet(
                key: String?,
                values: MutableSet<String>?
            ): SharedPreferences.Editor = put(key, values?.toMutableSet())

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor =
                put(key, value)

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor =
                put(key, value)

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor =
                put(key, value)

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor =
                put(key, value)

            override fun remove(key: String?): SharedPreferences.Editor {
                key?.let { removals += it }
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                clear = true
                return this
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clear) values.clear()
                removals.forEach(values::remove)
                updates.forEach { (key, value) -> values[key] = value }
            }

            private fun put(key: String?, value: Any?): SharedPreferences.Editor {
                key?.let { updates[it] = value }
                return this
            }
        }
    }
}
