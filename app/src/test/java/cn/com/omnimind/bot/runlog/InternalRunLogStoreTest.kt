package cn.com.omnimind.bot.runlog

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import cn.com.omnimind.baselib.runlog.OobReusableFunctionStore
import cn.com.omnimind.bot.omniflow.OobFunctionRepository
import cn.com.omnimind.bot.workbench.WorkspaceFunctionStore
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InternalRunLogStoreTest {
    @Test
    fun `timeline and list bind registered function status`() {
        val context = TempFilesContext()
        try {
            val runId = "run-registered-${System.nanoTime()}"
            InternalRunLogStore.beginRun(
                context = context,
                runId = runId,
                goal = "Open Settings",
                source = "vlm",
                toolName = "vlm"
            )
            InternalRunLogStore.appendCard(
                context = context,
                runId = runId,
                card = linkedMapOf(
                    "card_id" to "card-1",
                    "tool_name" to "open_app",
                    "tool_call" to linkedMapOf(
                        "name" to "open_app",
                        "arguments" to linkedMapOf(
                            "package_name" to "com.android.settings"
                        )
                    )
                )
            )
            InternalRunLogStore.finishRun(
                context = context,
                runId = runId,
                success = true,
                doneReason = "finished"
            )
            OobReusableFunctionStore.register(
                context = context,
                functionSpec = linkedMapOf(
                    "schema_version" to "oob.reusable_function.v1",
                    "function_id" to "fn_open_settings_from_run",
                    "name" to "Open Settings",
                    "description" to "Open Android Settings",
                    "source" to linkedMapOf(
                        "kind" to "run_log",
                        "run_id" to runId
                    ),
                    "metadata" to linkedMapOf(
                        "source_run_ids" to listOf(runId)
                    ),
                    "execution" to linkedMapOf(
                        "kind" to "tool_sequence",
                        "steps" to listOf(
                            linkedMapOf(
                                "id" to "step_1",
                                "tool" to "open_app",
                                "executor" to "omniflow",
                                "args" to linkedMapOf(
                                    "package_name" to "com.android.settings"
                                )
                            )
                        )
                    )
                )
            )

            val timeline = InternalRunLogStore.timelinePayload(context, runId)
            assertEquals(true, timeline["registered_as_function"])
            assertEquals(true, timeline["is_registered_function"])
            assertEquals("fn_open_settings_from_run", timeline["registered_function_id"])
            assertEquals(1, timeline["registered_function_count"])
            val ids = timeline["registered_function_ids"] as List<*>
            assertEquals(listOf("fn_open_settings_from_run"), ids)
            val summary = timeline["registered_function_summary"] as Map<*, *>
            assertEquals("Open Settings", summary["name"])
            val spec = timeline["registered_function_spec"] as Map<*, *>
            assertEquals("fn_open_settings_from_run", spec["function_id"])

            val listPayload = InternalRunLogStore.listRuns(context, limit = 10)
            val runs = listPayload["runs"] as List<*>
            val listed = runs.first { (it as Map<*, *>)["run_id"] == runId } as Map<*, *>
            assertEquals(true, listed["registered_as_function"])
            assertEquals("fn_open_settings_from_run", listed["registered_function_id"])
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `explicit runlog binding marks registered function without source run metadata`() {
        val context = TempFilesContext()
        try {
            val runId = "run-explicit-binding-${System.nanoTime()}"
            val functionId = "fn_without_source_run_metadata"
            InternalRunLogStore.beginRun(
                context = context,
                runId = runId,
                goal = "Open app",
                source = "vlm",
                toolName = "vlm"
            )
            InternalRunLogStore.finishRun(
                context = context,
                runId = runId,
                success = true,
                doneReason = "finished"
            )
            val spec = linkedMapOf<String, Any?>(
                "schema_version" to "oob.reusable_function.v1",
                "function_id" to functionId,
                "name" to "Open app without source metadata",
                "description" to "Registered first, then explicitly bound to a RunLog",
                "execution" to linkedMapOf(
                    "kind" to "tool_sequence",
                    "steps" to listOf(
                        linkedMapOf(
                            "id" to "step_1",
                            "tool" to "open_app",
                            "executor" to "omniflow",
                            "args" to linkedMapOf(
                                "package_name" to "com.android.settings"
                            )
                        )
                    )
                )
            )
            OobReusableFunctionStore.register(context, spec)

            val bindResult = InternalRunLogStore.bindRegisteredFunction(
                context = context,
                runId = runId,
                functionId = functionId,
                functionSpec = spec
            )

            assertEquals(true, bindResult["registered_as_function"])
            assertEquals(listOf(functionId), bindResult["registered_function_ids"])
            assertEquals(listOf(functionId), bindResult["registered_function_binding_ids"])

            val timeline = InternalRunLogStore.timelinePayload(context, runId)
            assertEquals(true, timeline["registered_as_function"])
            assertEquals(functionId, timeline["registered_function_id"])
            assertEquals(true, timeline["has_registered_function_binding"])
            val bindings = timeline["registered_function_bindings"] as List<*>
            assertEquals(functionId, (bindings.single() as Map<*, *>)["function_id"])

            val listPayload = InternalRunLogStore.listRuns(context, limit = 10)
            val runs = listPayload["runs"] as List<*>
            val listed = runs.first { (it as Map<*, *>)["run_id"] == runId } as Map<*, *>
            assertEquals(true, listed["registered_as_function"])
            assertEquals(functionId, listed["registered_function_id"])
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `runlog keeps registered status from explicit binding when function spec is unavailable`() {
        val context = TempFilesContext()
        try {
            val runId = "run-explicit-binding-only-${System.nanoTime()}"
            val functionId = "fn_binding_only"
            InternalRunLogStore.beginRun(
                context = context,
                runId = runId,
                goal = "Open app",
                source = "vlm",
                toolName = "vlm"
            )
            InternalRunLogStore.finishRun(
                context = context,
                runId = runId,
                success = true,
                doneReason = "finished"
            )
            val spec = linkedMapOf<String, Any?>(
                "schema_version" to "oob.reusable_function.v1",
                "function_id" to functionId,
                "name" to "Binding-only function",
                "description" to "RunLog should remember that this was registered",
                "execution" to linkedMapOf(
                    "kind" to "tool_sequence",
                    "steps" to listOf(
                        linkedMapOf(
                            "id" to "step_1",
                            "tool" to "open_app",
                            "executor" to "omniflow",
                            "args" to linkedMapOf(
                                "package_name" to "com.android.settings"
                            )
                        )
                    )
                )
            )

            InternalRunLogStore.bindRegisteredFunction(
                context = context,
                runId = runId,
                functionId = functionId,
                functionSpec = spec
            )

            val timeline = InternalRunLogStore.timelinePayload(context, runId)
            assertEquals(true, timeline["registered_as_function"])
            assertEquals(true, timeline["is_registered_function"])
            assertEquals(functionId, timeline["registered_function_id"])
            assertEquals(1, timeline["registered_function_count"])
            val summary = timeline["registered_function_summary"] as Map<*, *>
            assertEquals("Binding-only function", summary["name"])

            val listPayload = InternalRunLogStore.listRuns(context, limit = 10)
            val runs = listPayload["runs"] as List<*>
            val listed = runs.first { (it as Map<*, *>)["run_id"] == runId } as Map<*, *>
            assertEquals(true, listed["registered_as_function"])
            assertEquals(functionId, listed["registered_function_id"])
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `registering function spec writes source run binding back to runlog`() {
        val context = TempFilesContext()
        try {
            val runId = "run-register-service-binding-${System.nanoTime()}"
            val functionId = "fn_bound_by_register_service"
            InternalRunLogStore.beginRun(
                context = context,
                runId = runId,
                goal = "Open Settings",
                source = "vlm",
                toolName = "vlm"
            )
            InternalRunLogStore.finishRun(
                context = context,
                runId = runId,
                success = true,
                doneReason = "finished"
            )

            val result = OobFunctionRepository(
                context = context,
                workspaceFunctionStore = WorkspaceFunctionStore(File(context.root, "workspace"))
            ).register(
                linkedMapOf(
                    "schema_version" to "oob.reusable_function.v1",
                    "function_id" to functionId,
                    "name" to "Open Settings From Registered RunLog",
                    "description" to "Registered through replay service",
                    "source" to linkedMapOf(
                        "kind" to "run_log",
                        "run_id" to runId
                    ),
                    "execution" to linkedMapOf(
                        "kind" to "tool_sequence",
                        "steps" to listOf(
                            linkedMapOf(
                                "id" to "step_1",
                                "tool" to "open_app",
                                "executor" to "omniflow",
                                "args" to linkedMapOf(
                                    "package_name" to "com.android.settings"
                                )
                            )
                        )
                    )
                )
            )

            assertEquals(true, result["success"])
            assertEquals(1, result["run_log_binding_count"])
            val timeline = InternalRunLogStore.timelinePayload(context, runId)
            assertEquals(true, timeline["registered_as_function"])
            assertEquals(functionId, timeline["registered_function_id"])
            assertEquals(true, timeline["has_registered_function_binding"])
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `timeline applies append only running card events after snapshot`() {
        val context = TempFilesContext()
        try {
            val runId = "run-${System.nanoTime()}"
            InternalRunLogStore.beginRun(
                context = context,
                runId = runId,
                goal = "Replay append events",
                source = "agent",
                toolName = "agent"
            )
            InternalRunLogStore.upsertCard(
                context = context,
                runId = runId,
                cardId = "card-1",
                card = runningCard("card-1", "first")
            )

            val firstTimeline = InternalRunLogStore.timelinePayload(context, runId)
            val eventLog = File(firstTimeline["event_log_path"]?.toString().orEmpty())
            val firstEventCount = eventLog.readLines().size
            assertTrue(firstEventCount >= 2)

            InternalRunLogStore.upsertCard(
                context = context,
                runId = runId,
                cardId = "card-1",
                card = runningCard("card-1", "second")
            )

            val secondTimeline = InternalRunLogStore.timelinePayload(context, runId)
            val cards = secondTimeline["cards"] as List<*>
            val card = cards.single() as Map<*, *>
            assertEquals("second", card["summary"])
            assertTrue(eventLog.readLines().size > firstEventCount)
            assertTrue((secondTimeline["event_seq"] as Number).toLong() >= 3L)
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `timeline reports aggregate token usage`() {
        val context = TempFilesContext()
        try {
            val runId = "run-token-${System.nanoTime()}"
            InternalRunLogStore.beginRun(
                context = context,
                runId = runId,
                goal = "Token usage",
                source = "vlm",
                toolName = "vlm"
            )
            InternalRunLogStore.appendCard(
                context = context,
                runId = runId,
                card = tokenCard(
                    "card-1",
                    prompt = 10,
                    completion = 4,
                    total = 14,
                    attempts = listOf(
                        tokenUsage(prompt = 6, completion = 2, total = 8, attemptIndex = 1),
                        tokenUsage(prompt = 4, completion = 2, total = 6, attemptIndex = 2)
                    )
                )
            )
            InternalRunLogStore.appendCard(
                context = context,
                runId = runId,
                card = tokenCard("card-2", prompt = 20, completion = 6, total = 26)
            )

            val timeline = InternalRunLogStore.timelinePayload(context, runId)
            val usage = timeline["token_usage"] as Map<*, *>
            val byStep = timeline["token_usage_by_step"] as List<*>
            val byCall = timeline["token_usage_by_call"] as List<*>

            assertEquals(30L, usage["prompt_tokens"])
            assertEquals(10L, usage["completion_tokens"])
            assertEquals(40L, usage["total_tokens"])
            assertEquals(3L, usage["attempt_count"])
            assertEquals(3, usage["call_count"])
            assertEquals(40L, timeline["token_usage_total"])
            assertEquals(2, byStep.size)
            assertEquals(3, byCall.size)
            assertEquals(0, (byCall[0] as Map<*, *>)["call_index"])
            assertEquals(1, (byCall[0] as Map<*, *>)["attempt_index"])
            assertEquals(2, (byCall[1] as Map<*, *>)["attempt_index"])
            assertEquals(2, (byCall[2] as Map<*, *>)["step_index"])
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `timeline preserves internal timing fields on cards`() {
        val context = TempFilesContext()
        try {
            val runId = "run-timing-${System.nanoTime()}"
            InternalRunLogStore.beginRun(
                context = context,
                runId = runId,
                goal = "Timing usage",
                source = "vlm",
                toolName = "vlm"
            )
            InternalRunLogStore.appendCard(
                context = context,
                runId = runId,
                card = linkedMapOf(
                    "card_id" to "card-timing-1",
                    "tool_name" to "open_app",
                    "duration_ms" to 125L,
                    "started_at_ms" to 1700000000000L,
                    "finished_at_ms" to 1700000000125L,
                    "header" to linkedMapOf(
                        "step_index" to 0,
                        "duration_ms" to 125L
                    )
                )
            )

            val timeline = InternalRunLogStore.timelinePayload(context, runId)
            val cards = timeline["cards"] as List<*>
            val card = cards.single() as Map<*, *>
            val header = card["header"] as Map<*, *>

            assertEquals(125L, (card["duration_ms"] as Number).toLong())
            assertEquals(1700000000000L, (card["started_at_ms"] as Number).toLong())
            assertEquals(1700000000125L, (card["finished_at_ms"] as Number).toLong())
            assertEquals(125L, (header["duration_ms"] as Number).toLong())
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `timeline recovers event only cards before final snapshot`() {
        val context = TempFilesContext()
        try {
            val runId = "run-event-only-cards-${System.nanoTime()}"
            InternalRunLogStore.beginRun(
                context = context,
                runId = runId,
                goal = "Manual recording",
                source = "human_trajectory",
                toolName = "human_trajectory"
            )
            InternalRunLogStore.appendCards(
                context = context,
                runId = runId,
                cards = listOf(
                    linkedMapOf(
                        "card_id" to "card-1",
                        "tool_name" to "click",
                        "summary" to "tap search box"
                    ),
                    linkedMapOf(
                        "card_id" to "card-2",
                        "tool_name" to "input_text",
                        "summary" to "input final text"
                    )
                ),
                saveSnapshot = false
            )
            InternalRunLogStore.updateDiagnostics(
                context = context,
                runId = runId,
                diagnostics = linkedMapOf("recording_backend" to "overlay_touch"),
                saveSnapshot = false
            )

            val runningTimeline = InternalRunLogStore.timelinePayload(context, runId)
            val runningCards = runningTimeline["cards"] as List<*>
            assertEquals(2, runningCards.size)
            assertEquals("running", runningTimeline["status"])

            InternalRunLogStore.finishRun(
                context = context,
                runId = runId,
                success = true,
                doneReason = "user_completed"
            )

            val finishedTimeline = InternalRunLogStore.timelinePayload(context, runId)
            val finishedCards = finishedTimeline["cards"] as List<*>
            val diagnostics = finishedTimeline["diagnostics"] as Map<*, *>
            assertEquals(2, finishedCards.size)
            assertEquals("success", finishedTimeline["status"])
            assertEquals("overlay_touch", diagnostics["recording_backend"])
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `timeline preserves run level diagnostics`() {
        val context = TempFilesContext()
        try {
            val runId = "run-diagnostics-${System.nanoTime()}"
            InternalRunLogStore.beginRun(
                context = context,
                runId = runId,
                goal = "Manual diagnostics",
                source = "human_trajectory",
                toolName = "human_trajectory"
            )
            InternalRunLogStore.updateDiagnostics(
                context = context,
                runId = runId,
                diagnostics = linkedMapOf(
                    "raw_touch" to linkedMapOf(
                        "available" to true,
                        "backend" to "device_getevent",
                        "access_method" to "shizuku_session"
                    )
                )
            )

            val timeline = InternalRunLogStore.timelinePayload(context, runId)
            val diagnostics = timeline["diagnostics"] as Map<*, *>
            val rawTouch = diagnostics["raw_touch"] as Map<*, *>

            assertEquals(true, rawTouch["available"])
            assertEquals("device_getevent", rawTouch["backend"])
            assertEquals("shizuku_session", rawTouch["access_method"])
        } finally {
            context.root.deleteRecursively()
        }
    }

    private fun runningCard(cardId: String, summary: String): Map<String, Any?> {
        return linkedMapOf(
            "card_id" to cardId,
            "tool_name" to "browser_use",
            "summary" to summary,
            "status" to "running",
            "header" to linkedMapOf(
                "status" to "running",
                "success" to true
            )
        )
    }

    private fun tokenCard(
        cardId: String,
        prompt: Int,
        completion: Int,
        total: Int,
        attempts: List<Map<String, Any?>> = emptyList()
    ): Map<String, Any?> {
        return linkedMapOf(
            "card_id" to cardId,
            "tool_name" to "click",
            "step_index" to if (cardId == "card-2") 2 else 0,
            "token_usage" to linkedMapOf(
                "prompt_tokens" to prompt,
                "completion_tokens" to completion,
                "total_tokens" to total,
                "attempt_count" to if (attempts.isEmpty()) 1 else attempts.size
            ),
            "token_usage_attempts" to attempts.takeIf { it.isNotEmpty() }
        ).filterValues { it != null }
    }

    private fun tokenUsage(
        prompt: Int,
        completion: Int,
        total: Int,
        attemptIndex: Int
    ): Map<String, Any?> {
        return linkedMapOf(
            "prompt_tokens" to prompt,
            "completion_tokens" to completion,
            "total_tokens" to total,
            "attempt_index" to attemptIndex,
            "attempt_count" to 1
        )
    }

    private class TempFilesContext : ContextWrapper(null) {
        val root: File = Files.createTempDirectory("runlog-store-test").toFile()
        private val sharedPreferences = mutableMapOf<String, MemorySharedPreferences>()

        override fun getApplicationContext(): Context = this

        override fun getFilesDir(): File = root

        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
            return sharedPreferences.getOrPut(name.orEmpty()) { MemorySharedPreferences() }
        }
    }

    private class MemorySharedPreferences : SharedPreferences {
        private val values = linkedMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = linkedMapOf<String, Any?>().apply {
            putAll(this@MemorySharedPreferences.values)
        }

        override fun getString(key: String?, defValue: String?): String? {
            return values[key] as? String ?: defValue
        }

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
            return (values[key] as? Set<String>)?.toMutableSet() ?: defValues
        }

        override fun getInt(key: String?, defValue: Int): Int {
            return values[key] as? Int ?: defValue
        }

        override fun getLong(key: String?, defValue: Long): Long {
            return values[key] as? Long ?: defValue
        }

        override fun getFloat(key: String?, defValue: Float): Float {
            return values[key] as? Float ?: defValue
        }

        override fun getBoolean(key: String?, defValue: Boolean): Boolean {
            return values[key] as? Boolean ?: defValue
        }

        override fun contains(key: String?): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = MemoryEditor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        private inner class MemoryEditor : SharedPreferences.Editor {
            private val updates = linkedMapOf<String, Any?>()
            private val removals = linkedSetOf<String>()
            private var clear = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                key?.let { updates[it] = value }
                return this
            }

            override fun putStringSet(
                key: String?,
                values: MutableSet<String>?
            ): SharedPreferences.Editor {
                key?.let { updates[it] = values?.toSet() }
                return this
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                key?.let { updates[it] = value }
                return this
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                key?.let { updates[it] = value }
                return this
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                key?.let { updates[it] = value }
                return this
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                key?.let { updates[it] = value }
                return this
            }

            override fun remove(key: String?): SharedPreferences.Editor {
                key?.let(removals::add)
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
                updates.forEach { (key, value) ->
                    if (value == null) values.remove(key) else values[key] = value
                }
            }
        }
    }
}
