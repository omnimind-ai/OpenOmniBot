package cn.com.omnimind.bot.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SkillRuntimeBehaviorTest {
    private fun entry(
        id: String,
        description: String,
        enabled: Boolean = true,
        installed: Boolean = true
    ): SkillIndexEntry {
        return SkillIndexEntry(
            id = id,
            name = id,
            description = description,
            rootPath = "/tmp/$id",
            shellRootPath = "/workspace/.omnibot/skills/$id",
            skillFilePath = "/tmp/$id/SKILL.md",
            shellSkillFilePath = "/workspace/.omnibot/skills/$id/SKILL.md",
            hasScripts = false,
            hasReferences = false,
            hasAssets = false,
            hasEvals = false,
            enabled = enabled,
            installed = installed
        )
    }

    @Test
    fun resolveMatchesSkipsDisabledAndUninstalledSkills() {
        val matches = SkillTriggerMatcher.resolveMatches(
            userMessage = "请用 skill-creator 帮我创建一个新的技能",
            entries = listOf(
                entry(
                    id = "skill-creator",
                    description = "用于创建和更新技能",
                    enabled = false
                ),
                entry(
                    id = "skill-creator",
                    description = "用于创建和更新技能",
                    installed = false
                )
            )
        )

        assertTrue(matches.isEmpty())
    }

    @Test
    fun resolveMatchesFindSkillsFromChineseTriggerPhrase() {
        val matches = SkillTriggerMatcher.resolveMatches(
            userMessage = "帮我找个 skill 来处理 changelog",
            entries = listOf(
                entry(
                    id = "find-install-skills",
                    description = "Find and install relevant Omnibot skills. Use when the user asks \"找个 skill\", \"有没有这个功能的 skill\", \"find a skill for X\", \"is there a skill for X\", or wants to extend the agent with an installable workflow."
                )
            )
        )

        assertTrue(matches.any { it.entry.id == "find-install-skills" })
    }

    @Test
    fun resolveMatchesProjectCreationRequestsToOobProjectSkill() {
        val matches = SkillTriggerMatcher.resolveMatches(
            userMessage = "帮我做一个健身打卡 tracker",
            entries = listOf(
                entry(
                    id = "oob-project",
                    description = "OOB Workbench Project 完整生命周期：新建、更新、审查。Use when the user says \"帮我做一个\", \"我想创建一个\", \"build me a\", \"make a [X] tracker/tool/app\", \"改一下界面\", \"加个字段\", \"新增功能\", \"修复\", or any intent to create or modify a persistent personal tool. Covers: domain research, proposal, entity blueprint, API design, Bridge Injection, HTML generation, hot update, review, PROJECT_SOUL, document maintenance."
                )
            )
        )

        assertTrue(matches.any { it.entry.id == "oob-project" })
    }

    @Test
    fun resolveMatchesProjectDistillationRequestsToOobProjectSkill() {
        val matches = SkillTriggerMatcher.resolveMatches(
            userMessage = "把这个 GitHub 项目蒸馏成我们的 OOB Project",
            entries = listOf(
                entry(
                    id = "oob-project",
                    description = "OOB Workbench Project 完整生命周期总入口：新建 Project、更新、蒸馏、审查、HTML Display、Project Tool/API 设计、热更新、导出、PROJECT_SOUL/PROJECT_CONTEXT 维护。Use when the user asks to create, distill, modify, review, export, or maintain a persistent personal tool."
                )
            )
        )

        assertTrue(matches.any { it.entry.id == "oob-project" })
    }

    @Test
    fun buildOmitsDisabledAndUninstalledSkillsFromPromptIndex() {
        val prompt = AgentSystemPrompt.build(
            workspace = AgentWorkspaceDescriptor(
                id = "conversation-1",
                rootPath = "/workspace",
                androidRootPath = "/data/user/0/cn.com.omnimind.bot/workspace",
                uriRoot = "omnibot://workspace",
                currentCwd = "/workspace",
                androidCurrentCwd = "/data/user/0/cn.com.omnimind.bot/workspace",
                shellRootPath = "/workspace",
                retentionPolicy = "shared_root"
            ),
            installedSkills = listOf(
                entry(
                    id = "active-skill",
                    description = "可正常使用的技能"
                ),
                entry(
                    id = "disabled-skill",
                    description = "已禁用的技能",
                    enabled = false
                ),
                entry(
                    id = "removed-skill",
                    description = "已删除但可恢复的技能",
                    installed = false
                )
            ),
            skillsRootShellPath = "/workspace/.omnibot/skills",
            skillsRootAndroidPath = "/data/user/0/cn.com.omnimind.bot/workspace/.omnibot/skills",
            resolvedSkills = emptyList(),
            memoryContext = null,
            activeWorkbenchProjectContext = null,
            workbenchDisplayLayoutContext = null
        )

        assertTrue(prompt.contains("active-skill (`active-skill`)"))
        assertFalse(prompt.contains("id=disabled-skill"))
        assertFalse(prompt.contains("id=removed-skill"))
    }

    @Test
    fun buildInjectsAutoMatchedSkillBodyIntoPrompt() {
        val prompt = AgentSystemPrompt.build(
            workspace = AgentWorkspaceDescriptor(
                id = "conversation-1",
                rootPath = "/workspace",
                androidRootPath = "/data/user/0/cn.com.omnimind.bot/workspace",
                uriRoot = "omnibot://workspace",
                currentCwd = "/workspace",
                androidCurrentCwd = "/data/user/0/cn.com.omnimind.bot/workspace",
                shellRootPath = "/workspace",
                retentionPolicy = "shared_root"
            ),
            installedSkills = listOf(
                entry(
                    id = "oob-project",
                    description = "Use when the user says \"帮我做一个\"."
                ).copy(hasReferences = true, hasScripts = true)
            ),
            skillsRootShellPath = "/workspace/.omnibot/skills",
            skillsRootAndroidPath = "/data/user/0/cn.com.omnimind.bot/workspace/.omnibot/skills",
            resolvedSkills = listOf(
                ResolvedSkillContext(
                    skillId = "oob-project",
                    frontmatter = mapOf("name" to "oob-project"),
                    bodyMarkdown = "### Phase 0 — 领域 + 开源调研\n至少 2 次 web_search，并至少 1 次 GitHub/OSS 查询。",
                    loadedReferences = listOf("/workspace/.omnibot/skills/oob-project/references/review-guide.md"),
                    scriptsDir = "/workspace/.omnibot/skills/oob-project/scripts",
                    triggerReason = "用户消息命中 skill 描述关键词"
                )
            ),
            memoryContext = null,
            activeWorkbenchProjectContext = null,
            workbenchDisplayLayoutContext = null
        )

        assertTrue(prompt.contains("本轮自动加载的 skill 正文"))
        assertTrue(prompt.contains("oob-project (`oob-project`)"))
        assertTrue(prompt.contains("Phase 0 — 领域 + 开源调研"))
        assertTrue(prompt.contains("GitHub/OSS"))
        assertTrue(prompt.contains("已自动加载到本轮 system prompt"))
        assertTrue(prompt.contains("skills_read_reference(skillId=\"oob-project\""))
    }

    @Test
    fun failureHookWritesErrorsLogAndFindsRelatedHints() {
        val skillsRoot = Files.createTempDirectory("self-improving-skill-test").toFile()
        val skillRoot = skillsRoot.resolve(SelfImprovingSkillFailureHook.SKILL_ID)
        val dataDir = skillRoot.resolve("data").apply { mkdirs() }
        val errorsFile = dataDir.resolve("ERRORS.md")
        errorsFile.writeText(
            """
            # Errors

            ## [ERR-20260409-OLD] terminal_execute

            **记录时间**: 2026-04-09T00:00:00Z
            **优先级**: high
            **状态**: pending
            **领域**: runtime

            ### 摘要
            旧的终端失败

            ---
            """.trimIndent() + "\n"
        )

        val payload = SelfImprovingSkillFailureHook.capture(
            skillsRoot = skillsRoot,
            skill = ResolvedSkillContext(
                skillId = SelfImprovingSkillFailureHook.SKILL_ID,
                frontmatter = mapOf("name" to SelfImprovingSkillFailureHook.SKILL_ID),
                bodyMarkdown = "先检查失败原因\n不要重复相同步骤",
                triggerReason = "test"
            ),
            userMessage = "修复终端命令报错",
            toolName = "terminal_execute",
            toolType = "terminal",
            argumentsJson = """{"command":"bad cmd"}""",
            result = ToolExecutionResult.TerminalResult(
                toolName = "terminal_execute",
                summaryText = "命令执行失败",
                previewJson = "{}",
                rawResultJson = """{"stderr":"not found"}""",
                success = false
            )
        )

        assertNotNull(payload)
        assertTrue(errorsFile.readText().contains("命令执行失败"))
        assertTrue(payload!!.guidance.contains("self-improving-agent"))
        assertTrue(payload.relatedHints.any { it.contains("ERR-20260409-OLD") })
    }
}
