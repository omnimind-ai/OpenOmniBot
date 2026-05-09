package cn.com.omnimind.bot.workbench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.zip.ZipFile

class WorkbenchProjectStoreTest {
    private fun store(): WorkbenchProjectStore {
        return WorkbenchProjectStore(Files.createTempDirectory("workbench-store-test").toFile())
    }

    @Test
    fun createProjectWritesProjectAndApiRegistries() {
        val root = Files.createTempDirectory("workbench-store-test").toFile()
        val store = WorkbenchProjectStore(root)
        val project = store.createProject(
            mapOf(
                "templateId" to WORKBENCH_TODO_TEMPLATE_ID,
                "projectId" to WORKBENCH_DEFAULT_PROJECT_ID
            )
        )

        assertEquals(WORKBENCH_DEFAULT_PROJECT_ID, project["projectId"])
        assertEquals(
            listOf(WORKBENCH_TODO_ADD_TOOL_ID, WORKBENCH_TODO_FINISH_TOOL_ID),
            project["apiIds"]
        )
        assertEquals(1, store.listProjects().size)
        assertEquals(
            listOf(WORKBENCH_TODO_ADD_TOOL_ID, WORKBENCH_TODO_FINISH_TOOL_ID),
            store.listApis(WORKBENCH_DEFAULT_PROJECT_ID).map { it["apiId"] }
        )
        assertTrue(root.resolve("projects/registry.json").exists())
        assertTrue(root.resolve("projects/api_registry.json").exists())
        assertTrue(root.resolve("projects/$WORKBENCH_DEFAULT_PROJECT_ID/project.json").exists())
        assertTrue(root.resolve("projects/$WORKBENCH_DEFAULT_PROJECT_ID/data/todos.json").exists())
        assertTrue(root.resolve("projects/$WORKBENCH_DEFAULT_PROJECT_ID/README.md").exists())
        assertTrue(root.resolve("projects/$WORKBENCH_DEFAULT_PROJECT_ID/frontend/page_spec.json").exists())
        assertTrue(root.resolve("projects/$WORKBENCH_DEFAULT_PROJECT_ID/backend/api_spec.json").exists())
    }

    @Test
    fun createProjectIsIdempotentAndPreservesTodos() {
        val store = store()
        val original = store.createProject(
            mapOf(
                "projectId" to WORKBENCH_DEFAULT_PROJECT_ID,
                "name" to "Original Project"
            )
        )
        val addResult = store.callApi(
            projectId = WORKBENCH_DEFAULT_PROJECT_ID,
            apiId = WORKBENCH_TODO_ADD_TOOL_ID,
            inputs = mapOf("title" to "Persist me"),
            caller = "ai"
        )
        assertTrue(addResult["success"] == true)

        store.createProject(
            mapOf(
                "projectId" to WORKBENCH_DEFAULT_PROJECT_ID,
                "name" to "Should Not Override"
            )
        )
        val project = store.getProject(WORKBENCH_DEFAULT_PROJECT_ID)
        val todos = project["todos"] as List<*>

        assertEquals("Original Project", project["name"])
        assertEquals(original["updatedAt"], project["updatedAt"])
        assertEquals(1, todos.size)
    }

    @Test
    fun createProjectStoresPromptDecompositionInSourceSpecs() {
        val root = Files.createTempDirectory("workbench-store-test").toFile()
        val store = WorkbenchProjectStore(root)
        val prompt = "Create a todolist that can add todos and archive todos"

        store.createProject(
            mapOf(
                "projectId" to WORKBENCH_DEFAULT_PROJECT_ID,
                "prompt" to prompt
            )
        )

        val projectJson = root
            .resolve("projects/$WORKBENCH_DEFAULT_PROJECT_ID/project.json")
            .readText()
        val frontendSpec = root
            .resolve("projects/$WORKBENCH_DEFAULT_PROJECT_ID/frontend/page_spec.json")
            .readText()
        val backendSpec = root
            .resolve("projects/$WORKBENCH_DEFAULT_PROJECT_ID/backend/api_spec.json")
            .readText()

        assertTrue(projectJson.contains(prompt))
        assertTrue(projectJson.contains("oob-native-workbench"))
        assertTrue(frontendSpec.contains("Controls -> Project API calls"))
        assertTrue(backendSpec.contains("\"archiveTodo\""))
        assertTrue(backendSpec.contains(WORKBENCH_TODO_FINISH_TOOL_ID))
    }

    @Test
    fun createProjectDoesNotOverwriteExistingPromptMetadata() {
        val root = Files.createTempDirectory("workbench-store-test").toFile()
        val store = WorkbenchProjectStore(root)
        val originalPrompt = "Create the first todo project"

        store.createProject(
            mapOf(
                "projectId" to WORKBENCH_DEFAULT_PROJECT_ID,
                "prompt" to originalPrompt
            )
        )
        store.createProject(
            mapOf(
                "projectId" to WORKBENCH_DEFAULT_PROJECT_ID,
                "prompt" to "Rewrite existing project prompt"
            )
        )

        val projectJson = root
            .resolve("projects/$WORKBENCH_DEFAULT_PROJECT_ID/project.json")
            .readText()

        assertTrue(projectJson.contains(originalPrompt))
        assertFalse(projectJson.contains("Rewrite existing project prompt"))
    }

    @Test
    fun createProjectRepairsMissingProjectApisWithoutChangingProjectRecord() {
        val root = Files.createTempDirectory("workbench-store-test").toFile()
        val store = WorkbenchProjectStore(root)
        store.createProject(
            mapOf(
                "projectId" to WORKBENCH_DEFAULT_PROJECT_ID,
                "name" to "Do Not Rewrite"
            )
        )
        root.resolve("projects/api_registry.json").writeText("[]")

        val project = store.createProject(
            mapOf(
                "projectId" to WORKBENCH_DEFAULT_PROJECT_ID,
                "name" to "Ignored Rewrite"
            )
        )

        assertEquals("Do Not Rewrite", project["name"])
        assertEquals(
            listOf(WORKBENCH_TODO_ADD_TOOL_ID, WORKBENCH_TODO_FINISH_TOOL_ID),
            store.listApis(WORKBENCH_DEFAULT_PROJECT_ID).map { it["apiId"] }
        )
    }

    @Test
    fun apiCallAddsAndFinishesTodos() {
        val root = Files.createTempDirectory("workbench-store-test").toFile()
        val store = WorkbenchProjectStore(root)
        store.createProject(mapOf("projectId" to WORKBENCH_DEFAULT_PROJECT_ID))

        val addResult = store.callApi(
            projectId = WORKBENCH_DEFAULT_PROJECT_ID,
            apiId = WORKBENCH_TODO_ADD_TOOL_ID,
            inputs = mapOf("title" to "Use native API"),
            caller = "ai"
        )
        assertTrue(addResult["success"] == true)
        val todo = ((addResult["outputs"] as Map<*, *>)["todo"] as Map<*, *>)

        val finishResult = store.callApi(
            projectId = WORKBENCH_DEFAULT_PROJECT_ID,
            apiId = WORKBENCH_TODO_FINISH_TOOL_ID,
            inputs = mapOf("todo_id" to todo["id"]),
            caller = "ui"
        )
        assertTrue(finishResult["success"] == true)

        val project = store.getProject(WORKBENCH_DEFAULT_PROJECT_ID)
        val todos = project["todos"] as List<*>
        val finished = todos.first() as Map<*, *>
        assertEquals("finished", finished["status"])
        val logFile = root.resolve("projects/$WORKBENCH_DEFAULT_PROJECT_ID/logs/api_calls.jsonl")
        assertTrue(logFile.exists())
        assertEquals(2, logFile.readLines().size)
        val apis = store.listApis(WORKBENCH_DEFAULT_PROJECT_ID)
        assertEquals(1, apis.first { it["apiId"] == WORKBENCH_TODO_ADD_TOOL_ID }["executionCount"])
        assertEquals(1, apis.first { it["apiId"] == WORKBENCH_TODO_FINISH_TOOL_ID }["executionCount"])
    }

    @Test
    fun apiCallReturnsExplicitErrors() {
        val store = store()
        store.createProject(mapOf("projectId" to WORKBENCH_DEFAULT_PROJECT_ID))

        val emptyAdd = store.callApi(
            projectId = WORKBENCH_DEFAULT_PROJECT_ID,
            apiId = WORKBENCH_TODO_ADD_TOOL_ID,
            inputs = mapOf("title" to " "),
            caller = "ai"
        )
        val missingFinish = store.callApi(
            projectId = WORKBENCH_DEFAULT_PROJECT_ID,
            apiId = WORKBENCH_TODO_FINISH_TOOL_ID,
            inputs = mapOf("todo_id" to "todo-missing"),
            caller = "ui"
        )

        assertFalse(emptyAdd["success"] == true)
        assertEquals("EMPTY_TODO_TITLE", emptyAdd["errorCode"])
        assertFalse(missingFinish["success"] == true)
        assertEquals("TODO_NOT_FOUND", missingFinish["errorCode"])
    }

    @Test
    fun apiListExcludesControlApis() {
        val store = store()
        store.createProject(mapOf("projectId" to WORKBENCH_DEFAULT_PROJECT_ID))

        val apiIds = store.listApis(WORKBENCH_DEFAULT_PROJECT_ID).map { it["apiId"] }

        assertEquals(
            listOf(WORKBENCH_TODO_ADD_TOOL_ID, WORKBENCH_TODO_FINISH_TOOL_ID),
            apiIds
        )
        assertFalse(apiIds.contains("workbench_project_create"))
        assertFalse(apiIds.contains("workbench_project_delete"))
        assertFalse(apiIds.contains("workbench_project_hot_update"))
        assertFalse(apiIds.contains("workbench_project_ingest_android"))
    }

    @Test
    fun activeProjectPersistsToolboxManifestAndClearsOnDelete() {
        val root = Files.createTempDirectory("workbench-store-test").toFile()
        val store = WorkbenchProjectStore(root)
        store.createProject(mapOf("projectId" to WORKBENCH_DEFAULT_PROJECT_ID))

        val activated = store.activateProject(WORKBENCH_DEFAULT_PROJECT_ID)
        val active = store.getActiveProject()
        val promptContext = store.activeProjectPromptContext().orEmpty()

        assertTrue(activated["success"] == true)
        assertTrue(root.resolve("projects/active_project.json").exists())
        assertEquals(
            WORKBENCH_DEFAULT_PROJECT_ID,
            (active["project"] as Map<*, *>)["projectId"]
        )
        assertTrue(promptContext.contains(WORKBENCH_DEFAULT_PROJECT_ID))
        assertTrue(promptContext.contains(WORKBENCH_TODO_ADD_TOOL_ID))
        assertTrue(promptContext.contains("workbench_api_call"))

        store.deleteProject(WORKBENCH_DEFAULT_PROJECT_ID)

        assertFalse(root.resolve("projects/active_project.json").exists())
        assertEquals(null, store.getActiveProject()["project"])
    }

    @Test
    fun ingestAndroidAssetWritesManifestLogAndKeepsApiRegistryClean() {
        val root = Files.createTempDirectory("workbench-store-test").toFile()
        val store = WorkbenchProjectStore(root)
        store.createProject(mapOf("projectId" to WORKBENCH_DEFAULT_PROJECT_ID))
        val sourceApk = root.resolve("source-demo.apk")
        sourceApk.writeBytes(byteArrayOf(1, 2, 3, 4))

        val result = store.ingestAndroidAsset(
            projectId = WORKBENCH_DEFAULT_PROJECT_ID,
            sourcePath = sourceApk.absolutePath,
            caller = "ai"
        )

        assertTrue(result["success"] == true)
        val asset = result["asset"] as Map<*, *>
        assertEquals(WORKBENCH_ANDROID_APK_KIND, asset["sourceKind"])
        assertEquals("source-demo.apk", asset["displayName"])
        assertTrue(asset["entryPath"].toString().endsWith("/source.apk"))
        assertTrue(root.resolve("projects/$WORKBENCH_DEFAULT_PROJECT_ID/android/manifest.json").exists())
        assertTrue(root.resolve("projects/$WORKBENCH_DEFAULT_PROJECT_ID/logs/android_ingest.jsonl").exists())
        assertTrue(root.resolve("projects/$WORKBENCH_DEFAULT_PROJECT_ID/project.json").readText().contains("androidAssetCount"))
        val project = store.getProject(WORKBENCH_DEFAULT_PROJECT_ID)
        val androidAssets = project["androidAssets"] as List<*>
        assertEquals(1, androidAssets.size)
        val apiIds = store.listApis(WORKBENCH_DEFAULT_PROJECT_ID).map { it["apiId"] }
        assertFalse(apiIds.contains("workbench_project_ingest_android"))
    }

    @Test
    fun hotUpdateWritesAuditLogAndRefreshesProjectState() {
        val root = Files.createTempDirectory("workbench-store-test").toFile()
        val store = WorkbenchProjectStore(root)
        store.createProject(mapOf("projectId" to WORKBENCH_DEFAULT_PROJECT_ID))

        val addUpdate = store.hotUpdateProject(
            projectId = WORKBENCH_DEFAULT_PROJECT_ID,
            prompt = "增加 todo：热更新后的任务",
            caller = "ui"
        )
        val finishUpdate = store.hotUpdateProject(
            projectId = WORKBENCH_DEFAULT_PROJECT_ID,
            prompt = "归档一个 todo",
            caller = "ai"
        )

        assertTrue(addUpdate["success"] == true)
        assertTrue(finishUpdate["success"] == true)
        val project = finishUpdate["project"] as Map<*, *>
        val todos = project["todos"] as List<*>
        val todo = todos.first() as Map<*, *>
        assertEquals("热更新后的任务", todo["title"])
        assertEquals("finished", todo["status"])
        val hotUpdateLog =
            root.resolve("projects/$WORKBENCH_DEFAULT_PROJECT_ID/logs/hot_updates.jsonl")
        val apiLog =
            root.resolve("projects/$WORKBENCH_DEFAULT_PROJECT_ID/logs/api_calls.jsonl")
        assertEquals(2, hotUpdateLog.readLines().size)
        assertTrue(hotUpdateLog.readText().contains("热更新后的任务"))
        assertEquals(2, apiLog.readLines().size)
        val apis = store.listApis(WORKBENCH_DEFAULT_PROJECT_ID)
        assertEquals(1, apis.first { it["apiId"] == WORKBENCH_TODO_ADD_TOOL_ID }["executionCount"])
        assertEquals(1, apis.first { it["apiId"] == WORKBENCH_TODO_FINISH_TOOL_ID }["executionCount"])
    }

    @Test
    fun deleteProjectRemovesRegistriesApisAndFiles() {
        val root = Files.createTempDirectory("workbench-store-test").toFile()
        val store = WorkbenchProjectStore(root)
        store.createProject(mapOf("projectId" to WORKBENCH_DEFAULT_PROJECT_ID))
        store.callApi(
            projectId = WORKBENCH_DEFAULT_PROJECT_ID,
            apiId = WORKBENCH_TODO_ADD_TOOL_ID,
            inputs = mapOf("title" to "Delete me"),
            caller = "ai"
        )

        val result = store.deleteProject(WORKBENCH_DEFAULT_PROJECT_ID)

        assertTrue(result["success"] == true)
        assertEquals(WORKBENCH_DEFAULT_PROJECT_ID, result["projectId"])
        assertEquals(0, result["remainingProjectCount"])
        assertTrue(store.listProjects().isEmpty())
        assertTrue(store.listApis(WORKBENCH_DEFAULT_PROJECT_ID).isEmpty())
        assertFalse(root.resolve("projects/$WORKBENCH_DEFAULT_PROJECT_ID").exists())
    }

    @Test
    fun exportProjectBuildsDistributionPackage() {
        val root = Files.createTempDirectory("workbench-store-test").toFile()
        val store = WorkbenchProjectStore(root)
        store.createProject(mapOf("projectId" to WORKBENCH_DEFAULT_PROJECT_ID))
        store.callApi(
            projectId = WORKBENCH_DEFAULT_PROJECT_ID,
            apiId = WORKBENCH_TODO_ADD_TOOL_ID,
            inputs = mapOf("title" to "Export me"),
            caller = "ai"
        )
        store.hotUpdateProject(
            projectId = WORKBENCH_DEFAULT_PROJECT_ID,
            prompt = "增加 todo：Export hot update",
            caller = "ui"
        )
        val sourceProject = root.resolve("android-source")
        sourceProject.mkdirs()
        sourceProject.resolve("settings.gradle").writeText("pluginManagement {}")
        sourceProject.resolve("app/src/main/AndroidManifest.xml").apply {
            parentFile?.mkdirs()
            writeText("<manifest />")
        }
        sourceProject.resolve("app/build/ignored.txt").apply {
            parentFile?.mkdirs()
            writeText("ignored")
        }
        store.ingestAndroidAsset(
            projectId = WORKBENCH_DEFAULT_PROJECT_ID,
            sourcePath = sourceProject.absolutePath,
            sourceKind = WORKBENCH_ANDROID_PROJECT_KIND,
            caller = "ui"
        )

        val export = store.exportProject(WORKBENCH_DEFAULT_PROJECT_ID)
        val packageName = export["packageName"]!!.toString()
        val zipFile = root.resolve("projects/exports/$packageName")

        assertTrue(export["success"] == true)
        assertEquals(zipFile.absolutePath, export["exportPath"])
        assertEquals("/workspace/projects/exports/$packageName", export["exportShellPath"])
        assertTrue(zipFile.exists())
        ZipFile(zipFile).use { zip ->
            val entries = mutableSetOf<String>()
            val iterator = zip.entries()
            while (iterator.hasMoreElements()) {
                entries.add(iterator.nextElement().name)
            }
            assertTrue(entries.contains("manifest.json"))
            assertTrue(entries.contains("registry/project_record.json"))
            assertTrue(entries.contains("registry/api_records.json"))
            assertTrue(entries.contains("project/README.md"))
            assertTrue(entries.contains("project/frontend/page_spec.json"))
            assertTrue(entries.contains("project/backend/api_spec.json"))
            assertTrue(entries.contains("project/project.json"))
            assertTrue(entries.contains("project/data/todos.json"))
            assertTrue(entries.contains("project/android/manifest.json"))
            assertTrue(entries.contains("project/android/README.md"))
            assertTrue(entries.contains("project/logs/api_calls.jsonl"))
            assertTrue(entries.contains("project/logs/hot_updates.jsonl"))
            assertTrue(entries.contains("project/logs/android_ingest.jsonl"))
            assertTrue(entries.any { it.endsWith("/source/settings.gradle") })
            assertFalse(entries.any { it.endsWith("/source/app/build/ignored.txt") })
        }
    }
}
