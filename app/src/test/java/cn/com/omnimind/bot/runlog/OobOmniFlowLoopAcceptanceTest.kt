package cn.com.omnimind.bot.runlog

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
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
    fun `simple function registration builds structured spec and UDEG recall candidate`() = runBlocking {
        val context = TempFilesContext()
        try {
            val toolkit = OobOmniFlowToolkitService(context, WorkspaceFunctionStore(context.root))
            val functionId = "simple_settings_from_agent"

            val register = toolkit.registerFunction(
                mapOf(
                    "functionId" to functionId,
                    "name" to "Open Settings",
                    "description" to "Open Android Settings from the launcher",
                    "packageName" to "com.android.settings",
                    "sourcePage" to mapOf(
                        "xml" to SOURCE_XML,
                        "packageName" to "com.example.settings",
                    ),
                    "steps" to listOf(
                        mapOf(
                            "action" to "open_app",
                            "packageName" to "com.android.settings",
                        ),
                        mapOf(
                            "action" to "finished",
                            "content" to "Settings opened",
                        ),
                    ),
                )
            )

            assertEquals(true, register["success"])
            assertEquals("simple", register["registration_input_mode"])
            val stored = toolkit.getFunction(mapOf("function_id" to functionId))
            assertEquals(true, stored["success"])
            assertEquals(functionId, stored["function_id"])
            assertEquals(functionId, (stored["function"] as? Map<*, *>)?.get("function_id"))
            assertEquals(functionId, (stored["summary"] as? Map<*, *>)?.get("function_id"))
            val execution = stored["execution"] as? Map<*, *>
            assertEquals(2, (execution?.get("step_count") as Number).toInt())
            assertEquals(false, execution["requires_agent_fallback"])

            val guard = toolkit.guardCheck(mapOf("functionId" to functionId))
            assertEquals(true, guard["success"])
            assertEquals("allow", guard["decision"])

            val recall = toolkit.recall(
                mapOf(
                    "goal" to "open settings",
                    "current_package" to "com.example.settings",
                    "current_xml" to SOURCE_XML,
                    "k" to 3,
                )
            )
            assertEquals(true, recall["success"])
            assertEquals("agent_compact", recall["payload_mode"])
            val candidates = recall["candidates"] as? List<*>
            val firstCandidate = candidates?.firstOrNull() as? Map<*, *>
            assertEquals(functionId, firstCandidate?.get("function_id"))
            val policy = recall["decision_policy"] as? Map<*, *>
            assertEquals("node_skill_context_only", policy?.get("mode"))
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `guard and replay skip get state observation steps without agent fallback`() = runBlocking {
        val context = TempFilesContext()
        try {
            val toolkit = OobOmniFlowToolkitService(context, WorkspaceFunctionStore(context.root))
            val functionId = "function_with_get_state_observation"

            val register = toolkit.registerFunction(
                mapOf(
                    "functionId" to functionId,
                    "name" to "Observation then done",
                    "description" to "Refresh page state before a terminal marker",
                    "steps" to listOf(
                        mapOf(
                            "action" to "get_state",
                            "reason" to "refresh current page",
                        ),
                        mapOf(
                            "action" to "finished",
                            "content" to "Done",
                        ),
                    ),
                )
            )
            assertEquals(true, register["success"])

            val guard = toolkit.guardCheck(mapOf("function_id" to functionId))
            assertEquals(true, guard["success"])
            assertEquals("allow", guard["decision"])
            val stepDecisions = guard["step_decisions"] as? List<*>
            val firstStep = stepDecisions?.firstOrNull() as? Map<*, *>
            assertEquals("get_state", firstStep?.get("tool"))
            assertEquals("allow", firstStep?.get("decision"))

            val call = toolkit.callFunction(mapOf("function_id" to functionId))
            assertEquals(true, call["success"])
            assertEquals(false, call["fallback"])
            assertEquals(false, (call["oob_result"] as? Map<*, *>)?.get("model_required"))
            val results = call["step_results"] as? List<*>
            val firstResult = results?.firstOrNull() as? Map<*, *>
            assertEquals(true, firstResult?.get("skipped"))
            assertEquals(true, firstResult?.get("success"))
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `fixed replay ignores legacy terminal postconditions and executes steps`() = runBlocking {
        val context = TempFilesContext()
        val backend = RecordingOmniflowBackend(
            initialPackage = "com.android.settings",
            currentXml = "<hierarchy><node text=\"Bluetooth\" checked=\"true\" /></hierarchy>",
        )
        val backendHandle = OmniflowActionRuntime.useBackendForTesting(backend)
        try {
            val toolkit = OobOmniFlowToolkitService(context, WorkspaceFunctionStore(context.root))
            val functionId = "settings_toggle_already_satisfied"
            val register = toolkit.registerFunction(
                mapOf(
                    "functionSpec" to mapOf(
                        "schema_version" to "oob.reusable_function.v1",
                        "function_id" to functionId,
                        "name" to "Open Bluetooth",
                        "description" to "打开蓝牙",
                        "parameters" to emptyList<Map<String, Any?>>(),
                        "terminal_postconditions" to listOf(
                            mapOf(
                                "kind" to "android_settings_toggle",
                                "goal" to "打开蓝牙",
                            )
                        ),
                        "execution" to mapOf(
                            "kind" to "tool_sequence",
                            "runner" to "oob_tool_sequence",
                            "steps" to listOf(
                                mapOf(
                                    "id" to "step_1",
                                    "index" to 0,
                                    "title" to "Click Bluetooth switch",
                                    "kind" to "omniflow_action",
                                    "executor" to "omniflow",
                                    "omniflow_action" to "click",
                                    "local_action" to "click",
                                    "tool" to "click",
                                    "callable_tool" to "click",
                                    "model_free" to true,
                                    "scriptable" to true,
                                    "args" to mapOf("x" to 360, "y" to 513),
                                ),
                            ),
                        ),
                    ),
                )
            )
            assertEquals(true, register["success"])

            val call = toolkit.callFunction(mapOf("function_id" to functionId))
            assertEquals(true, call["success"])
            assertEquals(false, call["fallback"])
            val results = call["step_results"] as? List<*>
            val firstResult = results?.single() as? Map<*, *>
            assertEquals("omniflow", firstResult?.get("executor"))
            assertEquals("click", firstResult?.get("tool"))
            assertEquals(true, firstResult?.get("success"))
            assertFalse(firstResult?.containsKey("skipped") == true)
            assertEquals(listOf(360f to 513f), backend.clicks)
        } finally {
            backendHandle.close()
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `simple function registration captures current page for UDEG recall when source page omitted`() = runBlocking {
        val context = TempFilesContext()
        val backend = RecordingOmniflowBackend(
            initialPackage = "com.example.settings",
            currentXml = SOURCE_XML,
        )
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            try {
                val toolkit = OobOmniFlowToolkitService(context, WorkspaceFunctionStore(context.root))
                val functionId = "simple_settings_current_page_capture"

                val register = toolkit.registerFunction(
                    mapOf(
                        "functionId" to functionId,
                        "name" to "Open Settings From Current Page",
                        "description" to "Open Android Settings from the current page",
                        "steps" to listOf(
                            mapOf(
                                "action" to "open_app",
                                "packageName" to "com.android.settings",
                            ),
                            mapOf(
                                "action" to "finished",
                                "content" to "Settings opened",
                            ),
                        ),
                    )
                )

                assertEquals(true, register["success"])
                val udeg = register["udeg"] as? Map<*, *>
                assertEquals(true, udeg?.get("indexed"))

                val stored = toolkit.getFunction(mapOf("function_id" to functionId))
                val execution = stored["execution"] as? Map<*, *>
                val steps = execution?.get("steps") as? List<*>
                val firstStep = steps?.firstOrNull() as? Map<*, *>
                val sourceContext = firstStep?.get("source_context") as? Map<*, *>
                val srcCtx = sourceContext?.get("src_ctx") as? Map<*, *>
                assertEquals(SOURCE_XML.trim(), srcCtx?.get("page")?.toString()?.trim())
                assertEquals("com.example.settings", srcCtx?.get("package_name"))

                val recall = toolkit.recall(
                    mapOf(
                        "goal" to "open settings",
                        "current_package" to "com.example.settings",
                        "current_xml" to SOURCE_XML,
                        "k" to 3,
                    )
                )
                assertEquals(true, recall["success"])
                assertEquals("node_skill_context_only", (recall["decision_policy"] as? Map<*, *>)?.get("mode"))
                val candidates = recall["candidates"] as? List<*>
                val firstCandidate = candidates?.firstOrNull() as? Map<*, *>
                assertEquals(functionId, firstCandidate?.get("function_id"))
                assertEquals(null, recall["hit"])

                val run = toolkit.runFunction(mapOf("functionId" to functionId))
                assertEquals(true, run["success"])
                assertEquals(2, (run["step_count"] as Number).toInt())
                assertEquals(2, (run["success_step_count"] as Number).toInt())
                assertEquals(2, (run["actions_executed"] as Number).toInt())
                assertTrue((run["duration_ms"] as Number).toLong() >= 0L)
                assertTrue((run["runner_duration_ms"] as Number).toLong() >= 0L)
                assertEquals(listOf("com.android.settings"), backend.launchedPackages)
            } finally {
                context.root.deleteRecursively()
            }
        }
    }

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
                    "include_debug" to true,
                )
            )
            assertEquals(true, recall["success"])
            assertEquals("recall", recall["decision"])
            assertEquals("oob_native_udeg_page_match", recall["source"])
            assertEquals(OobUdegNodeStore.UDEG_DECISION_PATH, recall["decision_path"])
            val recallTiming = requireNotNull(recall["timing"] as? Map<*, *>)
            assertEquals("oob_omniflow_recall", recallTiming["source"])
            assertTrue((recallTiming["duration_ms"] as Number).toLong() >= 0L)
            assertRecallTimingPhases(recallTiming)
            val currentNode = recall["current_node"] as? Map<*, *>
            assertNotNull(currentNode)
            val nodeSkill = currentNode?.get("skill") as? Map<*, *>
            assertEquals("udeg_node_skill", nodeSkill?.get("kind"))
            assertEquals("decision_context", nodeSkill?.get("role"))
            assertEquals(OobUdegNodeStore.UDEG_DECISION_PATH, nodeSkill?.get("decision_path"))
            val decisionContext = recall["decision_context"] as? Map<*, *>
            assertEquals("page_match_to_udeg_node", decisionContext?.get("entry_policy"))
            val nodeSkillContext = recall["node_skill_context"] as? Map<*, *>
            assertEquals("decision", nodeSkillContext?.get("role"))
            assertEquals("udeg_node_skill_like_decision_context", nodeSkillContext?.get("context_kind"))
            assertEquals(OobUdegNodeStore.UDEG_DECISION_PATH, nodeSkillContext?.get("decision_path"))
            val skillArtifact = currentNode?.get("skill_artifact") as? Map<*, *>
            assertStructuredSkillArtifact(
                context = context,
                artifact = skillArtifact,
                expectedPackage = "com.example.settings",
            )
            assertEquals(null, recall["hit"])
            val candidates = recall["candidates"] as? List<*>
            val firstCandidate = candidates?.firstOrNull() as? Map<*, *>
            assertEquals(functionId, firstCandidate?.get("function_id"))
            assertNotNull(firstCandidate?.get("udeg_node"))
            assertNotNull(firstCandidate?.get("node_skill_context"))
            assertNotNull((firstCandidate?.get("node_skill_context") as? Map<*, *>)?.get("skill_artifact"))
            val decisionPolicy = recall["decision_policy"] as? Map<*, *>
            assertEquals("node_skill_context_only", decisionPolicy?.get("mode"))
            assertEquals(true, decisionPolicy?.get("requires_vlm_or_tool_decision"))
            val recalledSteps = firstCandidate?.get("step_summaries") as? List<*>
            assertEquals(1, recalledSteps?.size)

            val compactRecall = toolkit.recall(
                mapOf(
                    "goal" to goal,
                    "current_package" to "com.example.settings",
                    "current_xml" to SOURCE_XML,
                    "k" to 3,
                )
            )
            assertEquals(true, compactRecall["success"])
            assertEquals("agent_compact", compactRecall["payload_mode"])
            assertEquals(null, compactRecall["timing"])
            assertEquals(null, compactRecall["node_skill"])
            val compactNode = compactRecall["current_node"] as? Map<*, *>
            assertEquals(null, compactNode?.get("skill_artifact"))
            val compactCandidate = (compactRecall["candidates"] as? List<*>)?.firstOrNull() as? Map<*, *>
            val compactNodeSkillContext = compactCandidate?.get("node_skill_context") as? Map<*, *>
            assertEquals(null, compactNodeSkillContext?.get("skill_artifact"))
            assertEquals(null, compactNodeSkillContext?.get("skill"))

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
            assertEquals("oob_fixed_replay", oobResult?.get("runner"))
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
    fun `failed run logs are rejected before reusable function conversion`() = runBlocking {
        val context = TempFilesContext()
        try {
            val workspaceStore = WorkspaceFunctionStore(context.root)
            val toolkit = OobOmniFlowToolkitService(context, workspaceStore)
            val runId = "failed-run-log-${System.nanoTime()}"

            InternalRunLogStore.beginRun(
                context = context,
                runId = runId,
                goal = "Open About phone settings",
                source = "vlm_task",
                toolName = "vlm_task",
                operationDescription = "Open About phone settings",
            )
            InternalRunLogStore.appendCard(
                context = context,
                runId = runId,
                card = linkedMapOf(
                    "card_id" to "$runId-vlm-1",
                    "tool_name" to "click",
                    "title" to "点击 About phone",
                    "success" to true,
                    "args" to linkedMapOf(
                        "target_description" to "About phone",
                        "x" to 360,
                        "y" to 968,
                    ),
                    "before" to linkedMapOf(
                        "package_name" to "com.android.settings",
                        "observation_xml" to SOURCE_XML,
                    ),
                    "after" to linkedMapOf(
                        "package_name" to "com.android.settings",
                        "observation_xml" to AFTER_XML,
                    ),
                )
            )
            InternalRunLogStore.finishRun(
                context = context,
                runId = runId,
                success = false,
                doneReason = "vlm_error",
                errorMessage = "stream aborted",
            )

            val convert = toolkit.convertRunLog(
                mapOf(
                    "run_id" to runId,
                    "register" to true,
                )
            )
            assertEquals(false, convert["success"])
            assertEquals("RUN_LOG_NOT_SUCCESSFUL", convert["error_code"])
            assertEquals(null, convert["function_spec"])

            val ingestById = toolkit.ingestRunLog(mapOf("run_id" to runId))
            assertEquals(false, ingestById["accepted"])
            assertEquals(false, ingestById["success"])
            val ingestResult = ingestById["result"] as? Map<*, *>
            assertEquals("RUN_LOG_NOT_SUCCESSFUL", ingestResult?.get("error_code"))

            val ingestInline = toolkit.ingestRunLog(
                mapOf(
                    "run_log" to mapOf(
                        "run_id" to "${runId}-inline",
                        "goal" to "Open About phone settings",
                        "success" to false,
                        "cards" to listOf(
                            mapOf(
                                "tool_name" to "click",
                                "success" to true,
                                "args" to mapOf("x" to 360, "y" to 968),
                            )
                        ),
                    )
                )
            )
            assertEquals(false, ingestInline["accepted"])
            assertEquals(false, ingestInline["success"])
            val inlineResult = ingestInline["result"] as? Map<*, *>
            assertEquals("RUN_LOG_NOT_SUCCESSFUL", inlineResult?.get("error_code"))

            val autoRegister = OobRunLogReplayService(
                context,
                workspaceStore,
            ).autoRegisterRecentRunLogs(limit = 20)
            assertEquals(0, (autoRegister["eligible_count"] as Number).toInt())
            assertEquals(1, (autoRegister["skipped_count"] as Number).toInt())
            val listAfter = toolkit.listFunctions(mapOf("limit" to 10))
            assertEquals(0, (listAfter["count"] as Number).toInt())
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `direct recall ranks functions attached to the page matched UDEG node`() = runBlocking {
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
                    "include_debug" to true,
                )
            )

            assertEquals(true, recall["success"])
            assertEquals("recall", recall["decision"])
            val candidates = recall["candidates"] as? List<*>
            val firstCandidate = candidates?.firstOrNull() as? Map<*, *>
            assertEquals(matchingFunctionId, firstCandidate?.get("function_id"))

            val currentNode = recall["current_node"] as? Map<*, *>
            val functionIds = currentNode?.get("function_ids") as? List<*>
            assertTrue(functionIds.orEmpty().contains(matchingFunctionId))
            assertFalse(functionIds.orEmpty().contains(otherFunctionId))
            assertStructuredSkillArtifact(
                context = context,
                artifact = currentNode?.get("skill_artifact") as? Map<*, *>,
                expectedPackage = "com.example.settings",
            )
            val nodeCapabilities = recall["node_capabilities"] as? List<*>
            val functionCapabilities = recall["node_function_capabilities"] as? List<*>
            val segmentCapabilities = recall["node_segment_capabilities"] as? List<*>
            assertEquals(matchingFunctionId, firstCapabilityId(nodeCapabilities))
            assertEquals(matchingFunctionId, firstCapabilityId(functionCapabilities))
            assertFalse(
                functionCapabilities.orEmpty()
                    .mapNotNull { (it as? Map<*, *>)?.get("function_id") }
                    .contains(otherFunctionId)
            )
            assertEquals(0, segmentCapabilities.orEmpty().size)
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `clear functions removes registry workspace tools and UDEG references`() = runBlocking {
        val context = TempFilesContext()
        try {
            val toolkit = OobOmniFlowToolkitService(context, WorkspaceFunctionStore(context.root))
            val functionId = "clearable_open_network"

            val register = toolkit.registerFunction(
                mapOf(
                    "functionSpec" to pageBackedFunctionSpec(
                        functionId = functionId,
                        description = "open network settings",
                        sourceXml = SOURCE_XML,
                        packageName = "com.example.settings",
                    )
                )
            )
            assertEquals(true, register["success"])

            val before = toolkit.recall(
                mapOf(
                    "goal" to "open network settings",
                    "current_package" to "com.example.settings",
                    "current_xml" to SOURCE_XML,
                    "k" to 5,
                    "include_debug" to true,
                )
            )
            val beforeNode = before["current_node"] as? Map<*, *>
            assertTrue((beforeNode?.get("function_ids") as? List<*>).orEmpty().contains(functionId))
            assertEquals(1, ((toolkit.listFunctions(mapOf("limit" to 10))["count"] as Number).toInt()))

            val blockedClear = toolkit.clearFunctions(emptyMap())
            assertEquals(false, blockedClear["success"])
            assertEquals("OOB_FUNCTION_CLEAR_CONFIRMATION_REQUIRED", blockedClear["error_code"])

            val clear = toolkit.clearFunctions(mapOf("confirm" to true))
            assertEquals(true, clear["success"])
            assertEquals(1, (clear["deleted_count"] as Number).toInt())

            val listAfter = toolkit.listFunctions(mapOf("limit" to 10))
            assertEquals(true, listAfter["success"])
            assertEquals(0, (listAfter["count"] as Number).toInt())

            val after = toolkit.recall(
                mapOf(
                    "goal" to "open network settings",
                    "current_package" to "com.example.settings",
                    "current_xml" to SOURCE_XML,
                    "k" to 5,
                    "include_debug" to true,
                )
            )
            assertEquals(true, after["success"])
            val afterNode = after["current_node"] as? Map<*, *>
            assertEquals(emptyList<String>(), afterNode?.get("function_ids"))
            assertEquals(emptyList<Any?>(), after["candidates"])
            assertEquals(emptyList<Any?>(), after["node_function_capabilities"])
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
            val recall = toolkit.recall(
                mapOf(
                    "goal" to "open settings",
                    "current_package" to "com.android.contacts",
                    "include_debug" to true,
                )
            )
            assertEquals(true, recall["success"])
            assertEquals("miss", recall["decision"])
            assertEquals("missing_current_page_for_udeg_page_match", recall["reason"])
            val recallTiming = requireNotNull(recall["timing"] as? Map<*, *>)
            assertEquals("oob_omniflow_recall", recallTiming["source"])
            assertTrue((recallTiming["duration_ms"] as Number).toLong() >= 0L)
            assertRecallTimingPhases(recallTiming)
            val recallCounts = recallTiming["counts"] as? Map<*, *>
            assertEquals(0, (recallCounts?.get("segment_candidates") as Number).toInt())

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

    @Test
    fun `recall returns segment hit and callFunction can run only the suffix`() = runBlocking {
        val context = TempFilesContext()
        val backend = RecordingOmniflowBackend(initialPackage = "com.example.target")
        val backendHandle = OmniflowActionRuntime.useBackendForTesting(backend)
        try {
            val toolkit = OobOmniFlowToolkitService(context, WorkspaceFunctionStore(context.root))
            val functionId = "continue_from_internal_page_segment"
            OobUdegNodeStore(context).observePage(
                OobUdegNodeStore.ObservedPage(
                    pageXml = TARGET_XML,
                    packageName = "com.example.target",
                    activityName = "TargetActivity",
                    goal = "open settings",
                )
            )
            val register = toolkit.registerFunction(
                mapOf(
                    "functionSpec" to reusableFunctionSpec(
                        functionId = functionId,
                        name = "Continue from target page",
                        description = "continue flow opening settings",
                        steps = listOf(
                            clickTransitionStep(
                                sourceXml = SOURCE_XML,
                                sourcePackage = "com.example.settings",
                                destXml = TARGET_XML,
                                destPackage = "com.example.settings",
                            ),
                            openAppStepWithSource(
                                id = "open_settings_from_target",
                                sourceXml = TARGET_XML,
                                sourcePackage = "com.example.target",
                                targetPackage = "com.android.settings",
                            )
                        )
                    )
                )
            )
            assertEquals(true, register["success"])

            val recall = toolkit.recall(
                mapOf(
                    "goal" to "settings",
                    "current_package" to "com.example.target",
                    "current_xml" to TARGET_XML,
                    "k" to 5,
                    "include_debug" to true,
                )
            )
            assertEquals(true, recall["success"])
            assertEquals("segment_recall", recall["decision"])
            assertEquals("udeg_node_segment_recall", recall["reason"])
            assertEquals(null, recall["segment_hit"])
            val segmentCandidates = recall["segment_candidates"] as? List<*>
            val segmentHit = requireNotNull(segmentCandidates?.firstOrNull() as? Map<*, *>)
            assertEquals(functionId, segmentHit["function_id"])
            assertEquals(1, (segmentHit["start_step_index"] as Number).toInt())
            assertEquals(1, (segmentHit["remaining_step_count"] as Number).toInt())
            assertEquals("function_suffix", segmentHit["execution_scope"])
            assertEquals("udeg_node_segment", segmentHit["recall_scope"])
            assertEquals("function_segment", segmentHit["capability_type"])
            val currentNode = requireNotNull(recall["current_node"] as? Map<*, *>)
            val functionIds = currentNode["function_ids"] as? List<*>
            assertFalse(functionIds.orEmpty().contains(functionId))
            assertStructuredSkillArtifact(
                context = context,
                artifact = currentNode["skill_artifact"] as? Map<*, *>,
                expectedPackage = "com.example.target",
            )
            val segmentSummaries = currentNode["segments"] as? List<*>
            val nodeSegment = segmentSummaries.orEmpty()
                .mapNotNull { it as? Map<*, *> }
                .firstOrNull { it["function_id"] == functionId }
            assertNotNull(nodeSegment)
            assertEquals(1, (nodeSegment?.get("start_step_index") as Number).toInt())
            val timing = requireNotNull(recall["timing"] as? Map<*, *>)
            assertRecallTimingPhases(timing)
            val counts = requireNotNull(timing["counts"] as? Map<*, *>)
            assertEquals(1, (counts["segment_candidates"] as Number).toInt())
            assertEquals(1, (counts["segment_scanned_functions"] as Number).toInt())
            assertEquals(1, (counts["node_capabilities"] as Number).toInt())
            assertEquals(0, (counts["node_function_capabilities"] as Number).toInt())
            assertEquals(1, (counts["node_segment_capabilities"] as Number).toInt())
            assertTrue((counts["segment_boundaries"] as Number).toInt() >= 1)
            assertTrue((counts["segment_boundary_page_hits"] as Number).toInt() >= 1)
            val nodeCapabilities = recall["node_capabilities"] as? List<*>
            val segmentCapabilities = recall["node_segment_capabilities"] as? List<*>
            assertEquals(functionId, firstCapabilityId(nodeCapabilities))
            assertEquals(functionId, firstCapabilityId(segmentCapabilities))
            val capability = requireNotNull(segmentCapabilities?.firstOrNull() as? Map<*, *>)
            assertEquals("function_segment", capability["capability_type"])
            assertEquals("udeg_node_segment", capability["recall_scope"])
            assertEquals(1, (capability["start_step_index"] as Number).toInt())
            assertEquals("oob_udeg_node_capability", capability["source"])

            val directRecall = toolkit.recall(
                mapOf(
                    "goal" to "settings",
                    "current_package" to "com.example.target",
                    "current_xml" to TARGET_XML,
                    "k" to 5,
                    "auto_execute" to true,
                    "include_debug" to true,
                )
            )
            assertEquals(true, directRecall["success"])
            assertEquals("segment_recall", directRecall["decision"])
            assertEquals("udeg_node_segment_recall", directRecall["reason"])
            assertEquals(null, directRecall["segment_hit"])

            val call = toolkit.callFunction(
                mapOf(
                    "function_id" to functionId,
                    "goal" to "settings",
                    "start_step_index" to 1,
                )
            )
            assertEquals(true, call["success"])
            assertEquals(false, call["fallback"])
            assertEquals(listOf("com.android.settings"), backend.launchedPackages)
            val oobResult = call["oob_result"] as? Map<*, *>
            val segment = requireNotNull(oobResult?.get("segment") as? Map<*, *>)
            assertEquals(1, (segment["start_step_index"] as Number).toInt())
            assertEquals(1, (segment["remaining_step_count"] as Number).toInt())
            val stepResults = call["step_results"] as? List<*>
            val replayedStep = stepResults?.single() as? Map<*, *>
            assertEquals("open_app", replayedStep?.get("tool"))
        } finally {
            backendHandle.close()
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `observed UDEG page writes structured node context artifact outside skill repository`() {
        val context = TempFilesContext()
        try {
            val nodeStore = OobUdegNodeStore(context)
            val observed = requireNotNull(
                nodeStore.observePage(
                    OobUdegNodeStore.ObservedPage(
                        pageXml = SOURCE_XML,
                        packageName = "com.example.settings",
                        activityName = "SettingsActivity",
                        goal = "open network settings",
                    )
                )
            )
            val observedMap = observed.toMap()
            val artifact = observedMap["skill_artifact"] as? Map<*, *>
            val nodeId = observedMap["node_id"]?.toString().orEmpty()
            assertStructuredSkillArtifact(
                context = context,
                artifact = artifact,
                expectedPackage = "com.example.settings",
            )

            val nodeFromArtifact = nodeStore.getNode(nodeId)
            assertEquals(nodeId, nodeFromArtifact["node_id"])
            assertNotNull(nodeFromArtifact["page_vector_set"])
            assertNotNull(nodeFromArtifact["skill_artifact"])

            val skillRepoArtifactRoot = File(context.root, "workspace/.omnibot/skills/oob-udeg-node-skills")
            assertFalse(skillRepoArtifactRoot.exists())
        } finally {
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

    private fun firstCapabilityId(capabilities: List<*>?): Any? =
        (capabilities?.firstOrNull() as? Map<*, *>)?.get("function_id")

    private fun assertStructuredSkillArtifact(
        context: TempFilesContext,
        artifact: Map<*, *>?,
        expectedPackage: String,
    ) {
        assertNotNull(artifact)
        assertEquals("oob.udeg.node_skill_artifact.v1", artifact?.get("schema_version"))
        assertEquals("oob_udeg_node_skill_artifact", artifact?.get("kind"))
        assertEquals(expectedPackage, artifact?.get("package_name"))
        assertEquals(OobUdegNodeStore.UDEG_DECISION_PATH, artifact?.get("decision_path"))
        assertEquals("OobUdegNodeStore.memoryRoot", artifact?.get("indexed_by"))
        val paths = artifact?.get("paths") as? Map<*, *>
        val skillFile = File(paths?.get("skill_file_path")?.toString().orEmpty())
        val payloadFile = File(paths?.get("payload_path")?.toString().orEmpty())
        assertTrue(skillFile.absolutePath.contains("/workspace/.omnibot/memory/omniflow/udeg-node-contexts/"))
        assertFalse(skillFile.absolutePath.contains("/workspace/.omnibot/skills/"))
        assertTrue(skillFile.exists())
        assertTrue(payloadFile.exists())
        assertTrue(skillFile.readText().contains("metadata:"))
        assertTrue(skillFile.readText().contains("structured_payload:"))
        val payloadText = payloadFile.readText()
        assertTrue(payloadText.contains("\"page_match\""))
        assertTrue(payloadText.contains("\"page_vector\""))
        val indexFile = File(context.root, "workspace/.omnibot/memory/omniflow/udeg-node-contexts/index.json")
        assertTrue(indexFile.exists())
    }

    private fun assertRecallTimingPhases(timing: Map<*, *>) {
        val phases = requireNotNull(timing["phase_ms"] as? Map<*, *>)
        listOf(
            "parse_request_ms",
            "read_current_package_ms",
            "read_current_page_ms",
            "page_match_ms",
            "rank_functions_ms",
            "segment_match_ms",
        ).forEach { phaseName ->
            assertTrue("missing timing phase $phaseName", phases.containsKey(phaseName))
            assertTrue(
                "negative timing phase $phaseName",
                (phases[phaseName] as Number).toLong() >= 0L
            )
        }
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

    private fun openAppStepWithSource(
        id: String,
        sourceXml: String,
        sourcePackage: String,
        targetPackage: String,
    ): Map<String, Any?> = mapOf(
        "id" to id,
        "index" to 1,
        "title" to "Open $targetPackage",
        "kind" to "omniflow_action",
        "executor" to "omniflow",
        "omniflow_action" to "open_app",
        "local_action" to "open_app",
        "tool" to "open_app",
        "callable_tool" to "open_app",
        "model_free" to true,
        "scriptable" to true,
        "args" to mapOf("package_name" to targetPackage),
        "source_context" to mapOf(
            "src_ctx" to mapOf(
                "page" to sourceXml,
                "package_name" to sourcePackage,
            )
        ),
    )

    private fun clickTransitionStep(
        sourceXml: String,
        sourcePackage: String,
        destXml: String,
        destPackage: String,
    ): Map<String, Any?> = mapOf(
        "id" to "click_into_target_page",
        "index" to 0,
        "title" to "Click into target page",
        "kind" to "omniflow_action",
        "executor" to "omniflow",
        "omniflow_action" to "click",
        "local_action" to "click",
        "tool" to "click",
        "callable_tool" to "click",
        "model_free" to true,
        "scriptable" to true,
        "args" to mapOf("x" to 100, "y" to 260),
        "source_context" to mapOf(
            "src_ctx" to mapOf(
                "page" to sourceXml,
                "package_name" to sourcePackage,
            ),
            "dst_ctx" to mapOf(
                "page" to destXml,
                "package_name" to destPackage,
            ),
        ),
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

    private class RecordingOmniflowBackend(
        initialPackage: String,
        private var currentXml: String? = null,
        private var currentActivity: String? = null,
    ) : OmniflowActionBackend {
        private var currentPackage = initialPackage
        val launchedPackages = mutableListOf<String>()
        val clicks = mutableListOf<Pair<Float, Float>>()

        override fun isReady(): Boolean = true

        override suspend fun click(x: Float, y: Float) {
            clicks += x to y
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
            currentXml = null
        }

        override suspend fun pressHotKey(key: String) {
            error("pressHotKey should not be used by this test")
        }

        override fun currentXml(): String? = currentXml

        override fun currentPackageName(): String = currentPackage

        override fun currentActivityName(): String? = currentActivity

        fun setCurrentPackage(packageName: String) {
            currentPackage = packageName
        }
    }

    class TempFilesContext : ContextWrapper(null) {
        val root: File = Files.createTempDirectory("oob-omniflow-loop-test").toFile()
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

        private const val TARGET_XML = """
            <hierarchy bounds="[0,0][1080,1920]">
              <node index="0" package="com.example.target" class="android.widget.LinearLayout" text="" content-desc="" bounds="[0,0][1080,1920]">
                <node index="1" package="com.example.target" class="android.widget.TextView" text="Target details" content-desc="" resource-id="target:title" clickable="false" enabled="true" visible-to-user="true" bounds="[40,80][800,180]" />
                <node index="2" package="com.example.target" class="android.widget.Button" text="Open settings" content-desc="" resource-id="target:open_settings" clickable="true" enabled="true" visible-to-user="true" bounds="[80,620][1000,760]" />
              </node>
            </hierarchy>
        """
    }
}
