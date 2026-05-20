package cn.com.omnimind.assists.task.vlmserver

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VLMClientRequestTest {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `raw VLM model id keeps primary tool-action scene and uses model override`() {
        val client = VLMClient()

        assertEquals("scene.vlm.operation.primary", client.resolveVlmSceneId("androidworld-vlm-model"))
        assertEquals("androidworld-vlm-model", client.resolveVlmModelOverride("androidworld-vlm-model"))
    }

    @Test
    fun `scene model id is not duplicated as override`() {
        val client = VLMClient()

        assertEquals("scene.vlm.operation.primary", client.resolveVlmSceneId("scene.vlm.operation.primary"))
        assertEquals(null, client.resolveVlmModelOverride("scene.vlm.operation.primary"))
    }

    @Test
    fun `model override is transport metadata and not serialized to provider payload`() {
        val request = cn.com.omnimind.baselib.llm.ChatCompletionRequest(
            model = "scene.vlm.operation.primary",
            modelOverride = "androidworld-vlm-model",
            messages = emptyList()
        )
        val encoded = json.encodeToString(request)

        assertFalse(encoded.contains("modelOverride"))
        assertFalse(encoded.contains("model_override"))
        assertTrue(encoded.contains("scene.vlm.operation.primary"))
    }
}
