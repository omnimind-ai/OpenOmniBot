package cn.com.omnimind.bot.omniflow

import cn.com.omnimind.assists.task.vlmserver.OperationResult
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniFlowSimpleIngestTest {
    @Test
    fun `ingest converts vlm run log into replayable function`() {
        val ingest = OmniFlowSimpleIngest()

        val (runLog, function) = ingest.ingestRunLogJson(
            """
            {
              "run_id": "run_settings_1",
              "goal": "打开设置并搜索网络",
              "success": true,
              "started_at_ms": 1000,
              "finished_at_ms": 3200,
              "final_package_name": "com.android.settings",
              "steps": [
                {
                  "index": 0,
                  "action": {
                    "name": "open_app",
                    "package_name": "com.android.settings"
                  },
                  "success": true
                },
                {
                  "index": 1,
                  "action": {
                    "name": "type",
                    "content": "网络"
                  },
                  "success": true
                },
                {
                  "index": 2,
                  "action": {
                    "name": "record",
                    "content": "ignore non replay action"
                  },
                  "success": true
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals("run_settings_1", runLog.runId)
        assertTrue(runLog.replayable)
        requireNotNull(function)
        assertEquals("com.android.settings", function.packageName)
        assertEquals(listOf("open_app", "input_text"), function.actions.map { it.type })
        assertEquals("com.android.settings", function.actions[0].params["package_name"]?.toInteropValue())
        assertEquals("网络", function.actions[1].params["text"]?.toInteropValue())
    }

    @Test
    fun `ingest stores summary task run log without creating function`() {
        val store = tempStore()
        val ingest = OmniFlowSimpleIngest(store)

        val (runLog, function) = ingest.ingestRunLogJson(
            """
            {
              "run_id": "chat_task_1",
              "task_id": "chat_task_1",
              "task_type": "chat",
              "goal": "帮我整理今天的计划",
              "success": true,
              "status": "success",
              "message": "done",
              "source": "chat",
              "started_at_ms": 1000,
              "finished_at_ms": 2000,
              "metadata": {
                "provider": "openclaw",
                "message_count": "2"
              },
              "steps": []
            }
            """.trimIndent()
        )

        assertEquals("chat", runLog.taskType)
        assertFalse(runLog.replayable)
        assertEquals("openclaw", runLog.metadata["provider"])
        assertEquals(null, function)
        assertEquals(1, store.listRunLogs().size)
        assertTrue(store.listFunctions().isEmpty())
    }

    @Test
    fun `action rich run log upgrades existing summary run log`() {
        val store = tempStore()
        val ingest = OmniFlowSimpleIngest(store)

        ingest.ingestRunLogJson(
            """
            {
              "run_id": "vlm_task_1",
              "task_id": "vlm_task_1",
              "task_type": "vlm_operation_execution",
              "goal": "打开设置",
              "success": true,
              "started_at_ms": 1000,
              "finished_at_ms": 1500,
              "source": "vlm",
              "metadata": {"model": "scene.vlm.operation.primary"},
              "steps": []
            }
            """.trimIndent()
        )

        val (runLog, function) = ingest.ingestRunLogJson(
            """
            {
              "run_id": "vlm_task_1",
              "task_id": "vlm_task_1",
              "task_type": "vlm_operation_execution",
              "goal": "打开设置",
              "success": true,
              "started_at_ms": 1000,
              "finished_at_ms": 2500,
              "final_package_name": "com.android.settings",
              "source": "vlm",
              "steps": [
                {
                  "index": 0,
                  "action": {
                    "name": "open_app",
                    "package_name": "com.android.settings"
                  },
                  "success": true
                }
              ]
            }
            """.trimIndent()
        )

        assertTrue(runLog.replayable)
        assertEquals("scene.vlm.operation.primary", runLog.metadata["model"])
        requireNotNull(function)
        assertEquals(1, store.listRunLogs().size)
        assertEquals(1, store.listFunctions().size)
    }

    @Test
    fun `executor uses provider hit before local replay`() = runBlocking {
        val store = tempStore()
        val function = store.saveFunction(
            OmniFlowSimpleFunction(
                functionId = "fn_provider",
                name = "fn_provider",
                description = "打开设置",
                actions = listOf(OmniFlowSimpleAction(type = "click"))
            )
        )
        val runner = RecordingRunner()
        val provider = object : OmniFlowSimpleProvider {
            override suspend fun execute(
                function: OmniFlowSimpleFunction,
                input: Map<String, Any?>
            ): ProviderExecutionHit {
                return ProviderExecutionHit(
                    functionId = function.functionId,
                    response = mapOf("success" to true, "route" to "provider")
                )
            }
        }

        val result = OmniFlowSimpleExecutor(store, provider, runner)
            .execute(function.functionId)

        assertTrue(result.success)
        assertEquals("provider", result.route)
        assertTrue(runner.actions.isEmpty())
        assertEquals(1, store.getFunction(function.functionId)?.runStats?.callCount)
    }

    @Test
    fun `executor falls back to local replay when provider misses`() = runBlocking {
        val store = tempStore()
        val function = store.saveFunction(
            OmniFlowSimpleFunction(
                functionId = "fn_local",
                name = "fn_local",
                description = "点击并完成",
                actions = listOf(
                    OmniFlowSimpleAction(type = "click"),
                    OmniFlowSimpleAction(type = "finished")
                )
            )
        )
        val runner = RecordingRunner()
        val provider = object : OmniFlowSimpleProvider {
            override suspend fun execute(
                function: OmniFlowSimpleFunction,
                input: Map<String, Any?>
            ): ProviderExecutionHit? = null
        }

        val result = OmniFlowSimpleExecutor(store, provider, runner)
            .execute(function.functionId)

        assertTrue(result.success)
        assertEquals("local_replay", result.route)
        assertEquals(listOf("click", "finished"), runner.actions)
        assertEquals(1, store.getFunction(function.functionId)?.runStats?.successCount)
    }

    @Test
    fun `executor stops local replay on unsupported action`() = runBlocking {
        val store = tempStore()
        val function = store.saveFunction(
            OmniFlowSimpleFunction(
                functionId = "fn_bad",
                name = "fn_bad",
                description = "unsupported",
                actions = listOf(
                    OmniFlowSimpleAction(type = "click"),
                    OmniFlowSimpleAction(type = "record"),
                    OmniFlowSimpleAction(type = "finished")
                )
            )
        )
        val runner = RecordingRunner()

        val result = OmniFlowSimpleExecutor(store, provider = null, runner = runner)
            .execute(function.functionId)

        assertFalse(result.success)
        assertEquals("local_replay", result.route)
        assertEquals(listOf("click"), runner.actions)
        assertEquals(1, store.getFunction(function.functionId)?.runStats?.failCount)
    }

    private fun tempStore(): OmniFlowSimpleStore {
        val dir = File(
            System.getProperty("java.io.tmpdir"),
            "omniflow-simple-utg-test-${System.nanoTime()}"
        )
        return OmniFlowSimpleStore(dir)
    }

    private class RecordingRunner : OmniFlowSimpleActionRunner {
        val actions = mutableListOf<String>()

        override suspend fun run(action: OmniFlowSimpleAction): OperationResult {
            val type = action.normalizedType()
            actions.add(type)
            return OperationResult(true, if (type == "finished") "done" else "ok")
        }
    }
}
