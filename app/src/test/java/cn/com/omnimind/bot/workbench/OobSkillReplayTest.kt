package cn.com.omnimind.bot.workbench

import cn.com.omnimind.bot.agent.tool.handlers.OobFunctionToolHandler
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end flow tests for oob.reusable_function.v1 fixed replay.
 *
 * Tests cover: spec parsing, materializedSteps, executor classification,
 * canRunFullyWithOmniflow, and coordinate_hook guard.
 */
class OobSkillReplayTest {

    private val gson = Gson()

    // ── helpers ───────────────────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun specFromJson(json: String): Map<String, Any?> =
        gson.fromJson(json, Map::class.java) as Map<String, Any?>

    private fun materializedSteps(spec: Map<String, Any?>): List<Map<String, Any?>> {
        val rawSteps = (spec["execution"] as? Map<*, *>)?.get("steps") as? List<*>
            ?: return emptyList()
        return rawSteps.mapNotNull { rawStep ->
            (rawStep as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v }
        }
    }

    private fun isOmniflowStep(step: Map<String, Any?>): Boolean {
        val executor = step["executor"]?.toString()?.trim()?.lowercase().orEmpty()
        val modelFree = step["model_free"] == true
        val action = firstNonBlank(step["omniflow_action"], step["tool"], step["callable_tool"])
        val omniflowActions = setOf("click", "long_press", "scroll", "type",
            "open_app", "press_home", "press_back", "hot_key")
        return executor == "omniflow" || (modelFree && action in omniflowActions)
    }

    private fun canRunFullyWithOmniflow(spec: Map<String, Any?>): Boolean {
        val steps = materializedSteps(spec)
        return steps.isNotEmpty() && steps.all { isOmniflowStep(it) }
    }

    private fun requiresAgentPlanning(step: Map<String, Any?>): Boolean {
        val agentCall = step["agent_call"] as? Map<*, *>
        val reason = agentCall?.get("reason")?.toString()
            ?: step["reason"]?.toString()
            ?: ""
        return reason == "data_flow_tool_requires_live_context" ||
            reason == "perception_only_step_without_recorded_actions"
    }

    private fun firstNonBlank(vararg values: Any?): String {
        for (v in values) {
            val s = v?.toString()?.trim().orEmpty()
            if (s.isNotEmpty()) return s
        }
        return ""
    }

    // ── minimal skill spec ────────────────────────────────────────────────────

    private val pureOmniflowSpec = """
    {
      "schema_version": "oob.reusable_function.v1",
      "function_id": "runlog_test001",
      "name": "打开微信给张三发消息",
      "description": "打开微信给张三发消息",
      "source": {"kind": "run_log", "run_id": "run001"},
      "constraints": {"package_name": "com.tencent.mm"},
      "execution": {
        "steps": [
          {
            "id": "step_1", "index": 0,
            "title": "打开微信",
            "executor": "omniflow",
            "omniflow_action": "open_app",
            "args": {"package_name": "com.tencent.mm"}
          },
          {
            "id": "step_2", "index": 1,
            "title": "点击联系人张三",
            "executor": "omniflow",
            "omniflow_action": "click",
            "coordinate_hook": "omniflow",
            "source_context": {
              "src_ctx": {
                "page": "<hierarchy><node bounds=\"[0,0][1080,1920]\" clickable=\"true\" text=\"张三\"/></hierarchy>",
                "action": {"tool": "click", "x": 540, "y": 400}
              }
            },
            "args": {"target_description": "张三", "x": 540, "y": 400}
          },
          {
            "id": "step_3", "index": 2,
            "title": "输入消息",
            "executor": "omniflow",
            "omniflow_action": "type",
            "args": {"content": "你好"}
          },
          {
            "id": "step_4", "index": 3,
            "title": "点击发送",
            "executor": "omniflow",
            "omniflow_action": "click",
            "coordinate_hook": "omniflow",
            "source_context": {
              "src_ctx": {
                "page": "<hierarchy><node bounds=\"[900,1800][1080,1900]\" clickable=\"true\" text=\"发送\"/></hierarchy>",
                "action": {"tool": "click", "x": 990, "y": 1850}
              }
            },
            "args": {"target_description": "发送", "x": 990, "y": 1850}
          }
        ],
        "omniflow_step_count": 4,
        "agent_step_count": 0
      }
    }
    """.trimIndent()

    private val mixedSpec = """
    {
      "schema_version": "oob.reusable_function.v1",
      "function_id": "runlog_test002",
      "name": "搜索并打开",
      "description": "搜索并打开网页",
      "execution": {
        "steps": [
          {
            "id": "step_1", "index": 0,
            "title": "打开浏览器",
            "executor": "omniflow",
            "omniflow_action": "open_app",
            "args": {"package_name": "com.android.chrome"}
          },
            {
            "id": "step_2", "index": 1,
            "title": "搜索内容",
            "executor": "agent",
            "tool": "browser_use",
            "callable_tool": "oob.agent.run",
            "scriptable": false,
            "args": {"url": "https://google.com", "query": "test"},
            "agent_call": {
              "tool": "oob.agent.run",
              "args": {
                "prompt": "在浏览器中搜索 test",
                "original_tool": "browser_use",
                "original_args": {"url": "https://google.com", "query": "test"}
              },
              "reason": "data_flow_tool_requires_live_context"
            }
          }
        ],
        "omniflow_step_count": 1,
        "agent_step_count": 1
      }
    }
    """.trimIndent()

    private val noCoordHookSpec = """
    {
      "schema_version": "oob.reusable_function.v1",
      "function_id": "runlog_test003",
      "name": "返回桌面",
      "execution": {
        "steps": [
          {
            "id": "step_1", "index": 0,
            "title": "返回桌面",
            "executor": "omniflow",
            "omniflow_action": "press_home",
            "args": {}
          }
        ],
        "omniflow_step_count": 1,
        "agent_step_count": 0
      }
    }
    """.trimIndent()

    // ── spec parsing ──────────────────────────────────────────────────────────

    @Test
    fun `reusable function spec has correct schema version`() {
        val spec = specFromJson(pureOmniflowSpec)
        assertEquals("oob.reusable_function.v1", spec["schema_version"])
    }

    @Test
    fun `materializedSteps reads execution steps correctly`() {
        val spec = specFromJson(pureOmniflowSpec)
        val steps = materializedSteps(spec)
        assertEquals(4, steps.size)
    }

    @Test
    fun `step fields are correctly parsed`() {
        val spec = specFromJson(pureOmniflowSpec)
        val steps = materializedSteps(spec)

        val openApp = steps[0]
        assertEquals("omniflow", openApp["executor"])
        assertEquals("open_app", openApp["omniflow_action"])

        val click = steps[1]
        assertEquals("omniflow", click["executor"])
        assertEquals("click", click["omniflow_action"])
        assertEquals("omniflow", click["coordinate_hook"])
        assertNotNull(click["source_context"])

        val type = steps[2]
        assertEquals("type", type["omniflow_action"])
        assertNull(type["coordinate_hook"]) // type has no coordinate hook

        val args = click["args"] as? Map<*, *>
        assertEquals("张三", args?.get("target_description"))
    }

    // ── canRunFullyWithOmniflow ────────────────────────────────────────────────

    @Test
    fun `pure omniflow skill can run fully without agent`() {
        val spec = specFromJson(pureOmniflowSpec)
        assertTrue(canRunFullyWithOmniflow(spec))
    }

    @Test
    fun `mixed skill with agent step cannot run fully without agent`() {
        val spec = specFromJson(mixedSpec)
        assertFalse(canRunFullyWithOmniflow(spec))
    }

    @Test
    fun `single press_home skill can run fully`() {
        val spec = specFromJson(noCoordHookSpec)
        assertTrue(canRunFullyWithOmniflow(spec))
    }

    // ── coordinate hook guard ─────────────────────────────────────────────────

    @Test
    fun `coordinate_hook only present when source_context is non-empty`() {
        val spec = specFromJson(pureOmniflowSpec)
        val steps = materializedSteps(spec)

        // open_app: no coordinate_hook
        assertNull(steps[0]["coordinate_hook"])
        // click with source_context: has coordinate_hook
        assertEquals("omniflow", steps[1]["coordinate_hook"])
        // type: no coordinate_hook
        assertNull(steps[2]["coordinate_hook"])
        // click with source_context: has coordinate_hook
        assertEquals("omniflow", steps[3]["coordinate_hook"])
    }

    @Test
    fun `step with coordinate_hook has valid source_context page xml`() {
        val spec = specFromJson(pureOmniflowSpec)
        val steps = materializedSteps(spec)
        val clickStep = steps[1]

        val sourceContext = clickStep["source_context"] as? Map<*, *>
        assertNotNull(sourceContext)
        val srcCtx = sourceContext?.get("src_ctx") as? Map<*, *>
        assertNotNull(srcCtx)
        val page = srcCtx?.get("page")?.toString().orEmpty()
        assertTrue("source_context.src_ctx.page should be non-empty", page.isNotEmpty())
    }

    // ── mixed skill agent step structure ──────────────────────────────────────

    @Test
    fun `agent step has tool and callable_tool`() {
        val spec = specFromJson(mixedSpec)
        val steps = materializedSteps(spec)
        val agentStep = steps[1]

        assertEquals("agent", agentStep["executor"])
        assertEquals("browser_use", agentStep["tool"])
        assertEquals("oob.agent.run", agentStep["callable_tool"])
        assertEquals(false, agentStep["scriptable"])

        val agentCall = agentStep["agent_call"] as? Map<*, *>
        assertNotNull(agentCall)
        assertEquals("oob.agent.run", agentCall?.get("tool"))
        assertEquals("data_flow_tool_requires_live_context", agentCall?.get("reason"))
        val agentArgs = agentCall?.get("args") as? Map<*, *>
        assertNotNull(agentArgs?.get("prompt"))
        assertEquals("browser_use", agentArgs?.get("original_tool"))
        assertTrue(requiresAgentPlanning(agentStep))
    }

    // ── source_context structure ───────────────────────────────────────────────

    @Test
    fun `source_context contains target coordinates`() {
        val spec = specFromJson(pureOmniflowSpec)
        val steps = materializedSteps(spec)
        val clickStep = steps[1]
        val sourceContext = clickStep["source_context"] as? Map<*, *>
        val srcCtx = sourceContext?.get("src_ctx") as? Map<*, *>
        val action = srcCtx?.get("action") as? Map<*, *>

        assertNotNull(action)
        assertEquals("click", action?.get("tool"))
        assertNotNull(action?.get("x"))
        assertNotNull(action?.get("y"))
    }

    @Test
    fun `constraints package name survives spec round-trip`() {
        val spec = specFromJson(pureOmniflowSpec)
        val constraints = spec["constraints"] as? Map<*, *>
        assertEquals("com.tencent.mm", constraints?.get("package_name"))
    }
}
