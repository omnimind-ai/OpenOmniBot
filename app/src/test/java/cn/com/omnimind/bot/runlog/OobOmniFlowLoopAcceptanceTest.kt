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
    fun `update function repairs wrong click target from human instruction`() = runBlocking {
        val context = TempFilesContext()
        try {
            val toolkit = OobOmniFlowToolkitService(context, WorkspaceFunctionStore(context.root))
            val functionId = "repair_takeout_target"
            val register = toolkit.registerFunction(
                mapOf(
                    "functionId" to functionId,
                    "name" to "打开美食",
                    "description" to "错误地点击了美食入口",
                    "sourcePage" to mapOf(
                        "xml" to TAKEOUT_XML,
                        "packageName" to "com.example.food",
                    ),
                    "steps" to listOf(
                        mapOf(
                            "action" to "click",
                            "title" to "点击美食",
                            "target_description" to "美食",
                            "x" to 230,
                            "y" to 140,
                        ),
                    ),
                )
            )
            assertEquals(true, register["success"])

            val update = toolkit.updateFunction(
                mapOf(
                    "function_id" to functionId,
                    "instruction" to "应该点「外卖」而不是点「美食」",
                )
            )

            assertEquals(true, update["success"])
            assertEquals("repair", update["mode"])
            assertEquals(true, update["changed"])
            assertEquals(true, update["saved"])
            assertEquals(false, update["requires_confirmation"])

            val stored = toolkit.getFunction(mapOf("function_id" to functionId))
            val function = stored["function"] as Map<*, *>
            val execution = function["execution"] as Map<*, *>
            val step = (execution["steps"] as List<*>).first() as Map<*, *>
            val args = step["args"] as Map<*, *>
            assertEquals("外卖", args["target_description"])
            assertEquals(790.0, (args["x"] as Number).toDouble(), 0.001)
            assertEquals(140.0, (args["y"] as Number).toDouble(), 0.001)
            assertEquals("[600,80][980,200]", args["bounds"])
            assertEquals("点击外卖", step["title"])

            val selectorHints = args["selector_hints"] as Map<*, *>
            assertEquals(listOf("外卖"), selectorHints["prefer_text"])
            assertEquals(listOf("美食"), selectorHints["avoid_text"])

            val metadata = function["metadata"] as Map<*, *>
            val audit = metadata["oob_function_update"] as Map<*, *>
            assertEquals("update_function", audit["tool"])
            assertEquals("repair", audit["mode"])
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `update function with runlog returns agent analysis context without saving`() = runBlocking {
        val context = TempFilesContext()
        try {
            val toolkit = OobOmniFlowToolkitService(context, WorkspaceFunctionStore(context.root))
            val functionId = "runlog_analysis_context_demo"
            val runId = "runlog-analysis-context-run"
            assertEquals(true, toolkit.registerFunction(
                mapOf(
                    "functionId" to functionId,
                    "name" to "打开外卖入口",
                    "description" to "点击外卖入口",
                    "steps" to listOf(
                        mapOf(
                            "action" to "click",
                            "title" to "点击外卖",
                            "target_description" to "外卖",
                            "x" to 790,
                            "y" to 140,
                        ),
                    ),
                )
            )["success"])
            InternalRunLogStore.beginRun(
                context = context,
                runId = runId,
                goal = "打开外卖入口",
                source = "test",
                toolName = "oob_function_run",
            )
            InternalRunLogStore.appendCard(
                context = context,
                runId = runId,
                card = mapOf(
                    "tool_name" to "click",
                    "header" to mapOf("success" to false),
                    "arguments" to mapOf("target_description" to "美食"),
                    "result" to mapOf("success" to false, "error" to "target_not_found"),
                )
            )
            InternalRunLogStore.finishRun(
                context = context,
                runId = runId,
                success = false,
                doneReason = "replay_failed",
                errorMessage = "target_not_found",
            )

            val update = toolkit.updateFunction(mapOf("function_id" to functionId, "run_id" to runId))

            assertEquals(true, update["success"])
            assertEquals(true, update["needs_agent_analysis"])
            assertEquals(false, update["changed"])
            assertEquals(false, update["saved"])
            assertTrue(update["agent_prompt"].toString().contains("Analyze this OOB Function"))
            val contextPayload = update["analysis_context"] as Map<*, *>
            assertEquals(functionId, contextPayload["function_id"])
            assertEquals(runId, (contextPayload["runlog"] as Map<*, *>)["run_id"])
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `update function saves agent runlog analysis metadata and recommended patch`() = runBlocking {
        val context = TempFilesContext()
        try {
            val toolkit = OobOmniFlowToolkitService(context, WorkspaceFunctionStore(context.root))
            val functionId = "runlog_analysis_save_demo"
            val runId = "runlog-analysis-save-run"
            assertEquals(true, toolkit.registerFunction(
                mapOf(
                    "functionId" to functionId,
                    "name" to "打开外卖入口",
                    "description" to "点击入口",
                    "steps" to listOf(
                        mapOf(
                            "action" to "click",
                            "title" to "点击入口",
                            "target_description" to "外卖",
                            "x" to 790,
                            "y" to 140,
                        ),
                    ),
                )
            )["success"])
            InternalRunLogStore.beginRun(
                context = context,
                runId = runId,
                goal = "打开外卖入口",
                source = "test",
                toolName = "oob_function_run",
            )
            InternalRunLogStore.appendCard(
                context = context,
                runId = runId,
                card = mapOf(
                    "tool_name" to "click",
                    "header" to mapOf("success" to true),
                    "arguments" to mapOf("target_description" to "外卖"),
                    "result" to mapOf("success" to true),
                )
            )
            InternalRunLogStore.finishRun(
                context = context,
                runId = runId,
                success = true,
                doneReason = "finished",
            )
            val analysis = mapOf(
                "summary" to "成功 RunLog 证明外卖入口点击是必要动作。",
                "step_findings" to listOf(
                    mapOf(
                        "function_step_index" to 0,
                        "runlog_card_index" to 0,
                        "label" to "点击外卖入口",
                        "role" to "success_evidence",
                        "reason" to "RunLog card 成功点击同一目标。",
                    )
                ),
                "failure_reason" to mapOf("code" to "unknown", "message" to ""),
                "recommended_patch" to mapOf(
                    "description" to "点击外卖入口，成功 RunLog 已验证。",
                    "ops" to emptyList<Map<String, Any?>>(),
                ),
            )

            val update = toolkit.updateFunction(
                mapOf(
                    "function_id" to functionId,
                    "run_id" to runId,
                    "analysis" to analysis,
                )
            )

            assertEquals(true, update["success"])
            assertEquals(true, update["changed"])
            assertEquals(true, update["saved"])
            val stored = toolkit.getFunction(mapOf("function_id" to functionId))
            val function = stored["function"] as Map<*, *>
            assertEquals("点击外卖入口，成功 RunLog 已验证。", function["description"])
            val metadata = function["metadata"] as Map<*, *>
            val evidence = metadata["oob_function_evidence"] as Map<*, *>
            assertEquals(runId, evidence["latest_run_id"])
            assertEquals(listOf(runId), evidence["source_run_ids"])
            val latestAnalysis = evidence["latest_analysis"] as Map<*, *>
            assertEquals("成功 RunLog 证明外卖入口点击是必要动作。", latestAnalysis["summary"])
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `update function materializes optional checker annotations as supported runtime rules`() = runBlocking {
        val context = TempFilesContext()
        try {
            val toolkit = OobOmniFlowToolkitService(context, WorkspaceFunctionStore(context.root))
            val functionId = "optional_checker_rule_update"
            val register = toolkit.registerFunction(
                mapOf(
                    "functionId" to functionId,
                    "name" to "关闭可选弹窗后继续",
                    "description" to "用于验证 optional checker 标注会落成运行时规则",
                    "sourcePage" to mapOf(
                        "xml" to TAKEOUT_XML,
                        "packageName" to "com.example.food",
                    ),
                    "steps" to listOf(
                        mapOf(
                            "action" to "click",
                            "title" to "关闭广告弹窗",
                            "target_description" to "关闭",
                            "x" to 900,
                            "y" to 120,
                        ),
                        mapOf(
                            "action" to "click",
                            "title" to "继续主路径",
                            "target_description" to "外卖",
                            "x" to 790,
                            "y" to 140,
                        ),
                    ),
                )
            )
            assertEquals(true, register["success"])

            val update = toolkit.updateFunction(
                mapOf(
                    "function_id" to functionId,
                    "mode" to "enhance",
                    "patch" to mapOf(
                        "steps" to listOf(
                            mapOf(
                                "index" to 0,
                                "title" to "关闭可选广告弹窗",
                                "description" to "如果广告弹窗遮挡主路径，关闭它以继续后续操作。",
                                "cleanup_annotation" to mapOf(
                                    "schema_version" to "oob.step_cleanup_annotation.v1",
                                    "cleanup_action" to "optional_checker",
                                    "optional_condition" to "仅当广告弹窗实际遮挡目标区域时执行。",
                                    "reason" to "广告弹窗不一定每次出现。",
                                ),
                            )
                        ),
                        "metadata" to mapOf(
                            "checker_rules" to listOf(
                                mapOf(
                                    "id" to "dismiss_optional_overlay_before_action",
                                    "phase" to "pre_transfer",
                                    "condition" to "overlay_blocking",
                                    "action" to "dismiss",
                                    "enabled" to true,
                                    "params" to mapOf("selector" to "unsupported"),
                                ),
                                mapOf(
                                    "id" to "unsupported_model_checker",
                                    "phase" to "pre_action",
                                    "condition" to "model_call",
                                    "action" to "run_script",
                                ),
                            )
                        ),
                    ),
                )
            )

            assertEquals(true, update["success"])
            assertEquals(true, update["changed"])
            assertEquals(true, update["saved"])

            val stored = toolkit.getFunction(mapOf("function_id" to functionId))
            val function = stored["function"] as Map<*, *>
            val metadata = function["metadata"] as Map<*, *>
            val checkerRules = metadata["checker_rules"] as List<*>
            assertEquals(1, checkerRules.size)
            val rule = checkerRules.single() as Map<*, *>
            assertEquals("dismiss_optional_overlay_before_action", rule["id"])
            assertEquals("overlay_blocking", rule["condition"])
            assertEquals("dismiss", rule["action"])
            assertEquals("pre_transfer", rule["phase"])
            assertEquals(emptyMap<String, Any?>(), rule["params"])

            val agentReuse = function["agent_reuse"] as Map<*, *>
            val checkerAssets = agentReuse["checker_assets"] as List<*>
            val asset = checkerAssets.single() as Map<*, *>
            assertEquals("dismiss_optional_overlay_before_action", asset["checker_id"])
            assertEquals(0, (asset["step_index"] as Number).toInt())
            assertEquals("checker_candidate", asset["role"])
            assertEquals("metadata_checker_rule", asset["materialization"])
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `update function normalizes ad checker aliases into runtime rule`() = runBlocking {
        val context = TempFilesContext()
        try {
            val toolkit = OobOmniFlowToolkitService(context, WorkspaceFunctionStore(context.root))
            val functionId = "ad_checker_alias_update"
            val register = toolkit.registerFunction(
                mapOf(
                    "functionId" to functionId,
                    "name" to "跳过广告后点外卖",
                    "description" to "用于验证 agent 生成的广告 checker 会落成运行时规则",
                    "sourcePage" to mapOf(
                        "xml" to TAKEOUT_XML,
                        "packageName" to "com.example.food",
                    ),
                    "steps" to listOf(
                        mapOf(
                            "action" to "click",
                            "title" to "点击外卖",
                            "target_description" to "外卖",
                            "x" to 790,
                            "y" to 140,
                        ),
                    ),
                )
            )
            assertEquals(true, register["success"])

            val update = toolkit.updateFunction(
                mapOf(
                    "function_id" to functionId,
                    "mode" to "enhance",
                    "patch" to mapOf(
                        "metadata" to mapOf(
                            "checker_rules" to listOf(
                                mapOf(
                                    "id" to "skip_ad_if_present",
                                    "condition" to "ad",
                                    "action" to "click",
                                    "enabled" to true,
                                ),
                            )
                        ),
                    ),
                )
            )

            assertEquals(true, update["success"])
            assertEquals(true, update["changed"])
            assertEquals(true, update["saved"])

            val stored = toolkit.getFunction(mapOf("function_id" to functionId))
            val function = stored["function"] as Map<*, *>
            val metadata = function["metadata"] as Map<*, *>
            val checkerRules = metadata["checker_rules"] as List<*>
            assertEquals(1, checkerRules.size)
            val rule = checkerRules.single() as Map<*, *>
            assertEquals("skip_ad_if_present", rule["id"])
            assertEquals("ad_blocking", rule["condition"])
            assertEquals("dismiss", rule["action"])
            assertEquals("pre_transfer", rule["phase"])
            assertEquals(true, rule["enabled"])
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `update function gates inserts and deletes as structural changes`() = runBlocking {
        val context = TempFilesContext()
        try {
            val toolkit = OobOmniFlowToolkitService(context, WorkspaceFunctionStore(context.root))
            val functionId = "structural_step_update"
            val register = toolkit.registerFunction(
                mapOf(
                    "functionId" to functionId,
                    "name" to "打开应用并完成",
                    "description" to "用于验证结构化步骤更新",
                    "steps" to listOf(
                        mapOf(
                            "action" to "open_app",
                            "packageName" to "com.example.food",
                        ),
                        mapOf(
                            "action" to "finished",
                            "content" to "Done",
                        ),
                    ),
                )
            )
            assertEquals(true, register["success"])

            val insertPatch = mapOf(
                "ops" to listOf(
                    mapOf(
                        "op" to "insert_step",
                        "step_index" to 1,
                        "step" to mapOf(
                            "action" to "click",
                            "title" to "点击外卖",
                            "target_description" to "外卖",
                            "x" to 790,
                            "y" to 140,
                        ),
                    ),
                ),
            )
            val blockedInsert = toolkit.updateFunction(
                mapOf(
                    "function_id" to functionId,
                    "patch" to insertPatch,
                )
            )
            assertEquals(false, blockedInsert["success"])
            assertEquals("STRUCTURAL_CHANGE_NOT_ALLOWED", blockedInsert["error_code"])

            val allowedInsert = toolkit.updateFunction(
                mapOf(
                    "function_id" to functionId,
                    "mode" to "repair",
                    "allowStructuralChange" to true,
                    "patch" to insertPatch,
                )
            )
            assertEquals(true, allowedInsert["success"])
            assertEquals(true, allowedInsert["changed"])
            assertEquals(true, allowedInsert["saved"])

            val insertedStored = toolkit.getFunction(mapOf("function_id" to functionId))
            val insertedFunction = insertedStored["function"] as Map<*, *>
            val insertedExecution = insertedFunction["execution"] as Map<*, *>
            val insertedSteps = insertedExecution["steps"] as List<*>
            assertEquals(3, insertedSteps.size)
            assertEquals(3, (insertedExecution["step_count"] as Number).toInt())
            insertedSteps.forEachIndexed { index, rawStep ->
                assertEquals(index, ((rawStep as Map<*, *>)["index"] as Number).toInt())
            }
            val insertedStep = insertedSteps[1] as Map<*, *>
            assertEquals("click", insertedStep["tool"])
            assertEquals("点击外卖", insertedStep["title"])
            val insertedArgs = insertedStep["args"] as Map<*, *>
            assertEquals("外卖", insertedArgs["target_description"])
            assertEquals(790, (insertedArgs["x"] as Number).toInt())
            assertEquals(140, (insertedArgs["y"] as Number).toInt())
            val stepIds = insertedSteps.map { rawStep -> (rawStep as Map<*, *>)["id"] }
            assertEquals(stepIds.toSet().size, stepIds.size)

            val allowedDelete = toolkit.updateFunction(
                mapOf(
                    "function_id" to functionId,
                    "mode" to "repair",
                    "allowStructuralChange" to true,
                    "patch" to mapOf(
                        "ops" to listOf(
                            mapOf(
                                "op" to "delete_step",
                                "step_index" to 1,
                                "reason" to "删除刚插入的测试动作",
                            ),
                        ),
                    ),
                )
            )
            assertEquals(true, allowedDelete["success"])
            assertEquals(true, allowedDelete["changed"])
            assertEquals(true, allowedDelete["saved"])

            val deletedStored = toolkit.getFunction(mapOf("function_id" to functionId))
            val deletedFunction = deletedStored["function"] as Map<*, *>
            val deletedExecution = deletedFunction["execution"] as Map<*, *>
            val deletedSteps = deletedExecution["steps"] as List<*>
            assertEquals(2, deletedSteps.size)
            assertEquals(2, (deletedExecution["step_count"] as Number).toInt())
            deletedSteps.forEachIndexed { index, rawStep ->
                assertEquals(index, ((rawStep as Map<*, *>)["index"] as Number).toInt())
            }
            assertEquals("open_app", (deletedSteps[0] as Map<*, *>)["tool"])
            assertEquals("finished", (deletedSteps[1] as Map<*, *>)["tool"])
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
            val functionTiming = call["timing"] as? Map<*, *>
            assertNotNull(functionTiming)
            assertEquals("oob_function_execute", functionTiming?.get("source"))
            val callPhaseMs = functionTiming?.get("call_phase_ms") as? Map<*, *>
            assertNotNull(callPhaseMs)
            assertTrue("missing call guard timing", callPhaseMs?.containsKey("guard_check_ms") == true)
            assertTrue("missing call execute timing", callPhaseMs?.containsKey("execute_function_ms") == true)
            val functionPhaseMs = functionTiming?.get("phase_ms") as? Map<*, *>
            assertNotNull(functionPhaseMs)
            listOf(
                "load_function_spec_ms",
                "check_arguments_ms",
                "materialize_function_ms",
                "create_runner_ms",
                "run_materialized_function_ms",
            ).forEach { phaseName ->
                assertTrue("missing function timing phase $phaseName", functionPhaseMs?.containsKey(phaseName) == true)
            }
            assertNotNull(functionTiming?.get("startup_phase_ms") as? Map<*, *>)
            assertNotNull(functionTiming?.get("runner_phase_ms") as? Map<*, *>)

            val oobResult = call["oob_result"] as? Map<*, *>
            assertNotNull(oobResult)
            assertEquals("oob_omniflow_loop", oobResult?.get("runner"))
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
            assertEquals(matchingFunctionId, firstCapabilityId(nodeCapabilities))
            assertEquals(matchingFunctionId, firstCapabilityId(functionCapabilities))
            assertFalse(
                functionCapabilities.orEmpty()
                    .mapNotNull { (it as? Map<*, *>)?.get("function_id") }
                    .contains(otherFunctionId)
            )
            assertFalse(recall.containsKey("node_segment_capabilities"))
            assertFalse(recall.containsKey("segment_candidates"))
            assertFalse(recall.containsKey("segment_hit"))
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
    fun `parent function loads reusable open settings function through call_tool`() = runBlocking {
        val context = TempFilesContext()
        val backend = RecordingOmniflowBackend(initialPackage = "com.android.launcher")
        val backendHandle = OmniflowActionRuntime.useBackendForTesting(backend)
        try {
            val toolkit = OobOmniFlowToolkitService(context, WorkspaceFunctionStore(context.root))
            val childFunctionId = "open_settings_function"
            val parentFunctionId = "parent_calls_open_settings_function"

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
                        description = "Parent Function that reuses the open Settings Function.",
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
            assertFalse(recallCounts.orEmpty().containsKey("segment_candidates"))

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
    fun `run function returns agent fallback context and resumes remaining steps`() = runBlocking {
        val context = TempFilesContext()
        val backend = RecordingOmniflowBackend(initialPackage = "com.example.food")
        val backendHandle = OmniflowActionRuntime.useBackendForTesting(backend)
        try {
            val toolkit = OobOmniFlowToolkitService(context, WorkspaceFunctionStore(context.root))
            val functionId = "fallback_then_resume_remaining"
            val register = toolkit.registerFunction(
                mapOf(
                    "functionSpec" to reusableFunctionSpec(
                        functionId = functionId,
                        name = "Agent fallback then local click",
                        description = "第一步需要 agent 接管，之后恢复本地重放。",
                        steps = listOf(
                            mapOf(
                                "id" to "tap_takeout_with_agent",
                                "index" to 0,
                                "title" to "点击外卖",
                                "kind" to "agent_action",
                                "executor" to "tool",
                                "tool" to "tap",
                                "callable_tool" to "tap",
                                "model_free" to false,
                                "scriptable" to false,
                                "args" to mapOf("target_description" to "外卖"),
                            ),
                            mapOf(
                                "id" to "confirm_after_agent",
                                "index" to 1,
                                "title" to "点击确认",
                                "kind" to "omniflow_action",
                                "executor" to "omniflow",
                                "omniflow_action" to "click",
                                "local_action" to "click",
                                "tool" to "click",
                                "callable_tool" to "click",
                                "model_free" to true,
                                "scriptable" to true,
                                "args" to mapOf("x" to 120, "y" to 240),
                            ),
                        )
                    )
                )
            )
            assertEquals(true, register["success"])

            val firstRun = toolkit.runFunction(mapOf("function_id" to functionId))
            assertEquals(false, firstRun["success"])
            assertEquals(true, firstRun["needs_agent"])
            assertEquals(true, firstRun["fallback_available"])
            assertEquals(0, (firstRun["resume_from_step"] as Number).toInt())
            assertEquals(1, (firstRun["fallback_attempt"] as Number).toInt())
            assertNotNull(firstRun["fallback_session_id"])
            val fallbackContext = firstRun["fallback_context"] as? Map<*, *>
            assertEquals("oob.function_fallback_context.v1", fallbackContext?.get("schema_version"))
            assertEquals(functionId, fallbackContext?.get("function_id"))
            assertEquals(0, (fallbackContext?.get("resume_from_step") as Number).toInt())
            val failedStep = fallbackContext["failed_step"] as? Map<*, *>
            assertEquals("tap_takeout_with_agent", failedStep?.get("step_id"))
            val returnInstruction = fallbackContext["return_instruction"] as? Map<*, *>
            assertEquals("oob_function_run", returnInstruction?.get("tool"))
            val returnArgs = returnInstruction?.get("args") as? Map<*, *>
            assertEquals(0, (returnArgs?.get("resume_from_step") as Number).toInt())

            val secondRun = toolkit.runFunction(
                mapOf(
                    "function_id" to functionId,
                    "resume_from_step" to 1,
                    "fallback_session_id" to firstRun["fallback_session_id"],
                    "fallback_attempt" to firstRun["fallback_attempt"],
                )
            )
            assertEquals(true, secondRun["success"])
            assertEquals(false, secondRun.containsKey("fallback_context"))
            assertEquals(1, (secondRun["actions_executed"] as Number).toInt())
            assertEquals(listOf(120f to 240f), backend.clicks)
            val secondResults = secondRun["step_results"] as? List<*>
            val resumedStep = secondResults?.single() as? Map<*, *>
            assertEquals(1, (resumedStep?.get("index") as Number).toInt())
            assertEquals("click", resumedStep?.get("tool"))

            val exhaustedRun = toolkit.runFunction(
                mapOf(
                    "function_id" to functionId,
                    "fallback_session_id" to firstRun["fallback_session_id"],
                    "fallback_attempt" to 2,
                )
            )
            assertEquals(false, exhaustedRun["success"])
            assertEquals(false, exhaustedRun["fallback_available"])
            assertEquals("repeated_failure_same_step", exhaustedRun["fallback_unavailable_reason"])
            val exhaustedContext = exhaustedRun["fallback_context"] as? Map<*, *>
            assertEquals(3, (exhaustedContext?.get("fallback_attempt") as Number).toInt())
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
        "id" to "call_open_settings_function",
        "index" to 0,
        "title" to "Call open Settings Function",
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

        private const val TAKEOUT_XML = """
            <hierarchy bounds="[0,0][1080,1920]">
              <node index="0" package="com.example.food" class="android.widget.TextView" text="美食" content-desc="" resource-id="food:tab_food" clickable="true" enabled="true" visible-to-user="true" bounds="[40,80][420,200]" />
              <node index="1" package="com.example.food" class="android.widget.TextView" text="外卖" content-desc="" resource-id="food:tab_takeout" clickable="true" enabled="true" visible-to-user="true" bounds="[600,80][980,200]" />
            </hierarchy>
        """
    }
}
