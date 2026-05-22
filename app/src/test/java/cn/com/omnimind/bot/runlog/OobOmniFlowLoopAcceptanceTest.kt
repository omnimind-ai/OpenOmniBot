package cn.com.omnimind.bot.runlog

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import cn.com.omnimind.bot.workbench.WorkspaceFunctionStore
import cn.com.omnimind.omniintelligence.models.ScrollDirection
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OobOmniFlowLoopAcceptanceTest {
    @Test
    fun `explored run log registers recalls and replays through local omniflow runner`() = runBlocking {
        val context = TempFilesContext()
        try {
            val workspaceStore = WorkspaceFunctionStore(context.root)
            val toolkit = OobOmniFlowToolkitService(context, workspaceStore)
            val runId = "utg-loop-${System.nanoTime()}"
            val goal = "open network settings"
            val functionId = "oob_acceptance_explore_replay_loop"

            val before = requireNotNull(
                OobOmniFlowExplorer.parseSnapshot(
                    xml = SOURCE_XML,
                    packageName = "com.example.settings",
                    activityName = "SettingsActivity",
                )
            )
            val after = requireNotNull(
                OobOmniFlowExplorer.parseSnapshot(
                    xml = AFTER_XML,
                    packageName = "com.example.settings",
                    activityName = "NetworkActivity",
                )
            )
            val exploredCandidate = OobOmniFlowExplorer.rankCandidates(before, goal).first()
            val terminalCandidate = exploredCandidate.copy(action = "finished")
            val edge = OobOmniFlowExplorer.edgeFor(
                before = before,
                after = after,
                candidate = terminalCandidate,
                stepIndex = 0,
            )
            val card = OobOmniFlowExplorer.buildActionCard(
                stepIndex = 0,
                before = before,
                after = after,
                candidate = terminalCandidate,
                edge = edge,
            )

            InternalRunLogStore.beginRun(
                context = context,
                runId = runId,
                goal = goal,
                source = "oob_native_omniflow_explorer",
                toolName = "omniflow.explore_replay",
                operationDescription = goal,
            )
            InternalRunLogStore.appendCard(context, runId, card)
            InternalRunLogStore.finishRun(
                context = context,
                runId = runId,
                success = true,
                doneReason = "utg_exploration_completed",
            )

            val convert = toolkit.convertRunLog(
                mapOf(
                    "run_id" to runId,
                    "register" to true,
                    "function_id" to functionId,
                    "name" to goal,
                    "description" to goal,
                )
            )
            assertEquals(true, convert["success"])
            assertEquals(functionId, convert["function_id"])
            assertEquals(true, convert["registered"])

            val recall = toolkit.recall(
                mapOf(
                    "goal" to goal,
                    "current_package" to "com.example.settings",
                    "current_xml" to SOURCE_XML,
                    "k" to 3,
                )
            )
            assertEquals(true, recall["success"])
            assertEquals("hit", recall["decision"])
            assertEquals("oob_native_udeg_page_match", recall["source"])
            assertEquals(OobUdegNodeStore.UDEG_DECISION_PATH, recall["decision_path"])
            val currentNode = recall["current_node"] as? Map<*, *>
            assertNotNull(currentNode)
            val nodeSkill = currentNode?.get("skill") as? Map<*, *>
            assertEquals("udeg_node_skill", nodeSkill?.get("kind"))
            assertEquals("decision_context", nodeSkill?.get("role"))
            assertEquals(OobUdegNodeStore.UDEG_DECISION_PATH, nodeSkill?.get("decision_path"))
            val decisionContext = recall["decision_context"] as? Map<*, *>
            assertEquals("page_match_to_udeg_node", decisionContext?.get("entry_policy"))
            val hit = recall["hit"] as? Map<*, *>
            assertEquals(functionId, hit?.get("function_id"))
            assertNotNull(hit?.get("udeg_node"))
            val recalledSteps = hit?.get("step_summaries") as? List<*>
            assertEquals(1, recalledSteps?.size)

            val call = toolkit.callFunction(
                mapOf(
                    "function_id" to functionId,
                    "goal" to goal,
                )
            )
            assertEquals(true, call["success"])
            assertEquals(false, call["fallback"])
            assertEquals(1, (call["actions_executed"] as Number).toInt())

            val oobResult = call["oob_result"] as? Map<*, *>
            assertNotNull(oobResult)
            assertEquals("oob_omniflow_replay", oobResult?.get("runner"))
            assertEquals(false, oobResult?.get("model_required"))

            val stepResults = call["step_results"] as? List<*>
            assertEquals(1, stepResults?.size)
            val replayedStep = stepResults?.single() as? Map<*, *>
            assertEquals("omniflow", replayedStep?.get("executor"))
            assertEquals("finished", replayedStep?.get("tool"))
            assertEquals(true, replayedStep?.get("success"))

            val stored = toolkit.getFunction(mapOf("function_id" to functionId))
            val execution = stored["execution"] as? Map<*, *>
            assertEquals(functionId, stored["function_id"])
            assertEquals(1, (execution?.get("omniflow_step_count") as Number).toInt())
            assertEquals(false, execution["requires_agent_fallback"])
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `recall only ranks functions attached to the page matched UDEG node`() = runBlocking {
        val context = TempFilesContext()
        try {
            val toolkit = OobOmniFlowToolkitService(context, WorkspaceFunctionStore(context.root))
            val matchingFunctionId = "open_network_from_matched_node"
            val otherFunctionId = "open_network_from_other_node"

            val matchingRegister = toolkit.registerFunction(
                mapOf(
                    "functionSpec" to pageBackedFunctionSpec(
                        functionId = matchingFunctionId,
                        description = "open network settings",
                        sourceXml = SOURCE_XML,
                        packageName = "com.example.settings"
                    )
                )
            )
            val otherRegister = toolkit.registerFunction(
                mapOf(
                    "functionSpec" to pageBackedFunctionSpec(
                        functionId = otherFunctionId,
                        description = "open network settings",
                        sourceXml = AFTER_XML,
                        packageName = "com.example.settings"
                    )
                )
            )
            assertEquals(true, matchingRegister["success"])
            assertEquals(true, otherRegister["success"])

            val recall = toolkit.recall(
                mapOf(
                    "goal" to "open network settings",
                    "current_package" to "com.example.settings",
                    "current_xml" to SOURCE_XML,
                    "k" to 5,
                )
            )

            assertEquals(true, recall["success"])
            assertEquals("hit", recall["decision"])
            val hit = recall["hit"] as? Map<*, *>
            assertEquals(matchingFunctionId, hit?.get("function_id"))

            val currentNode = recall["current_node"] as? Map<*, *>
            val functionIds = currentNode?.get("function_ids") as? List<*>
            assertTrue(functionIds.orEmpty().contains(matchingFunctionId))
            assertFalse(functionIds.orEmpty().contains(otherFunctionId))
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `guard allows finished marker even when source xml contains blocked words`() = runBlocking {
        val context = TempFilesContext()
        try {
            val workspaceStore = WorkspaceFunctionStore(context.root)
            val toolkit = OobOmniFlowToolkitService(context, workspaceStore)
            val functionId = "oob_guard_finished_marker"

            val register = toolkit.registerFunction(
                mapOf(
                    "function_id" to functionId,
                    "name" to "Terminal marker",
                    "description" to "Verify that terminal markers are not blocked by page XML.",
                    "execution" to mapOf(
                        "kind" to "tool_sequence",
                        "runner" to "oob_tool_sequence",
                        "entrypoint" to "execute",
                        "capabilities" to mapOf(
                            "scriptable_step_count" to 1,
                            "model_free_step_count" to 1,
                            "omniflow_step_count" to 1,
                            "agent_step_count" to 0,
                            "requires_agent_fallback" to false,
                        ),
                        "steps" to listOf(
                            mapOf(
                                "id" to "step_1",
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
                                "args" to mapOf("content" to "Done"),
                                "source_context" to mapOf(
                                    "src_ctx" to mapOf(
                                        "page" to "<hierarchy><node text=\"Fastboot and reboot settings\" /></hierarchy>"
                                    )
                                ),
                            )
                        ),
                        "step_count" to 1,
                        "omniflow_step_count" to 1,
                        "agent_step_count" to 0,
                        "requires_agent_fallback" to false,
                    ),
                )
            )
            assertEquals(true, register["success"])

            val guard = toolkit.guardCheck(mapOf("function_id" to functionId))
            assertEquals(true, guard["success"])
            assertEquals("allow", guard["decision"])
            assertEquals(false, guard["requires_root"])
            val stepDecisions = guard["step_decisions"] as? List<*>
            val step = stepDecisions?.single() as? Map<*, *>
            assertEquals("allow", step?.get("decision"))
            assertEquals("low", step?.get("risk_level"))

            val call = toolkit.callFunction(mapOf("function_id" to functionId))
            assertEquals(true, call["success"])
            assertEquals(false, call["fallback"])
            assertEquals(1, (call["actions_executed"] as Number).toInt())
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `parent function loads reusable open settings segment through call_tool`() = runBlocking {
        val context = TempFilesContext()
        val backend = RecordingOmniflowBackend(initialPackage = "com.android.launcher")
        val backendHandle = OmniflowActionRuntime.useBackendForTesting(backend)
        try {
            val toolkit = OobOmniFlowToolkitService(context, WorkspaceFunctionStore(context.root))
            val childFunctionId = "open_settings_segment"
            val parentFunctionId = "parent_calls_open_settings_segment"

            val registerChild = toolkit.registerFunction(
                mapOf(
                    "functionSpec" to reusableFunctionSpec(
                        functionId = childFunctionId,
                        name = "Open Settings",
                        description = "Open Android Settings from any current screen.",
                        steps = listOf(openSettingsStep())
                    )
                )
            )
            val registerParent = toolkit.registerFunction(
                mapOf(
                    "functionSpec" to reusableFunctionSpec(
                        functionId = parentFunctionId,
                        name = "Call Open Settings",
                        description = "Parent Function that reuses the open Settings segment.",
                        steps = listOf(callFunctionStep(childFunctionId))
                    )
                )
            )
            assertEquals(true, registerChild["success"])
            assertEquals(true, registerParent["success"])

            val storedChild = toolkit.getFunction(mapOf("function_id" to childFunctionId))
            assertEquals(childFunctionId, storedChild["function_id"])
            val recall = toolkit.recall(mapOf("goal" to "open settings", "current_package" to "com.android.contacts"))
            assertEquals(true, recall["success"])
            assertEquals("miss", recall["decision"])
            assertEquals("missing_current_page_for_udeg_page_match", recall["reason"])

            val firstRun = toolkit.callFunction(
                mapOf(
                    "function_id" to parentFunctionId,
                    "goal" to "open settings",
                )
            )
            assertOpenSettingsParentRun(firstRun, childFunctionId)
            assertEquals(listOf("com.android.settings"), backend.launchedPackages)
            assertEquals("com.android.settings", backend.currentPackageName())

            backend.setCurrentPackage("com.android.contacts")
            val secondRun = toolkit.callFunction(
                mapOf(
                    "function_id" to parentFunctionId,
                    "goal" to "open settings",
                )
            )
            assertOpenSettingsParentRun(secondRun, childFunctionId)
            assertEquals(
                listOf("com.android.settings", "com.android.settings"),
                backend.launchedPackages
            )
            assertEquals("com.android.settings", backend.currentPackageName())
        } finally {
            backendHandle.close()
            context.root.deleteRecursively()
        }
    }

    private fun assertOpenSettingsParentRun(run: Map<String, Any?>, childFunctionId: String) {
        assertEquals(true, run["success"])
        assertEquals(false, run["fallback"])
        assertEquals(1, (run["actions_executed"] as Number).toInt())
        val stepResults = run["step_results"] as? List<*>
        val parentStep = stepResults?.single() as? Map<*, *>
        assertEquals("call_tool", parentStep?.get("tool"))
        assertEquals("omniflow_function", parentStep?.get("executor"))
        assertEquals(childFunctionId, parentStep?.get("nested_function_id"))
        assertEquals(true, parentStep?.get("success"))
        val nestedSteps = parentStep?.get("step_results") as? List<*>
        val nestedStep = nestedSteps?.single() as? Map<*, *>
        assertEquals("open_app", nestedStep?.get("tool"))
        assertEquals("omniflow", nestedStep?.get("executor"))
        assertEquals(true, nestedStep?.get("success"))
    }

    private fun reusableFunctionSpec(
        functionId: String,
        name: String,
        description: String,
        steps: List<Map<String, Any?>>
    ): Map<String, Any?> = mapOf(
        "schema_version" to "oob.reusable_function.v1",
        "function_id" to functionId,
        "name" to name,
        "description" to description,
        "parameters" to emptyList<Map<String, Any?>>(),
        "source" to mapOf(
            "source" to "unit_test",
            "goal" to description,
            "tool_name" to "omniflow.call_tool"
        ),
        "execution" to mapOf(
            "kind" to "tool_sequence",
            "runner" to "oob_tool_sequence",
            "entrypoint" to "execute",
            "capabilities" to mapOf(
                "scriptable_step_count" to steps.size,
                "model_free_step_count" to steps.size,
                "omniflow_step_count" to steps.size,
                "agent_step_count" to 0,
                "requires_agent_fallback" to false,
            ),
            "steps" to steps,
            "step_count" to steps.size,
            "omniflow_step_count" to steps.size,
            "agent_step_count" to 0,
            "requires_agent_fallback" to false,
        )
    )

    private fun pageBackedFunctionSpec(
        functionId: String,
        description: String,
        sourceXml: String,
        packageName: String
    ): Map<String, Any?> = reusableFunctionSpec(
        functionId = functionId,
        name = description,
        description = description,
        steps = listOf(pageBackedFinishedStep(sourceXml, packageName))
    ) + mapOf(
        "source" to mapOf(
            "source" to "unit_test",
            "goal" to description,
            "tool_name" to "omniflow.call_tool",
            "package_name" to packageName,
        )
    )

    private fun pageBackedFinishedStep(sourceXml: String, packageName: String): Map<String, Any?> = mapOf(
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
        "args" to mapOf("content" to "Done"),
        "source_context" to mapOf(
            "src_ctx" to mapOf(
                "page" to sourceXml,
                "package_name" to packageName,
            )
        ),
    )

    private fun openSettingsStep(): Map<String, Any?> = mapOf(
        "id" to "open_settings",
        "index" to 0,
        "title" to "Open Settings",
        "kind" to "omniflow_action",
        "executor" to "omniflow",
        "omniflow_action" to "open_app",
        "local_action" to "open_app",
        "tool" to "open_app",
        "callable_tool" to "open_app",
        "model_free" to true,
        "scriptable" to true,
        "args" to mapOf("package_name" to "com.android.settings"),
    )

    private fun callFunctionStep(functionId: String): Map<String, Any?> = mapOf(
        "id" to "call_open_settings_segment",
        "index" to 0,
        "title" to "Call open Settings segment",
        "kind" to "omniflow_function",
        "executor" to "omniflow",
        "tool" to "call_tool",
        "callable_tool" to "call_tool",
        "model_free" to true,
        "scriptable" to true,
        "args" to mapOf(
            "function_id" to functionId,
            "arguments" to emptyMap<String, Any?>(),
        ),
    )

    private class RecordingOmniflowBackend(initialPackage: String) : OmniflowActionBackend {
        private var currentPackage = initialPackage
        val launchedPackages = mutableListOf<String>()

        override fun isReady(): Boolean = true

        override suspend fun click(x: Float, y: Float) {
            error("click should not be used by this test")
        }

        override suspend fun longPress(x: Float, y: Float, durationMs: Long) {
            error("longPress should not be used by this test")
        }

        override suspend fun scroll(
            x: Float,
            y: Float,
            direction: ScrollDirection,
            distance: Float,
            durationMs: Long
        ) {
            error("scroll should not be used by this test")
        }

        override suspend fun inputTextToFocusedNode(text: String) {
            error("inputTextToFocusedNode should not be used by this test")
        }

        override suspend fun launchApplication(packageName: String) {
            launchedPackages += packageName
            currentPackage = packageName
        }

        override suspend fun pressHotKey(key: String) {
            error("pressHotKey should not be used by this test")
        }

        override fun currentXml(): String? = null

        override fun currentPackageName(): String = currentPackage

        override fun currentActivityName(): String? = null

        fun setCurrentPackage(packageName: String) {
            currentPackage = packageName
        }
    }

    class TempFilesContext : ContextWrapper(null) {
        val root: File = Files.createTempDirectory("oob-omniflow-loop-test").toFile()
        private val prefsByName = linkedMapOf<String, InMemorySharedPreferences>()

        override fun getApplicationContext(): Context = this

        override fun getFilesDir(): File = root

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

    companion object {
        private const val SOURCE_XML = """
            <hierarchy bounds="[0,0][1080,1920]">
              <node index="0" package="com.example.settings" class="android.widget.TextView" text="Network" content-desc="" resource-id="android:id/network" clickable="true" enabled="true" visible-to-user="true" bounds="[40,200][1040,320]" />
              <node index="1" package="com.example.settings" class="android.widget.TextView" text="Delete account" content-desc="" resource-id="android:id/delete" clickable="true" enabled="true" visible-to-user="true" bounds="[40,360][1040,480]" />
            </hierarchy>
        """

        private const val AFTER_XML = """
            <hierarchy bounds="[0,0][1080,1920]">
              <node index="0" package="com.example.settings" class="android.widget.TextView" text="Internet" content-desc="" resource-id="android:id/internet" clickable="true" enabled="true" visible-to-user="true" bounds="[40,200][1040,320]" />
            </hierarchy>
        """
    }
}
