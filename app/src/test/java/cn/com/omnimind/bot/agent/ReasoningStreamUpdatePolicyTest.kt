package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.llm.ReasoningStreamUpdatePolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class ReasoningStreamUpdatePolicyTest {

    @Test
    fun `first reasoning update emits immediately`() {
        val delay = ReasoningStreamUpdatePolicy.nextDelayMs(
            hasEmittedBefore = false,
            lastEmitAtMs = 0L,
            nowMs = 1000L
        )

        assertEquals(0L, delay)
    }

    @Test
    fun `subsequent reasoning update keeps remaining throttle window`() {
        val delay = ReasoningStreamUpdatePolicy.nextDelayMs(
            hasEmittedBefore = true,
            lastEmitAtMs = 1000L,
            nowMs = 1120L
        )

        assertEquals(180L, delay)
    }

    @Test
    fun `subsequent reasoning update emits immediately after throttle window`() {
        val delay = ReasoningStreamUpdatePolicy.nextDelayMs(
            hasEmittedBefore = true,
            lastEmitAtMs = 1000L,
            nowMs = 1400L
        )

        assertEquals(0L, delay)
    }
}
