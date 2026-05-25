package cn.com.omnimind.bot.agent

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import cn.com.omnimind.baselib.runlog.OobReusableFunctionStore
import cn.com.omnimind.bot.agent.config.AgentToolFeatureStore
import cn.com.omnimind.bot.runlog.OobRunLogReplayService
import cn.com.omnimind.bot.workbench.WorkbenchProjectStore
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolRegistryOobFunctionTest {
    @Test
    fun `project tools are hidden when no project capability is active`() {
        val context = TempFilesContext()
        try {
            val registry = AgentToolRegistry(
                context = context,
                discoveredServers = emptyList(),
            )
            val toolNames = registry.toolsForModel.map { it.function.name }.toSet()

            assertFalse(toolNames.contains("workbench_project_create"))
            assertFalse(toolNames.contains("workbench_project_list"))
            assertFalse(toolNames.contains("workbench_api_call"))
            assertTrue(toolNames.contains("oob_function_list"))
            assertTrue(toolNames.contains("oob_function_get"))
            assertTrue(toolNames.contains("oob_function_register"))
            assertTrue(toolNames.contains("oob_function_guard_check"))
            assertTrue(toolNames.contains("oob_function_run"))
            assertTrue(toolNames.contains("oob_function_delete"))
            assertTrue(toolNames.contains("oob_function_clear"))
            assertTrue(toolNames.contains("oob_run_log_list"))
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `project tools are exposed after activating a project`() {
        val context = TempFilesContext()
        try {
            val store = WorkbenchProjectStore(context)
            store.createProject(
                mapOf(
                    "projectId" to "test-project",
                    "name" to "Test Project"
                )
            )
            store.activateProject("test-project")

            val registry = AgentToolRegistry(
                context = context,
                discoveredServers = emptyList(),
            )
            val toolNames = registry.toolsForModel.map { it.function.name }.toSet()

            assertTrue(toolNames.contains("workbench_project_create"))
            assertTrue(toolNames.contains("workbench_project_list"))
            assertTrue(toolNames.contains("workbench_api_call"))
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `registered oob function is exposed as agent tool and materializes changed argument`() {
        val context = TempFilesContext()
        try {
            val functionId = "oob_registered_text_input"
            val spec = functionSpec(functionId)
            val register = OobRunLogReplayService(context).registerFunctionSpec(spec)
            assertEquals(true, register["success"])

            val registry = AgentToolRegistry(
                context = context,
                discoveredServers = emptyList(),
            )
            val tool = registry.toolsForModel.singleOrNull { it.function.name == functionId }
            assertNotNull(tool)
            assertEquals(
                "oob_function",
                registry.runtimeDescriptor(functionId).toolType,
            )

            val schema = tool!!.function.parameters
            val properties = schema["properties"] as JsonObject
            val replacement = properties["replacement_text"] as JsonObject
            assertEquals("string", replacement["type"]?.jsonPrimitive?.content)
            assertEquals("hello", replacement["default"]?.jsonPrimitive?.content)
            val required = (schema["required"] as JsonArray)
                .map { it.jsonPrimitive.content }
            assertTrue(required.contains("tool_title"))
            assertFalse(required.contains("replacement_text"))

            registry.validateArguments(
                functionId,
                buildJsonObject {
                    put("tool_title", JsonPrimitive("Replay"))
                    put("replacement_text", JsonPrimitive("world"))
                },
            )

            val stored = requireNotNull(
                OobRunLogReplayService(context).getFunctionSpec(functionId)
            )
            val materialized = OobReusableFunctionStore.materialize(
                stored,
                mapOf("replacement_text" to "world"),
            )
            assertEquals(
                "world",
                (stepsFrom(materialized).single()["args"] as Map<*, *>)["text"],
            )
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `register normalizes invalid oob function id before exposing as agent tool`() {
        val context = TempFilesContext()
        try {
            val register = OobRunLogReplayService(context)
                .registerFunctionSpec(functionSpec("bad id.with.dot"))
            assertEquals(true, register["success"])
            assertEquals("bad_id_with_dot", register["function_id"])
            assertEquals("bad_id_with_dot", register["created_function_id"])
            assertEquals("bad id.with.dot", register["normalized_from_function_id"])

            val stored = requireNotNull(
                OobRunLogReplayService(context).getFunctionSpec("bad_id_with_dot")
            )
            assertEquals("bad_id_with_dot", stored["function_id"])

            val registry = AgentToolRegistry(
                context = context,
                discoveredServers = emptyList(),
            )
            assertNotNull(registry.toolsForModel.singleOrNull {
                it.function.name == "bad_id_with_dot"
            })
            assertFalse(registry.toolsForModel.any {
                it.function.name == "bad id.with.dot"
            })
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `invalid legacy oob function ids are not exposed as agent tools`() {
        val context = TempFilesContext()
        try {
            OobReusableFunctionStore.register(context, functionSpec("bad id.with.dot"))
            AgentToolFeatureStore.setOobFunctionAsToolEnabled(context, true)

            val registry = AgentToolRegistry(
                context = context,
                discoveredServers = emptyList(),
            )
            assertFalse(registry.toolsForModel.any {
                it.function.name == "bad id.with.dot"
            })
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `duplicate dynamic oob definitions do not duplicate model tools`() {
        val context = TempFilesContext()
        try {
            val functionId = "oob_duplicate_dynamic"
            val register = OobRunLogReplayService(context)
                .registerFunctionSpec(functionSpec(functionId))
            assertEquals(true, register["success"])

            val registry = AgentToolRegistry(
                context = context,
                discoveredServers = emptyList(),
                dynamicDefinitions = listOf(dynamicToolDefinition(functionId))
            )

            assertEquals(
                1,
                registry.toolsForModel.count { it.function.name == functionId }
            )
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `invalid dynamic tool names are skipped before model exposure`() {
        val context = TempFilesContext()
        try {
            val registry = AgentToolRegistry(
                context = context,
                discoveredServers = emptyList(),
                dynamicDefinitions = listOf(dynamicToolDefinition("bad id.with.dot"))
            )

            assertFalse(registry.toolsForModel.any {
                it.function.name == "bad id.with.dot"
            })
        } finally {
            context.root.deleteRecursively()
        }
    }

    private fun dynamicToolDefinition(functionId: String): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("function"))
        put("function", buildJsonObject {
            put("name", JsonPrimitive(functionId))
            put("displayName", JsonPrimitive("Dynamic OOB function"))
            put("toolType", JsonPrimitive("oob_function"))
            put("description", JsonPrimitive("Duplicate dynamic OOB definition"))
            put("parameters", JsonObject(emptyMap()))
        })
    }

    private fun functionSpec(functionId: String): Map<String, Any?> = linkedMapOf(
        "schema_version" to "oob.reusable_function.v1",
        "function_id" to functionId,
        "name" to "Reusable text input",
        "description" to "Replay text input with a replaceable value",
        "parameters" to listOf(
            linkedMapOf(
                "name" to "replacement_text",
                "type" to "string",
                "required" to false,
                "default" to "hello",
                "description" to "Text value for the recorded input step",
                "bindings" to listOf("$.execution.steps[0].args.text"),
            )
        ),
        "execution" to linkedMapOf(
            "kind" to "tool_sequence",
            "runner" to "oob_tool_sequence",
            "entrypoint" to "execute",
            "steps" to listOf(
                linkedMapOf(
                    "id" to "step_1",
                    "index" to 0,
                    "title" to "Input text",
                    "kind" to "omniflow_action",
                    "executor" to "omniflow",
                    "omniflow_action" to "input_text",
                    "local_action" to "input_text",
                    "model_free" to true,
                    "scriptable" to true,
                    "tool" to "input_text",
                    "callable_tool" to "input_text",
                    "args" to linkedMapOf("text" to "hello"),
                )
            ),
            "step_count" to 1,
        ),
    )

    private fun stepsFrom(spec: Map<String, Any?>): List<Map<String, Any?>> {
        val steps = (spec["execution"] as Map<*, *>)["steps"] as List<*>
        return steps.map { raw ->
            @Suppress("UNCHECKED_CAST")
            raw as Map<String, Any?>
        }
    }

    private class TempFilesContext : ContextWrapper(null) {
        val root: File = Files.createTempDirectory("agent-registry-oob-function-test").toFile()
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
