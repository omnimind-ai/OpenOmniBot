package cn.com.omnimind.assists.task.vlmserver

import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.AssistantToolCallFunction
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionTurn
import cn.com.omnimind.baselib.llm.ModelSceneRegistry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    @Test
    fun `operation request can include raw and marked screenshots`() {
        val client = VLMClient(
            systemPromptBuilder = { "system prompt" },
            turnPromptBuilder = { context, _ -> context.overallTask }
        )

        val envelope = client.buildUIOperationRequest(
            context = UIContext(overallTask = "Open Settings"),
            screenshot = "RAW_IMAGE",
            markedScreenshot = "MARKED_IMAGE",
            conversationState = VLMConversationState()
        )

        val currentUser = envelope.request.messages.last()
        val blocks = currentUser.content!!.jsonArray
        assertEquals(5, blocks.size)
        assertEquals("Raw screenshot.", blocks[1].jsonObject["text"]!!.jsonPrimitive.contentOrNull)
        assertEquals(
            "data:image/png;base64,RAW_IMAGE",
            blocks[2].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.contentOrNull
        )
        assertEquals(
            "Marked screenshot with indexes matching OOB indexed page evidence.",
            blocks[3].jsonObject["text"]!!.jsonPrimitive.contentOrNull
        )
        assertEquals(
            "data:image/png;base64,MARKED_IMAGE",
            blocks[4].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.contentOrNull
        )
    }

    @Test
    fun `conversation tool result includes post action page observation`() {
        val client = VLMClient()
        val round = client.buildConversationRound(
            currentUserText = "current turn",
            assistantTurn = SceneChatCompletionTurn(
                parser = ModelSceneRegistry.ResponseParser.OPENAI_TOOL_ACTIONS,
                route = "scene.vlm.operation.primary",
                resolvedModel = "vlm-test-model",
                turn = ChatCompletionTurn(
                    message = ChatCompletionMessage(
                        role = "assistant",
                        content = JsonPrimitive("""{"thought":"tap settings"}"""),
                        toolCalls = listOf(
                            AssistantToolCall(
                                id = "call_1",
                                function = AssistantToolCallFunction(
                                    name = "click",
                                    arguments = """{"target_description":"Settings","x":100,"y":100}"""
                                )
                            )
                        )
                    )
                )
            ),
            executedStep = UIStep(
                observation = "before",
                thought = "tap",
                action = ClickAction(targetDescription = "Settings", x = 100f, y = 100f),
                result = "OK",
                observationXml = BEFORE_XML,
                afterObservationXml = AFTER_XML,
                packageName = "com.android.launcher",
                afterPackageName = "com.android.settings"
            )
        )

        val payload = json.parseToJsonElement(round.toolMessage.content!!.jsonPrimitive.contentOrNull!!).jsonObject
        assertTrue(payload["screen_changed"]!!.jsonPrimitive.boolean)
        assertTrue(payload["package_changed"]!!.jsonPrimitive.boolean)
        assertEquals("com.android.settings", payload["after_package"]!!.jsonPrimitive.contentOrNull)
        assertTrue(payload["after_visible_texts"].toString().contains("Network & internet"))
        assertTrue(payload["appeared_texts"].toString().contains("Network & internet"))
        assertTrue(payload["post_action_observation"].toString().contains("after action screen changed"))
    }

    @Test
    fun `openai tool action parser supports input_text with target grounding`() {
        val client = VLMClient()
        val result = client.parseVLMResponse(
            SceneChatCompletionTurn(
                parser = ModelSceneRegistry.ResponseParser.OPENAI_TOOL_ACTIONS,
                route = "scene.vlm.operation.primary",
                resolvedModel = "vlm-test-model",
                turn = ChatCompletionTurn(
                    message = ChatCompletionMessage(
                        role = "assistant",
                        toolCalls = listOf(
                            AssistantToolCall(
                                id = "call_1",
                                function = AssistantToolCallFunction(
                                    name = "input_text",
                                    arguments = """{"target_description":"Last name","content":"Smith","x":356,"y":799.5}"""
                                )
                            )
                        )
                    )
                )
            ),
            modelOrScene = "scene.vlm.operation.primary"
        )

        assertTrue(result.success)
        val step = requireNotNull(result.step)
        val action = step.action as InputTextAction
        assertEquals("Last name", action.targetDescription)
        assertEquals("Smith", action.content)
        assertEquals(356f, action.x, 0.01f)
        assertEquals(799.5f, action.y, 0.01f)
    }

    @Test
    fun `text fallback tool parser supports input_text`() {
        val client = VLMClient()
        val result = client.parseVLMResponse(
            SceneChatCompletionTurn(
                parser = ModelSceneRegistry.ResponseParser.OPENAI_TOOL_ACTIONS,
                route = "scene.vlm.operation.primary",
                resolvedModel = "vlm-test-model",
                turn = ChatCompletionTurn(
                    message = ChatCompletionMessage(
                        role = "assistant",
                        content = JsonPrimitive(
                            """{"name":"input_text","arguments":{"target_description":"Phone","content":"415-555-0130","x":480,"y":702}}"""
                        )
                    )
                )
            ),
            modelOrScene = "scene.vlm.operation.primary"
        )

        assertTrue(result.success)
        val action = requireNotNull(result.step).action as InputTextAction
        assertEquals("Phone", action.targetDescription)
        assertEquals("415-555-0130", action.content)
    }

    @Test
    fun `openai tool action parser preserves indexed grounding fields`() {
        val client = VLMClient()
        val clickResult = client.parseVLMResponse(
            SceneChatCompletionTurn(
                parser = ModelSceneRegistry.ResponseParser.OPENAI_TOOL_ACTIONS,
                route = "scene.vlm.operation.primary",
                resolvedModel = "vlm-test-model",
                turn = ChatCompletionTurn(
                    message = ChatCompletionMessage(
                        role = "assistant",
                        toolCalls = listOf(
                            AssistantToolCall(
                                id = "call_1",
                                function = AssistantToolCallFunction(
                                    name = "click",
                                    arguments = """{"target_description":"Display","element_index":3,"x":500,"y":740}"""
                                )
                            )
                        )
                    )
                )
            ),
            modelOrScene = "scene.vlm.operation.primary"
        )

        assertTrue(clickResult.success)
        val click = requireNotNull(clickResult.step).action as ClickAction
        assertEquals(3, click.elementIndex)

        val scrollResult = client.parseVLMResponse(
            SceneChatCompletionTurn(
                parser = ModelSceneRegistry.ResponseParser.OPENAI_TOOL_ACTIONS,
                route = "scene.vlm.operation.primary",
                resolvedModel = "vlm-test-model",
                turn = ChatCompletionTurn(
                    message = ChatCompletionMessage(
                        role = "assistant",
                        toolCalls = listOf(
                            AssistantToolCall(
                                id = "call_2",
                                function = AssistantToolCallFunction(
                                    name = "scroll",
                                    arguments = """{"target_description":"Settings list","scrollable_index":0,"direction":"down","x1":500,"y1":880,"x2":500,"y2":250}"""
                                )
                            )
                        )
                    )
                )
            ),
            modelOrScene = "scene.vlm.operation.primary"
        )

        assertTrue(scrollResult.success)
        val scroll = requireNotNull(scrollResult.step).action as ScrollAction
        assertEquals(0, scroll.scrollableIndex)
        assertEquals("down", scroll.direction)
    }

    companion object {
        private const val BEFORE_XML =
            """
            <hierarchy>
              <node bounds="[0,0][720,1280]">
                <node text="Settings" bounds="[20,20][120,80]" clickable="true" />
              </node>
            </hierarchy>
            """

        private const val AFTER_XML =
            """
            <hierarchy>
              <node bounds="[0,0][720,1280]">
                <node text="Settings" bounds="[48,256][312,353]" />
                <node text="Network &amp; internet" bounds="[144,579][475,633]" clickable="true" />
              </node>
            </hierarchy>
            """
    }
}
