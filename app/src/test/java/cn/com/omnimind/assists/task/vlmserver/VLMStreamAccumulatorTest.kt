package cn.com.omnimind.assists.task.vlmserver

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class VLMStreamAccumulatorTest {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `reads tokens per second from usage performance payload`() {
        val accumulator = VLMStreamAccumulator(json)

        accumulator.consume("""{"choices":[{"delta":{"content":"已完成。"}}]}""")
        accumulator.consume(
            """
            {"id":"chatcmpl-test","object":"chat.completion.chunk","choices":[],"usage":{"prompt_tokens":15,"completion_tokens":100,"total_tokens":115,"performance":{"prefill_tokens_per_second":36.6,"decode_tokens_per_second":12.4}}}
            """.trimIndent()
        )

        val turn = accumulator.buildTurn()

        assertNotNull(turn.usage)
        assertEquals(36.6, turn.usage?.prefillTokensPerSecond ?: 0.0, 0.0)
        assertEquals(12.4, turn.usage?.decodeTokensPerSecond ?: 0.0, 0.0)
    }
}
