package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.i18n.PromptLocale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentSystemPromptTest {
    @Test
    fun buildMentionsWorkspaceVenvInsteadOfBreakingSystemPackages() {
        val prompt = AgentSystemPrompt.build(
            workspace = AgentWorkspaceDescriptor(
                id = "conversation-1",
                rootPath = "/workspace",
                androidRootPath = "/data/user/0/cn.com.omnimind.bot/workspace",
                uriRoot = "omnibot://workspace",
                currentCwd = "/workspace/demo",
                androidCurrentCwd = "/data/user/0/cn.com.omnimind.bot/workspace/demo",
                shellRootPath = "/workspace",
                retentionPolicy = "shared_root"
            ),
            installedSkills = emptyList(),
            skillsRootShellPath = "/workspace/.omnibot/skills",
            skillsRootAndroidPath = "/data/user/0/cn.com.omnimind.bot/workspace/.omnibot/skills",
            resolvedSkills = emptyList(),
            memoryContext = null,
            activeWorkbenchProjectContext = null,
            locale = PromptLocale.ZH_CN
        )

        assertTrue(prompt.contains(".venv"))
        assertTrue(prompt.contains("uv"))
        assertTrue(prompt.contains("--copies"))
        assertTrue(prompt.contains("--break-system-packages"))
        assertTrue(prompt.contains("shell.exec"))
        assertTrue(prompt.contains("android_privileged_session_*"))
    }

    @Test
    fun buildCachedSystemPromptContentAddsEphemeralCacheControl() {
        val content = OmniAgentExecutor.buildCachedSystemPromptContent("system prompt")
        val blocks = content as JsonArray
        val firstBlock = blocks.first() as JsonObject

        assertEquals("\"text\"", firstBlock["type"].toString())
        assertEquals("\"system prompt\"", firstBlock["text"].toString())
        assertEquals(
            "\"ephemeral\"",
            (firstBlock["cache_control"] as JsonObject)["type"].toString()
        )
    }

    @Test
    fun buildUsesEnglishPromptWhenLocaleIsEnglish() {
        val prompt = AgentSystemPrompt.build(
            workspace = AgentWorkspaceDescriptor(
                id = "conversation-1",
                rootPath = "/workspace",
                androidRootPath = "/data/user/0/cn.com.omnimind.bot/workspace",
                uriRoot = "omnibot://workspace",
                currentCwd = "/workspace/demo",
                androidCurrentCwd = "/data/user/0/cn.com.omnimind.bot/workspace/demo",
                shellRootPath = "/workspace",
                retentionPolicy = "shared_root"
            ),
            installedSkills = emptyList(),
            skillsRootShellPath = "/workspace/.omnibot/skills",
            skillsRootAndroidPath = "/data/user/0/cn.com.omnimind.bot/workspace/.omnibot/skills",
            resolvedSkills = emptyList(),
            memoryContext = null,
            activeWorkbenchProjectContext = null,
            locale = PromptLocale.EN_US
        )

        assertTrue(prompt.contains("You are an AI Agent operating inside an Alpine workspace environment"))
        assertTrue(prompt.contains("File and artifact rules"))
        assertTrue(prompt.contains("Skills:"))
        assertTrue(prompt.contains("action=shell.exec"))
        assertTrue(prompt.contains("android_privileged_session_*"))
    }

    @Test
    fun buildInjectsActiveWorkbenchProjectContext() {
        val prompt = AgentSystemPrompt.build(
            workspace = AgentWorkspaceDescriptor(
                id = "conversation-1",
                rootPath = "/workspace",
                androidRootPath = "/data/user/0/cn.com.omnimind.bot/workspace",
                uriRoot = "omnibot://workspace",
                currentCwd = "/workspace/demo",
                androidCurrentCwd = "/data/user/0/cn.com.omnimind.bot/workspace/demo",
                shellRootPath = "/workspace",
                retentionPolicy = "shared_root"
            ),
            installedSkills = emptyList(),
            skillsRootShellPath = "/workspace/.omnibot/skills",
            skillsRootAndroidPath = "/data/user/0/cn.com.omnimind.bot/workspace/.omnibot/skills",
            resolvedSkills = emptyList(),
            memoryContext = null,
            activeWorkbenchProjectContext = "projectId: oob-workbench-todo-log\napi: todo.add",
            locale = PromptLocale.ZH_CN
        )

        assertTrue(prompt.contains("当前激活的 OOB Workbench Project"))
        assertTrue(prompt.contains("oob-workbench-todo-log"))
        assertTrue(prompt.contains("todo.add"))
    }

    @Test
    fun buildIncludesWorkbenchHomeInputRoutingRules() {
        val prompt = AgentSystemPrompt.build(
            workspace = AgentWorkspaceDescriptor(
                id = "conversation-1",
                rootPath = "/workspace",
                androidRootPath = "/data/user/0/cn.com.omnimind.bot/workspace",
                uriRoot = "omnibot://workspace",
                currentCwd = "/workspace/demo",
                androidCurrentCwd = "/data/user/0/cn.com.omnimind.bot/workspace/demo",
                shellRootPath = "/workspace",
                retentionPolicy = "shared_root"
            ),
            installedSkills = emptyList(),
            skillsRootShellPath = "/workspace/.omnibot/skills",
            skillsRootAndroidPath = "/data/user/0/cn.com.omnimind.bot/workspace/.omnibot/skills",
            resolvedSkills = emptyList(),
            memoryContext = null,
            activeWorkbenchProjectContext = null,
            locale = PromptLocale.ZH_CN
        )

        assertTrue(prompt.contains("Home 大输入框是 Project 创建"))
        assertTrue(prompt.contains("workbench_project_create"))
        assertTrue(prompt.contains("workbench_project_activate"))
        assertTrue(prompt.contains("workbench_project_active_get"))
        assertTrue(prompt.contains("workbench_project_delete"))
        assertTrue(prompt.contains("workbench_api_list"))
        assertTrue(prompt.contains("workbench_api_call"))
        assertTrue(prompt.contains("frontendContext"))
        assertTrue(prompt.contains("drawingPaths"))
    }
}
