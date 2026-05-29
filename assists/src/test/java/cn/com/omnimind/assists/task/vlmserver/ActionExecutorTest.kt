package cn.com.omnimind.assists.task.vlmserver

import cn.com.omnimind.baselib.util.OmniLog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionExecutorTest {
    @Test
    fun `click uses coordinates even when indexed node id is present`() = runBlocking {
        val previousLogLevel = OmniLog.getLogLevel()
        OmniLog.setLogLevel(OmniLog.Level.DISABLE)
        val operator = FakeDeviceOperator()
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

            assertEquals(emptyList<String>(), operator.clickedNodeIds)
            assertEquals(listOf(360f to 625f), operator.clickedCoordinates)
            assertFalse(result.result.orEmpty().startsWith("执行失败"))
            assertTrue(result.result.orEmpty().contains("点击坐标"))
        } finally {
            OmniLog.setLogLevel(previousLogLevel)
        }
    }

    @Test
    fun `input text uses indexed node id before coordinate focus fallback`() = runBlocking {
        val previousLogLevel = OmniLog.getLogLevel()
        OmniLog.setLogLevel(OmniLog.Level.DISABLE)
        val operator = FakeDeviceOperator()
        val executor = ActionExecutor(operator, UIContextManager())

        try {
            val result = executor.executeAction(
                VLMStep(
                    observation = "",
                    thought = "type first name",
                    action = InputTextAction(
                        targetDescription = "First name",
                        content = "Alice",
                        x = 356f,
                        y = 660f,
                        elementIndex = 2,
                        nodeId = "33"
                    )
                )
            )

            assertEquals(listOf("33" to "Alice"), operator.nodeInputs)
            assertEquals(emptyList<Pair<Float, Float>>(), operator.clickedCoordinates)
            assertFalse(result.result.orEmpty().startsWith("执行失败"))
            assertTrue(result.result.orEmpty().contains("输入文本成功"))
        } finally {
            OmniLog.setLogLevel(previousLogLevel)
        }
    }

    @Test
    fun `input text falls back to coordinate focus when node input fails`() = runBlocking {
        val previousLogLevel = OmniLog.getLogLevel()
        OmniLog.setLogLevel(OmniLog.Level.DISABLE)
        val operator = FakeDeviceOperator(
            nodeInputResult = OperationResult(false, "组件输入失败")
        )
        val executor = ActionExecutor(operator, UIContextManager())

        try {
            val result = executor.executeAction(
                VLMStep(
                    observation = "",
                    thought = "type phone",
                    action = InputTextAction(
                        targetDescription = "Phone",
                        content = "415-555-0130",
                        x = 356f,
                        y = 1112f,
                        elementIndex = 4,
                        nodeId = "56"
                    )
                )
            )

            assertEquals(listOf("56" to "415-555-0130"), operator.nodeInputs)
            assertEquals(listOf(356f to 1112f), operator.clickedCoordinates)
            assertEquals(listOf("415-555-0130"), operator.focusedInputs)
            assertFalse(result.result.orEmpty().startsWith("执行失败"))
        } finally {
            OmniLog.setLogLevel(previousLogLevel)
        }
    }

    private class FakeDeviceOperator(
        private val clickNodeResult: OperationResult = OperationResult(true, "node clicked"),
        private val nodeInputResult: OperationResult = OperationResult(true, "向组件输入文本成功")
    ) : DeviceOperator {
        val clickedNodeIds = mutableListOf<String>()
        val clickedCoordinates = mutableListOf<Pair<Float, Float>>()
        val nodeInputs = mutableListOf<Pair<String, String>>()
        val focusedInputs = mutableListOf<String>()

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
            OperationResult(true, "输入文本成功: $text").also {
                focusedInputs += text
            }

        override suspend fun inputTextToNodeById(
            nodeId: String,
            text: String,
            targetDescription: String
        ): OperationResult {
            nodeInputs += nodeId to text
            return nodeInputResult
        }

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
