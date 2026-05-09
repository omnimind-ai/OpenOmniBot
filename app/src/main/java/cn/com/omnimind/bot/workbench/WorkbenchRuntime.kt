package cn.com.omnimind.bot.workbench

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

const val WORKBENCH_TODO_TEMPLATE_ID = "todo_log_demo"
const val WORKBENCH_SCHEMA_TEMPLATE_ID = "schema_app"
const val WORKBENCH_QUICK_CAPTURE_TEMPLATE_ID = "quick_capture_inbox"
const val WORKBENCH_DEFAULT_PROJECT_ID = "oob-workbench-todo-log"
const val WORKBENCH_QUICK_CAPTURE_PROJECT_ID = "oob-workbench-quick-capture"
const val WORKBENCH_TODO_ADD_TOOL_ID = "todo.add"
const val WORKBENCH_TODO_FINISH_TOOL_ID = "todo.finish"
const val WORKBENCH_CAPTURE_INGEST_TOOL_ID = "capture.ingest"
const val WORKBENCH_CAPTURE_ARCHIVE_TOOL_ID = "capture.archive"
const val WORKBENCH_CAPTURE_PROMOTE_TOOL_ID = "capture.promote_to_todo"
const val WORKBENCH_CAPTURE_SUMMARIZE_TOOL_ID = "capture.summarize"
const val WORKBENCH_HOT_UPDATE_CALLER = "xiaowan_hot_update"
const val WORKBENCH_ANDROID_APK_KIND = "apk"
const val WORKBENCH_ANDROID_PROJECT_KIND = "android_project"
const val WORKBENCH_OSS_REPOSITORY_KIND = "oss_repo"
const val WORKBENCH_OSS_GITHUB_KIND = "github_repo"
const val WORKBENCH_OSS_LOCAL_KIND = "local_source"
const val WORKBENCH_SCHEMA_EXECUTOR_KIND = "native_schema_collection"
const val WORKBENCH_WORKSPACE_PYTHON_EXECUTOR_KIND = "workspace_python_script"

data class WorkbenchProjectRecord(
    val projectId: String,
    val name: String,
    val templateId: String,
    val route: String,
    val spacePath: String,
    val apiIds: List<String>,
    val createdAt: String,
    val updatedAt: String
) {
    fun toPayload(): Map<String, Any?> = linkedMapOf(
        "projectId" to projectId,
        "name" to name,
        "templateId" to templateId,
        "route" to route,
        "spacePath" to spacePath,
        "apiIds" to apiIds,
        "createdAt" to createdAt,
        "updatedAt" to updatedAt
    )
}

data class WorkbenchApiRecord(
    val apiId: String,
    val projectId: String,
    val toolId: String,
    val displayName: String,
    val description: String,
    val inputSchema: Map<String, Any?>,
    val outputSchema: Map<String, Any?>,
    val executorKind: String
) {
    fun toPayload(executionCount: Int = 0): Map<String, Any?> = linkedMapOf(
        "apiId" to apiId,
        "projectId" to projectId,
        "toolId" to toolId,
        "displayName" to displayName,
        "description" to description,
        "inputSchema" to inputSchema,
        "outputSchema" to outputSchema,
        "executorKind" to executorKind,
        "kind" to executorKind,
        "inputKeys" to inputSchema.keys.toList(),
        "outputKeys" to outputSchema.keys.toList(),
        "executionCount" to executionCount
    )
}

data class WorkbenchTodoRecord(
    val id: String,
    val title: String,
    val status: String,
    val createdAt: String,
    val finishedAt: String? = null
) {
    fun toPayload(): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "title" to title,
        "status" to status,
        "createdAt" to createdAt,
        "finishedAt" to finishedAt
    )
}

data class WorkbenchSchemaItemRecord(
    val id: String,
    val title: String,
    val status: String,
    val fields: Map<String, Any?> = emptyMap(),
    val createdAt: String,
    val archivedAt: String? = null
) {
    fun toPayload(): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "title" to title,
        "status" to status,
        "fields" to fields,
        "createdAt" to createdAt,
        "archivedAt" to archivedAt
    )
}

data class WorkbenchQuickCaptureRecord(
    val id: String,
    val type: String,
    val title: String,
    val summary: String,
    val status: String,
    val url: String? = null,
    val sourceApp: String? = null,
    val rawText: String? = null,
    val shareText: String? = null,
    val screenshotPath: String? = null,
    val dueHint: String? = null,
    val priority: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val archivedAt: String? = null
) {
    fun toPayload(): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "type" to type,
        "title" to title,
        "summary" to summary,
        "status" to status,
        "url" to url,
        "sourceApp" to sourceApp,
        "rawText" to rawText,
        "shareText" to shareText,
        "screenshotPath" to screenshotPath,
        "dueHint" to dueHint,
        "priority" to priority,
        "createdAt" to createdAt,
        "updatedAt" to updatedAt,
        "archivedAt" to archivedAt
    )
}

data class WorkbenchAndroidAsset(
    val assetId: String,
    val projectId: String,
    val sourceKind: String,
    val displayName: String,
    val originalPath: String,
    val projectPath: String,
    val shellPath: String,
    val entryPath: String,
    val packageName: String? = null,
    val versionName: String? = null,
    val versionCode: Long? = null,
    val sizeBytes: Long = 0,
    val fileCount: Int = 0,
    val importedAt: String
) {
    fun toPayload(): Map<String, Any?> = linkedMapOf(
        "assetId" to assetId,
        "projectId" to projectId,
        "sourceKind" to sourceKind,
        "displayName" to displayName,
        "originalPath" to originalPath,
        "projectPath" to projectPath,
        "shellPath" to shellPath,
        "entryPath" to entryPath,
        "packageName" to packageName,
        "versionName" to versionName,
        "versionCode" to versionCode,
        "sizeBytes" to sizeBytes,
        "fileCount" to fileCount,
        "importedAt" to importedAt
    )
}

data class WorkbenchOssSourceAsset(
    val sourceId: String,
    val projectId: String,
    val sourceKind: String,
    val displayName: String,
    val sourceUrl: String? = null,
    val ref: String? = null,
    val originalPath: String? = null,
    val projectPath: String,
    val shellPath: String,
    val entryPath: String,
    val requiresFetch: Boolean = false,
    val fetchHint: String? = null,
    val detectedStack: List<String> = emptyList(),
    val packageFiles: List<Map<String, Any?>> = emptyList(),
    val entrypoints: List<Map<String, Any?>> = emptyList(),
    val sizeBytes: Long = 0,
    val fileCount: Int = 0,
    val importedAt: String
) {
    fun toPayload(): Map<String, Any?> = linkedMapOf(
        "sourceId" to sourceId,
        "projectId" to projectId,
        "sourceKind" to sourceKind,
        "displayName" to displayName,
        "sourceUrl" to sourceUrl,
        "ref" to ref,
        "originalPath" to originalPath,
        "projectPath" to projectPath,
        "shellPath" to shellPath,
        "entryPath" to entryPath,
        "requiresFetch" to requiresFetch,
        "fetchHint" to fetchHint,
        "detectedStack" to detectedStack,
        "packageFiles" to packageFiles,
        "entrypoints" to entrypoints,
        "sizeBytes" to sizeBytes,
        "fileCount" to fileCount,
        "importedAt" to importedAt
    )
}

/**
 * Stores OOB Workbench projects and their registered Project APIs under the shared workspace.
 *
 * @param workspaceRoot Android-side workspace root that is bind-mounted as `/workspace` in Alpine.
 */
class WorkbenchProjectStore(
    private val workspaceRoot: File,
    private val appContext: Context? = null
) {
    constructor(context: Context) : this(
        AgentWorkspaceManager.rootDirectory(context),
        context.applicationContext
    )

    private val gson: Gson = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()
    private val logGson: Gson = Gson()
    private val projectRecordListType =
        object : TypeToken<List<WorkbenchProjectRecord>>() {}.type
    private val apiRecordListType = object : TypeToken<List<WorkbenchApiRecord>>() {}.type
    private val todoRecordListType = object : TypeToken<List<WorkbenchTodoRecord>>() {}.type
    private val schemaItemRecordListType =
        object : TypeToken<List<WorkbenchSchemaItemRecord>>() {}.type
    private val quickCaptureRecordListType =
        object : TypeToken<List<WorkbenchQuickCaptureRecord>>() {}.type
    private val androidAssetListType =
        object : TypeToken<List<WorkbenchAndroidAsset>>() {}.type
    private val ossSourceAssetListType =
        object : TypeToken<List<WorkbenchOssSourceAsset>>() {}.type
    private val mapType = object : TypeToken<Map<String, Any?>>() {}.type

    private val projectsRoot: File
        get() = File(workspaceRoot, "projects")
    private val registryFile: File
        get() = File(projectsRoot, "registry.json")
    private val apiRegistryFile: File
        get() = File(projectsRoot, "api_registry.json")
    private val activeProjectFile: File
        get() = File(projectsRoot, "active_project.json")
    private val exportsRoot: File
        get() = File(projectsRoot, "exports")

    /**
     * Creates a Workbench project from a template config, or returns an existing project unchanged.
     *
     * @param config Project creation config from AI tools or Flutter. `todo_log_demo` keeps the
     * original demo path, while `schema_app` creates a generic OOB-native Project from API and
     * Display specs.
     * @return Full project payload including registered business APIs and persisted state.
     */
    @Synchronized
    fun createProject(config: Map<String, Any?>): Map<String, Any?> {
        val templateId = config["templateId"]?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: WORKBENCH_TODO_TEMPLATE_ID
        require(
            templateId == WORKBENCH_TODO_TEMPLATE_ID ||
                templateId == WORKBENCH_SCHEMA_TEMPLATE_ID ||
                templateId == WORKBENCH_QUICK_CAPTURE_TEMPLATE_ID
        ) { "Unsupported workbench template: $templateId" }
        val projectId = sanitizeProjectId(
            config["projectId"]?.toString()?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: defaultProjectId(templateId, config)
        )
        val name = config["name"]?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: config["displayName"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: defaultProjectName(templateId, config)
        val sourcePrompt = config["prompt"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val now = nowIso()
        val existing = readProjectRegistry().firstOrNull { it.projectId == projectId }
        val apis = templateApis(projectId, templateId, config)
        val record = existing
            ?: WorkbenchProjectRecord(
                projectId = projectId,
                name = name,
                templateId = templateId,
                route = routeForTemplate(projectId, templateId),
                spacePath = "${AgentWorkspaceManager.SHELL_ROOT_PATH}/projects/$projectId",
                apiIds = apis.map { it.apiId },
                createdAt = now,
                updatedAt = now
            )
        val projectDir = projectDir(projectId)
        File(projectDir, "data").mkdirs()
        File(projectDir, "logs").mkdirs()
        appendProjectProgress(
            projectId = record.projectId,
            stage = "project_create_started",
            status = "running",
            message = "Preparing Workbench Project workspace.",
            percent = 5,
            caller = "workbench",
            details = linkedMapOf(
                "templateId" to templateId,
                "isExistingProject" to (existing != null)
            )
        )
        if (existing == null) {
            writeProjectRegistry(readProjectRegistry() + record)
            writeApiRegistry(readApiRegistry() + apis)
            appendProjectProgress(
                projectId = record.projectId,
                stage = "project_registered",
                status = "running",
                message = "Project registry and API registry are written.",
                percent = 35,
                caller = "workbench",
                details = linkedMapOf("apiIds" to apis.map { it.apiId })
            )
        } else {
            val apiRegistry = readApiRegistry()
            val existingApiIds = apiRegistry
                .filter { it.projectId == projectId }
                .map { it.apiId }
                .toSet()
            val missingApis = apis.filterNot { existingApiIds.contains(it.apiId) }
            if (missingApis.isNotEmpty()) {
                writeApiRegistry(apiRegistry + missingApis)
            }
            appendProjectProgress(
                projectId = record.projectId,
                stage = "project_reused",
                status = "running",
                message = "Existing Project reused and missing API records repaired if needed.",
                percent = 35,
                caller = "workbench",
                details = linkedMapOf("missingApiIds" to missingApis.map { it.apiId })
            )
        }
        if (record.templateId == WORKBENCH_TODO_TEMPLATE_ID && !todosFile(projectId).exists()) {
            writeTodos(projectId, initialTodos(config))
        }
        if (record.templateId == WORKBENCH_SCHEMA_TEMPLATE_ID && !schemaItemsFile(projectId).exists()) {
            writeSchemaItems(projectId, initialSchemaItems(config))
        }
        if (
            record.templateId == WORKBENCH_QUICK_CAPTURE_TEMPLATE_ID &&
            !quickCaptureItemsFile(projectId).exists()
        ) {
            writeQuickCaptureItems(projectId, initialQuickCaptureItems(config))
        }
        val creationPrompt = if (existing == null) sourcePrompt else null
        ensureProjectSourceFiles(record, apis, creationPrompt, config)
        appendProjectProgress(
            projectId = record.projectId,
            stage = "project_workspace_ready",
            status = "running",
            message = "Project frontend/backend source specs and persistence files are ready.",
            percent = 75,
            caller = "workbench",
            details = linkedMapOf(
                "frontend" to "frontend/page_spec.json",
                "backend" to "backend/api_spec.json"
            )
        )
        appendProjectProgress(
            projectId = record.projectId,
            stage = "project_create_completed",
            status = "completed",
            message = "Workbench Project is ready.",
            percent = 100,
            caller = "workbench",
            details = linkedMapOf("route" to record.route, "spacePath" to record.spacePath)
        )
        writeProjectJson(record, apis, creationPrompt)
        return projectPayload(record)
    }

    /**
     * Lists project records currently registered in the Workbench project registry.
     *
     * @return Project payloads with their current API list and persisted state.
     */
    @Synchronized
    fun listProjects(): List<Map<String, Any?>> {
        return readProjectRegistry().map { record -> projectPayload(record) }
    }

    /**
     * Loads one registered project.
     *
     * @param projectId Stable project id stored in `/workspace/projects/registry.json`.
     * @return Project payload backed by the project package on disk.
     */
    @Synchronized
    fun getProject(projectId: String): Map<String, Any?> {
        val record = findProject(projectId)
        return projectPayload(record)
    }

    /**
     * Marks one registered Project as the active Workbench context for the Agent.
     *
     * @param projectId Project whose APIs, displays, Workspace path, and skill should be injected
     * into the next Agent prompt as the current Project toolbox.
     * @return Active Project manifest plus the refreshed Project payload.
     */
    @Synchronized
    fun activateProject(projectId: String): Map<String, Any?> {
        val record = findProject(projectId)
        val manifest = activeProjectManifest(record, nowIso())
        projectsRoot.mkdirs()
        activeProjectFile.writeText(gson.toJson(manifest))
        return linkedMapOf(
            "success" to true,
            "activeProject" to manifest,
            "project" to projectPayload(record)
        )
    }

    /**
     * Loads the active Workbench Project context when one is still registered.
     *
     * @return A nullable active Project payload. Stale active ids are cleared and reported as
     * inactive, so callers can safely refresh the Home input chip.
     */
    @Synchronized
    fun getActiveProject(): Map<String, Any?> {
        val activeProjectId = readActiveProjectId()
        if (activeProjectId.isNullOrBlank()) {
            return linkedMapOf("success" to true, "activeProject" to null, "project" to null)
        }
        val record = readProjectRegistry().firstOrNull { it.projectId == activeProjectId }
        if (record == null) {
            activeProjectFile.delete()
            return linkedMapOf("success" to true, "activeProject" to null, "project" to null)
        }
        return linkedMapOf(
            "success" to true,
            "activeProject" to activeProjectManifest(record),
            "project" to projectPayload(record)
        )
    }

    /**
     * Clears the active Workbench Project without deleting the Project itself.
     *
     * @return Small status payload for MethodChannel and UI callers.
     */
    @Synchronized
    fun deactivateProject(): Map<String, Any?> {
        val previousProjectId = readActiveProjectId()
        activeProjectFile.delete()
        return linkedMapOf(
            "success" to true,
            "previousProjectId" to previousProjectId
        )
    }

    /**
     * Builds the compact prompt section injected into the Agent system prompt.
     *
     * @return Markdown-like text describing the active Project toolbox, or null when inactive.
     */
    @Synchronized
    fun activeProjectPromptContext(): String? {
        val activeProjectId = readActiveProjectId() ?: return null
        val record = readProjectRegistry().firstOrNull { it.projectId == activeProjectId }
            ?: run {
                activeProjectFile.delete()
                return null
            }
        val displays = workbenchDisplays(record).joinToString("\n") { display ->
            val title = display["title"]?.toString().orEmpty()
            val shortName = display["shortName"]?.toString().orEmpty()
            val route = display["route"]?.toString().orEmpty()
            "- ${display["id"]}: $title ($shortName) -> $route"
        }
        val counts = apiExecutionCounts(record.projectId)
        val apis = projectApis(record.projectId).joinToString("\n") { api ->
            "- ${api.apiId}: ${api.displayName}; inputs=${api.inputSchema.keys}; " +
                "outputs=${api.outputSchema.keys}; executionCount=${counts[api.apiId] ?: 0}"
        }
        val sources = readOssSources(record.projectId).joinToString("\n") { source ->
            "- ${source.sourceId}: ${source.displayName}; stack=${source.detectedStack}; entry=${source.entryPath}"
        }.ifBlank { "- none" }
        return """
            Active OOB Workbench Project:
            - projectId: ${record.projectId}
            - name: ${record.name}
            - workspace: ${record.spacePath}
            - skill: oob-native-workbench
            - displays:
            $displays
            - project business APIs:
            $apis
            - imported source assets:
            $sources
            Rules: treat these APIs as the active Project toolbox, not as MCP tools. To use them, call `workbench_api_call` with this projectId and the apiId. To create, export, delete, open, or hot-update the Project, use the `workbench_project_*` control tools instead of writing registry files.
        """.trimIndent()
    }

    /**
     * Deletes one registered Workbench project and removes its registered APIs and files.
     *
     * @param projectId Project id stored in the registry. The same normalization as create/get is
     * used, so callers may pass the raw id from UI or AI tool inputs.
     * @return Deletion result including the removed project id and remaining project count.
     */
    @Synchronized
    fun deleteProject(projectId: String): Map<String, Any?> {
        val record = findProject(projectId)
        val projectPath = projectDir(record.projectId)
        val remainingProjects = readProjectRegistry().filterNot { it.projectId == record.projectId }
        val remainingApis = readApiRegistry().filterNot { it.projectId == record.projectId }
        writeProjectRegistry(remainingProjects)
        writeApiRegistry(remainingApis)
        if (projectPath.exists()) {
            projectPath.deleteRecursively()
        }
        if (readActiveProjectId() == record.projectId) {
            activeProjectFile.delete()
        }
        return linkedMapOf(
            "success" to true,
            "projectId" to record.projectId,
            "projectPath" to projectPath.absolutePath,
            "spacePath" to record.spacePath,
            "remainingProjectCount" to remainingProjects.size
        )
    }

    /**
     * Lists registered business APIs. Control APIs such as project creation are intentionally excluded.
     *
     * @param projectId Optional project id filter. Null or blank returns all Project APIs.
     * @return API registry entries suitable for AI tool calls and Flutter Tool List rendering.
     */
    @Synchronized
    fun listApis(projectId: String? = null): List<Map<String, Any?>> {
        val normalizedProjectId = projectId?.trim().orEmpty()
        val counts = if (normalizedProjectId.isEmpty()) {
            emptyMap()
        } else {
            apiExecutionCounts(normalizedProjectId)
        }
        return readApiRegistry()
            .filter { normalizedProjectId.isEmpty() || it.projectId == normalizedProjectId }
            .map { it.toPayload(counts[it.apiId] ?: 0) }
    }

    /**
     * Calls a registered Project API through the native executor.
     *
     * @param projectId Project owning the API.
     * @param apiId API id or tool id, for example `todo.add`.
     * @param inputs User or AI supplied API inputs. Shape is validated by the executor.
     * @param caller Caller label such as `ai` or `ui`, persisted in the API call log.
     * @return Tool-style result payload plus the refreshed project state.
     */
    @Synchronized
    fun callApi(
        projectId: String,
        apiId: String,
        inputs: Map<String, Any?>,
        caller: String
    ): Map<String, Any?> {
        val api = findApi(projectId, apiId)
        val result = when (api.toolId) {
            WORKBENCH_TODO_ADD_TOOL_ID -> addTodo(projectId, inputs)
            WORKBENCH_TODO_FINISH_TOOL_ID -> finishTodo(projectId, inputs)
            else -> if (api.executorKind == WORKBENCH_SCHEMA_EXECUTOR_KIND) {
                callSchemaCollectionApi(projectId, api, inputs)
            } else if (api.executorKind == WORKBENCH_WORKSPACE_PYTHON_EXECUTOR_KIND) {
                callQuickCaptureApi(projectId, api, inputs)
            } else {
                apiError(api, "UNKNOWN_API", "Unknown workbench API: ${api.toolId}")
            }
        }
        appendApiCall(projectId, api, inputs, caller, result)
        writeProjectJson(findProject(projectId), projectApis(projectId))
        return result + ("project" to getProject(projectId))
    }

    /**
     * Applies a prompt-driven hot update to a registered Workbench project.
     *
     * @param projectId Project whose native display should refresh after the update.
     * @param prompt User request captured from the Xiaowan floating assistant; it is stored in
     * `logs/hot_updates.jsonl` and interpreted by the current project template.
     * @param caller Caller label such as `ui` or `ai`, persisted for audit and future replay.
     * @param frontendContext Optional current Flutter Display context attached by Xiaowan or VLM
     * input, such as route, display id, visible state, selected element, selected region,
     * raw drawing paths, selected region, or screenshot summary.
     * @return Hot-update result plus the refreshed project payload.
     */
    @Synchronized
    fun hotUpdateProject(
        projectId: String,
        prompt: String,
        caller: String,
        frontendContext: Map<String, Any?> = emptyMap()
    ): Map<String, Any?> {
        val record = findProject(projectId)
        val request = prompt.trim()
        require(request.isNotEmpty()) { "Hot update prompt is required." }
        require(
            record.templateId == WORKBENCH_TODO_TEMPLATE_ID ||
                record.templateId == WORKBENCH_SCHEMA_TEMPLATE_ID ||
                record.templateId == WORKBENCH_QUICK_CAPTURE_TEMPLATE_ID
        ) { "Unsupported workbench hot update template: ${record.templateId}" }
        val appliedActions = mutableListOf<Map<String, Any?>>()
        val lower = request.lowercase()
        val wantsFinish =
            listOf("归档", "完成", "finish", "archive", "done").any { lower.contains(it) }
        val wantsAdd =
            listOf("增加", "新增", "添加", "add", "create").any { lower.contains(it) }

        if (record.templateId == WORKBENCH_QUICK_CAPTURE_TEMPLATE_ID) {
            if (wantsFinish) {
                val activeItem = readQuickCaptureItems(record.projectId)
                    .firstOrNull { it.status != "archived" }
                if (activeItem == null) {
                    appliedActions.add(
                        linkedMapOf(
                            "apiId" to WORKBENCH_CAPTURE_ARCHIVE_TOOL_ID,
                            "success" to false,
                            "errorCode" to "NO_ACTIVE_CAPTURE_ITEM"
                        )
                    )
                } else {
                    appliedActions.add(
                        compactToolResult(
                            callApi(
                                projectId = record.projectId,
                                apiId = WORKBENCH_CAPTURE_ARCHIVE_TOOL_ID,
                                inputs = mapOf("item_id" to activeItem.id),
                                caller = WORKBENCH_HOT_UPDATE_CALLER
                            )
                        )
                    )
                }
            }
            if (wantsAdd || !wantsFinish) {
                appliedActions.add(
                    compactToolResult(
                        callApi(
                            projectId = record.projectId,
                            apiId = WORKBENCH_CAPTURE_INGEST_TOOL_ID,
                            inputs = mapOf("text" to hotUpdateTodoTitle(request)),
                            caller = WORKBENCH_HOT_UPDATE_CALLER
                        )
                    )
                )
            }
        } else if (record.templateId == WORKBENCH_SCHEMA_TEMPLATE_ID) {
            applySchemaHotUpdate(record, request, wantsAdd, wantsFinish, appliedActions)
        } else if (wantsFinish) {
            val openTodo = readTodos(record.projectId).firstOrNull { it.status != "finished" }
            if (openTodo == null) {
                appliedActions.add(
                    linkedMapOf(
                        "apiId" to WORKBENCH_TODO_FINISH_TOOL_ID,
                        "success" to false,
                        "errorCode" to "NO_OPEN_TODO"
                    )
                )
            } else {
                appliedActions.add(
                    compactToolResult(
                        callApi(
                            projectId = record.projectId,
                            apiId = WORKBENCH_TODO_FINISH_TOOL_ID,
                            inputs = mapOf("todo_id" to openTodo.id),
                            caller = WORKBENCH_HOT_UPDATE_CALLER
                        )
                    )
                )
            }
        }

        if (record.templateId == WORKBENCH_TODO_TEMPLATE_ID && (wantsAdd || !wantsFinish)) {
            appliedActions.add(
                compactToolResult(
                    callApi(
                        projectId = record.projectId,
                        apiId = WORKBENCH_TODO_ADD_TOOL_ID,
                        inputs = mapOf("title" to hotUpdateTodoTitle(request)),
                        caller = WORKBENCH_HOT_UPDATE_CALLER
                    )
                )
            )
        }

        appendHotUpdate(record.projectId, request, caller, appliedActions, frontendContext)
        writeProjectJson(record, projectApis(record.projectId))
        return linkedMapOf(
            "success" to true,
            "projectId" to record.projectId,
            "prompt" to request,
            "frontendContext" to frontendContext,
            "appliedActions" to appliedActions,
            "hotUpdateLogPath" to "${record.spacePath}/logs/hot_updates.jsonl",
            "project" to getProject(record.projectId)
        )
    }

    /**
     * Imports an Android APK or Android project directory into a Workbench Project.
     *
     * @param projectId Project that will own the imported Android asset.
     * @param sourcePath Android absolute path or `/workspace/...` shell path to an APK file or
     * Android project directory that already exists on the device.
     * @param sourceKind Optional explicit kind. Use `apk` for APK files or `android_project` for
     * source directories; blank values are inferred from the source path.
     * @param displayName Optional user-facing asset name stored in the project manifest.
     * @param caller UI or AI caller label persisted in `logs/android_ingest.jsonl`.
     * @return Import result, the asset manifest entry, and the refreshed Project payload.
     */
    @Synchronized
    fun ingestAndroidAsset(
        projectId: String,
        sourcePath: String,
        sourceKind: String? = null,
        displayName: String? = null,
        caller: String = "unknown"
    ): Map<String, Any?> {
        val record = findProject(projectId)
        appendProjectProgress(
            projectId = record.projectId,
            stage = "android_ingest_started",
            status = "running",
            message = "Preparing Android asset import.",
            percent = 10,
            caller = caller,
            details = linkedMapOf(
                "sourcePath" to sourcePath,
                "sourceKind" to sourceKind
            )
        )
        val source = resolveSourcePath(sourcePath)
        require(source.exists()) { "Android source does not exist: $sourcePath" }
        val kind = resolveAndroidSourceKind(source, sourceKind)
        val now = nowIso()
        val baseName = displayName?.trim()?.takeIf { it.isNotEmpty() }
            ?: source.name.ifBlank { kind }
        val assetId = uniqueAndroidAssetId(record.projectId, baseName, now)
        val assetDir = File(projectDir(record.projectId), "android/apps/$assetId")
        assetDir.mkdirs()
        val includedFiles = mutableListOf<String>()
        val entry = if (kind == WORKBENCH_ANDROID_APK_KIND) {
            val target = File(assetDir, "source.apk")
            source.copyTo(target, overwrite = true)
            includedFiles.add("source.apk")
            target
        } else {
            val target = File(assetDir, "source")
            copyAndroidProjectTree(source, target, includedFiles)
            target
        }
        appendProjectProgress(
            projectId = record.projectId,
            stage = "android_asset_copied",
            status = "running",
            message = "Android asset snapshot copied into the Project workspace.",
            percent = 65,
            caller = caller,
            details = linkedMapOf(
                "assetId" to assetId,
                "sourceKind" to kind,
                "copiedFileCount" to includedFiles.size
            )
        )
        val apkInfo = if (kind == WORKBENCH_ANDROID_APK_KIND) readApkInfo(entry) else emptyMap()
        val asset = WorkbenchAndroidAsset(
            assetId = assetId,
            projectId = record.projectId,
            sourceKind = kind,
            displayName = baseName,
            originalPath = source.absolutePath,
            projectPath = assetDir.absolutePath,
            shellPath = shellPathForProjectFile(record.projectId, assetDir),
            entryPath = shellPathForProjectFile(record.projectId, entry),
            packageName = apkInfo["packageName"] as? String,
            versionName = apkInfo["versionName"] as? String,
            versionCode = apkInfo["versionCode"] as? Long,
            sizeBytes = fileSizeBytes(entry),
            fileCount = if (entry.isDirectory) includedFiles.size else 1,
            importedAt = now
        )
        val updatedAssets = readAndroidAssets(record.projectId) + asset
        writeAndroidAssets(record.projectId, updatedAssets)
        appendAndroidIngest(record.projectId, asset, caller, includedFiles)
        appendProjectProgress(
            projectId = record.projectId,
            stage = "android_ingest_completed",
            status = "completed",
            message = "Android asset import is recorded in the Project runtime.",
            percent = 100,
            caller = caller,
            details = linkedMapOf(
                "assetId" to asset.assetId,
                "packageName" to asset.packageName,
                "fileCount" to asset.fileCount
            )
        )
        writeProjectJson(record, projectApis(record.projectId))
        return linkedMapOf(
            "success" to true,
            "projectId" to record.projectId,
            "asset" to asset.toPayload(),
            "androidManifestPath" to
                "${record.spacePath}/android/manifest.json",
            "androidIngestLogPath" to
                "${record.spacePath}/logs/android_ingest.jsonl",
            "project" to getProject(record.projectId)
        )
    }

    /**
     * Imports or registers an open-source repository snapshot into a Workbench Project.
     *
     * @param projectId Project that owns the imported repository source.
     * @param sourceUrl Optional GitHub or git URL. URL-only imports are registered as fetch-required
     * metadata so the Agent can fetch through the approved terminal/tool path later.
     * @param sourcePath Optional Android or `/workspace/...` path to an already downloaded repo.
     * @param sourceKind Optional source kind: `oss_repo`, `github_repo`, or `local_source`.
     * @param ref Optional branch, tag, or commit recorded for future fetch/replay.
     * @param displayName Optional user-facing name for the source asset.
     * @param caller UI or AI caller label persisted in `logs/oss_ingest.jsonl`.
     * @return Source manifest entry, package analysis, progress log path, and refreshed Project.
     */
    @Synchronized
    fun ingestOssSource(
        projectId: String,
        sourceUrl: String? = null,
        sourcePath: String? = null,
        sourceKind: String? = null,
        ref: String? = null,
        displayName: String? = null,
        caller: String = "unknown"
    ): Map<String, Any?> {
        val record = findProject(projectId)
        val normalizedUrl = sourceUrl?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedPath = sourcePath?.trim()?.takeIf { it.isNotEmpty() }
        require(normalizedUrl != null || normalizedPath != null) {
            "OSS ingest requires sourceUrl or sourcePath."
        }
        appendProjectProgress(
            projectId = record.projectId,
            stage = "oss_ingest_started",
            status = "running",
            message = "Preparing OSS source import.",
            percent = 10,
            caller = caller,
            details = linkedMapOf(
                "sourceUrl" to normalizedUrl,
                "sourcePath" to normalizedPath,
                "ref" to ref
            )
        )
        return runCatching {
            val source = normalizedPath?.let { resolveSourcePath(it) }
            if (source != null) {
                require(source.exists()) { "OSS source does not exist: $normalizedPath" }
            }
            val kind = resolveOssSourceKind(source, normalizedUrl, sourceKind)
            val now = nowIso()
            val baseName = displayName?.trim()?.takeIf { it.isNotEmpty() }
                ?: normalizedUrl?.substringAfterLast('/')?.removeSuffix(".git")?.takeIf { it.isNotEmpty() }
                ?: source?.name?.takeIf { it.isNotEmpty() }
                ?: "oss-source"
            val sourceId = uniqueOssSourceId(record.projectId, baseName, now)
            val sourceRoot = File(projectDir(record.projectId), "source/repos/$sourceId")
            val includedFiles = mutableListOf<String>()
            val entry = if (source == null) {
                sourceRoot.mkdirs()
                sourceRoot
            } else if (source.isDirectory) {
                val target = File(sourceRoot, "source")
                copyOssSourceTree(source, target, includedFiles)
                target
            } else {
                sourceRoot.mkdirs()
                val target = File(sourceRoot, source.name.ifBlank { "source" })
                source.copyTo(target, overwrite = true)
                includedFiles.add(target.name)
                target
            }
            appendProjectProgress(
                projectId = record.projectId,
                stage = if (source == null) "oss_fetch_required" else "oss_source_copied",
                status = if (source == null) "waiting" else "running",
                message = if (source == null) {
                    "Source URL registered. Fetch through terminal/tool execution before binding APIs."
                } else {
                    "OSS source snapshot copied into the Project workspace."
                },
                percent = if (source == null) 45 else 60,
                caller = caller,
                details = linkedMapOf(
                    "sourceId" to sourceId,
                    "copiedFileCount" to includedFiles.size
                )
            )
            val analysisRoot = if (entry.isDirectory) entry else sourceRoot
            val analysis = if (source == null) {
                linkedMapOf<String, Any?>(
                    "detectedStack" to emptyList<String>(),
                    "packageFiles" to emptyList<Map<String, Any?>>(),
                    "entrypoints" to emptyList<Map<String, Any?>>()
                )
            } else {
                analyzeOssSourceTree(analysisRoot)
            }
            appendProjectProgress(
                projectId = record.projectId,
                stage = "oss_package_analyzed",
                status = if (source == null) "waiting" else "running",
                message = "OSS package files and entrypoints analyzed.",
                percent = if (source == null) 55 else 85,
                caller = caller,
                details = linkedMapOf(
                    "sourceId" to sourceId,
                    "detectedStack" to analysis["detectedStack"],
                    "packageFileCount" to ((analysis["packageFiles"] as? List<*>)?.size ?: 0)
                )
            )
            val fetchHint = if (source == null && normalizedUrl != null) {
                "Fetch this repo with terminal tools, then call workbench_project_ingest_oss again with sourcePath pointing at the downloaded directory."
            } else {
                null
            }
            val asset = WorkbenchOssSourceAsset(
                sourceId = sourceId,
                projectId = record.projectId,
                sourceKind = kind,
                displayName = baseName,
                sourceUrl = normalizedUrl,
                ref = ref?.trim()?.takeIf { it.isNotEmpty() },
                originalPath = source?.absolutePath,
                projectPath = sourceRoot.absolutePath,
                shellPath = shellPathForProjectFile(record.projectId, sourceRoot),
                entryPath = shellPathForProjectFile(record.projectId, entry),
                requiresFetch = source == null,
                fetchHint = fetchHint,
                detectedStack = (analysis["detectedStack"] as? List<*>)
                    ?.mapNotNull { it?.toString() }
                    ?: emptyList(),
                packageFiles = (analysis["packageFiles"] as? List<*>)
                    ?.mapNotNull { it as? Map<String, Any?> }
                    ?: emptyList(),
                entrypoints = (analysis["entrypoints"] as? List<*>)
                    ?.mapNotNull { it as? Map<String, Any?> }
                    ?: emptyList(),
                sizeBytes = if (source == null) 0 else fileSizeBytes(entry),
                fileCount = if (source == null) 0 else if (entry.isDirectory) includedFiles.size else 1,
                importedAt = now
            )
            val updatedSources = readOssSources(record.projectId) + asset
            writeOssSources(record.projectId, updatedSources)
            appendOssIngest(record.projectId, asset, caller, includedFiles)
            appendProjectProgress(
                projectId = record.projectId,
                stage = "oss_ingest_completed",
                status = if (asset.requiresFetch) "waiting" else "completed",
                message = if (asset.requiresFetch) {
                    "OSS source URL registered and waiting for fetch."
                } else {
                    "OSS source import is ready for Project API binding."
                },
                percent = if (asset.requiresFetch) 60 else 100,
                caller = caller,
                details = linkedMapOf(
                    "sourceId" to asset.sourceId,
                    "requiresFetch" to asset.requiresFetch,
                    "detectedStack" to asset.detectedStack,
                    "entrypointCount" to asset.entrypoints.size
                )
            )
            writeProjectJson(record, projectApis(record.projectId))
            linkedMapOf(
                "success" to true,
                "projectId" to record.projectId,
                "source" to asset.toPayload(),
                "sourceManifestPath" to "${record.spacePath}/source/manifest.json",
                "ossIngestLogPath" to "${record.spacePath}/logs/oss_ingest.jsonl",
                "progressLogPath" to "${record.spacePath}/logs/project_progress.jsonl",
                "project" to getProject(record.projectId)
            )
        }.getOrElse { error ->
            appendProjectProgress(
                projectId = record.projectId,
                stage = "oss_ingest_failed",
                status = "failed",
                message = error.message ?: "OSS source import failed.",
                percent = 100,
                caller = caller,
                details = linkedMapOf(
                    "sourceUrl" to normalizedUrl,
                    "sourcePath" to normalizedPath
                )
            )
            throw error
        }
    }

    /**
     * Reads Workbench Project progress events for creation, ingest, and backend framework work.
     *
     * @param projectId Optional project id. Blank values return the latest progress summary for
     * every registered Project; a concrete id returns recent events for that Project.
     * @param limit Maximum events returned for one project, newest events retained.
     * @return Progress payload for MethodChannel and Agent control tools.
     */
    @Synchronized
    fun getProjectProgress(projectId: String? = null, limit: Int = 50): Map<String, Any?> {
        val normalizedProjectId = projectId?.trim()?.takeIf { it.isNotEmpty() }
        if (normalizedProjectId == null) {
            val projects = readProjectRegistry().map { record ->
                linkedMapOf<String, Any?>(
                    "projectId" to record.projectId,
                    "name" to record.name,
                    "templateId" to record.templateId,
                    "lastProgress" to lastProjectProgress(record.projectId),
                    "progressLogPath" to "${record.spacePath}/logs/project_progress.jsonl"
                )
            }
            return linkedMapOf(
                "success" to true,
                "projectId" to null,
                "projects" to projects,
                "count" to projects.size
            )
        }
        val record = findProject(normalizedProjectId)
        val events = readProjectProgress(record.projectId, limit.coerceIn(1, 200))
        return linkedMapOf(
            "success" to true,
            "projectId" to record.projectId,
            "progressLogPath" to "${record.spacePath}/logs/project_progress.jsonl",
            "lastProgress" to events.lastOrNull(),
            "events" to events,
            "count" to events.size
        )
    }

    /**
     * Resolves the native Flutter route for a project.
     *
     * @param projectId Project to open.
     * @return OOB route that renders the project in the native Workbench UI.
     */
    fun routeForProject(projectId: String): String {
        return findProject(projectId).route
    }

    /**
     * Exports a registered Workbench project as a distributable zip package.
     *
     * @param projectId Project id whose metadata, APIs, workspace files, logs, and skill contract
     * should be captured. The id is normalized the same way as project creation.
     * @return Export metadata including Android path, Alpine `/workspace` path, package name, and
     * the zip entries that were written for auditing or UI display.
     */
    @Synchronized
    fun exportProject(projectId: String): Map<String, Any?> {
        val record = findProject(projectId)
        val apis = projectApis(record.projectId)
        val timestamp = nowIso().replace(Regex("[^A-Za-z0-9]+"), "-").trim('-')
        val packageName = "${record.projectId}-$timestamp.zip"
        exportsRoot.mkdirs()
        val packageFile = File(exportsRoot, packageName)
        val includedFiles = mutableListOf<String>()
        val counts = apiExecutionCounts(record.projectId)
        val androidAssets = readAndroidAssets(record.projectId)
        val sourceAssets = readOssSources(record.projectId)
        val skillEntry = linkedMapOf<String, Any?>(
            "skillId" to "oob-native-workbench",
            "source" to "builtin_asset",
            "path" to "skills/oob-native-workbench/SKILL.md"
        )
        val manifest = linkedMapOf<String, Any?>(
            "formatVersion" to 1,
            "source" to "oob-native-workbench",
            "exportedAt" to nowIso(),
            "packageName" to packageName,
            "projectId" to record.projectId,
            "project" to record.toPayload(),
            "apis" to apis.map { it.toPayload(counts[it.apiId] ?: 0) },
            "androidAssets" to androidAssets.map { it.toPayload() },
            "sourceAssets" to sourceAssets.map { it.toPayload() },
            "skills" to listOf(skillEntry),
            "workspaceShellPath" to record.spacePath
        )

        ZipOutputStream(packageFile.outputStream()).use { zip ->
            addTextZipEntry(zip, "manifest.json", gson.toJson(manifest), includedFiles)
            addTextZipEntry(
                zip,
                "registry/project_record.json",
                gson.toJson(record.toPayload()),
                includedFiles
            )
            addTextZipEntry(
                zip,
                "registry/api_records.json",
                gson.toJson(apis.map { it.toPayload(counts[it.apiId] ?: 0) }),
                includedFiles
            )
            addFileTreeZipEntries(
                zip = zip,
                rootDir = projectDir(record.projectId),
                current = projectDir(record.projectId),
                entryPrefix = "project",
                includedFiles = includedFiles
            )
            readBuiltinWorkbenchSkill()?.let { skillBody ->
                addTextZipEntry(
                    zip,
                    "skills/oob-native-workbench/SKILL.md",
                    skillBody,
                    includedFiles
                )
            }
        }

        return linkedMapOf(
            "success" to true,
            "projectId" to record.projectId,
            "packageName" to packageName,
            "exportPath" to packageFile.absolutePath,
            "exportShellPath" to
                "${AgentWorkspaceManager.SHELL_ROOT_PATH}/projects/exports/$packageName",
            "includedFiles" to includedFiles
        )
    }

    private fun projectPayload(record: WorkbenchProjectRecord): Map<String, Any?> {
        val apis = projectApis(record.projectId)
        ensureProjectSourceFiles(record, apis)
        val todos = if (record.templateId == WORKBENCH_TODO_TEMPLATE_ID) {
            readTodos(record.projectId)
        } else {
            emptyList()
        }
        val schemaItems = if (record.templateId == WORKBENCH_SCHEMA_TEMPLATE_ID) {
            readSchemaItems(record.projectId)
        } else {
            emptyList()
        }
        val quickCaptureItems = if (record.templateId == WORKBENCH_QUICK_CAPTURE_TEMPLATE_ID) {
            readQuickCaptureItems(record.projectId)
        } else {
            emptyList()
        }
        val counts = apiExecutionCounts(record.projectId)
        val androidAssets = readAndroidAssets(record.projectId)
        val sourceAssets = readOssSources(record.projectId)
        val displays = workbenchDisplays(record)
        return linkedMapOf(
            "projectId" to record.projectId,
            "name" to record.name,
            "templateId" to record.templateId,
            "route" to record.route,
            "spacePath" to record.spacePath,
            "pageIds" to displays.mapNotNull { it["pageId"]?.toString() ?: it["id"]?.toString() },
            "displays" to displays,
            "schema" to workbenchPageSpec(record),
            "apiIds" to record.apiIds,
            "tools" to apis.map { it.toPayload(counts[it.apiId] ?: 0) },
            "apis" to apis.map { it.toPayload(counts[it.apiId] ?: 0) },
            "androidAssets" to androidAssets.map { it.toPayload() },
            "sourceAssets" to sourceAssets.map { it.toPayload() },
            "flows" to emptyList<Map<String, Any?>>(),
            "todos" to todos.map { it.toPayload() },
            "items" to schemaItems.map { it.toPayload() },
            "captureItems" to quickCaptureItems.map { it.toPayload() },
            "lastProgress" to lastProjectProgress(record.projectId),
            "progressLogPath" to "${record.spacePath}/logs/project_progress.jsonl",
            "createdAt" to record.createdAt,
            "updatedAt" to record.updatedAt
        )
    }

    private fun activeProjectManifest(
        record: WorkbenchProjectRecord,
        activatedAt: String? = null
    ): Map<String, Any?> {
        val counts = apiExecutionCounts(record.projectId)
        return linkedMapOf(
            "projectId" to record.projectId,
            "name" to record.name,
            "route" to record.route,
            "spacePath" to record.spacePath,
            "skillId" to "oob-native-workbench",
            "displays" to workbenchDisplays(record),
            "apis" to projectApis(record.projectId).map { it.toPayload(counts[it.apiId] ?: 0) },
            "sourceAssets" to readOssSources(record.projectId).map { it.toPayload() },
            "lastProgress" to lastProjectProgress(record.projectId),
            "activatedAt" to (activatedAt ?: readActiveProjectActivatedAt())
        )
    }

    private fun writeProjectJson(
        record: WorkbenchProjectRecord,
        apis: List<WorkbenchApiRecord>,
        sourcePrompt: String? = null
    ) {
        val counts = apiExecutionCounts(record.projectId)
        val androidAssets = readAndroidAssets(record.projectId)
        val sourceAssets = readOssSources(record.projectId)
        val todos = if (record.templateId == WORKBENCH_TODO_TEMPLATE_ID) {
            readTodos(record.projectId)
        } else {
            emptyList()
        }
        val schemaItems = if (record.templateId == WORKBENCH_SCHEMA_TEMPLATE_ID) {
            readSchemaItems(record.projectId)
        } else {
            emptyList()
        }
        val quickCaptureItems = if (record.templateId == WORKBENCH_QUICK_CAPTURE_TEMPLATE_ID) {
            readQuickCaptureItems(record.projectId)
        } else {
            emptyList()
        }
        val prompt = sourcePrompt?.trim()?.takeIf { it.isNotEmpty() }
            ?: readProjectSourcePrompt(record.projectId)
        val displays = workbenchDisplays(record)
        val payload = linkedMapOf<String, Any?>(
            "project" to record.toPayload(),
            "generation" to linkedMapOf(
                "skillId" to "oob-native-workbench",
                "prompt" to prompt,
                "decomposition" to listOf(
                    "Project registry",
                    "OOB native Flutter frontend",
                    "Project business APIs",
                    "Persistent data and API logs"
                )
            ),
            "page" to linkedMapOf(
                "pageId" to ((displays.firstOrNull()?.get("pageId") ?: displays.firstOrNull()?.get("id"))
                    ?: "workbench-page"),
                "renderer" to (workbenchPageSpec(record)["renderer"] ?: "oob_native_schema"),
                "route" to record.route
            ),
            "displays" to displays,
            "schema" to workbenchPageSpec(record),
            "apis" to apis.map { it.toPayload(counts[it.apiId] ?: 0) },
            "android" to linkedMapOf(
                "manifest" to "android/manifest.json",
                "assets" to androidAssets.map { it.toPayload() }
            ),
            "source" to linkedMapOf(
                "manifest" to "source/manifest.json",
                "assets" to sourceAssets.map { it.toPayload() }
            ),
            "progress" to linkedMapOf(
                "log" to "logs/project_progress.jsonl",
                "last" to lastProjectProgress(record.projectId)
            ),
            "state" to linkedMapOf(
                "todoCount" to todos.size,
                "openTodoCount" to todos.count { it.status != "finished" },
                "finishedTodoCount" to todos.count { it.status == "finished" },
                "itemCount" to schemaItems.size,
                "activeItemCount" to schemaItems.count { it.status != "archived" },
                "archivedItemCount" to schemaItems.count { it.status == "archived" },
                "captureItemCount" to quickCaptureItems.size,
                "activeCaptureItemCount" to quickCaptureItems.count { it.status != "archived" },
                "archivedCaptureItemCount" to quickCaptureItems.count { it.status == "archived" },
                "androidAssetCount" to androidAssets.size,
                "sourceAssetCount" to sourceAssets.size
            )
        )
        val file = File(projectDir(record.projectId), "project.json")
        file.parentFile?.mkdirs()
        file.writeText(gson.toJson(payload))
    }

    /**
     * Reads the editable frontend schema for one Project.
     *
     * @param record Project whose `frontend/page_spec.json` may define the current Display.
     * @return Parsed schema map, or an empty map when the Project predates schema files.
     */
    private fun workbenchPageSpec(record: WorkbenchProjectRecord): Map<String, Any?> {
        val file = File(projectDir(record.projectId), "frontend/page_spec.json")
        if (!file.exists()) return emptyMap()
        return runCatching {
            gson.fromJson<Map<String, Any?>>(file.readText(), mapType) ?: emptyMap()
        }.getOrElse { emptyMap() }
    }

    /**
     * Builds the native display registry exposed by one Workbench Project.
     *
     * @param record Project registry record that owns the display route and stable project id.
     * @return Display payloads shown by Flutter as Project-scoped frontends, not business APIs.
     */
    private fun workbenchDisplays(record: WorkbenchProjectRecord): List<Map<String, Any?>> {
        val spec = workbenchPageSpec(record)
        val explicitDisplays = spec["displays"]
        if (explicitDisplays is List<*>) {
            val displays = explicitDisplays.mapNotNull { item ->
                @Suppress("UNCHECKED_CAST")
                (item as? Map<String, Any?>)?.takeIf { display ->
                    display["route"]?.toString()?.trim()?.isNotEmpty() == true
                }
            }
            if (displays.isNotEmpty()) {
                return displays
            }
        }
        if (spec.isNotEmpty() && spec["route"]?.toString()?.trim()?.isNotEmpty() == true) {
            return listOf(
                linkedMapOf(
                    "id" to (spec["displayId"] ?: spec["pageId"] ?: "schema-main-display"),
                    "pageId" to (spec["pageId"] ?: "schema-main-page"),
                    "title" to (spec["title"] ?: record.name),
                    "shortName" to (spec["shortName"] ?: "APP"),
                    "route" to spec["route"],
                    "kind" to "oob_flutter",
                    "renderer" to (spec["renderer"] ?: "oob_schema_collection"),
                    "isDefault" to true,
                    "description" to (spec["description"] ?: spec["subtitle"] ?: "")
                )
            )
        }
        return when (record.templateId) {
            WORKBENCH_SCHEMA_TEMPLATE_ID -> listOf(
                linkedMapOf(
                    "id" to "schema-main-display",
                    "pageId" to "schema-main-page",
                    "title" to record.name,
                    "shortName" to "APP",
                    "route" to record.route,
                    "kind" to "oob_flutter",
                    "renderer" to "oob_schema_collection",
                    "isDefault" to true,
                    "description" to "Schema display bound to this Project API registry."
                )
            )
            WORKBENCH_QUICK_CAPTURE_TEMPLATE_ID -> listOf(
                linkedMapOf(
                    "id" to "quick-capture-display",
                    "pageId" to "quick-capture-page",
                    "title" to "随手记 Inbox",
                    "shortName" to "NOTE",
                    "route" to record.route,
                    "kind" to "oob_flutter",
                    "renderer" to "oob_quick_capture_inbox",
                    "isDefault" to true,
                    "description" to "Quick capture inbox bound to capture Project APIs."
                )
            )
            else -> listOf(
                linkedMapOf(
                    "id" to "todo-log-display",
                    "pageId" to "todo-log-page",
                    "title" to "Todo 日志",
                    "shortName" to "TODO",
                    "route" to record.route,
                    "kind" to "oob_flutter",
                    "renderer" to "oob_native_schema",
                    "isDefault" to true,
                    "description" to "Todo display bound to this Project API registry."
                )
            )
        }
    }

    /**
     * Creates the editable source-spec files that make a project understandable outside memory.
     *
     * @param record Stable project registry record used for route, workspace path, and display name.
     * @param apis Business API records owned by this project. Control APIs are intentionally excluded.
     */
    private fun ensureProjectSourceFiles(
        record: WorkbenchProjectRecord,
        apis: List<WorkbenchApiRecord>,
        sourcePrompt: String? = null,
        config: Map<String, Any?> = emptyMap()
    ) {
        val projectDir = projectDir(record.projectId)
        projectDir.mkdirs()
        val readme = File(projectDir, "README.md")
        if (!readme.exists()) {
            val dataFiles = when (record.templateId) {
                WORKBENCH_SCHEMA_TEMPLATE_ID ->
                    "- `data/items.json`: persistent schema item state"
                WORKBENCH_QUICK_CAPTURE_TEMPLATE_ID ->
                    "- `data/items.json`: persistent quick capture inbox state\n" +
                        "- `logs/link_fetch.jsonl`: best-effort link read and fallback log\n" +
                        "- `logs/script_runs.jsonl`: workspace Python executor audit log"
                else -> "- `data/todos.json`: persistent todo state"
            }
            readme.writeText(
                """
                |# ${record.name}
                |
                |This is an OOB Native Workbench project.
                |${sourcePrompt?.let { "\n## Source Prompt\n$it\n" } ?: ""}
                |
                |## Runtime
                |- Project id: `${record.projectId}`
                |- Template: `${record.templateId}`
                |- Native display route: `${record.route}`
                |- Workspace path: `${record.spacePath}`
                |
                |## Frontend
                |The frontend is rendered by OOB's native Flutter Display. The editable page
                |contract lives in `frontend/page_spec.json`; OOB binds visible controls to
                |Project APIs through `workbenchApiCall`.
                |
                |## Backend
                |Business APIs are declared in `backend/api_spec.json`. API execution stays behind
                |the Workbench runtime boundary, so AI calls and UI clicks share one backend path.
                |
                |## Data
                |$dataFiles
                |- `logs/api_calls.jsonl`: append-only AI/UI API call log
                |- `logs/hot_updates.jsonl`: append-only Xiaowan hot update requests
                |- `android/manifest.json`: imported Android APK/project assets
                |- `logs/android_ingest.jsonl`: append-only Android asset import log
                |- `source/manifest.json`: imported OSS/GitHub source snapshots
                |- `logs/oss_ingest.jsonl`: append-only OSS source import log
                |- `logs/project_progress.jsonl`: append-only Project creation/import progress
                |
                """.trimMargin()
            )
        }

        val frontendDir = File(projectDir, "frontend")
        val backendDir = File(projectDir, "backend")
        val scriptsDir = File(backendDir, "scripts")
        frontendDir.mkdirs()
        backendDir.mkdirs()
        scriptsDir.mkdirs()
        val pageSpec = File(frontendDir, "page_spec.json")
        if (!pageSpec.exists()) {
            pageSpec.writeText(gson.toJson(defaultPageSpec(record, apis, sourcePrompt, config)))
        }
        val apiSpec = File(backendDir, "api_spec.json")
        if (!apiSpec.exists()) {
            apiSpec.writeText(
                gson.toJson(
                    linkedMapOf<String, Any?>(
                        "executorBoundary" to "oob_native_workbench",
                        "sourcePrompt" to sourcePrompt,
                        "templateId" to record.templateId,
                        "runtime" to linkedMapOf(
                            "workspace" to record.spacePath,
                            "progressLog" to "logs/project_progress.jsonl",
                            "sourceManifest" to "source/manifest.json",
                            "androidManifest" to "android/manifest.json",
                            "executorKinds" to apis.map { it.executorKind }.distinct()
                        ),
                        "controlApis" to listOf(
                            "workbench_project_progress_get",
                            "workbench_project_ingest_oss",
                            "workbench_project_ingest_android",
                            "workbench_project_hot_update",
                            "workbench_project_export"
                        ),
                        "promptDecomposition" to promptDecomposition(record, apis),
                        "apis" to apis.map { api ->
                            linkedMapOf(
                                "apiId" to api.apiId,
                                "displayName" to api.displayName,
                                "description" to api.description,
                                "inputSchema" to api.inputSchema,
                                "outputSchema" to api.outputSchema,
                                "executorKind" to api.executorKind,
                                "runtime" to linkedMapOf(
                                    "executorBoundary" to "oob_native_workbench",
                                    "executorKind" to api.executorKind,
                                    "workingDirectory" to "backend",
                                    "status" to if (api.executorKind == WORKBENCH_WORKSPACE_PYTHON_EXECUTOR_KIND) {
                                        "native_backed_workspace_script_contract"
                                    } else {
                                        "native_executor"
                                    },
                                    "scriptPath" to if (api.executorKind == WORKBENCH_WORKSPACE_PYTHON_EXECUTOR_KIND) {
                                        "backend/scripts/${api.apiId.replace('.', '_')}.py"
                                    } else {
                                        null
                                    }
                                ),
                                "capabilities" to when (api.executorKind) {
                                    WORKBENCH_WORKSPACE_PYTHON_EXECUTOR_KIND -> listOf(
                                        "persistent_project_data",
                                        "script_audit_log",
                                        "future_bridge_tools",
                                        "future_alpine_execution"
                                    )
                                    WORKBENCH_SCHEMA_EXECUTOR_KIND -> listOf(
                                        "persistent_project_data",
                                        "native_collection_crud"
                                    )
                                    else -> listOf("persistent_project_data", "native_template")
                                },
                                "sourceRefs" to listOf(
                                    linkedMapOf(
                                        "kind" to "page_spec",
                                        "path" to "frontend/page_spec.json"
                                    ),
                                    linkedMapOf(
                                        "kind" to "api_spec",
                                        "path" to "backend/api_spec.json"
                                    )
                                ),
                                "persistence" to persistenceFiles(record),
                                "frontendBinding" to "frontend/page_spec.json",
                                "aiUsage" to "Call through workbench_api_call with this projectId."
                            )
                        }
                    )
                )
            )
        }
        val sourceDir = File(projectDir, "source")
        sourceDir.mkdirs()
        val sourceReadme = File(sourceDir, "README.md")
        if (!sourceReadme.exists()) {
            sourceReadme.writeText(
                """
                |# Source Assets
                |
                |This directory stores OSS/GitHub source snapshots imported through the OOB
                |Workbench control API.
                |
                |Use `workbench_project_ingest_oss` or MethodChannel
                |`workbenchProjectIngestOss`; do not register source import as a Project
                |business API and do not hand-edit `manifest.json`.
                |
                |URL-only imports are recorded as fetch-required metadata. Fetch through the
                |approved terminal/tool path, then ingest the downloaded directory with
                |`sourcePath` to analyze package files and entrypoints.
                |
                """.trimMargin()
            )
        }
        if (record.templateId == WORKBENCH_QUICK_CAPTURE_TEMPLATE_ID) {
            writeQuickCaptureScriptFiles(backendDir, scriptsDir)
        }
        val androidDir = File(projectDir, "android")
        androidDir.mkdirs()
        val androidReadme = File(androidDir, "README.md")
        if (!androidReadme.exists()) {
            androidReadme.writeText(
                """
                |# Android Assets
                |
                |This directory stores Android APKs and Android project source snapshots imported
                |through the OOB Workbench control API.
                |
                |Use `workbench_project_ingest_android` or MethodChannel
                |`workbenchProjectIngestAndroid`; do not register Android import as a Project
                |business API and do not hand-edit `manifest.json`.
                |
                """.trimMargin()
            )
        }
    }

    /**
     * Builds the editable frontend contract for a new Project.
     *
     * @param record Project registry record used for route and identity.
     * @param apis Business APIs that the generated Display can bind to.
     * @param sourcePrompt User's original prompt, persisted for later hot-update context.
     * @param config Optional creation config containing entity/display hints for generic Projects.
     * @return Page spec written to `frontend/page_spec.json`.
     */
    private fun defaultPageSpec(
        record: WorkbenchProjectRecord,
        apis: List<WorkbenchApiRecord>,
        sourcePrompt: String?,
        config: Map<String, Any?>
    ): Map<String, Any?> {
        if (record.templateId == WORKBENCH_QUICK_CAPTURE_TEMPLATE_ID) {
            return linkedMapOf(
                "pageId" to "quick-capture-page",
                "displayId" to "quick-capture-display",
                "title" to "随手记 Inbox",
                "shortName" to "NOTE",
                "description" to "Capture text, links, share content, and screenshots into Todo, Summary, Link Card, and later-read items.",
                "renderer" to "oob_quick_capture_inbox",
                "route" to record.route,
                "templateId" to record.templateId,
                "sourcePrompt" to sourcePrompt,
                "decomposition" to listOf(
                    "Explicit Project creation prompt -> Project registry",
                    "Quick capture frontend -> OOB native Flutter inbox renderer",
                    "Controls -> capture Project API calls",
                    "State -> project data/items.json and logs"
                ),
                "state" to linkedMapOf("items" to "data/items.json"),
                "categories" to listOf("todo", "summary", "link", "later"),
                "bindings" to listOf(
                    linkedMapOf(
                        "controlId" to "quick-capture-ingest",
                        "apiId" to WORKBENCH_CAPTURE_INGEST_TOOL_ID,
                        "inputs" to linkedMapOf("text" to "page.capture_input")
                    ),
                    linkedMapOf(
                        "controlId" to "quick-capture-archive",
                        "apiId" to WORKBENCH_CAPTURE_ARCHIVE_TOOL_ID,
                        "inputs" to linkedMapOf("item_id" to "item.id")
                    ),
                    linkedMapOf(
                        "controlId" to "quick-capture-promote-to-todo",
                        "apiId" to WORKBENCH_CAPTURE_PROMOTE_TOOL_ID,
                        "inputs" to linkedMapOf("item_id" to "item.id")
                    ),
                    linkedMapOf(
                        "controlId" to "quick-capture-summarize",
                        "apiId" to WORKBENCH_CAPTURE_SUMMARIZE_TOOL_ID,
                        "inputs" to linkedMapOf("item_id" to "item.id")
                    )
                )
            )
        }
        if (record.templateId == WORKBENCH_SCHEMA_TEMPLATE_ID) {
            val entityName = schemaEntityName(config, record.name, sourcePrompt)
            val title = config["title"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: config["displayName"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: record.name
            val description = config["description"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: "Prompt-generated OOB native schema display."
            return linkedMapOf(
                "pageId" to "schema-main-page",
                "displayId" to "schema-main-display",
                "title" to title,
                "shortName" to schemaShortName(entityName),
                "description" to description,
                "renderer" to "oob_schema_collection",
                "route" to record.route,
                "templateId" to record.templateId,
                "sourcePrompt" to sourcePrompt,
                "entityName" to entityName,
                "primaryField" to "title",
                "decomposition" to listOf(
                    "Prompt requirement -> Project registry",
                    "Frontend display -> OOB schema Flutter renderer",
                    "Controls -> Project API calls",
                    "State -> project data/items.json and logs"
                ),
                "state" to linkedMapOf("items" to "data/items.json"),
                "fields" to listOf(
                    linkedMapOf(
                        "id" to "title",
                        "label" to entityName,
                        "type" to "string",
                        "required" to true
                    )
                ),
                "actions" to apis.map { api ->
                    linkedMapOf(
                        "apiId" to api.apiId,
                        "kind" to schemaApiAction(api),
                        "label" to api.displayName,
                        "inputs" to if (schemaApiAction(api) == "archive") {
                            linkedMapOf("item_id" to "item.id")
                        } else {
                            linkedMapOf("title" to "page.input.title")
                        }
                    )
                }
            )
        }
        return linkedMapOf(
            "pageId" to "todo-log-page",
            "displayId" to "todo-log-display",
            "title" to "Todo 日志",
            "shortName" to "TODO",
            "renderer" to "oob_native_flutter_display",
            "route" to record.route,
            "templateId" to record.templateId,
            "sourcePrompt" to sourcePrompt,
            "decomposition" to listOf(
                "Prompt requirement -> Project registry",
                "Frontend display -> OOB native Flutter route",
                "Controls -> Project API calls",
                "State -> project data and logs"
            ),
            "state" to linkedMapOf("todos" to "data/todos.json"),
            "bindings" to listOf(
                linkedMapOf(
                    "controlId" to "add-todo-button",
                    "apiId" to WORKBENCH_TODO_ADD_TOOL_ID,
                    "inputs" to linkedMapOf("title" to "page.todo_input")
                ),
                linkedMapOf(
                    "controlId" to "finish-todo-button",
                    "apiId" to WORKBENCH_TODO_FINISH_TOOL_ID,
                    "inputs" to linkedMapOf("todo_id" to "todo.id")
                )
            )
        )
    }

    /**
     * Explains the prompt-to-runtime split in `backend/api_spec.json`.
     *
     * @param record Project whose template determines the state file.
     * @param apis Registered business APIs for this Project.
     * @return Stable metadata map for handoff, export, and future agents.
     */
    private fun promptDecomposition(
        record: WorkbenchProjectRecord,
        apis: List<WorkbenchApiRecord>
    ): Map<String, Any?> {
        return when (record.templateId) {
            WORKBENCH_SCHEMA_TEMPLATE_ID -> linkedMapOf(
                "frontend" to "frontend/page_spec.json",
                "data" to "data/items.json",
                "logs" to "logs/api_calls.jsonl",
                "hotUpdates" to "logs/hot_updates.jsonl",
                "businessApis" to apis.map { it.apiId }
            )
            WORKBENCH_QUICK_CAPTURE_TEMPLATE_ID -> linkedMapOf(
                "ingest" to WORKBENCH_CAPTURE_INGEST_TOOL_ID,
                "archive" to WORKBENCH_CAPTURE_ARCHIVE_TOOL_ID,
                "promoteToTodo" to WORKBENCH_CAPTURE_PROMOTE_TOOL_ID,
                "summarize" to WORKBENCH_CAPTURE_SUMMARIZE_TOOL_ID,
                "frontend" to "frontend/page_spec.json",
                "backendScripts" to "backend/scripts/*.py",
                "data" to "data/items.json",
                "logs" to listOf(
                    "logs/api_calls.jsonl",
                    "logs/link_fetch.jsonl",
                    "logs/script_runs.jsonl",
                    "logs/hot_updates.jsonl"
                )
            )
            else -> linkedMapOf(
                "addTodo" to WORKBENCH_TODO_ADD_TOOL_ID,
                "archiveTodo" to WORKBENCH_TODO_FINISH_TOOL_ID,
                "frontend" to "frontend/page_spec.json",
                "data" to "data/todos.json",
                "logs" to "logs/api_calls.jsonl",
                "hotUpdates" to "logs/hot_updates.jsonl"
            )
        }
    }

    /**
     * Lists Project files that a business API is expected to read or write.
     *
     * @param record Project whose template owns the state file.
     * @return Relative persistence paths stored in API specs.
     */
    private fun persistenceFiles(record: WorkbenchProjectRecord): List<String> {
        return when (record.templateId) {
            WORKBENCH_SCHEMA_TEMPLATE_ID -> listOf("data/items.json", "logs/api_calls.jsonl")
            WORKBENCH_QUICK_CAPTURE_TEMPLATE_ID -> listOf(
                "data/items.json",
                "logs/api_calls.jsonl",
                "logs/link_fetch.jsonl",
                "logs/script_runs.jsonl"
            )
            else -> listOf("data/todos.json", "logs/api_calls.jsonl")
        }
    }

    /**
     * Writes a UTF-8 text entry into the export zip.
     *
     * @param zip Open zip stream owned by `exportProject`; the caller keeps lifecycle control.
     * @param entryName Package-relative path to write, using `/` separators.
     * @param text Text payload to encode and store.
     * @param includedFiles Mutable audit list that records every written entry.
     */
    private fun addTextZipEntry(
        zip: ZipOutputStream,
        entryName: String,
        text: String,
        includedFiles: MutableList<String>
    ) {
        zip.putNextEntry(ZipEntry(entryName))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        includedFiles.add(entryName)
    }

    /**
     * Copies a project file tree into the export zip while preserving project-relative paths.
     *
     * @param zip Open zip stream owned by `exportProject`.
     * @param rootDir Project directory that defines the relative path boundary.
     * @param current Current file or directory being visited.
     * @param entryPrefix Top-level zip directory where project files should be placed.
     * @param includedFiles Mutable audit list that records every written file entry.
     */
    private fun addFileTreeZipEntries(
        zip: ZipOutputStream,
        rootDir: File,
        current: File,
        entryPrefix: String,
        includedFiles: MutableList<String>
    ) {
        if (!current.exists()) return
        if (current.isDirectory) {
            current.listFiles()
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
                ?.forEach { child ->
                    addFileTreeZipEntries(zip, rootDir, child, entryPrefix, includedFiles)
                }
            return
        }
        val relative = rootDir.toPath().relativize(current.toPath()).toString()
            .replace(File.separatorChar, '/')
        if (relative.isBlank() || relative.contains("..")) return
        val entryName = "$entryPrefix/$relative"
        zip.putNextEntry(ZipEntry(entryName))
        current.inputStream().use { input -> input.copyTo(zip) }
        zip.closeEntry()
        includedFiles.add(entryName)
    }

    /**
     * Reads the bundled Workbench skill so exported packages include the prompt contract.
     *
     * @return SKILL.md body when the store was created from Android context; null in pure unit tests
     * or if the asset is unavailable.
     */
    private fun readBuiltinWorkbenchSkill(): String? {
        val context = appContext ?: return null
        return runCatching {
            context.assets.open("builtin_skills/oob-native-workbench/SKILL.md")
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        }.getOrNull()
    }

    /**
     * Writes illustrative workspace Python script files for the quick-capture executor contract.
     *
     * @param backendDir Project backend directory that receives `oob_sdk.py`.
     * @param scriptsDir Project script directory that receives one file per capture API.
     */
    private fun writeQuickCaptureScriptFiles(backendDir: File, scriptsDir: File) {
        val sdkDir = File(backendDir, "oob_sdk")
        sdkDir.mkdirs()
        val sdk = File(backendDir, "oob_sdk.py")
        if (!sdk.exists()) {
            sdk.writeText(
                """
                |class OobProjectSdk:
                |    '''Minimal SDK sketch for OOB Workbench Project scripts.'''
                |
                |    def __init__(self, project_id):
                |        self.project_id = project_id
                |
                |    def read_json(self, path, default=None):
                |        return default
                |
                |    def write_json(self, path, value):
                |        return value
                |
                |    def browser_use(self, **kwargs):
                |        return {"status": "deferred", **kwargs}
                |
                |    def vlm_task(self, **kwargs):
                |        return {"status": "deferred", **kwargs}
                |
                |    def memory_write_daily(self, **kwargs):
                |        return {"status": "optional", **kwargs}
                |
                |
                |def execute(inputs, sdk):
                |    raise NotImplementedError("Each script defines its own execute(inputs, sdk).")
                |
                """.trimMargin()
            )
        }
        val initFile = File(sdkDir, "__init__.py")
        if (!initFile.exists()) {
            initFile.writeText("from .oob import OobProjectSdk\n")
        }
        val oobFile = File(sdkDir, "oob.py")
        if (!oobFile.exists()) {
            oobFile.writeText(
                """
                |class OobProjectSdk:
                |    '''Stable facade for generated OOB Workbench scripts.'''
                |
                |    def __init__(self, project_id, bridge=None):
                |        self.project_id = project_id
                |        self.bridge = bridge
                |
                |    def vlm_task(self, **kwargs):
                |        return self._call("vlm_task", kwargs)
                |
                |    def file_read(self, path):
                |        return self._call("file_read", {"path": path})
                |
                |    def file_write(self, path, content):
                |        return self._call("file_write", {"path": path, "content": content})
                |
                |    def memory_write_daily(self, text):
                |        return self._call("memory_write_daily", {"text": text})
                |
                |    def browser_use(self, **kwargs):
                |        return self._call("browser_use", kwargs)
                |
                |    def _call(self, tool, args):
                |        if self.bridge is None:
                |            return {"tool": tool, "status": "deferred", "args": args}
                |        return self.bridge.call(tool, args)
                |
                """.trimMargin()
            )
        }
        val bridgeFile = File(sdkDir, "_bridge.py")
        if (!bridgeFile.exists()) {
            bridgeFile.writeText(
                """
                |class WorkbenchBridge:
                |    '''Placeholder bridge client; native BridgeServer will replace this in a later executor pass.'''
                |
                |    def __init__(self, socket_path):
                |        self.socket_path = socket_path
                |
                |    def call(self, tool, args):
                |        return {"tool": tool, "status": "deferred", "socket_path": self.socket_path, "args": args}
                |
                """.trimMargin()
            )
        }
        val runnerFile = File(sdkDir, "runner.py")
        if (!runnerFile.exists()) {
            runnerFile.writeText(
                """
                |import json
                |import sys
                |
                |
                |def main(handler):
                |    payload = json.load(sys.stdin)
                |    result = handler(payload.get("inputs", {}))
                |    json.dump(result, sys.stdout, ensure_ascii=False)
                |
                """.trimMargin()
            )
        }
        val scripts = mapOf(
            "capture_ingest.py" to "Classify text/url/share/screenshot into Todo, Summary, Link Card, or later-read item.",
            "capture_archive.py" to "Archive one item by item_id.",
            "capture_promote_to_todo.py" to "Convert an existing item into a todo.",
            "capture_summarize.py" to "Refresh an existing item's summary."
        )
        scripts.forEach { (name, description) ->
            val file = File(scriptsDir, name)
            if (!file.exists()) {
                file.writeText(
                    """
                    |from oob_sdk import OobProjectSdk
                    |
                    |
                    |def execute(inputs: dict, sdk: OobProjectSdk) -> dict:
                    |    '''$description
                    |
                    |    OOB v1 runs this contract through the native Workbench executor while
                    |    keeping the editable script artifact in Workspace for future replacement.
                    |    '''
                    |    return {"ok": True, "inputs": inputs}
                    |
                    """.trimMargin()
                )
            }
        }
    }

    /**
     * Preserves the original prompt stored in `project.json` when an existing project is reopened.
     *
     * @param projectId Project directory id whose current metadata should be inspected.
     * @return Prompt text previously stored under `generation.prompt`, or null when absent.
     */
    private fun readProjectSourcePrompt(projectId: String): String? {
        val file = File(projectDir(projectId), "project.json")
        if (!file.exists()) return null
        return runCatching {
            val root = gson.fromJson<Map<String, Any?>>(file.readText(), mapType)
            val generation = root["generation"] as? Map<*, *>
            generation?.get("prompt")?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    /**
     * Counts persisted Project API executions from the append-only API call log.
     *
     * @param projectId Project directory whose `logs/api_calls.jsonl` should be read.
     * @return Number of log records per API id. Both successful and failed calls count as executions.
     */
    private fun apiExecutionCounts(projectId: String): Map<String, Int> {
        val file = File(projectDir(projectId), "logs/api_calls.jsonl")
        if (!file.exists()) return emptyMap()
        val counts = linkedMapOf<String, Int>()
        Regex("\"apiId\"\\s*:\\s*\"([^\"]+)\"")
            .findAll(file.readText())
            .forEach { match ->
                val apiId = match.groupValues[1].trim()
                if (apiId.isNotEmpty()) {
                    counts[apiId] = (counts[apiId] ?: 0) + 1
                }
        }
        return counts
    }

    private fun addTodo(projectId: String, inputs: Map<String, Any?>): Map<String, Any?> {
        val api = findApi(projectId, WORKBENCH_TODO_ADD_TOOL_ID)
        val title = inputs["title"]?.toString()?.trim().orEmpty()
        if (title.isEmpty()) {
            return apiError(api, "EMPTY_TODO_TITLE", "Todo title is required.")
        }
        val todos = readTodos(projectId).toMutableList()
        val now = nowIso()
        val todo = WorkbenchTodoRecord(
            id = "todo-${System.currentTimeMillis()}-${todos.size + 1}",
            title = title,
            status = "open",
            createdAt = now
        )
        todos.add(0, todo)
        writeTodos(projectId, todos)
        return apiSuccess(api, mapOf("todo" to todo.toPayload()))
    }

    private fun finishTodo(projectId: String, inputs: Map<String, Any?>): Map<String, Any?> {
        val api = findApi(projectId, WORKBENCH_TODO_FINISH_TOOL_ID)
        val todoId = inputs["todo_id"]?.toString()?.trim()
            ?: inputs["todoId"]?.toString()?.trim()
            ?: ""
        if (todoId.isEmpty()) {
            return apiError(api, "MISSING_TODO_ID", "todo_id is required.")
        }
        val todos = readTodos(projectId)
        val index = todos.indexOfFirst { it.id == todoId }
        if (index < 0) {
            return apiError(api, "TODO_NOT_FOUND", "Todo not found: $todoId")
        }
        val current = todos[index]
        val finished = if (current.status == "finished") {
            current
        } else {
            current.copy(status = "finished", finishedAt = nowIso())
        }
        val updated = todos.toMutableList()
        updated[index] = finished
        writeTodos(projectId, updated)
        return apiSuccess(api, mapOf("todo" to finished.toPayload()))
    }

    private fun apiSuccess(
        api: WorkbenchApiRecord,
        outputs: Map<String, Any?>
    ): Map<String, Any?> = linkedMapOf(
        "success" to true,
        "apiId" to api.apiId,
        "toolId" to api.toolId,
        "outputs" to outputs
    )

    private fun apiError(
        api: WorkbenchApiRecord,
        errorCode: String,
        errorMessage: String
    ): Map<String, Any?> = linkedMapOf(
        "success" to false,
        "apiId" to api.apiId,
        "toolId" to api.toolId,
        "outputs" to emptyMap<String, Any?>(),
        "errorCode" to errorCode,
        "errorMessage" to errorMessage
    )

    private fun appendApiCall(
        projectId: String,
        api: WorkbenchApiRecord,
        inputs: Map<String, Any?>,
        caller: String,
        result: Map<String, Any?>
    ) {
        val file = File(projectDir(projectId), "logs/api_calls.jsonl")
        file.parentFile?.mkdirs()
        val row = linkedMapOf<String, Any?>(
            "timestamp" to nowIso(),
            "projectId" to projectId,
            "apiId" to api.apiId,
            "toolId" to api.toolId,
            "caller" to caller.ifBlank { "unknown" },
            "inputs" to inputs,
            "success" to (result["success"] == true),
            "errorCode" to result["errorCode"]
        )
        file.appendText(logGson.toJson(row) + "\n")
    }

    /**
     * Appends one Project-level hot update request to the audit log.
     *
     * @param projectId Project directory that owns the `logs/hot_updates.jsonl` file.
     * @param prompt User prompt captured from the floating assistant.
     * @param caller UI or AI caller label for future replay/debugging.
     * @param appliedActions Compact API action summaries produced while applying the prompt.
     * @param frontendContext Current Flutter Display context captured by Xiaowan or VLM input,
     * including raw drawing paths, selected region, and screenshot summary when available.
     */
    private fun appendHotUpdate(
        projectId: String,
        prompt: String,
        caller: String,
        appliedActions: List<Map<String, Any?>>,
        frontendContext: Map<String, Any?>
    ) {
        val file = File(projectDir(projectId), "logs/hot_updates.jsonl")
        file.parentFile?.mkdirs()
        val row = linkedMapOf<String, Any?>(
            "timestamp" to nowIso(),
            "projectId" to projectId,
            "caller" to caller.ifBlank { "unknown" },
            "prompt" to prompt,
            "frontendContext" to frontendContext,
            "appliedActions" to appliedActions
        )
        file.appendText(logGson.toJson(row) + "\n")
    }

    /**
     * Appends an Android asset import event for audit and future replay.
     *
     * @param projectId Project directory that owns the log file.
     * @param asset Manifest entry created by `ingestAndroidAsset`.
     * @param caller UI or AI caller label.
     * @param includedFiles Project-relative copied files for source-directory imports.
     */
    private fun appendAndroidIngest(
        projectId: String,
        asset: WorkbenchAndroidAsset,
        caller: String,
        includedFiles: List<String>
    ) {
        val file = File(projectDir(projectId), "logs/android_ingest.jsonl")
        file.parentFile?.mkdirs()
        val row = linkedMapOf<String, Any?>(
            "timestamp" to nowIso(),
            "projectId" to projectId,
            "assetId" to asset.assetId,
            "sourceKind" to asset.sourceKind,
            "caller" to caller.ifBlank { "unknown" },
            "originalPath" to asset.originalPath,
            "entryPath" to asset.entryPath,
            "packageName" to asset.packageName,
            "fileCount" to asset.fileCount,
            "includedFiles" to includedFiles.take(200)
        )
        file.appendText(logGson.toJson(row) + "\n")
    }

    /**
     * Appends an OSS source import event for audit, replay, and later API binding.
     *
     * @param projectId Project directory that owns the log file.
     * @param asset Source manifest entry created by `ingestOssSource`.
     * @param caller UI or AI caller label.
     * @param includedFiles Project-relative copied files for local source imports.
     */
    private fun appendOssIngest(
        projectId: String,
        asset: WorkbenchOssSourceAsset,
        caller: String,
        includedFiles: List<String>
    ) {
        val file = File(projectDir(projectId), "logs/oss_ingest.jsonl")
        file.parentFile?.mkdirs()
        val row = linkedMapOf<String, Any?>(
            "timestamp" to nowIso(),
            "projectId" to projectId,
            "sourceId" to asset.sourceId,
            "sourceKind" to asset.sourceKind,
            "caller" to caller.ifBlank { "unknown" },
            "sourceUrl" to asset.sourceUrl,
            "ref" to asset.ref,
            "originalPath" to asset.originalPath,
            "entryPath" to asset.entryPath,
            "requiresFetch" to asset.requiresFetch,
            "detectedStack" to asset.detectedStack,
            "packageFiles" to asset.packageFiles,
            "entrypoints" to asset.entrypoints,
            "fileCount" to asset.fileCount,
            "includedFiles" to includedFiles.take(200)
        )
        file.appendText(logGson.toJson(row) + "\n")
    }

    /**
     * Appends a Project progress event to the runtime log.
     *
     * @param projectId Project directory that owns `logs/project_progress.jsonl`.
     * @param stage Stable stage id such as `project_create_started` or `oss_source_copied`.
     * @param status Current state: `running`, `waiting`, `completed`, or `failed`.
     * @param message Human-readable short status for UI/tool progress surfaces.
     * @param percent Coarse progress percentage in the 0..100 range.
     * @param caller UI, AI, or workbench label persisted for audit.
     * @param details Optional structured metadata for replay and debugging.
     */
    private fun appendProjectProgress(
        projectId: String,
        stage: String,
        status: String,
        message: String,
        percent: Int,
        caller: String,
        details: Map<String, Any?> = emptyMap()
    ) {
        val file = projectProgressFile(projectId)
        file.parentFile?.mkdirs()
        val row = linkedMapOf<String, Any?>(
            "timestamp" to nowIso(),
            "projectId" to projectId,
            "stage" to stage,
            "status" to status,
            "message" to message,
            "percent" to percent.coerceIn(0, 100),
            "caller" to caller.ifBlank { "unknown" },
            "details" to details
        )
        file.appendText(logGson.toJson(row) + "\n")
    }

    /**
     * Appends a quick-capture script executor audit row.
     *
     * @param projectId Project directory that owns `logs/script_runs.jsonl`.
     * @param api Workspace script API that was invoked through the native boundary.
     * @param inputs Original API inputs.
     * @param result Tool-style execution result.
     */
    private fun appendScriptRun(
        projectId: String,
        api: WorkbenchApiRecord,
        inputs: Map<String, Any?>,
        result: Map<String, Any?>
    ) {
        val file = File(projectDir(projectId), "logs/script_runs.jsonl")
        file.parentFile?.mkdirs()
        val row = linkedMapOf<String, Any?>(
            "timestamp" to nowIso(),
            "projectId" to projectId,
            "apiId" to api.apiId,
            "executorKind" to api.executorKind,
            "scriptPath" to "backend/scripts/${api.apiId.replace('.', '_')}.py",
            "inputs" to inputs,
            "success" to (result["success"] == true),
            "errorCode" to result["errorCode"]
        )
        file.appendText(logGson.toJson(row) + "\n")
    }

    /**
     * Appends a best-effort link fetch record for platforms that may require app login or VLM.
     *
     * @param projectId Project directory that owns `logs/link_fetch.jsonl`.
     * @param itemId Capture item associated with the URL.
     * @param url Raw URL saved by `capture.ingest`.
     * @param sourceApp Optional app that supplied the share text.
     * @param contextText User/share context stored with the item.
     */
    private fun appendLinkFetch(
        projectId: String,
        itemId: String,
        url: String,
        sourceApp: String?,
        contextText: String
    ) {
        val file = File(projectDir(projectId), "logs/link_fetch.jsonl")
        file.parentFile?.mkdirs()
        val platform = when {
            url.contains("xiaohongshu", ignoreCase = true) ||
                url.contains("xhslink", ignoreCase = true) -> "xiaohongshu"
            url.contains("weixin", ignoreCase = true) ||
                url.contains("wechat", ignoreCase = true) ||
                url.contains("mp.weixin.qq.com", ignoreCase = true) -> "wechat"
            else -> "web"
        }
        val row = linkedMapOf<String, Any?>(
            "timestamp" to nowIso(),
            "projectId" to projectId,
            "itemId" to itemId,
            "url" to url,
            "sourceApp" to sourceApp,
            "platform" to platform,
            "status" to "deferred",
            "reason" to "v1 stores the link and user context first; browser_use/VLM can enrich it when accessible.",
            "contextPreview" to contextText.take(220)
        )
        file.appendText(logGson.toJson(row) + "\n")
    }

    /**
     * Reduces a full API call result to a log-safe action summary.
     *
     * @param result Payload returned by `callApi`; the full refreshed Project is intentionally
     * omitted so `hot_updates.jsonl` stays small and append-only.
     * @return API id, tool id, success flag, and optional error code.
     */
    private fun compactToolResult(result: Map<String, Any?>): Map<String, Any?> {
        return linkedMapOf(
            "apiId" to (result["apiId"] ?: result["toolId"]),
            "toolId" to result["toolId"],
            "success" to (result["success"] == true),
            "errorCode" to result["errorCode"]
        )
    }

    /**
     * Converts a natural-language hot update prompt into the demo todo title.
     *
     * @param prompt Raw prompt from Xiaowan. If it contains a colon, the text after the first
     * colon is treated as the concrete todo title; otherwise the full prompt is used.
     * @return Bounded todo title suitable for persistence in `data/todos.json`.
     */
    private fun hotUpdateTodoTitle(prompt: String): String {
        val candidate = when {
            prompt.contains("：") -> prompt.substringAfter("：")
            prompt.contains(":") -> prompt.substringAfter(":")
            else -> prompt
        }.trim().ifBlank { prompt }
        return candidate.take(120)
    }

    /**
     * Applies a natural-language hot update to a generic schema Project.
     *
     * @param record Project whose API registry owns schema create/archive APIs.
     * @param request Raw user hot-update request.
     * @param wantsAdd Whether the prompt appears to request a new item.
     * @param wantsArchive Whether the prompt appears to request archiving an item.
     * @param appliedActions Mutable action list written into `hot_updates.jsonl`.
     */
    private fun applySchemaHotUpdate(
        record: WorkbenchProjectRecord,
        request: String,
        wantsAdd: Boolean,
        wantsArchive: Boolean,
        appliedActions: MutableList<Map<String, Any?>>
    ) {
        val apis = projectApis(record.projectId)
        val createApi = apis.firstOrNull { schemaApiAction(it) == "create" }
        val archiveApi = apis.firstOrNull { schemaApiAction(it) == "archive" }
        if (wantsArchive && archiveApi != null) {
            val activeItem = readSchemaItems(record.projectId).firstOrNull { it.status != "archived" }
            if (activeItem == null) {
                appliedActions.add(
                    linkedMapOf(
                        "apiId" to archiveApi.apiId,
                        "success" to false,
                        "errorCode" to "NO_ACTIVE_ITEM"
                    )
                )
            } else {
                appliedActions.add(
                    compactToolResult(
                        callApi(
                            projectId = record.projectId,
                            apiId = archiveApi.apiId,
                            inputs = mapOf("item_id" to activeItem.id),
                            caller = WORKBENCH_HOT_UPDATE_CALLER
                        )
                    )
                )
            }
        }
        if ((wantsAdd || !wantsArchive) && createApi != null) {
            appliedActions.add(
                compactToolResult(
                    callApi(
                        projectId = record.projectId,
                        apiId = createApi.apiId,
                        inputs = mapOf("title" to hotUpdateTodoTitle(request)),
                        caller = WORKBENCH_HOT_UPDATE_CALLER
                    )
                )
            )
        }
    }

    private fun initialTodos(config: Map<String, Any?>): List<WorkbenchTodoRecord> {
        val raw = config["initialTodos"]
        val titles = when (raw) {
            is Iterable<*> -> raw.mapNotNull { item ->
                when (item) {
                    is Map<*, *> -> item["title"]?.toString()
                    else -> item?.toString()
                }?.trim()?.takeIf { it.isNotEmpty() }
            }
            else -> emptyList()
        }
        val now = nowIso()
        return titles.mapIndexed { index, title ->
            WorkbenchTodoRecord(
                id = "todo-initial-${index + 1}",
                title = title,
                status = "open",
                createdAt = now
            )
        }
    }

    /**
     * Parses initial generic items from Project creation config.
     *
     * @param config Project creation config; accepts `initialItems` as strings or maps.
     * @return Initial item records for `data/items.json`.
     */
    private fun initialSchemaItems(config: Map<String, Any?>): List<WorkbenchSchemaItemRecord> {
        val raw = config["initialItems"] ?: config["items"]
        val entries = when (raw) {
            is Iterable<*> -> raw.mapNotNull { item ->
                when (item) {
                    is Map<*, *> -> {
                        val title = item["title"]?.toString()?.trim()
                            ?: item["name"]?.toString()?.trim()
                        val fields = item.entries.associate { entry ->
                            entry.key.toString() to entry.value
                        }.filterKeys { it != "id" && it != "title" && it != "name" && it != "status" }
                        title?.takeIf { it.isNotEmpty() }?.let { it to fields }
                    }
                    else -> item?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                        ?.let { it to emptyMap<String, Any?>() }
                }
            }
            else -> emptyList()
        }
        val now = nowIso()
        return entries.mapIndexed { index, (title, fields) ->
            WorkbenchSchemaItemRecord(
                id = "item-initial-${index + 1}",
                title = title,
                status = "active",
                fields = fields,
                createdAt = now
            )
        }
    }

    /**
     * Parses initial quick-capture items from explicit Project creation config.
     *
     * @param config Project creation config; accepts `initialItems` or `initialCaptures`.
     * @return Initial active capture records for `data/items.json`.
     */
    private fun initialQuickCaptureItems(config: Map<String, Any?>): List<WorkbenchQuickCaptureRecord> {
        val raw = config["initialCaptures"] ?: config["initialItems"] ?: return emptyList()
        if (raw !is Iterable<*>) return emptyList()
        val now = nowIso()
        return raw.mapIndexedNotNull { index, item ->
            val map = item as? Map<*, *>
            val text = map?.get("text")?.toString()?.trim()
                ?: map?.get("title")?.toString()?.trim()
                ?: item?.toString()?.trim()
            val title = text?.takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
            val url = map?.get("url")?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: extractFirstUrl(title).takeIf { it.isNotEmpty() }
            val type = map?.get("type")?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: quickCaptureType(title, url.orEmpty(), "")
            WorkbenchQuickCaptureRecord(
                id = "capture-initial-${index + 1}",
                type = type,
                title = quickCaptureTitle(type, title, url.orEmpty()),
                summary = quickCaptureSummary(type, title, url.orEmpty()),
                status = "active",
                url = url,
                rawText = title,
                createdAt = now,
                updatedAt = now
            )
        }
    }

    /**
     * Builds Project APIs for the requested template.
     *
     * @param projectId Project namespace for API registry records.
     * @param templateId Template id selected by `workbench_project_create`.
     * @param config Creation config that may include explicit schema APIs.
     * @return Business API records only; Workbench control APIs are excluded.
     */
    private fun templateApis(
        projectId: String,
        templateId: String,
        config: Map<String, Any?>
    ): List<WorkbenchApiRecord> {
        return when (templateId) {
            WORKBENCH_SCHEMA_TEMPLATE_ID -> schemaTemplateApis(projectId, config)
            WORKBENCH_QUICK_CAPTURE_TEMPLATE_ID -> quickCaptureTemplateApis(projectId)
            else -> todoTemplateApis(projectId)
        }
    }

    private fun todoTemplateApis(projectId: String): List<WorkbenchApiRecord> {
        return listOf(
            WorkbenchApiRecord(
                apiId = WORKBENCH_TODO_ADD_TOOL_ID,
                projectId = projectId,
                toolId = WORKBENCH_TODO_ADD_TOOL_ID,
                displayName = "Add todo",
                description = "Create a todo item in the project state.",
                inputSchema = linkedMapOf("title" to "string"),
                outputSchema = linkedMapOf("todo" to "object"),
                executorKind = "native_template"
            ),
            WorkbenchApiRecord(
                apiId = WORKBENCH_TODO_FINISH_TOOL_ID,
                projectId = projectId,
                toolId = WORKBENCH_TODO_FINISH_TOOL_ID,
                displayName = "Archive todo",
                description = "Archive an existing todo item.",
                inputSchema = linkedMapOf("todo_id" to "string"),
                outputSchema = linkedMapOf("todo" to "object"),
                executorKind = "native_template"
            )
        )
    }

    private fun quickCaptureTemplateApis(projectId: String): List<WorkbenchApiRecord> {
        return listOf(
            WorkbenchApiRecord(
                apiId = WORKBENCH_CAPTURE_INGEST_TOOL_ID,
                projectId = projectId,
                toolId = WORKBENCH_CAPTURE_INGEST_TOOL_ID,
                displayName = "Capture ingest",
                description = "Classify text, links, share content, or screenshot references into the inbox.",
                inputSchema = linkedMapOf(
                    "text" to "string?",
                    "url" to "string?",
                    "sourceApp" to "string?",
                    "shareText" to "string?",
                    "screenshotPath" to "string?"
                ),
                outputSchema = linkedMapOf("item" to "object", "items" to "array"),
                executorKind = WORKBENCH_WORKSPACE_PYTHON_EXECUTOR_KIND
            ),
            WorkbenchApiRecord(
                apiId = WORKBENCH_CAPTURE_ARCHIVE_TOOL_ID,
                projectId = projectId,
                toolId = WORKBENCH_CAPTURE_ARCHIVE_TOOL_ID,
                displayName = "Archive capture",
                description = "Archive one quick capture inbox item.",
                inputSchema = linkedMapOf("item_id" to "string"),
                outputSchema = linkedMapOf("item" to "object"),
                executorKind = WORKBENCH_WORKSPACE_PYTHON_EXECUTOR_KIND
            ),
            WorkbenchApiRecord(
                apiId = WORKBENCH_CAPTURE_PROMOTE_TOOL_ID,
                projectId = projectId,
                toolId = WORKBENCH_CAPTURE_PROMOTE_TOOL_ID,
                displayName = "Promote to todo",
                description = "Convert a summary, link card, or later-read item into a todo.",
                inputSchema = linkedMapOf("item_id" to "string", "todo_title" to "string?"),
                outputSchema = linkedMapOf("item" to "object"),
                executorKind = WORKBENCH_WORKSPACE_PYTHON_EXECUTOR_KIND
            ),
            WorkbenchApiRecord(
                apiId = WORKBENCH_CAPTURE_SUMMARIZE_TOOL_ID,
                projectId = projectId,
                toolId = WORKBENCH_CAPTURE_SUMMARIZE_TOOL_ID,
                displayName = "Summarize capture",
                description = "Refresh the summary for an existing quick capture item.",
                inputSchema = linkedMapOf("item_id" to "string"),
                outputSchema = linkedMapOf("item" to "object"),
                executorKind = WORKBENCH_WORKSPACE_PYTHON_EXECUTOR_KIND
            )
        )
    }

    /**
     * Builds generic schema Project APIs from explicit config or a prompt-derived entity namespace.
     *
     * @param projectId Project namespace for API registry records.
     * @param config Creation config. `apis` may provide apiId/displayName/schema/executorKind.
     * @return API records handled by the native schema collection executor.
     */
    private fun schemaTemplateApis(
        projectId: String,
        config: Map<String, Any?>
    ): List<WorkbenchApiRecord> {
        val explicit = explicitApiRecords(projectId, config)
        if (explicit.isNotEmpty()) return explicit
        val namespace = schemaApiNamespace(config)
        return listOf(
            WorkbenchApiRecord(
                apiId = "$namespace.create",
                projectId = projectId,
                toolId = "$namespace.create",
                displayName = "Create ${schemaEntityName(config, namespace, null)}",
                description = "Create an item in this schema Project state.",
                inputSchema = linkedMapOf("title" to "string"),
                outputSchema = linkedMapOf("item" to "object"),
                executorKind = WORKBENCH_SCHEMA_EXECUTOR_KIND
            ),
            WorkbenchApiRecord(
                apiId = "$namespace.archive",
                projectId = projectId,
                toolId = "$namespace.archive",
                displayName = "Archive ${schemaEntityName(config, namespace, null)}",
                description = "Archive an active item in this schema Project state.",
                inputSchema = linkedMapOf("item_id" to "string"),
                outputSchema = linkedMapOf("item" to "object"),
                executorKind = WORKBENCH_SCHEMA_EXECUTOR_KIND
            )
        )
    }

    /**
     * Converts explicit `apis` config into Workbench API registry records.
     *
     * @param projectId Project namespace for each record.
     * @param config Creation config supplied by Agent or Flutter.
     * @return Valid API records with nonblank ids.
     */
    private fun explicitApiRecords(
        projectId: String,
        config: Map<String, Any?>
    ): List<WorkbenchApiRecord> {
        val raw = config["apis"] ?: config["apiSpecs"] ?: return emptyList()
        if (raw !is Iterable<*>) return emptyList()
        return raw.mapNotNull { item ->
            @Suppress("UNCHECKED_CAST")
            val map = item as? Map<String, Any?> ?: return@mapNotNull null
            val apiId = map["apiId"]?.toString()?.trim()
                ?: map["toolId"]?.toString()?.trim()
                ?: return@mapNotNull null
            if (apiId.isEmpty()) return@mapNotNull null
            WorkbenchApiRecord(
                apiId = apiId,
                projectId = projectId,
                toolId = map["toolId"]?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: apiId,
                displayName = map["displayName"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: apiId,
                description = map["description"]?.toString()?.trim().orEmpty(),
                inputSchema = asStringKeyMap(map["inputSchema"]),
                outputSchema = asStringKeyMap(map["outputSchema"]),
                executorKind = map["executorKind"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: WORKBENCH_SCHEMA_EXECUTOR_KIND
            )
        }
    }

    /**
     * Normalizes a dynamic map into a JSON-safe string-key map.
     *
     * @param value Potential map from MethodChannel or tool arguments.
     * @return Map with string keys, or an empty map when the input is absent.
     */
    private fun asStringKeyMap(value: Any?): Map<String, Any?> {
        val raw = value as? Map<*, *> ?: return emptyMap()
        return raw.entries.associate { entry -> entry.key.toString() to entry.value }
    }

    /**
     * Executes generic schema collection create/archive APIs.
     *
     * @param projectId Project whose `data/items.json` should be updated.
     * @param api API registry record. The action is inferred from its id.
     * @param inputs Tool inputs from AI or UI.
     * @return Tool-style success/error payload.
     */
    private fun callSchemaCollectionApi(
        projectId: String,
        api: WorkbenchApiRecord,
        inputs: Map<String, Any?>
    ): Map<String, Any?> {
        return when (schemaApiAction(api)) {
            "create" -> createSchemaItem(projectId, api, inputs)
            "archive" -> archiveSchemaItem(projectId, api, inputs)
            else -> apiError(api, "UNKNOWN_SCHEMA_ACTION", "Unknown schema API: ${api.apiId}")
        }
    }

    private fun createSchemaItem(
        projectId: String,
        api: WorkbenchApiRecord,
        inputs: Map<String, Any?>
    ): Map<String, Any?> {
        val title = schemaItemTitle(inputs)
        if (title.isEmpty()) {
            return apiError(api, "EMPTY_ITEM_TITLE", "Item title is required.")
        }
        val items = readSchemaItems(projectId).toMutableList()
        val fields = inputs.filterKeys { key ->
            key !in setOf("id", "item_id", "itemId", "title", "name", "label", "status")
        }
        val item = WorkbenchSchemaItemRecord(
            id = "item-${System.currentTimeMillis()}-${items.size + 1}",
            title = title,
            status = "active",
            fields = fields,
            createdAt = nowIso()
        )
        items.add(0, item)
        writeSchemaItems(projectId, items)
        return apiSuccess(api, mapOf("item" to item.toPayload()))
    }

    private fun archiveSchemaItem(
        projectId: String,
        api: WorkbenchApiRecord,
        inputs: Map<String, Any?>
    ): Map<String, Any?> {
        val itemId = inputs["item_id"]?.toString()?.trim()
            ?: inputs["itemId"]?.toString()?.trim()
            ?: inputs["id"]?.toString()?.trim()
            ?: ""
        if (itemId.isEmpty()) {
            return apiError(api, "MISSING_ITEM_ID", "item_id is required.")
        }
        val items = readSchemaItems(projectId)
        val index = items.indexOfFirst { it.id == itemId }
        if (index < 0) {
            return apiError(api, "ITEM_NOT_FOUND", "Item not found: $itemId")
        }
        val current = items[index]
        val archived = if (current.status == "archived") {
            current
        } else {
            current.copy(status = "archived", archivedAt = nowIso())
        }
        val updated = items.toMutableList()
        updated[index] = archived
        writeSchemaItems(projectId, updated)
        return apiSuccess(api, mapOf("item" to archived.toPayload()))
    }

    private fun schemaItemTitle(inputs: Map<String, Any?>): String {
        val direct = inputs["title"]?.toString()?.trim()
            ?: inputs["name"]?.toString()?.trim()
            ?: inputs["label"]?.toString()?.trim()
        if (!direct.isNullOrEmpty()) return direct.take(160)
        return inputs.values.firstOrNull { value ->
            value?.toString()?.trim()?.isNotEmpty() == true
        }?.toString()?.trim()?.take(160).orEmpty()
    }

    private fun schemaApiAction(api: WorkbenchApiRecord): String {
        val id = "${api.apiId}.${api.toolId}".lowercase()
        return when {
            listOf(".archive", ".finish", ".complete", ".done").any { id.contains(it) } ->
                "archive"
            listOf(".create", ".add", ".new").any { id.contains(it) } ->
                "create"
            else -> "custom"
        }
    }

    /**
     * Executes the v1 quick-capture workspace script contract through the native Workbench path.
     *
     * @param projectId Project whose `data/items.json` and script logs are updated.
     * @param api API registry record using `workspace_python_script`.
     * @param inputs Capture, archive, promote, or summarize inputs from AI/UI.
     * @return Tool-style result with refreshed inbox item state.
     */
    private fun callQuickCaptureApi(
        projectId: String,
        api: WorkbenchApiRecord,
        inputs: Map<String, Any?>
    ): Map<String, Any?> {
        val result = when (api.toolId) {
            WORKBENCH_CAPTURE_INGEST_TOOL_ID -> ingestQuickCapture(projectId, api, inputs)
            WORKBENCH_CAPTURE_ARCHIVE_TOOL_ID -> archiveQuickCapture(projectId, api, inputs)
            WORKBENCH_CAPTURE_PROMOTE_TOOL_ID -> promoteQuickCaptureToTodo(projectId, api, inputs)
            WORKBENCH_CAPTURE_SUMMARIZE_TOOL_ID -> summarizeQuickCapture(projectId, api, inputs)
            else -> apiError(api, "UNKNOWN_CAPTURE_ACTION", "Unknown capture API: ${api.apiId}")
        }
        appendScriptRun(projectId, api, inputs, result)
        return result
    }

    private fun ingestQuickCapture(
        projectId: String,
        api: WorkbenchApiRecord,
        inputs: Map<String, Any?>
    ): Map<String, Any?> {
        val directText = inputs["text"]?.toString()?.trim().orEmpty()
        val shareText = inputs["shareText"]?.toString()?.trim().orEmpty()
        val explicitUrl = inputs["url"]?.toString()?.trim().orEmpty()
        val screenshotPath = inputs["screenshotPath"]?.toString()?.trim().orEmpty()
        val combined = listOf(directText, shareText, explicitUrl, screenshotPath)
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .trim()
        if (combined.isEmpty()) {
            return apiError(api, "EMPTY_CAPTURE_INPUT", "Capture text, url, shareText, or screenshotPath is required.")
        }
        val url = explicitUrl.ifBlank { extractFirstUrl(combined) }
        val sourceApp = inputs["sourceApp"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val itemType = quickCaptureType(combined, url, screenshotPath)
        val now = nowIso()
        val items = readQuickCaptureItems(projectId).toMutableList()
        val item = WorkbenchQuickCaptureRecord(
            id = "capture-${System.currentTimeMillis()}-${items.size + 1}",
            type = itemType,
            title = quickCaptureTitle(itemType, combined, url),
            summary = quickCaptureSummary(itemType, combined, url),
            status = "active",
            url = url.takeIf { it.isNotEmpty() },
            sourceApp = sourceApp,
            rawText = directText.takeIf { it.isNotEmpty() },
            shareText = shareText.takeIf { it.isNotEmpty() },
            screenshotPath = screenshotPath.takeIf { it.isNotEmpty() },
            dueHint = quickCaptureDueHint(combined),
            priority = quickCapturePriority(combined),
            createdAt = now,
            updatedAt = now
        )
        items.add(0, item)
        writeQuickCaptureItems(projectId, items)
        if (url.isNotEmpty()) {
            appendLinkFetch(projectId, item.id, url, sourceApp, combined)
        }
        return apiSuccess(
            api,
            mapOf(
                "item" to item.toPayload(),
                "items" to items.map { it.toPayload() }
            )
        )
    }

    private fun archiveQuickCapture(
        projectId: String,
        api: WorkbenchApiRecord,
        inputs: Map<String, Any?>
    ): Map<String, Any?> {
        val itemId = captureItemId(inputs)
        if (itemId.isEmpty()) {
            return apiError(api, "MISSING_ITEM_ID", "item_id is required.")
        }
        val items = readQuickCaptureItems(projectId)
        val index = items.indexOfFirst { it.id == itemId }
        if (index < 0) {
            return apiError(api, "CAPTURE_ITEM_NOT_FOUND", "Capture item not found: $itemId")
        }
        val current = items[index]
        val now = nowIso()
        val archived = if (current.status == "archived") {
            current
        } else {
            current.copy(status = "archived", updatedAt = now, archivedAt = now)
        }
        val updated = items.toMutableList()
        updated[index] = archived
        writeQuickCaptureItems(projectId, updated)
        return apiSuccess(api, mapOf("item" to archived.toPayload()))
    }

    private fun promoteQuickCaptureToTodo(
        projectId: String,
        api: WorkbenchApiRecord,
        inputs: Map<String, Any?>
    ): Map<String, Any?> {
        val itemId = captureItemId(inputs)
        if (itemId.isEmpty()) {
            return apiError(api, "MISSING_ITEM_ID", "item_id is required.")
        }
        val items = readQuickCaptureItems(projectId)
        val index = items.indexOfFirst { it.id == itemId }
        if (index < 0) {
            return apiError(api, "CAPTURE_ITEM_NOT_FOUND", "Capture item not found: $itemId")
        }
        val current = items[index]
        val title = inputs["todo_title"]?.toString()?.trim()
            ?: inputs["todoTitle"]?.toString()?.trim()
            ?: current.title
        val updatedItem = current.copy(
            type = "todo",
            title = title.take(120).ifBlank { current.title },
            summary = current.summary.ifBlank { "Converted to a todo from quick capture." },
            updatedAt = nowIso()
        )
        val updated = items.toMutableList()
        updated[index] = updatedItem
        writeQuickCaptureItems(projectId, updated)
        return apiSuccess(api, mapOf("item" to updatedItem.toPayload()))
    }

    private fun summarizeQuickCapture(
        projectId: String,
        api: WorkbenchApiRecord,
        inputs: Map<String, Any?>
    ): Map<String, Any?> {
        val itemId = captureItemId(inputs)
        if (itemId.isEmpty()) {
            return apiError(api, "MISSING_ITEM_ID", "item_id is required.")
        }
        val items = readQuickCaptureItems(projectId)
        val index = items.indexOfFirst { it.id == itemId }
        if (index < 0) {
            return apiError(api, "CAPTURE_ITEM_NOT_FOUND", "Capture item not found: $itemId")
        }
        val current = items[index]
        val source = listOfNotNull(current.rawText, current.shareText, current.url, current.screenshotPath)
            .joinToString("\n")
        val updatedItem = current.copy(
            type = if (current.type == "todo") current.type else "summary",
            summary = quickCaptureSummary("summary", source.ifBlank { current.title }, current.url.orEmpty()),
            updatedAt = nowIso()
        )
        val updated = items.toMutableList()
        updated[index] = updatedItem
        writeQuickCaptureItems(projectId, updated)
        return apiSuccess(api, mapOf("item" to updatedItem.toPayload()))
    }

    private fun captureItemId(inputs: Map<String, Any?>): String {
        return inputs["item_id"]?.toString()?.trim()
            ?: inputs["itemId"]?.toString()?.trim()
            ?: inputs["id"]?.toString()?.trim()
            ?: ""
    }

    private fun quickCaptureType(text: String, url: String, screenshotPath: String): String {
        val lower = text.lowercase()
        return when {
            screenshotPath.isNotEmpty() -> "summary"
            quickCaptureLooksActionable(lower) -> "todo"
            url.isNotEmpty() && listOf("稍后", "later", "read later", "收藏").any { lower.contains(it) } ->
                "later"
            url.isNotEmpty() -> "link"
            text.length > 120 || text.lines().size > 2 -> "summary"
            else -> "todo"
        }
    }

    private fun quickCaptureLooksActionable(lower: String): Boolean {
        return listOf(
            "记一下",
            "提醒",
            "待办",
            "todo",
            "明天",
            "后天",
            "今天",
            "整理",
            "问",
            "买",
            "做",
            "follow up",
            "remind"
        ).any { lower.contains(it) }
    }

    private fun quickCaptureTitle(itemType: String, text: String, url: String): String {
        val withoutUrl = text.replace(url, "")
        val afterMarker = when {
            withoutUrl.contains("记一下：") -> withoutUrl.substringAfter("记一下：")
            withoutUrl.contains("记一下:") -> withoutUrl.substringAfter("记一下:")
            else -> withoutUrl
        }
        val cleaned = afterMarker.trim()
            .lines()
            .firstOrNull { it.trim().isNotEmpty() }
            ?.trim()
            .orEmpty()
        val fallback = when {
            cleaned.isNotEmpty() -> cleaned
            url.isNotEmpty() -> url.removePrefix("https://").removePrefix("http://")
            itemType == "summary" -> "新摘要"
            else -> "新随手记"
        }
        return fallback.take(120)
    }

    private fun quickCaptureSummary(itemType: String, text: String, url: String): String {
        val compact = text
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(220)
        return when {
            itemType == "link" && url.isNotEmpty() ->
                "链接已保存。若页面需要登录或 App 内访问，可继续补充分享文本或截图让 VLM 摘要。"
            itemType == "later" && url.isNotEmpty() ->
                "已加入稍后读。能访问时可用 summarize 刷新摘要，不能访问时保留链接和上下文。"
            itemType == "todo" ->
                compact.ifBlank { "已整理为待办。" }
            compact.isNotEmpty() -> compact
            else -> "已保存到随手记 Inbox。"
        }
    }

    private fun quickCaptureDueHint(text: String): String? {
        val lower = text.lowercase()
        return listOf("今天", "明天", "后天", "下周", "本周", "today", "tomorrow", "next week")
            .firstOrNull { lower.contains(it) }
    }

    private fun quickCapturePriority(text: String): String? {
        val lower = text.lowercase()
        return when {
            listOf("紧急", "重要", "urgent", "asap").any { lower.contains(it) } -> "high"
            else -> null
        }
    }

    private fun extractFirstUrl(text: String): String {
        return Regex("""https?://[^\s，。；；、)）\]】"'<>]+""")
            .find(text)
            ?.value
            ?.trim()
            .orEmpty()
    }

    private fun projectApis(projectId: String): List<WorkbenchApiRecord> {
        return readApiRegistry().filter { it.projectId == projectId }
    }

    private fun findProject(projectId: String): WorkbenchProjectRecord {
        val normalized = sanitizeProjectId(projectId)
        return readProjectRegistry().firstOrNull { it.projectId == normalized }
            ?: throw IllegalArgumentException("Workbench project not found: $projectId")
    }

    private fun findApi(projectId: String, apiIdOrToolId: String): WorkbenchApiRecord {
        val normalized = apiIdOrToolId.trim()
        return projectApis(projectId).firstOrNull { api ->
            api.apiId == normalized || api.toolId == normalized
        } ?: throw IllegalArgumentException("Workbench API not found: $apiIdOrToolId")
    }

    private fun readActiveProjectId(): String? {
        if (!activeProjectFile.exists()) return null
        return runCatching {
            val payload = gson.fromJson<Map<String, Any?>>(
                activeProjectFile.readText(),
                mapType
            ) ?: return null
            payload["projectId"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun readActiveProjectActivatedAt(): String? {
        if (!activeProjectFile.exists()) return null
        return runCatching {
            val payload = gson.fromJson<Map<String, Any?>>(
                activeProjectFile.readText(),
                mapType
            ) ?: return null
            payload["activatedAt"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun readProjectRegistry(): List<WorkbenchProjectRecord> {
        if (!registryFile.exists()) return emptyList()
        return runCatching {
            gson.fromJson<List<WorkbenchProjectRecord>>(
                registryFile.readText(),
                projectRecordListType
            ) ?: emptyList()
        }.getOrElse { emptyList() }
    }

    private fun writeProjectRegistry(records: List<WorkbenchProjectRecord>) {
        projectsRoot.mkdirs()
        registryFile.writeText(gson.toJson(records.sortedBy { it.projectId }))
    }

    private fun readApiRegistry(): List<WorkbenchApiRecord> {
        if (!apiRegistryFile.exists()) return emptyList()
        return runCatching {
            gson.fromJson<List<WorkbenchApiRecord>>(
                apiRegistryFile.readText(),
                apiRecordListType
            ) ?: emptyList()
        }.getOrElse { emptyList() }
    }

    private fun writeApiRegistry(records: List<WorkbenchApiRecord>) {
        projectsRoot.mkdirs()
        apiRegistryFile.writeText(
            gson.toJson(records.sortedWith(compareBy({ it.projectId }, { it.apiId })))
        )
    }

    private fun readTodos(projectId: String): List<WorkbenchTodoRecord> {
        val file = todosFile(projectId)
        if (!file.exists()) return emptyList()
        return runCatching {
            gson.fromJson<List<WorkbenchTodoRecord>>(
                file.readText(),
                todoRecordListType
            ) ?: emptyList()
        }.getOrElse { emptyList() }
    }

    private fun writeTodos(projectId: String, todos: List<WorkbenchTodoRecord>) {
        val file = todosFile(projectId)
        file.parentFile?.mkdirs()
        file.writeText(gson.toJson(todos))
    }

    /**
     * Reads generic schema items for a Project.
     *
     * @param projectId Project whose `data/items.json` should be parsed.
     * @return Persisted item records, or an empty list for non-schema Projects.
     */
    private fun readSchemaItems(projectId: String): List<WorkbenchSchemaItemRecord> {
        val file = schemaItemsFile(projectId)
        if (!file.exists()) return emptyList()
        return runCatching {
            gson.fromJson<List<WorkbenchSchemaItemRecord>>(
                file.readText(),
                schemaItemRecordListType
            ) ?: emptyList()
        }.getOrElse { emptyList() }
    }

    /**
     * Writes generic schema item state for a Project.
     *
     * @param projectId Project whose state file should be replaced.
     * @param items Full desired item state.
     */
    private fun writeSchemaItems(projectId: String, items: List<WorkbenchSchemaItemRecord>) {
        val file = schemaItemsFile(projectId)
        file.parentFile?.mkdirs()
        file.writeText(gson.toJson(items))
    }

    /**
     * Reads quick-capture inbox items from Project state.
     *
     * @param projectId Project whose `data/items.json` stores capture records.
     * @return Persisted capture items, or an empty list when absent.
     */
    private fun readQuickCaptureItems(projectId: String): List<WorkbenchQuickCaptureRecord> {
        val file = quickCaptureItemsFile(projectId)
        if (!file.exists()) return emptyList()
        return runCatching {
            gson.fromJson<List<WorkbenchQuickCaptureRecord>>(
                file.readText(),
                quickCaptureRecordListType
            ) ?: emptyList()
        }.getOrElse { emptyList() }
    }

    /**
     * Writes quick-capture inbox state.
     *
     * @param projectId Project whose state file should be replaced.
     * @param items Full desired capture item state.
     */
    private fun writeQuickCaptureItems(
        projectId: String,
        items: List<WorkbenchQuickCaptureRecord>
    ) {
        val file = quickCaptureItemsFile(projectId)
        file.parentFile?.mkdirs()
        file.writeText(gson.toJson(items))
    }

    /**
     * Reads imported Android assets for a project.
     *
     * @param projectId Project whose `android/manifest.json` should be parsed.
     * @return Previously imported APK/project asset records, or an empty list when absent.
     */
    private fun readAndroidAssets(projectId: String): List<WorkbenchAndroidAsset> {
        val file = androidManifestFile(projectId)
        if (!file.exists()) return emptyList()
        return runCatching {
            gson.fromJson<List<WorkbenchAndroidAsset>>(
                file.readText(),
                androidAssetListType
            ) ?: emptyList()
        }.getOrElse { emptyList() }
    }

    /**
     * Writes the Android asset manifest for a project.
     *
     * @param projectId Project whose Android manifest should be replaced.
     * @param assets Full desired asset list; callers pass the existing list plus a new record.
     */
    private fun writeAndroidAssets(projectId: String, assets: List<WorkbenchAndroidAsset>) {
        val file = androidManifestFile(projectId)
        file.parentFile?.mkdirs()
        file.writeText(gson.toJson(assets.sortedBy { it.importedAt }))
    }

    /**
     * Reads imported OSS/GitHub source assets for a project.
     *
     * @param projectId Project whose `source/manifest.json` should be parsed.
     * @return Previously imported source records, or an empty list when absent.
     */
    private fun readOssSources(projectId: String): List<WorkbenchOssSourceAsset> {
        val file = ossManifestFile(projectId)
        if (!file.exists()) return emptyList()
        return runCatching {
            gson.fromJson<List<WorkbenchOssSourceAsset>>(
                file.readText(),
                ossSourceAssetListType
            ) ?: emptyList()
        }.getOrElse { emptyList() }
    }

    /**
     * Writes the OSS source manifest for a project.
     *
     * @param projectId Project whose source manifest should be replaced.
     * @param sources Full desired source list; callers pass existing records plus a new import.
     */
    private fun writeOssSources(projectId: String, sources: List<WorkbenchOssSourceAsset>) {
        val file = ossManifestFile(projectId)
        file.parentFile?.mkdirs()
        file.writeText(gson.toJson(sources.sortedBy { it.importedAt }))
    }

    /**
     * Reads recent Project progress rows from the append-only progress log.
     *
     * @param projectId Project whose `logs/project_progress.jsonl` should be read.
     * @param limit Maximum number of newest rows to return.
     * @return JSON-safe progress event maps ordered oldest to newest within the returned window.
     */
    private fun readProjectProgress(projectId: String, limit: Int): List<Map<String, Any?>> {
        val file = projectProgressFile(projectId)
        if (!file.exists()) return emptyList()
        return file.readLines()
            .takeLast(limit)
            .mapNotNull { line ->
                runCatching {
                    gson.fromJson<Map<String, Any?>>(line, mapType)
                }.getOrNull()
            }
    }

    /**
     * Reads the most recent progress row for a Project.
     *
     * @param projectId Project whose progress status should be summarized.
     * @return Latest progress row, or null when the Project predates progress logging.
     */
    private fun lastProjectProgress(projectId: String): Map<String, Any?>? {
        return readProjectProgress(projectId, 1).lastOrNull()
    }

    /**
     * Resolves UI/AI source paths into an Android filesystem path.
     *
     * @param rawPath Either an Android absolute path or a `/workspace/...` path from the Alpine
     * shell view of the same workspace.
     * @return Canonical Android file object used for existence checks and copying.
     */
    private fun resolveSourcePath(rawPath: String): File {
        val trimmed = rawPath.trim()
        require(trimmed.isNotEmpty()) { "Android source path is required." }
        val shellRoot = AgentWorkspaceManager.SHELL_ROOT_PATH
        val file = if (trimmed == shellRoot || trimmed.startsWith("$shellRoot/")) {
            val suffix = trimmed.removePrefix(shellRoot).trimStart('/')
            File(workspaceRoot, suffix)
        } else {
            File(trimmed)
        }
        return file.canonicalFile
    }

    /**
     * Determines whether an import source should be treated as an APK or Android source project.
     *
     * @param source Existing file or directory.
     * @param explicitKind Optional user-provided kind from AI/UI.
     * @return `apk` for APK files or `android_project` for directories.
     */
    private fun resolveAndroidSourceKind(source: File, explicitKind: String?): String {
        val normalized = explicitKind?.trim()?.lowercase().orEmpty()
        val inferred = when {
            source.isDirectory -> WORKBENCH_ANDROID_PROJECT_KIND
            source.name.lowercase().endsWith(".apk") -> WORKBENCH_ANDROID_APK_KIND
            else -> ""
        }
        val kind = normalized.ifEmpty { inferred }
        require(kind == WORKBENCH_ANDROID_APK_KIND || kind == WORKBENCH_ANDROID_PROJECT_KIND) {
            "Unsupported Android source kind: ${explicitKind ?: source.name}"
        }
        require(kind != WORKBENCH_ANDROID_APK_KIND || source.isFile) {
            "APK import requires a file source."
        }
        require(kind != WORKBENCH_ANDROID_PROJECT_KIND || source.isDirectory) {
            "Android project import requires a directory source."
        }
        return kind
    }

    /**
     * Determines whether an OSS import is a local source snapshot or a remote repo URL.
     *
     * @param source Optional existing local source path.
     * @param sourceUrl Optional GitHub/git URL recorded for later terminal fetch.
     * @param explicitKind Optional user-provided kind from AI/UI.
     * @return One of `oss_repo`, `github_repo`, or `local_source`.
     */
    private fun resolveOssSourceKind(
        source: File?,
        sourceUrl: String?,
        explicitKind: String?
    ): String {
        val normalized = explicitKind?.trim()?.lowercase().orEmpty()
        val inferred = when {
            source != null -> WORKBENCH_OSS_LOCAL_KIND
            sourceUrl?.contains("github.com", ignoreCase = true) == true ->
                WORKBENCH_OSS_GITHUB_KIND
            else -> WORKBENCH_OSS_REPOSITORY_KIND
        }
        val kind = normalized.ifEmpty { inferred }
        require(
            kind == WORKBENCH_OSS_REPOSITORY_KIND ||
                kind == WORKBENCH_OSS_GITHUB_KIND ||
                kind == WORKBENCH_OSS_LOCAL_KIND
        ) { "Unsupported OSS source kind: ${explicitKind ?: sourceUrl ?: source?.name}" }
        return kind
    }

    /**
     * Copies an Android project source tree into the Project while skipping generated cache dirs.
     *
     * @param source Existing Android project directory.
     * @param target Destination directory under the Workbench Project asset.
     * @param includedFiles Mutable audit list that receives target-relative copied files.
     */
    private fun copyAndroidProjectTree(
        source: File,
        target: File,
        includedFiles: MutableList<String>
    ) {
        if (target.exists()) {
            target.deleteRecursively()
        }
        target.mkdirs()
        val excludedDirNames = setOf(".git", ".gradle", ".idea", "build", "node_modules")
        source.walkTopDown()
            .onEnter { file ->
                file == source || !excludedDirNames.contains(file.name)
            }
            .filter { it.isFile }
            .forEach { file ->
                val relative = source.toPath().relativize(file.toPath()).toString()
                    .replace(File.separatorChar, '/')
                if (relative.isBlank() || relative.contains("..")) return@forEach
                val output = File(target, relative)
                output.parentFile?.mkdirs()
                file.copyTo(output, overwrite = true)
                includedFiles.add(relative)
        }
    }

    /**
     * Copies an OSS source tree into the Project while skipping generated and dependency folders.
     *
     * @param source Existing local source directory.
     * @param target Destination directory under the Workbench Project source asset.
     * @param includedFiles Mutable audit list that receives target-relative copied files.
     */
    private fun copyOssSourceTree(
        source: File,
        target: File,
        includedFiles: MutableList<String>
    ) {
        if (target.exists()) {
            target.deleteRecursively()
        }
        target.mkdirs()
        val excludedDirNames = setOf(
            ".git",
            ".gradle",
            ".idea",
            ".dart_tool",
            ".venv",
            "__pycache__",
            "build",
            "dist",
            "node_modules",
            "target",
            ".next",
            ".turbo"
        )
        source.walkTopDown()
            .onEnter { file ->
                file == source || !excludedDirNames.contains(file.name)
            }
            .filter { it.isFile }
            .forEach { file ->
                val relative = source.toPath().relativize(file.toPath()).toString()
                    .replace(File.separatorChar, '/')
                if (relative.isBlank() || relative.contains("..")) return@forEach
                val output = File(target, relative)
                output.parentFile?.mkdirs()
                file.copyTo(output, overwrite = true)
                includedFiles.add(relative)
            }
    }

    /**
     * Detects package managers, frameworks, and likely entrypoints from a copied OSS source tree.
     *
     * @param root Directory that contains the copied source snapshot.
     * @return JSON-safe analysis payload used by Project runtime metadata and Agent planning.
     */
    private fun analyzeOssSourceTree(root: File): Map<String, Any?> {
        val packageFiles = mutableListOf<Map<String, Any?>>()
        val entrypoints = mutableListOf<Map<String, Any?>>()
        val stacks = linkedSetOf<String>()
        if (!root.exists()) {
            return linkedMapOf(
                "detectedStack" to emptyList<String>(),
                "packageFiles" to packageFiles,
                "entrypoints" to entrypoints
            )
        }
        val interestingNames = setOf(
            "package.json",
            "pnpm-lock.yaml",
            "yarn.lock",
            "package-lock.json",
            "pyproject.toml",
            "requirements.txt",
            "setup.py",
            "Pipfile",
            "pubspec.yaml",
            "build.gradle",
            "build.gradle.kts",
            "settings.gradle",
            "settings.gradle.kts",
            "Cargo.toml",
            "go.mod",
            "pom.xml"
        )
        root.walkTopDown()
            .onEnter { file ->
                file == root || file.name !in setOf(".git", "node_modules", "build", "target", ".dart_tool")
            }
            .filter { it.isFile && it.name in interestingNames }
            .take(80)
            .forEach { file ->
                val relative = root.toPath().relativize(file.toPath()).toString()
                    .replace(File.separatorChar, '/')
                val stack = stackForPackageFile(file.name, file.readTextSafely())
                stacks.addAll(stack)
                packageFiles.add(
                    linkedMapOf(
                        "path" to relative,
                        "name" to file.name,
                        "stack" to stack,
                        "sizeBytes" to file.length()
                    )
                )
                entrypoints.addAll(entrypointsForPackageFile(relative, file.name, file.readTextSafely()))
            }
        if (File(root, "app/src/main/AndroidManifest.xml").exists()) {
            stacks.add("android")
            entrypoints.add(
                linkedMapOf(
                    "kind" to "android_manifest",
                    "path" to "app/src/main/AndroidManifest.xml",
                    "command" to "./gradlew :app:assembleDebug"
                )
            )
        }
        if (File(root, "lib/main.dart").exists()) {
            stacks.add("flutter")
            entrypoints.add(
                linkedMapOf(
                    "kind" to "flutter_app",
                    "path" to "lib/main.dart",
                    "command" to "flutter run"
                )
            )
        }
        return linkedMapOf(
            "detectedStack" to stacks.toList(),
            "packageFiles" to packageFiles,
            "entrypoints" to entrypoints.distinctBy { "${it["kind"]}:${it["path"]}:${it["command"]}" }
        )
    }

    /**
     * Infers stack labels from one package or build file.
     *
     * @param fileName Package/build file name.
     * @param content Small file body used for framework hints such as React or Vite.
     * @return Stack labels that guide future Project API binding.
     */
    private fun stackForPackageFile(fileName: String, content: String): List<String> {
        val lower = content.lowercase()
        return when (fileName) {
            "package.json" -> buildList {
                add("node")
                if (lower.contains("\"react\"")) add("react")
                if (lower.contains("\"vite\"")) add("vite")
                if (lower.contains("\"next\"")) add("nextjs")
            }
            "pyproject.toml", "requirements.txt", "setup.py", "Pipfile" -> listOf("python")
            "pubspec.yaml" -> if (lower.contains("flutter:")) listOf("flutter", "dart") else listOf("dart")
            "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts" ->
                listOf("android", "gradle")
            "Cargo.toml" -> listOf("rust")
            "go.mod" -> listOf("go")
            "pom.xml" -> listOf("java", "maven")
            else -> emptyList()
        }
    }

    /**
     * Extracts likely local run/build commands from one package or build file.
     *
     * @param relativePath Path relative to the copied source root.
     * @param fileName Package/build file name.
     * @param content Small file body used for package script detection.
     * @return Candidate entrypoints for future executor binding.
     */
    private fun entrypointsForPackageFile(
        relativePath: String,
        fileName: String,
        content: String
    ): List<Map<String, Any?>> {
        val dir = relativePath.substringBeforeLast('/', "")
        val prefix = dir.takeIf { it.isNotEmpty() }?.let { "cd $it && " }.orEmpty()
        return when (fileName) {
            "package.json" -> {
                val entries = mutableListOf<Map<String, Any?>>()
                Regex("\"(dev|start|build|test)\"\\s*:")
                    .findAll(content)
                    .map { it.groupValues[1] }
                    .distinct()
                    .forEach { script ->
                        entries.add(
                            linkedMapOf(
                                "kind" to "npm_script",
                                "path" to relativePath,
                                "name" to script,
                                "command" to "${prefix}npm run $script"
                            )
                        )
                    }
                entries
            }
            "pyproject.toml" -> listOf(
                linkedMapOf(
                    "kind" to "python_project",
                    "path" to relativePath,
                    "command" to "${prefix}python -m pytest"
                )
            )
            "requirements.txt" -> listOf(
                linkedMapOf(
                    "kind" to "python_requirements",
                    "path" to relativePath,
                    "command" to "${prefix}python -m pytest"
                )
            )
            "pubspec.yaml" -> listOf(
                linkedMapOf(
                    "kind" to "flutter_or_dart",
                    "path" to relativePath,
                    "command" to "${prefix}flutter test"
                )
            )
            "build.gradle", "build.gradle.kts" -> listOf(
                linkedMapOf(
                    "kind" to "gradle",
                    "path" to relativePath,
                    "command" to "${prefix}./gradlew test"
                )
            )
            "Cargo.toml" -> listOf(
                linkedMapOf(
                    "kind" to "cargo",
                    "path" to relativePath,
                    "command" to "${prefix}cargo test"
                )
            )
            "go.mod" -> listOf(
                linkedMapOf(
                    "kind" to "go",
                    "path" to relativePath,
                    "command" to "${prefix}go test ./..."
                )
            )
            else -> emptyList()
        }
    }

    private fun File.readTextSafely(): String {
        return runCatching {
            if (length() > 256_000) "" else readText()
        }.getOrDefault("")
    }

    /**
     * Reads basic APK package metadata when Android PackageManager is available.
     *
     * @param apkFile Copied APK file inside the Project asset directory.
     * @return Package name and version metadata, or an empty map in unit tests/non-APK cases.
     */
    private fun readApkInfo(apkFile: File): Map<String, Any?> {
        val context = appContext ?: return emptyMap()
        val info = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageArchiveInfo(
                    apkFile.absolutePath,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
            }
        }.getOrNull() ?: return emptyMap()
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        return linkedMapOf(
            "packageName" to info.packageName,
            "versionName" to info.versionName,
            "versionCode" to versionCode
        )
    }

    /**
     * Creates a unique, filesystem-safe Android asset id.
     *
     * @param projectId Project namespace used to avoid collisions with existing imports.
     * @param baseName User display name or source file name.
     * @param timestamp ISO timestamp used as a stable suffix for this import.
     * @return Safe id such as `sample-apk-2026-05-09t...`.
     */
    private fun uniqueAndroidAssetId(
        projectId: String,
        baseName: String,
        timestamp: String
    ): String {
        val base = sanitizeAssetId(baseName.substringBeforeLast('.'))
        val suffix = timestamp.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').lowercase()
        var candidate = "$base-$suffix".trim('-')
        var index = 2
        while (File(projectDir(projectId), "android/apps/$candidate").exists()) {
            candidate = "$base-$suffix-$index"
            index += 1
        }
        return candidate
    }

    /**
     * Creates a unique, filesystem-safe OSS source id.
     *
     * @param projectId Project namespace used to avoid collisions with existing imports.
     * @param baseName User display name, repo name, or source directory name.
     * @param timestamp ISO timestamp used as a stable suffix for this import.
     * @return Safe id such as `demo-repo-2026-05-10t...`.
     */
    private fun uniqueOssSourceId(
        projectId: String,
        baseName: String,
        timestamp: String
    ): String {
        val base = sanitizeAssetId(baseName.substringBeforeLast('.'))
        val suffix = timestamp.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').lowercase()
        var candidate = "$base-$suffix".trim('-')
        var index = 2
        while (File(projectDir(projectId), "source/repos/$candidate").exists()) {
            candidate = "$base-$suffix-$index"
            index += 1
        }
        return candidate
    }

    /**
     * Normalizes a user or file supplied name for Android asset directories.
     *
     * @param value Raw display name or source file name.
     * @return Lowercase id fragment that cannot traverse directories.
     */
    private fun sanitizeAssetId(value: String): String {
        return value.trim()
            .lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-', '.', '_')
            .ifBlank { "android-asset" }
            .take(80)
    }

    /**
     * Computes a recursive byte size for a copied asset.
     *
     * @param file APK file or copied Android project directory.
     * @return Total bytes of regular files under the asset entry.
     */
    private fun fileSizeBytes(file: File): Long {
        return if (file.isFile) {
            file.length()
        } else {
            file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }
    }

    /**
     * Converts a project-local Android file path into the `/workspace` shell view.
     *
     * @param projectId Project directory id.
     * @param file Android filesystem file inside the Project directory.
     * @return Shell path that AI terminal tools can reuse.
     */
    private fun shellPathForProjectFile(projectId: String, file: File): String {
        val projectRoot = projectDir(projectId)
        val relative = projectRoot.toPath().relativize(file.toPath()).toString()
            .replace(File.separatorChar, '/')
        return "${AgentWorkspaceManager.SHELL_ROOT_PATH}/projects/$projectId/$relative"
    }

    private fun defaultProjectId(templateId: String, config: Map<String, Any?>): String {
        return when (templateId) {
            WORKBENCH_SCHEMA_TEMPLATE_ID -> "oob-workbench-${schemaApiNamespace(config)}"
            WORKBENCH_QUICK_CAPTURE_TEMPLATE_ID -> WORKBENCH_QUICK_CAPTURE_PROJECT_ID
            else -> WORKBENCH_DEFAULT_PROJECT_ID
        }
    }

    private fun defaultProjectName(templateId: String, config: Map<String, Any?>): String {
        return when (templateId) {
            WORKBENCH_SCHEMA_TEMPLATE_ID -> "${schemaEntityName(config, "Project", null)} Workbench"
            WORKBENCH_QUICK_CAPTURE_TEMPLATE_ID -> "随手记 Inbox"
            else -> "Todo Log Workbench"
        }
    }

    private fun routeForTemplate(projectId: String, templateId: String): String {
        return when (templateId) {
            WORKBENCH_SCHEMA_TEMPLATE_ID -> "/workbench/schema_app?projectId=$projectId"
            WORKBENCH_QUICK_CAPTURE_TEMPLATE_ID -> "/workbench/quick_capture?projectId=$projectId"
            else -> "/workbench/todo_log?projectId=$projectId"
        }
    }

    /**
     * Resolves a human-facing entity name for generic schema Projects.
     *
     * @param config Project creation config supplied by UI or Agent.
     * @param fallback Value used when no explicit entity can be found.
     * @param prompt Optional natural language prompt used for simple domain inference.
     * @return Display entity label stored in frontend/backend specs.
     */
    private fun schemaEntityName(
        config: Map<String, Any?>,
        fallback: String,
        prompt: String?
    ): String {
        val explicit = config["entityName"]?.toString()?.trim()
            ?: config["entity"]?.toString()?.trim()
            ?: config["itemName"]?.toString()?.trim()
        if (!explicit.isNullOrEmpty()) return explicit.take(40)
        val hint = "${prompt.orEmpty()} ${config["prompt"]?.toString().orEmpty()} ${config["name"]?.toString().orEmpty()}"
        return when {
            hint.contains("笔记", ignoreCase = true) || hint.contains("note", ignoreCase = true) ->
                "Note"
            hint.contains("开销", ignoreCase = true) || hint.contains("expense", ignoreCase = true) ->
                "Expense"
            hint.contains("习惯", ignoreCase = true) || hint.contains("habit", ignoreCase = true) ->
                "Habit"
            hint.contains("客户", ignoreCase = true) || hint.contains("customer", ignoreCase = true) ->
                "Customer"
            else -> fallback.ifBlank { "Item" }.take(40)
        }
    }

    private fun schemaApiNamespace(config: Map<String, Any?>): String {
        val raw = config["apiNamespace"]?.toString()?.trim()
            ?: config["entityName"]?.toString()?.trim()
            ?: config["entity"]?.toString()?.trim()
            ?: config["name"]?.toString()?.trim()
            ?: "item"
        return sanitizeApiSegment(raw)
    }

    private fun schemaShortName(entityName: String): String {
        val ascii = sanitizeApiSegment(entityName).uppercase().take(5)
        return ascii.ifBlank { "APP" }
    }

    private fun sanitizeApiSegment(value: String): String {
        return value.trim()
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "item" }
            .take(48)
    }

    private fun todosFile(projectId: String): File {
        return File(projectDir(projectId), "data/todos.json")
    }

    private fun schemaItemsFile(projectId: String): File {
        return File(projectDir(projectId), "data/items.json")
    }

    private fun quickCaptureItemsFile(projectId: String): File {
        return File(projectDir(projectId), "data/items.json")
    }

    private fun androidManifestFile(projectId: String): File {
        return File(projectDir(projectId), "android/manifest.json")
    }

    private fun ossManifestFile(projectId: String): File {
        return File(projectDir(projectId), "source/manifest.json")
    }

    private fun projectProgressFile(projectId: String): File {
        return File(projectDir(projectId), "logs/project_progress.jsonl")
    }

    private fun projectDir(projectId: String): File {
        return File(projectsRoot, sanitizeProjectId(projectId))
    }

    private fun sanitizeProjectId(projectId: String): String {
        val sanitized = projectId.trim()
            .lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-', '.', '_')
        require(sanitized.isNotEmpty()) { "Workbench projectId is required." }
        require(!sanitized.contains("..")) { "Workbench projectId cannot contain path traversal." }
        return sanitized
    }

    private fun nowIso(): String = Instant.now().toString()
}
