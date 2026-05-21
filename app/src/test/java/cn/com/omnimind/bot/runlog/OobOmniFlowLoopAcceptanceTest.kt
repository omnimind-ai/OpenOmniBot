package cn.com.omnimind.bot.runlog

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import cn.com.omnimind.bot.workbench.WorkspaceFunctionStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
                    "k" to 3,
                )
            )
            assertEquals(true, recall["success"])
            assertEquals("hit", recall["decision"])
            val hit = recall["hit"] as? Map<*, *>
            assertEquals(functionId, hit?.get("function_id"))
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

    private class TempFilesContext : ContextWrapper(null) {
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
