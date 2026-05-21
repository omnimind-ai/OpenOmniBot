package cn.com.omnimind.assists.task.vlmserver

import org.junit.Assert.assertFalse
import org.junit.Test

class VLMTaskCompletionPolicyTest {
    @Test
    fun `max steps reached is not treated as task success`() {
        val trace = listOf(
            UIStep(
                observation = "Settings",
                thought = "点击目标",
                action = ClickAction(
                    targetDescription = "Connected devices",
                    x = 540f,
                    y = 1100f
                ),
                result = "点击坐标 (540.0, 1100.0) 成功"
            )
        )

        assertFalse(
            VLMTaskCompletionPolicy.shouldTreatMaxStepsAsBoundedSuccess(
                normalizedMaxSteps = 1,
                executionTrace = trace,
                lastError = null
            )
        )
    }

    @Test
    fun `bounded success is not used for failed or unbounded traces`() {
        val failedTrace = listOf(
            UIStep(
                observation = "Settings",
                thought = "点击目标",
                action = ClickAction(
                    targetDescription = "Connected devices",
                    x = 540f,
                    y = 1100f
                ),
                result = "执行失败: target not found"
            )
        )

        assertFalse(
            VLMTaskCompletionPolicy.shouldTreatMaxStepsAsBoundedSuccess(
                normalizedMaxSteps = 1,
                executionTrace = failedTrace,
                lastError = null
            )
        )
        assertFalse(
            VLMTaskCompletionPolicy.shouldTreatMaxStepsAsBoundedSuccess(
                normalizedMaxSteps = null,
                executionTrace = failedTrace,
                lastError = null
            )
        )
        assertFalse(
            VLMTaskCompletionPolicy.shouldTreatMaxStepsAsBoundedSuccess(
                normalizedMaxSteps = 1,
                executionTrace = failedTrace,
                lastError = "任务未完成"
            )
        )
    }
}
