package cn.com.omnimind.bot.agent

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BuiltinSkillManifestConsistencyTest {
    private val gson = Gson()
    private val retiredProjectSkillIds = setOf(
        "oob-native-" + "workbench",
        "oob-project-" + "designer",
        "oob-project-" + "distiller"
    )

    @Test
    fun builtinSkillManifestMatchesSkillFrontmatterAndDirectories() {
        val root = builtinSkillsRoot()
        val manifestFile = root.resolve("manifest.json")
        assertTrue("missing builtin skill manifest", manifestFile.exists())

        @Suppress("UNCHECKED_CAST")
        val manifest = gson.fromJson(manifestFile.readText(), Map::class.java) as Map<String, Any?>
        val skills = manifest["skills"] as List<*>
        val ids = mutableSetOf<String>()

        skills.forEach { raw ->
            val skill = raw as Map<*, *>
            val id = skill["id"].toString()
            assertTrue("duplicate builtin skill id: $id", ids.add(id))
            assertFalse("retired Project skill id must not be packaged: $id", id in retiredProjectSkillIds)

            val dir = root.resolve(id)
            val skillFile = dir.resolve("SKILL.md")
            assertEquals("builtin_skills/$id", skill["assetPath"])
            assertTrue("missing builtin skill dir for $id", dir.isDirectory)
            assertTrue("missing SKILL.md for $id", skillFile.isFile)

            val frontmatter = parseFrontmatter(skillFile)
            assertEquals(id, frontmatter["name"])
            assertEquals(id, skill["name"])
            assertEquals(frontmatter["description"], skill["description"])

            assertEquals(dir.resolve("scripts").isDirectory, skill["hasScripts"])
            assertEquals(dir.resolve("references").isDirectory, skill["hasReferences"])
            assertEquals(dir.resolve("assets").isDirectory, skill["hasAssets"])
            assertEquals(dir.resolve("evals").isDirectory, skill["hasEvals"])
        }
    }

    @Test
    fun vlmAndroidGuiSkillOwnsAndroidWorldFirstStepAndValidationGuidance() {
        val skillBody = builtinSkillsRoot()
            .resolve("vlm-android-gui/SKILL.md")
            .readText()

        assertTrue(skillBody.contains("First-Step AndroidWorld Rules"))
        assertTrue(skillBody.contains("Do not encode"))
        assertTrue(skillBody.contains("packageName"))
        assertTrue(skillBody.contains("permission, onboarding, or account prompt"))
        assertTrue(skillBody.contains("editable field is already focused"))
        assertTrue(skillBody.contains("sliders, seekbars, and system panels"))
        assertTrue(skillBody.contains("90-95%"))
        assertTrue(skillBody.contains("0-1000 normalized"))
        assertTrue(skillBody.contains("x2=990"))
        assertTrue(skillBody.contains("x1=990,y1=110,x2=10,y2=110"))
        assertTrue(skillBody.contains("x1` must be greater than `x2`"))
        assertTrue(skillBody.contains("Display brightness"))
        assertTrue(skillBody.contains("not `click`"))
        assertTrue(skillBody.contains("on-screen numeric keypads"))
        assertTrue(skillBody.contains("Validation prompts"))
        assertTrue(skillBody.contains("at least two UI states"))

        val injectedStepGuidance = ResolvedSkillContext(
            skillId = "vlm-android-gui",
            frontmatter = mapOf("name" to "vlm-android-gui"),
            bodyMarkdown = skillBody,
            triggerReason = "test"
        ).stepGuidance()
        assertFalse("Injected VLM step guidance should not be truncated", injectedStepGuidance.endsWith("..."))
        assertTrue(injectedStepGuidance.contains("AndroidWorld first-step policy"))
        assertTrue(injectedStepGuidance.contains("OOB indexed page evidence"))
        assertTrue(injectedStepGuidance.contains("Pass `packageName` when known"))
        assertTrue(injectedStepGuidance.contains("Focused editable input"))
        assertTrue(injectedStepGuidance.contains("Slider/seekbar"))
        assertTrue(injectedStepGuidance.contains("0-1000 normalized"))
        assertTrue(injectedStepGuidance.contains("x2=990"))
        assertTrue(injectedStepGuidance.contains("x1=990,y1=110,x2=10,y2=110"))
        assertTrue(injectedStepGuidance.contains("Display brightness"))
        assertTrue(injectedStepGuidance.contains("do not click"))
        assertTrue(injectedStepGuidance.contains("Numeric keypad targets"))
        assertTrue(injectedStepGuidance.contains("Validate after at least two visible UI states"))
        assertTrue(injectedStepGuidance.contains("simplest action"))
    }

    @Test
    fun projectCreationRequestsResolveOnlyToCanonicalOobProjectSkill() {
        val root = builtinSkillsRoot()
        @Suppress("UNCHECKED_CAST")
        val manifest = gson.fromJson(root.resolve("manifest.json").readText(), Map::class.java)
            as Map<String, Any?>
        val entries = (manifest["skills"] as List<*>).map { raw ->
            val skill = raw as Map<*, *>
            SkillIndexEntry(
                id = skill["id"].toString(),
                name = skill["name"].toString(),
                description = skill["description"].toString(),
                rootPath = root.resolve(skill["id"].toString()).absolutePath,
                shellRootPath = "/workspace/.omnibot/skills/${skill["id"]}",
                skillFilePath = root.resolve("${skill["id"]}/SKILL.md").absolutePath,
                shellSkillFilePath = "/workspace/.omnibot/skills/${skill["id"]}/SKILL.md",
                hasScripts = skill["hasScripts"] == true,
                hasReferences = skill["hasReferences"] == true,
                hasAssets = skill["hasAssets"] == true,
                hasEvals = skill["hasEvals"] == true
            )
        }

        val matches = SkillTriggerMatcher.resolveMatches(
            userMessage = "帮我做一个支出记录 Project，并从现有记账产品里蒸馏设计",
            entries = entries
        ).map { it.entry.id }

        assertTrue(matches.contains("oob-project"))
        retiredProjectSkillIds.forEach { retiredId ->
            assertFalse("retired Project skill id matched: $retiredId", matches.contains(retiredId))
        }
    }

    @Test
    fun retiredProjectSkillInstallationsAreRemovedDuringBuiltinSeedMigration() {
        val skillsRoot = Files.createTempDirectory("retired-project-skills").toFile()
        val retiredId = "oob-project-" + "distiller"
        val activeId = "oob-project"
        val retiredDir = skillsRoot.resolve(retiredId).apply {
            mkdirs()
            resolve("SKILL.md").writeText(
                """
                ---
                name: $retiredId
                description: retired
                ---
                """.trimIndent()
            )
        }
        val registry = linkedMapOf(
            retiredId to "retired",
            activeId to "active"
        )

        val changed = removeRetiredBuiltinSkillInstallations(skillsRoot, registry)

        assertTrue(changed)
        assertFalse(retiredDir.exists())
        assertFalse(registry.containsKey(retiredId))
        assertTrue(registry.containsKey(activeId))
    }

    private fun builtinSkillsRoot(): File {
        val candidates = listOf(
            File("src/main/assets/builtin_skills"),
            File("app/src/main/assets/builtin_skills")
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Cannot locate builtin skills root from ${File(".").absolutePath}")
    }

    private fun parseFrontmatter(skillFile: File): Map<String, String> {
        val lines = skillFile.readLines()
        assertTrue("${skillFile.path} must start with YAML frontmatter", lines.firstOrNull() == "---")
        val end = lines.drop(1).indexOf("---")
        assertTrue("${skillFile.path} must close YAML frontmatter", end >= 0)
        return lines.drop(1).take(end).mapNotNull { line ->
            val idx = line.indexOf(':')
            if (idx <= 0) return@mapNotNull null
            line.substring(0, idx).trim() to line.substring(idx + 1).trim()
        }.toMap()
    }
}
