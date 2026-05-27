package cn.com.omnimind.assists.task.vlmserver

import cn.com.omnimind.baselib.util.OmniLog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionExecutorTest {
    @Test
    fun `click falls back to coordinates when indexed node click fails`() = runBlocking {
        val previousLogLevel = OmniLog.getLogLevel()
        OmniLog.setLogLevel(OmniLog.Level.DISABLE)
        val operator = FakeDeviceOperator(
            clickNodeResult = OperationResult(false, "组件点击不支持: node_id=20")
        )
        val executor = ActionExecutor(operator, UIContextManager())

        try {
            val result = executor.executeAction(
                VLMStep(
                    observation = "",
                    thought = "tap settings row",
                    action = ClickAction(
                        targetDescription = "Network & internet",
                        x = 360f,
                        y = 625f,
                        elementIndex = 3,
                        nodeId = "20"
                    )
                )
            )

            assertEquals(listOf("20"), operator.clickedNodeIds)
            assertEquals(listOf(360f to 625f), operator.clickedCoordinates)
            assertFalse(result.result.orEmpty().startsWith("执行失败"))
            assertTrue(result.result.orEmpty().contains("点击坐标"))
        } finally {
            OmniLog.setLogLevel(previousLogLevel)
        }
    }

    private class FakeDeviceOperator(
        private val clickNodeResult: OperationResult = OperationResult(true, "node clicked")
    ) : DeviceOperator {
        val clickedNodeIds = mutableListOf<String>()
        val clickedCoordinates = mutableListOf<Pair<Float, Float>>()

        override suspend fun clickCoordinate(x: Float, y: Float): OperationResult {
            clickedCoordinates += x to y
            return OperationResult(true, "点击坐标 ($x, $y) 成功")
        }

        override suspend fun clickNodeById(
            nodeId: String,
            targetDescription: String
        ): OperationResult {
            clickedNodeIds += nodeId
            return clickNodeResult
        }

        override suspend fun longClickCoordinate(
            x: Float,
            y: Float,
            duration: Long
        ): OperationResult = OperationResult(true, "long clicked")

        override suspend fun inputText(text: String): OperationResult =
            OperationResult(true, "input")

        override suspend fun pressHotKey(key: String): OperationResult =
            OperationResult(true, "hotkey")

        override suspend fun copyToClipboard(text: String): OperationResult =
            OperationResult(true, "copied")

        override suspend fun getClipboard(): String? = null

        override suspend fun slideCoordinate(
            x1: Float,
            y1: Float,
            x2: Float,
            y2: Float,
            duration: Long
        ): OperationResult = OperationResult(true, "slid")

        override suspend fun goHome(): OperationResult = OperationResult(true, "home")

        override suspend fun goBack(): OperationResult = OperationResult(true, "back")

        override suspend fun launchApplication(packageName: String): OperationResult =
            OperationResult(true, "launched")

        override suspend fun captureScreenshot(): String = ""

        override fun getLastScreenshotWidth(): Int = 720

        override fun getLastScreenshotHeight(): Int = 1280

        override fun getDisplayWidth(): Int = 720

        override fun getDisplayHeight(): Int = 1280

        override suspend fun showInfo(message: String) = Unit
    }
}
