package cn.com.omnimind.bot.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpResponseBuilderPermissionTest {
    @Test
    fun `error response carries automation permission fields without exposing raw code in text`() {
        val state = TaskState(
            taskId = "task-permission",
            goal = "open settings",
            status = TaskStatus.ERROR,
            message = "请先开启无障碍权限和悬浮窗权限，视觉执行才能点击、滑动、输入并显示执行状态。",
        ).apply {
            errorCode = "OOB_ACCESSIBILITY_REQUIRED"
            missingPermissions = listOf("accessibility", "overlay")
        }

        val response = McpResponseBuilder.buildErrorResponse(state)
        val content = response["content"] as List<*>
        val text = (content.first() as Map<*, *>)["text"].toString()

        assertEquals("ERROR", response["status"])
        assertEquals("OOB_ACCESSIBILITY_REQUIRED", response["errorCode"])
        assertEquals(listOf("accessibility", "overlay"), response["missingPermissions"])
        assertTrue(text.contains("无障碍权限") || text.contains("Accessibility"))
        assertTrue(text.contains("悬浮窗权限") || text.contains("Overlay"))
        assertFalse(text.contains("OOB_ACCESSIBILITY_REQUIRED"))
    }

    @Test
    fun `mcp task responses expose execution status without compile wording`() {
        val state = TaskState(
            taskId = "task-status",
            goal = "open settings",
            status = TaskStatus.FINISHED,
            message = "done",
            needSummary = true,
        ).apply {
            compileStatus = "hit"
            executionRoute = "omniflow_recall_hit:open_settings"
            finishedContent = "done"
            summaryText = "opened settings"
            feedback = "ok"
            addChatMessage("[SYSTEM] done")
        }

        val responses = listOf(
            McpResponseBuilder.buildFinishedResponse(state),
            McpResponseBuilder.buildErrorResponse(state.copyForStatus(TaskStatus.ERROR)),
            McpResponseBuilder.buildWaitingInputResponse(
                state.copyForStatus(TaskStatus.WAITING_INPUT).apply {
                    waitingQuestion = "continue?"
                }
            ),
            McpResponseBuilder.buildUserPausedResponse(state.copyForStatus(TaskStatus.USER_PAUSED)),
            McpResponseBuilder.buildScreenLockedResponse(state.copyForStatus(TaskStatus.SCREEN_LOCKED), isInitial = true),
            McpResponseBuilder.buildTimeoutResponse(state.taskId, state.goal, state),
            McpResponseBuilder.buildTaskStatusResponse(state),
            state.toResponseMap(),
        )

        responses.forEach { response ->
            assertFalse(response.deepContainsCompileWord())
            assertEquals("hit", response["executionStatus"])
            assertFalse(response.containsKey("compileStatus"))
        }
    }

    private fun TaskState.copyForStatus(status: TaskStatus): TaskState =
        TaskState(
            taskId = taskId,
            goal = goal,
            status = status,
            needSummary = needSummary,
            message = message,
        ).also { copy ->
            copy.waitingQuestion = waitingQuestion
            copy.finishedContent = finishedContent
            copy.summaryText = summaryText
            copy.feedback = feedback
            copy.summaryUnavailable = summaryUnavailable
            copy.compileStatus = compileStatus
            copy.executionRoute = executionRoute
            copy.errorCode = errorCode
            copy.missingPermissions = missingPermissions
            chatMessages.forEach(copy::addChatMessage)
        }

    private fun Any?.deepContainsCompileWord(): Boolean {
        return when (this) {
            null -> false
            is Map<*, *> -> entries.any { (key, value) ->
                key.toString().contains("compile", ignoreCase = true) ||
                    value.deepContainsCompileWord()
            }
            is Iterable<*> -> any { it.deepContainsCompileWord() }
            else -> toString().contains("compile", ignoreCase = true) ||
                toString().contains("编译")
        }
    }
}
