package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.i18n.PromptLocale
import cn.com.omnimind.bot.omniflow.OobFunctionToolNames
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertFalse
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
            workbenchDisplayLayoutContext = null,
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
            workbenchDisplayLayoutContext = null,
            locale = PromptLocale.EN_US
        )

        assertTrue(prompt.contains("You are an AI Agent operating inside an Alpine workspace environment"))
        assertTrue(prompt.contains("File and artifact rules"))
        assertTrue(prompt.contains("Skills:"))
        assertTrue(prompt.contains("action=shell.exec"))
        assertTrue(prompt.contains("android_privileged_session_*"))
    }

    @Test
    fun buildUsesLoadedSkillForFunctionManagementProfile() {
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
            installedSkills = listOf(
                SkillIndexEntry(
                    id = "oob-project",
                    name = "oob-project",
                    description = "Workbench project rules.",
                    rootPath = "/android/.omnibot/skills/oob-project",
                    shellRootPath = "/workspace/.omnibot/skills/oob-project",
                    skillFilePath = "/android/.omnibot/skills/oob-project/SKILL.md",
                    shellSkillFilePath = "/workspace/.omnibot/skills/oob-project/SKILL.md",
                    hasScripts = true,
                    hasReferences = true,
                    hasAssets = true,
                    hasEvals = false
                )
            ),
            skillsRootShellPath = "/workspace/.omnibot/skills",
            skillsRootAndroidPath = "/data/user/0/cn.com.omnimind.bot/workspace/.omnibot/skills",
            resolvedSkills = listOf(oobFunctionManagementSkillFixture()),
            memoryContext = WorkspaceMemoryPromptContext(
                soul = "long soul",
                longTermMemory = "long memory",
                todayShortMemory = "short memory"
            ),
            activeWorkbenchProjectContext = "projectId: project-heavy",
            workbenchDisplayLayoutContext = "Workbench viewport: 412x700dp",
            locale = PromptLocale.ZH_CN,
            toolExposurePolicy = AgentToolExposurePolicy(
                profile = AgentToolExposurePolicy.PROFILE_FUNCTION_MANAGEMENT,
            )
        )

        assertTrue(prompt.contains("oob-function-management"))
        assertTrue(prompt.contains("Register The Previous RunLog"))
        assertTrue(prompt.contains(OobFunctionToolNames.RUN_LOG_CONVERT))
        assertTrue(prompt.contains("function_management tool profile"))
        assertTrue(prompt.contains("Workspace 记忆上下文"))
        assertTrue(prompt.contains("已安装 skills"))
        assertTrue(prompt.contains("OOB Workbench"))
        assertTrue(prompt.contains("terminal_execute"))
        assertTrue(prompt.contains("workbench_project_hot_update"))
        assertFalse(prompt.contains("复用指令管理执行器"))
    }

    @Test
    fun buildInjectsOobFunctionCandidateContext() {
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
            workbenchDisplayLayoutContext = null,
            oobFunctionCandidateContext =
                "当前可复用的 OmniFlow Functions：\n- 高置信匹配时用 `${OobFunctionToolNames.FUNCTION_RUN}`\n- `order_food` 点外卖",
            locale = PromptLocale.ZH_CN
        )

        assertTrue(prompt.contains("当前可复用的 OmniFlow Functions"))
        assertTrue(prompt.contains("order_food"))
        assertTrue(prompt.contains(OobFunctionToolNames.FUNCTION_RUN))
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
            workbenchDisplayLayoutContext = null,
            locale = PromptLocale.ZH_CN
        )

        assertTrue(prompt.contains("当前激活的 OOB Workbench Project"))
        assertTrue(prompt.contains("oob-workbench-todo-log"))
        assertTrue(prompt.contains("todo.add"))
    }

    @Test
    fun buildExplainsInstalledSkillsInIndex() {
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
            installedSkills = listOf(
                SkillIndexEntry(
                    id = "oob-prompt-runtime",
                    name = "oob-prompt-runtime",
                    description = "Use for 系统提示词 and prompt dump debugging.",
                    rootPath = "/android/.omnibot/skills/oob-prompt-runtime",
                    shellRootPath = "/workspace/.omnibot/skills/oob-prompt-runtime",
                    skillFilePath = "/android/.omnibot/skills/oob-prompt-runtime/SKILL.md",
                    shellSkillFilePath = "/workspace/.omnibot/skills/oob-prompt-runtime/SKILL.md",
                    hasScripts = false,
                    hasReferences = true,
                    hasAssets = false,
                    hasEvals = false
                )
            ),
            skillsRootShellPath = "/workspace/.omnibot/skills",
            skillsRootAndroidPath = "/android/.omnibot/skills",
            resolvedSkills = emptyList(),
            memoryContext = null,
            activeWorkbenchProjectContext = null,
            workbenchDisplayLayoutContext = null,
            locale = PromptLocale.ZH_CN
        )

        assertTrue(prompt.contains("已安装 skills"))
        assertTrue(prompt.contains("讲解: Use for 系统提示词 and prompt dump debugging."))
        assertTrue(prompt.contains("样例: 系统提示词怎么拆分"))
        assertTrue(prompt.contains("把 Project 上下文注入成 project_context"))
        assertTrue(prompt.contains("能力目录: references"))
        assertTrue(prompt.contains("本轮状态: 未自动加载；相关时调用 skills_read"))
        assertTrue(prompt.contains("何时读正文: 准备执行该类任务、需要 references"))
        assertTrue(prompt.contains("读取正文: skills_read(skillId=\"oob-prompt-runtime\")"))
    }

    @Test
    fun buildOmitsWorkbenchProjectRulesByDefault() {
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
            workbenchDisplayLayoutContext = null,
            locale = PromptLocale.ZH_CN
        )

        assertFalse(prompt.contains("OOB Workbench"))
        assertFalse(prompt.contains("workbench_api_call"))
        assertFalse(prompt.contains("workbench_project_hot_update"))
        assertFalse(prompt.contains("GitHub/OSS"))
        assertFalse(prompt.contains("frontendContext.selectedElement.oobId"))
    }

    @Test
    fun buildIncludesWorkbenchRulesWhenProjectIsActive() {
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
            workbenchDisplayLayoutContext = "Workbench viewport: 412x700dp",
            locale = PromptLocale.ZH_CN
        )

        assertTrue(prompt.contains("当前激活的 OOB Workbench Project"))
        assertTrue(prompt.contains("workbench_api_call"))
        assertTrue(prompt.contains("workbench_project_hot_update"))
        assertTrue(prompt.contains("htmlPatches"))
        assertTrue(prompt.contains("frontendContext.selectedElement.oobId"))
        assertTrue(prompt.contains("data-oob-id"))
        assertTrue(prompt.contains("file_read(lineStart/lineCount)"))
    }

    private fun oobFunctionManagementSkillFixture(): ResolvedSkillContext =
        ResolvedSkillContext(
            skillId = "oob-function-management",
            frontmatter = mapOf("name" to "oob-function-management"),
            bodyMarkdown = """
                # OOB Function Management

                ## Workflows

                ### Register The Previous RunLog

                1. Call `oob_run_log_list` and select the newest successful RunLog.
                2. Call `oob_run_log_convert` with `register=true`.
                3. Report the real `function_id`.
            """.trimIndent(),
            triggerReason = "function_management tool profile"
        )
}
