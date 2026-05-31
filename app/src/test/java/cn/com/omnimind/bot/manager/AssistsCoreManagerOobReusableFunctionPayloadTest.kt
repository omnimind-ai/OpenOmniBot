package cn.com.omnimind.bot.manager

import cn.com.omnimind.bot.vlm.VlmToolOutcome
import cn.com.omnimind.bot.vlm.VlmToolOutcomeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistsCoreManagerOobReusableFunctionPayloadTest {
    @Test
    fun `local reusable function payload reports completed local status and timing internally`() {
        val timing = mapOf(
            "duration_ms" to 21L,
            "phase_ms" to mapOf("rank_functions_ms" to 3L)
        )
        val stepResults = listOf<Map<*, *>>(
            mapOf("tool" to "click", "success" to true),
            mapOf("tool" to "finished", "success" to true),
        )

        val payload = buildOobReusableFunctionLocalPayload(
            functionId = "open_settings",
            localSuccess = true,
            runPayload = mapOf(
                "runner" to "oob_omniflow_replay",
                "step_count" to 2,
                "success_step_count" to 2,
                "model_used" to false,
                "timing" to timing
            ),
            stepResults = stepResults,
            argumentCount = 1
        )

        assertEquals(true, payload["success"])
        assertEquals(OOB_REUSABLE_EXECUTION_STATUS_COMPLETED_LOCAL, payload["execution_status"])
        val terminalState = payload["terminal_state"] as Map<*, *>
        assertEquals(OOB_REUSABLE_EXECUTION_STATUS_COMPLETED_LOCAL, terminalState["status"])
        assertEquals(2, terminalState["step_count"])
        assertEquals(2, terminalState["success_step_count"])
        assertEquals(false, terminalState["model_used"])
        assertEquals(timing, terminalState["timing"])
        val context = payload["context"] as Map<*, *>
        assertEquals(1, context["argument_count"])
        assertEquals(2, context["step_count"])
        assertEquals(timing, context["timing"])
    }

    @Test
    fun `agent fallback payload keeps local prefix and pending step counts in context`() {
        val timing = mapOf(
            "duration_ms" to 34L,
            "phase_ms" to mapOf("rank_functions_ms" to 5L)
        )
        val stepResults = listOf<Map<*, *>>(
            mapOf("tool" to "click", "success" to true),
            mapOf(
                "executor" to "agent",
                "tool" to "call_tool",
                "fallback_available" to true,
                "needs_agent" to true,
                "success" to false
            ),
        )

        val payload = buildOobReusableFunctionAgentFallbackPayload(
            functionId = "open_settings_then_vlm",
            taskId = "agent-task-1",
            conversationId = 42L,
            started = true,
            startErrorCode = null,
            startErrorMessage = null,
            runPayload = mapOf(
                "runner" to "oob_mixed_runner",
                "model_required" to true,
                "fallback_available" to true,
                "timing" to timing
            ),
            stepResults = stepResults,
            completedStepCount = 1,
            pendingAgentStepCount = 1,
            argumentCount = 0
        )

        assertEquals(true, payload["success"])
        assertEquals(
            OOB_REUSABLE_EXECUTION_STATUS_STARTED_AGENT_FALLBACK,
            payload["execution_status"]
        )
        val terminalState = payload["terminal_state"] as Map<*, *>
        assertEquals("agent-task-1", terminalState["agent_task_id"])
        assertEquals(42L, terminalState["conversationId"])
        assertEquals(42L, terminalState["conversation_id"])
        assertEquals(true, terminalState["agent_task_started"])
        assertEquals(1, terminalState["local_steps_completed"])
        assertEquals(1, terminalState["agent_steps_pending"])
        assertEquals(2, terminalState["step_count"])
        assertEquals(1, terminalState["success_step_count"])
        assertEquals(true, terminalState["model_required"])
        assertEquals(timing, terminalState["timing"])
        val context = payload["context"] as Map<*, *>
        assertEquals("agent-task-1", context["agent_task_id"])
        assertEquals(42L, context["conversationId"])
        assertEquals(42L, context["conversation_id"])
        assertEquals(1, context["local_steps_completed"])
        assertEquals(1, context["agent_steps_pending"])
        assertEquals(2, context["step_count"])
        assertEquals(1, context["success_step_count"])
        assertEquals(timing, context["timing"])
    }

    @Test
    fun `direct vlm fallback payload reports completed vlm status`() {
        val stepResults = listOf<Map<*, *>>(
            mapOf("tool" to "open_app", "success" to true),
            mapOf("tool" to "input_text", "success" to false, "needs_agent" to true),
        )
        val payload = buildOobReusableFunctionVlmFallbackPayload(
            functionId = "open_settings_then_vlm",
            runId = "run-1",
            vlmTaskId = "vlm-1",
            outcome = VlmToolOutcome(
                taskId = "vlm-1",
                goal = "继续执行",
                status = VlmToolOutcomeStatus.FINISHED,
                message = "已完成",
                needSummary = false,
            ),
            success = true,
            runPayload = mapOf(
                "runner" to "oob_mixed_runner",
                "fallback_available" to true,
            ),
            stepResults = stepResults,
            completedStepCount = 1,
            pendingAgentStepCount = 1,
            argumentCount = 0,
        )

        assertEquals(true, payload["success"])
        assertEquals(
            OOB_REUSABLE_EXECUTION_STATUS_COMPLETED_VLM_FALLBACK,
            payload["execution_status"]
        )
        val terminalState = payload["terminal_state"] as Map<*, *>
        assertEquals("run-1", terminalState["taskId"])
        assertEquals("vlm-1", terminalState["vlm_task_id"])
        assertEquals(false, terminalState["model_required"])
        assertEquals(true, terminalState["delegated_tool_used"])
        assertEquals("FINISHED", terminalState["vlm_status"])
        assertEquals(2, terminalState["success_step_count"])
    }

    @Test
    fun `local reusable function payload preserves accessibility preflight error`() {
        val payload = buildOobReusableFunctionLocalPayload(
            functionId = "click_requires_accessibility",
            localSuccess = false,
            runPayload = mapOf(
                "runner" to "oob_omniflow_replay",
                "step_count" to 1,
                "success_step_count" to 0,
                "error_code" to "OOB_ACCESSIBILITY_REQUIRED",
                "error_message" to "请先开启无障碍权限，复用指令才能执行点击、滑动和输入。",
            ),
            stepResults = listOf(
                mapOf(
                    "tool" to "click",
                    "success" to false,
                    "error_code" to "OOB_ACCESSIBILITY_REQUIRED",
                )
            ),
            argumentCount = 0
        )

        assertEquals(false, payload["success"])
        assertEquals(OOB_REUSABLE_EXECUTION_STATUS_FAILED, payload["execution_status"])
        assertEquals("OOB_ACCESSIBILITY_REQUIRED", payload["error_code"])
        assertTrue(payload["error_message"].toString().contains("无障碍"))
    }

    @Test
    fun `pending agent step detection covers agent and blocked executor forms`() {
        assertTrue(
            isOobReusableFunctionPendingAgentStep(
                mapOf("executor" to "agent", "fallback_available" to true)
            )
        )
        assertTrue(isOobReusableFunctionPendingAgentStep(mapOf("blocked_executor" to "tool")))
        assertTrue(isOobReusableFunctionPendingAgentStep(mapOf("blocked_executor" to "omniflow")))
        assertFalse(isOobReusableFunctionPendingAgentStep(mapOf("executor" to "omniflow")))
    }
}
