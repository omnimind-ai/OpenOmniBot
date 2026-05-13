package cn.com.omnimind.bot.workbench

import cn.com.omnimind.bot.workbench.executor.WorkbenchExecutor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WorkbenchProjectStoreTest {
    private fun store(): WorkbenchProjectStore {
        return WorkbenchProjectStore(Files.createTempDirectory("workbench-store-test").toFile())
    }

    @Test
    fun createProjectWritesGenericProjectRuntimeFiles() {
        val root = Files.createTempDirectory("workbench-store-test").toFile()
        val store = WorkbenchProjectStore(root)
        val project = store.createProject(
            mapOf(
                "projectId" to "oob-workbench-research-brief",
                "name" to "Research Brief",
                "entityName" to "Finding",
                "description" to "Track research findings.",
                "initialItems" to listOf(
                    mapOf("title" to "High-priority risk", "severity" to "high")
                ),
                "apis" to listOf(
                    mapOf(
                        "toolId" to "finding.create",
                        "displayName" to "Add finding",
                        "inputSchema" to mapOf("title" to "string"),
                        "outputSchema" to mapOf("item" to "object"),
                        "run" to mapOf("use" to "native.collection.create")
                    ),
                    mapOf(
                        "toolId" to "finding.archive",
                        "displayName" to "Archive finding",
                        "inputSchema" to mapOf("item_id" to "string"),
                        "outputSchema" to mapOf("item" to "object"),
                        "run" to mapOf("use" to "native.collection.archive")
                    )
                ),
                "htmlFiles" to listOf(
                    mapOf(
                        "path" to "index.html",
                        "content" to "<!doctype html><button data-oob-id=\"add\">Add</button>"
                    )
                )
            )
        )

        assertEquals("oob-workbench-research-brief", project["projectId"])
        assertEquals("/workbench/html?projectId=oob-workbench-research-brief", project["route"])
        assertEquals(listOf("finding.create", "finding.archive"), project["apiIds"])
        assertTrue(root.resolve("projects/registry.json").exists())
        assertTrue(root.resolve("projects/api_registry.json").exists())
        assertTrue(root.resolve("projects/oob-workbench-research-brief/project.json").exists())
        assertTrue(root.resolve("projects/oob-workbench-research-brief/data/items.json").exists())
        assertTrue(root.resolve("projects/oob-workbench-research-brief/frontend/page_spec.json").exists())
        assertTrue(root.resolve("projects/oob-workbench-research-brief/frontend/html/index.html").exists())
        assertTrue(root.resolve("projects/oob-workbench-research-brief/frontend/html/manifest.json").exists())
        assertTrue(project.containsKey("frontendHtml"))
        val pageSpec = root
            .resolve("projects/oob-workbench-research-brief/frontend/page_spec.json")
            .readText()
        assertTrue(pageSpec.contains("\"renderer\""))
        assertTrue(pageSpec.contains("\"html_webview\""))
        val listed = store.listProjects().first { it["projectId"] == "oob-workbench-research-brief" }
        assertEquals("/workbench/html?projectId=oob-workbench-research-brief", listed["route"])
        val displays = listed["displays"] as List<*>
        val display = displays.first() as Map<*, *>
        assertEquals(WORKBENCH_HTML_RENDERER, display["renderer"])
    }

    @Test
    fun projectApisMutateGenericItems() {
        val store = store()
        store.createProject(
            mapOf(
                "projectId" to "oob-workbench-notes",
                "entityName" to "Note"
            )
        )

        val createResult = store.callApi(
            projectId = "oob-workbench-notes",
            apiId = "note.create",
            inputs = mapOf("title" to "Reusable display idea", "priority" to "high"),
            caller = "test"
        )
        assertTrue(createResult["success"] == true)
        val createOutputs = createResult["outputs"] as Map<*, *>
        val created = createOutputs["item"] as Map<*, *>
        assertEquals("Reusable display idea", created["title"])

        val archiveResult = store.callApi(
            projectId = "oob-workbench-notes",
            apiId = "note.archive",
            inputs = mapOf("item_id" to created["id"]),
            caller = "test"
        )
        assertTrue(archiveResult["success"] == true)
        val archiveOutputs = archiveResult["outputs"] as Map<*, *>
        val archived = archiveOutputs["item"] as Map<*, *>
        assertEquals("archived", archived["status"])
    }

    @Test
    fun projectApiExecutionCountsUseIncrementalSidecar() {
        val root = Files.createTempDirectory("workbench-store-test").toFile()
        val store = WorkbenchProjectStore(root)
        store.createProject(
            mapOf(
                "projectId" to "oob-workbench-counts",
                "entityName" to "Note"
            )
        )

        val first = store.callApi(
            projectId = "oob-workbench-counts",
            apiId = "note.create",
            inputs = mapOf("title" to "First note"),
            caller = "test"
        )
        assertEquals(1, executionCount(first, "note.create"))

        val second = store.callApi(
            projectId = "oob-workbench-counts",
            apiId = "note.create",
            inputs = mapOf("title" to "Second note"),
            caller = "test"
        )
        assertEquals(2, executionCount(second, "note.create"))
        assertTrue(
            root.resolve("projects/oob-workbench-counts/logs/api_call_counts.json").exists()
        )
    }

    @Test
    fun longExecutorDoesNotHoldStoreMonitor() {
        val root = Files.createTempDirectory("workbench-store-test").toFile()
        val store = WorkbenchProjectStore(root)
        store.createProject(
            mapOf(
                "projectId" to "oob-workbench-slow",
                "apis" to listOf(
                    mapOf(
                        "toolId" to "slow.run",
                        "displayName" to "Slow run",
                        "inputSchema" to emptyMap<String, Any?>(),
                        "outputSchema" to mapOf("done" to "boolean"),
                        "executorKind" to "slow_test_executor"
                    )
                )
            )
        )

        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        store.executorRegistry.register("slow_test_executor", object : WorkbenchExecutor {
            override suspend fun execute(
                projectId: String,
                api: WorkbenchApiRecord,
                inputs: Map<String, Any?>
            ): Map<String, Any?> {
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                return linkedMapOf(
                    "success" to true,
                    "apiId" to api.apiId,
                    "toolId" to api.toolId,
                    "outputs" to mapOf("done" to true)
                )
            }
        })

        var workerError: Throwable? = null
        val worker = Thread {
            runCatching {
                store.callApi(
                    projectId = "oob-workbench-slow",
                    apiId = "slow.run",
                    inputs = emptyMap(),
                    caller = "test"
                )
            }.onFailure { workerError = it }
        }.apply { start() }

        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val startedAt = System.nanoTime()
        val projects = store.listProjects()
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        assertTrue(projects.isNotEmpty())
        assertTrue("listProjects blocked for ${elapsedMs}ms", elapsedMs < 500)

        release.countDown()
        worker.join(5_000)
        assertTrue("worker did not finish", !worker.isAlive)
        workerError?.let { throw AssertionError(it) }
    }

    @Test
    fun projectApisAcceptStandardJsonSchemaContracts() {
        val store = store()
        store.createProject(
            mapOf(
                "projectId" to "oob-workbench-hotpot",
                "apis" to listOf(
                    mapOf(
                        "toolId" to "status.create",
                        "displayName" to "Add status",
                        "inputSchema" to mapOf(
                            "type" to "object",
                            "properties" to mapOf(
                                "weight" to mapOf("type" to "number"),
                                "mood" to mapOf("type" to "string")
                            ),
                            "required" to listOf("weight")
                        ),
                        "outputSchema" to mapOf(
                            "type" to "object",
                            "properties" to mapOf("item" to mapOf("type" to "object"))
                        ),
                        "run" to mapOf("use" to "native.collection.create")
                    )
                )
            )
        )

        val createResult = store.callApi(
            projectId = "oob-workbench-hotpot",
            apiId = "status.create",
            inputs = mapOf("weight" to 80.5, "mood" to "ok"),
            caller = "test"
        )
        assertTrue(createResult["success"] == true)

        val invalidResult = store.callApi(
            projectId = "oob-workbench-hotpot",
            apiId = "status.create",
            inputs = mapOf("mood" to "missing weight"),
            caller = "test"
        )
        assertEquals(false, invalidResult["success"])
        assertEquals("Missing required input field(s): weight", invalidResult["errorMessage"])
    }

    @Test
    fun updateProjectWritesHtmlFilesAndMakesHtmlDisplayDefault() {
        val store = store()
        store.createProject(
            mapOf(
                "projectId" to "oob-workbench-dashboard",
                "entityName" to "Metric"
            )
        )

        val update = store.updateProject(
            mapOf(
                "projectId" to "oob-workbench-dashboard",
                "htmlFiles" to listOf(
                    mapOf("path" to "index.html", "content" to "<!doctype html><h1>Dashboard</h1>"),
                    mapOf("path" to "styles/app.css", "content" to "body{font-family:sans-serif}")
                ),
                "prompt" to "Make this a dashboard."
            ),
            caller = "test"
        )

        val project = update["project"] as Map<*, *>
        assertEquals("/workbench/html?projectId=oob-workbench-dashboard", project["route"])
        val frontendHtml = project["frontendHtml"] as Map<*, *>
        assertEquals("index.html", frontendHtml["entryFile"])
        val displays = project["displays"] as List<*>
        val display = displays.first() as Map<*, *>
        assertEquals(WORKBENCH_HTML_RENDERER, display["renderer"])
    }

    private fun executionCount(result: Map<String, Any?>, toolId: String): Int {
        val project = result["project"] as Map<*, *>
        val tools = project["tools"] as List<*>
        val tool = tools
            .filterIsInstance<Map<*, *>>()
            .first { item ->
                item["toolId"] == toolId || item["apiId"] == toolId || item["id"] == toolId
            }
        return (tool["executionCount"] as Number).toInt()
    }
}
