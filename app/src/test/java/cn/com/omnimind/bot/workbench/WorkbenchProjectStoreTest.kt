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
        assertTrue(root.resolve("projects/$WORKBENCH_DEFAULT_PROJECT_ID/logs/project_progress.jsonl").exists())
        assertTrue(project["lastProgress"].toString().contains("project_create_completed"))
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
    fun createSchemaProjectWritesGenericDisplayApiAndStateSpecs() {
        val root = Files.createTempDirectory("workbench-store-test").toFile()
        val store = WorkbenchProjectStore(root)
        val project = store.createProject(
            mapOf(
                "templateId" to WORKBENCH_SCHEMA_TEMPLATE_ID,
                "projectId" to "customer-tracker",
                "name" to "Customer Tracker",
                "entityName" to "Customer",
                "description" to "Track customer follow-up records.",
                "initialItems" to listOf("Alice")
            )
        )

        assertEquals("customer-tracker", project["projectId"])
        assertEquals(WORKBENCH_SCHEMA_TEMPLATE_ID, project["templateId"])
        assertEquals("/workbench/schema_app?projectId=customer-tracker", project["route"])
        assertEquals(listOf("customer.create", "customer.archive"), project["apiIds"])
        assertEquals(
            listOf("customer.archive", "customer.create"),
            store.listApis("customer-tracker").map { it["apiId"].toString() }.sorted()
        )
        val displays = project["displays"] as List<*>
        val display = displays.first() as Map<*, *>
        assertEquals("oob_schema_collection", display["renderer"])
        val items = project["items"] as List<*>
        assertEquals(1, items.size)
        assertTrue(root.resolve("projects/customer-tracker/data/items.json").exists())
        assertTrue(
            root.resolve("projects/customer-tracker/frontend/page_spec.json")
                .readText()
                .contains("oob_schema_collection")
        )
        assertTrue(
            root.resolve("projects/customer-tracker/backend/api_spec.json")
                .readText()
                .contains("customer.archive")
        )
    }

    @Test
    fun schemaApiCallCreatesAndArchivesItems() {
        val root = Files.createTempDirectory("workbench-store-test").toFile()
        val store = WorkbenchProjectStore(root)
        store.createProject(
            mapOf(
                "templateId" to WORKBENCH_SCHEMA_TEMPLATE_ID,
                "projectId" to "notes",
                "entityName" to "Note"
            )
        )

        val createResult = store.callApi(
            projectId = "notes",
            apiId = "note.create",
            inputs = mapOf("title" to "Capture a reusable project idea", "priority" to "high"),
            caller = "ai"
        )
        assertTrue(createResult["success"] == true)
        val item = ((createResult["outputs"] as Map<*, *>)["item"] as Map<*, *>)

        val archiveResult = store.callApi(
            projectId = "notes",
            apiId = "note.archive",
            inputs = mapOf("item_id" to item["id"]),
            caller = "ui"
        )
        assertTrue(archiveResult["success"] == true)

        val project = store.getProject("notes")
        val items = project["items"] as List<*>
        val archived = items.first() as Map<*, *>
        assertEquals("archived", archived["status"])
        val apis = store.listApis("notes")
        assertEquals(1, apis.first { it["apiId"] == "note.create" }["executionCount"])
        assertEquals(1, apis.first { it["apiId"] == "note.archive" }["executionCount"])
    }

    @Test
    fun quickCaptureProjectRunsWorkspacePythonScriptApis() {
        val root = Files.createTempDirectory("workbench-store-test").toFile()
        val store = WorkbenchProjectStore(root)
        val project = store.createProject(
            mapOf(
                "templateId" to WORKBENCH_QUICK_CAPTURE_TEMPLATE_ID,
                "projectId" to WORKBENCH_QUICK_CAPTURE_PROJECT_ID,
                "name" to "随手记 Inbox",
                "prompt" to "帮我创建一个随手记的 Project，可以收文本、链接、摘要和归档"
            )
        )

        val ingestTodo = store.callApi(
            projectId = WORKBENCH_QUICK_CAPTURE_PROJECT_ID,
            apiId = WORKBENCH_CAPTURE_INGEST_TOOL_ID,
            inputs = mapOf("text" to "记一下：明天问张三报价"),
            caller = "ai"
        )
        val ingestLink = store.callApi(
            projectId = WORKBENCH_QUICK_CAPTURE_PROJECT_ID,
            apiId = WORKBENCH_CAPTURE_INGEST_TOOL_ID,
            inputs = mapOf(
                "text" to "https://xhslink.com/demo 装修案例",
                "url" to "https://xhslink.com/demo",
                "sourceApp" to "小红书"
            ),
            caller = "ui"
        )
        val linkItem = (ingestLink["outputs"] as Map<*, *>)["item"] as Map<*, *>
        val promoted = store.callApi(
            projectId = WORKBENCH_QUICK_CAPTURE_PROJECT_ID,
            apiId = WORKBENCH_CAPTURE_PROMOTE_TOOL_ID,
            inputs = mapOf("item_id" to linkItem["id"], "todo_title" to "整理装修案例"),
            caller = "ai"
        )
        val archived = store.callApi(
            projectId = WORKBENCH_QUICK_CAPTURE_PROJECT_ID,
            apiId = WORKBENCH_CAPTURE_ARCHIVE_TOOL_ID,
            inputs = mapOf("item_id" to linkItem["id"]),
            caller = "ui"
        )

        assertEquals(WORKBENCH_QUICK_CAPTURE_PROJECT_ID, project["projectId"])
        assertEquals(WORKBENCH_QUICK_CAPTURE_TEMPLATE_ID, project["templateId"])
        assertEquals(
            "/workbench/quick_capture?projectId=$WORKBENCH_QUICK_CAPTURE_PROJECT_ID",
            project["route"]
        )
        assertEquals(
            listOf(
                WORKBENCH_CAPTURE_ARCHIVE_TOOL_ID,
                WORKBENCH_CAPTURE_INGEST_TOOL_ID,
                WORKBENCH_CAPTURE_PROMOTE_TOOL_ID,
                WORKBENCH_CAPTURE_SUMMARIZE_TOOL_ID
            ),
            store.listApis(WORKBENCH_QUICK_CAPTURE_PROJECT_ID).map { it["apiId"].toString() }.sorted()
        )
        assertTrue(ingestTodo["success"] == true)
        assertTrue(ingestLink["success"] == true)
        assertTrue(promoted["success"] == true)
        assertTrue(archived["success"] == true)
        val refreshed = store.getProject(WORKBENCH_QUICK_CAPTURE_PROJECT_ID)
        val captureItems = refreshed["captureItems"] as List<*>
        assertEquals(2, captureItems.size)
        assertEquals(1, captureItems.count { (it as Map<*, *>)["status"] == "archived" })
        assertTrue(
            root.resolve("projects/$WORKBENCH_QUICK_CAPTURE_PROJECT_ID/data/items.json")
                .readText()
                .contains("整理装修案例")
        )
        assertTrue(
            root.resolve("projects/$WORKBENCH_QUICK_CAPTURE_PROJECT_ID/logs/link_fetch.jsonl")
                .readText()
                .contains("xiaohongshu")
        )
        assertEquals(
            4,
            root.resolve("projects/$WORKBENCH_QUICK_CAPTURE_PROJECT_ID/logs/script_runs.jsonl")
                .readLines()
                .size
        )
        assertTrue(
            root.resolve("projects/$WORKBENCH_QUICK_CAPTURE_PROJECT_ID/backend/scripts/capture_ingest.py")
                .exists()
        )
        assertTrue(
            root.resolve("projects/$WORKBENCH_QUICK_CAPTURE_PROJECT_ID/backend/oob_sdk.py")
                .exists()
        )
        assertTrue(
            root.resolve("projects/$WORKBENCH_QUICK_CAPTURE_PROJECT_ID/backend/oob_sdk/runner.py")
                .exists()
        )
        val apis = store.listApis(WORKBENCH_QUICK_CAPTURE_PROJECT_ID)
        assertEquals(2, apis.first { it["apiId"] == WORKBENCH_CAPTURE_INGEST_TOOL_ID }["executionCount"])
        assertEquals(1, apis.first { it["apiId"] == WORKBENCH_CAPTURE_ARCHIVE_TOOL_ID }["executionCount"])
    }

    @Test
    fun activeProjectBuildsMcpToolboxDynamicTools() {
        val root = Files.createTempDirectory("workbench-store-test").toFile()
        val store = WorkbenchProjectStore(root)
        val projectId = "oob-workbench-v01-quick-note"
        store.createProject(
            mapOf(
                "templateId" to WORKBENCH_QUICK_CAPTURE_TEMPLATE_ID,
                "projectId" to projectId,
                "name" to "随手记 Toolbox"
            )
        )

        store.activateProject(projectId)
        val toolbox = store.activeToolbox()!!
        val mcpTools = store.activeMcpTools()
        val toolNames = mcpTools.map { it["name"].toString() }.sorted()
        val projectJson = root.resolve("projects/$projectId/project.json").readText()
        val backendSpec = root.resolve("projects/$projectId/backend/api_spec.json").readText()

        assertEquals("quick_note", toolbox["toolboxId"])
        assertTrue(toolNames.contains("quick_note.capture_ingest"))
        assertTrue(toolNames.contains("quick_note.capture_archive"))
        assertFalse(toolNames.contains("workbench_project_create"))
        assertTrue(projectJson.contains("\"toolbox\""))
        assertTrue(projectJson.contains("quick_note.capture_ingest"))
        assertTrue(backendSpec.contains("\"toolName\""))
        assertTrue(backendSpec.contains("\"apiVersion\""))
        assertTrue(backendSpec.contains("\"toolbox\""))
    }

    @Test
    fun mcpDynamicToolCallDispatchesProjectApiAndLogsCaller() {
        val root = Files.createTempDirectory("workbench-store-test").toFile()
        val store = WorkbenchProjectStore(root)
        val projectId = "oob-workbench-v01-quick-note"
        store.createProject(
            mapOf(
                "templateId" to WORKBENCH_QUICK_CAPTURE_TEMPLATE_ID,
                "projectId" to projectId
            )
        )
        store.activateProject(projectId)

        val result = store.callMcpTool(
            toolName = "quick_note.capture_ingest",
            inputs = mapOf("text" to "记一下：明天 3 点开会"),
            caller = "mcp_toolbox"
        )

        assertTrue(result["success"] == true)
        assertEquals(WORKBENCH_CAPTURE_INGEST_TOOL_ID, result["apiId"])
        val apiLog = root.resolve("projects/$projectId/logs/api_calls.jsonl").readText()
        assertTrue(apiLog.contains("\"caller\":\"mcp_toolbox\""))
        assertTrue(apiLog.contains("\"toolName\":\"quick_note.capture_ingest\""))
        assertTrue(apiLog.contains("\"durationMs\""))
    }

    @Test
    fun apiCallSchemaValidationWritesStructuredErrorAndLastError() {
        val root = Files.createTempDirectory("workbench-store-test").toFile()
        val store = WorkbenchProjectStore(root)
        val projectId = "schema-validation"
        store.createProject(
            mapOf(
                "templateId" to WORKBENCH_SCHEMA_TEMPLATE_ID,
                "projectId" to projectId,
                "entityName" to "Note"
            )
        )

        val result = store.callApi(
            projectId = projectId,
            apiId = "note.create",
            inputs = emptyMap(),
            caller = "mcp_toolbox"
        )

        assertFalse(result["success"] == true)
        assertEquals("INVALID_INPUT", result["errorCode"])
        val apiLog = root.resolve("projects/$projectId/logs/api_calls.jsonl").readText()
        assertTrue(apiLog.contains("\"success\":false"))
        assertTrue(apiLog.contains("\"errorCode\":\"INVALID_INPUT\""))
        assertTrue(apiLog.contains("Missing required input field"))
        val project = store.getProject(projectId)
        val lastError = project["lastError"] as Map<*, *>
        assertEquals("INVALID_INPUT", lastError["errorCode"])
    }

    @Test
    fun mcpResourcesExposeProjectToolboxProgressAndLogs() {
        val root = Files.createTempDirectory("workbench-store-test").toFile()
        val store = WorkbenchProjectStore(root)
        val projectId = "oob-workbench-v01-quick-note"
        store.createProject(
            mapOf(
                "templateId" to WORKBENCH_QUICK_CAPTURE_TEMPLATE_ID,
                "projectId" to projectId
            )
        )
        store.activateProject(projectId)
        store.callMcpTool(
            toolName = "quick_note.capture_ingest",
            inputs = mapOf("text" to "MCP resource log item"),
            caller = "mcp_toolbox"
        )

        val resources = store.listMcpResources().map { it["uri"].toString() }
        val toolbox = store.readMcpResource("oob://projects/$projectId/toolbox")
        val logs = store.readMcpResource("oob://projects/$projectId/logs/api_calls")
        val progress = store.readMcpResource("oob://projects/$projectId/progress")

        assertTrue(resources.contains("oob://projects/$projectId/toolbox"))
        assertTrue(toolbox.toString().contains("quick_note.capture_ingest"))
        assertTrue(logs.toString().contains("mcp_toolbox"))
        assertTrue(progress.toString().contains("project_create_completed"))
    }

    @Test
    fun promptGeneratedSchemaProjectRunsE2eThroughOobWorkbench() {
        val root = Files.createTempDirectory("workbench-store-test").toFile()
        val store = WorkbenchProjectStore(root)
        val projectId = "oob-workbench-customer-tracker"

        val created = store.createProject(
            mapOf(
                "templateId" to WORKBENCH_SCHEMA_TEMPLATE_ID,
                "projectId" to projectId,
                "name" to "客户跟进工作台",
                "entityName" to "Customer",
                "description" to "记录客户跟进、下一步动作和归档状态",
                "prompt" to "我想创建一个客户跟进系统，可以新增客户并归档客户"
            )
        )
        val activated = store.activateProject(projectId)
        val apis = store.listApis(projectId)
        val first = store.callApi(
            projectId = projectId,
            apiId = "customer.create",
            inputs = mapOf("title" to "张三：下周二回访", "source" to "ai"),
            caller = "ai"
        )
        val firstItem = (first["outputs"] as Map<*, *>)["item"] as Map<*, *>
        store.callApi(
            projectId = projectId,
            apiId = "customer.create",
            inputs = mapOf("title" to "李四：发送报价单", "source" to "ui"),
            caller = "ui"
        )
        val archived = store.callApi(
            projectId = projectId,
            apiId = "customer.archive",
            inputs = mapOf("item_id" to firstItem["id"]),
            caller = "ai"
        )
        val openedRoute = store.routeForProject(projectId)
        val exported = store.exportProject(projectId)
        val zipFile = root.resolve("projects/exports/${exported["packageName"]}")

        assertEquals(projectId, created["projectId"])
        assertEquals(WORKBENCH_SCHEMA_TEMPLATE_ID, created["templateId"])
        assertEquals("/workbench/schema_app?projectId=$projectId", openedRoute)
        assertTrue(activated["success"] == true)
        assertEquals(projectId, ((activated["project"] as Map<*, *>)["projectId"]))
        assertEquals(listOf("customer.archive", "customer.create"), apis.map { it["apiId"].toString() }.sorted())
        assertTrue(archived["success"] == true)
        val project = store.getProject(projectId)
        val items = project["items"] as List<*>
        assertEquals(2, items.size)
        assertEquals(1, items.count { (it as Map<*, *>)["status"] == "archived" })
        assertTrue(store.activeProjectPromptContext().orEmpty().contains("customer.create"))
        assertEquals(3, root.resolve("projects/$projectId/logs/api_calls.jsonl").readLines().size)
        assertTrue(root.resolve("projects/registry.json").readText().contains(projectId))
        assertTrue(root.resolve("projects/api_registry.json").readText().contains("customer.archive"))
        assertTrue(root.resolve("projects/$projectId/frontend/page_spec.json").readText().contains("oob_schema_collection"))
        assertTrue(root.resolve("projects/$projectId/backend/api_spec.json").readText().contains("customer.create"))
        ZipFile(zipFile).use { zip ->
            val entries = zip.entries().asSequence().map { it.name }.toSet()
            assertTrue(entries.contains("manifest.json"))
            assertTrue(entries.contains("project/frontend/page_spec.json"))
            assertTrue(entries.contains("project/backend/api_spec.json"))
            assertTrue(entries.contains("project/data/items.json"))
            assertTrue(entries.contains("project/logs/api_calls.jsonl"))
            val manifest = zip.getInputStream(zip.getEntry("manifest.json"))
                .bufferedReader()
                .use { it.readText() }
            assertTrue(manifest.contains("skills/oob-native-workbench/SKILL.md"))
        }
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
        assertFalse(apiIds.contains("workbench_project_ingest_oss"))
        assertFalse(apiIds.contains("workbench_project_progress_get"))
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
    fun ingestOssSourceCopiesLocalRepoAnalyzesProgressAndKeepsApiRegistryClean() {
        val root = Files.createTempDirectory("workbench-store-test").toFile()
        val store = WorkbenchProjectStore(root)
        store.createProject(mapOf("projectId" to WORKBENCH_DEFAULT_PROJECT_ID))
        val repo = root.resolve("demo-repo")
        repo.mkdirs()
        repo.resolve("package.json").writeText(
            """
            {"scripts":{"dev":"vite --host 0.0.0.0","test":"vitest"},"dependencies":{"react":"latest","vite":"latest"}}
            """.trimIndent()
        )
        repo.resolve("src/main.tsx").apply {
            parentFile?.mkdirs()
            writeText("export const app = true")
        }
        repo.resolve("node_modules/ignored.js").apply {
            parentFile?.mkdirs()
            writeText("ignored")
        }

        val result = store.ingestOssSource(
            projectId = WORKBENCH_DEFAULT_PROJECT_ID,
            sourcePath = repo.absolutePath,
            sourceUrl = "https://github.com/example/demo-repo",
            caller = "ai"
        )

        assertTrue(result["success"] == true)
        val source = result["source"] as Map<*, *>
        assertEquals(false, source["requiresFetch"])
        assertTrue((source["detectedStack"] as List<*>).contains("react"))
        assertTrue((source["entrypoints"] as List<*>).toString().contains("npm run dev"))
        assertTrue(root.resolve("projects/$WORKBENCH_DEFAULT_PROJECT_ID/source/manifest.json").exists())
        assertTrue(root.resolve("projects/$WORKBENCH_DEFAULT_PROJECT_ID/logs/oss_ingest.jsonl").exists())
        assertTrue(root.resolve("projects/$WORKBENCH_DEFAULT_PROJECT_ID/project.json").readText().contains("sourceAssetCount"))
        val copiedRoot = root.resolve("projects/$WORKBENCH_DEFAULT_PROJECT_ID/source/repos")
        assertTrue(copiedRoot.walkTopDown().any { it.name == "package.json" })
        assertFalse(copiedRoot.walkTopDown().any { it.name == "ignored.js" })
        val progress = store.getProjectProgress(WORKBENCH_DEFAULT_PROJECT_ID)
        assertTrue(progress["count"].toString().toDouble() >= 1.0)
        assertTrue(progress["events"].toString().contains("oss_ingest_completed"))
        val apiIds = store.listApis(WORKBENCH_DEFAULT_PROJECT_ID).map { it["apiId"] }
        assertFalse(apiIds.contains("workbench_project_ingest_oss"))
        assertFalse(apiIds.contains("workbench_project_progress_get"))
    }

    @Test
    fun ingestOssUrlOnlyRegistersFetchRequiredMetadata() {
        val root = Files.createTempDirectory("workbench-store-test").toFile()
        val store = WorkbenchProjectStore(root)
        store.createProject(mapOf("projectId" to WORKBENCH_DEFAULT_PROJECT_ID))

        val result = store.ingestOssSource(
            projectId = WORKBENCH_DEFAULT_PROJECT_ID,
            sourceUrl = "https://github.com/example/not-downloaded",
            ref = "main",
            caller = "ai"
        )

        assertTrue(result["success"] == true)
        val source = result["source"] as Map<*, *>
        assertEquals(true, source["requiresFetch"])
        assertEquals(WORKBENCH_OSS_GITHUB_KIND, source["sourceKind"])
        assertEquals("main", source["ref"])
        assertTrue(source["fetchHint"].toString().contains("sourcePath"))
        val progress = store.getProjectProgress(WORKBENCH_DEFAULT_PROJECT_ID, limit = 20)
        assertTrue(progress["events"].toString().contains("oss_fetch_required"))
        assertTrue(progress["events"].toString().contains("waiting"))
    }

    @Test
    fun hotUpdateWritesAuditLogAndRefreshesProjectState() {
        val root = Files.createTempDirectory("workbench-store-test").toFile()
        val store = WorkbenchProjectStore(root)
        store.createProject(mapOf("projectId" to WORKBENCH_DEFAULT_PROJECT_ID))

        val addUpdate = store.hotUpdateProject(
            projectId = WORKBENCH_DEFAULT_PROJECT_ID,
            prompt = "增加 todo：热更新后的任务",
            caller = "ui",
            frontendContext = mapOf(
                "displayId" to "todo-log-display",
                "route" to "/workbench/todo_log"
            )
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
        assertTrue(hotUpdateLog.readText().contains("todo-log-display"))
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
