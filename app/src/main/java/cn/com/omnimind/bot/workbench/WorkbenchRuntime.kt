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
const val WORKBENCH_DEFAULT_PROJECT_ID = "oob-workbench-todo-log"
const val WORKBENCH_TODO_ADD_TOOL_ID = "todo.add"
const val WORKBENCH_TODO_FINISH_TOOL_ID = "todo.finish"
const val WORKBENCH_HOT_UPDATE_CALLER = "xiaowan_hot_update"
const val WORKBENCH_ANDROID_APK_KIND = "apk"
const val WORKBENCH_ANDROID_PROJECT_KIND = "android_project"

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
    private val androidAssetListType =
        object : TypeToken<List<WorkbenchAndroidAsset>>() {}.type
    private val mapType = object : TypeToken<Map<String, Any?>>() {}.type

    private val projectsRoot: File
        get() = File(workspaceRoot, "projects")
    private val registryFile: File
        get() = File(projectsRoot, "registry.json")
    private val apiRegistryFile: File
        get() = File(projectsRoot, "api_registry.json")
    private val exportsRoot: File
        get() = File(projectsRoot, "exports")

    /**
     * Creates a Workbench project from a template config, or returns an existing project unchanged.
     *
     * @param config Project creation config from AI tools or Flutter. v1 supports `templateId=todo_log_demo`.
     * @return Full project payload including registered business APIs and persisted state.
     */
    @Synchronized
    fun createProject(config: Map<String, Any?>): Map<String, Any?> {
        val templateId = config["templateId"]?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: WORKBENCH_TODO_TEMPLATE_ID
        require(templateId == WORKBENCH_TODO_TEMPLATE_ID) {
            "Unsupported workbench template: $templateId"
        }
        val projectId = sanitizeProjectId(
            config["projectId"]?.toString()?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: WORKBENCH_DEFAULT_PROJECT_ID
        )
        val name = config["name"]?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: config["displayName"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: "Todo Log Workbench"
        val sourcePrompt = config["prompt"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val now = nowIso()
        val existing = readProjectRegistry().firstOrNull { it.projectId == projectId }
        val apis = todoTemplateApis(projectId)
        val record = existing
            ?: WorkbenchProjectRecord(
                projectId = projectId,
                name = name,
                templateId = templateId,
                route = "/workbench/todo_log?projectId=$projectId",
                spacePath = "${AgentWorkspaceManager.SHELL_ROOT_PATH}/projects/$projectId",
                apiIds = apis.map { it.apiId },
                createdAt = now,
                updatedAt = now
            )
        if (existing == null) {
            writeProjectRegistry(readProjectRegistry() + record)
            writeApiRegistry(readApiRegistry() + apis)
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
        }
        val projectDir = projectDir(projectId)
        File(projectDir, "data").mkdirs()
        File(projectDir, "logs").mkdirs()
        val todoFile = todosFile(projectId)
        if (!todoFile.exists()) {
            writeTodos(projectId, initialTodos(config))
        }
        val creationPrompt = if (existing == null) sourcePrompt else null
        ensureProjectSourceFiles(record, apis, creationPrompt)
        writeProjectJson(record, apis, readTodos(projectId), creationPrompt)
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
            else -> apiError(api, "UNKNOWN_API", "Unknown workbench API: ${api.toolId}")
        }
        appendApiCall(projectId, api, inputs, caller, result)
        writeProjectJson(findProject(projectId), projectApis(projectId), readTodos(projectId))
        return result + ("project" to getProject(projectId))
    }

    /**
     * Applies a prompt-driven hot update to a registered Workbench project.
     *
     * @param projectId Project whose native display should refresh after the update.
     * @param prompt User request captured from the Xiaowan floating assistant; it is stored in
     * `logs/hot_updates.jsonl` and interpreted by the current project template.
     * @param caller Caller label such as `ui` or `ai`, persisted for audit and future replay.
     * @return Hot-update result plus the refreshed project payload.
     */
    @Synchronized
    fun hotUpdateProject(
        projectId: String,
        prompt: String,
        caller: String
    ): Map<String, Any?> {
        val record = findProject(projectId)
        val request = prompt.trim()
        require(request.isNotEmpty()) { "Hot update prompt is required." }
        require(record.templateId == WORKBENCH_TODO_TEMPLATE_ID) {
            "Unsupported workbench hot update template: ${record.templateId}"
        }
        val appliedActions = mutableListOf<Map<String, Any?>>()
        val lower = request.lowercase()
        val wantsFinish =
            listOf("归档", "完成", "finish", "archive", "done").any { lower.contains(it) }
        val wantsAdd =
            listOf("增加", "新增", "添加", "add", "create").any { lower.contains(it) }

        if (wantsFinish) {
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

        if (wantsAdd || !wantsFinish) {
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

        appendHotUpdate(record.projectId, request, caller, appliedActions)
        writeProjectJson(record, projectApis(record.projectId), readTodos(record.projectId))
        return linkedMapOf(
            "success" to true,
            "projectId" to record.projectId,
            "prompt" to request,
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
        writeProjectJson(record, projectApis(record.projectId), readTodos(record.projectId))
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
        val todos = readTodos(record.projectId)
        val counts = apiExecutionCounts(record.projectId)
        val androidAssets = readAndroidAssets(record.projectId)
        return linkedMapOf(
            "projectId" to record.projectId,
            "name" to record.name,
            "templateId" to record.templateId,
            "route" to record.route,
            "spacePath" to record.spacePath,
            "pageIds" to listOf("todo-log-page"),
            "displays" to workbenchDisplays(record),
            "apiIds" to record.apiIds,
            "tools" to apis.map { it.toPayload(counts[it.apiId] ?: 0) },
            "apis" to apis.map { it.toPayload(counts[it.apiId] ?: 0) },
            "androidAssets" to androidAssets.map { it.toPayload() },
            "flows" to emptyList<Map<String, Any?>>(),
            "todos" to todos.map { it.toPayload() },
            "createdAt" to record.createdAt,
            "updatedAt" to record.updatedAt
        )
    }

    private fun writeProjectJson(
        record: WorkbenchProjectRecord,
        apis: List<WorkbenchApiRecord>,
        todos: List<WorkbenchTodoRecord>,
        sourcePrompt: String? = null
    ) {
        val counts = apiExecutionCounts(record.projectId)
        val androidAssets = readAndroidAssets(record.projectId)
        val prompt = sourcePrompt?.trim()?.takeIf { it.isNotEmpty() }
            ?: readProjectSourcePrompt(record.projectId)
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
                "pageId" to "todo-log-page",
                "renderer" to "oob_native_schema",
                "route" to record.route
            ),
            "displays" to workbenchDisplays(record),
            "apis" to apis.map { it.toPayload(counts[it.apiId] ?: 0) },
            "android" to linkedMapOf(
                "manifest" to "android/manifest.json",
                "assets" to androidAssets.map { it.toPayload() }
            ),
            "state" to linkedMapOf(
                "todoCount" to todos.size,
                "openTodoCount" to todos.count { it.status != "finished" },
                "finishedTodoCount" to todos.count { it.status == "finished" },
                "androidAssetCount" to androidAssets.size
            )
        )
        val file = File(projectDir(record.projectId), "project.json")
        file.parentFile?.mkdirs()
        file.writeText(gson.toJson(payload))
    }

    /**
     * Builds the native display registry exposed by one Workbench Project.
     *
     * @param record Project registry record that owns the display route and stable project id.
     * @return Display payloads shown by Flutter as Project-scoped frontends, not business APIs.
     */
    private fun workbenchDisplays(record: WorkbenchProjectRecord): List<Map<String, Any?>> {
        return listOf(
            linkedMapOf(
                "id" to "todo-log-display",
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

    /**
     * Creates the editable source-spec files that make a project understandable outside memory.
     *
     * @param record Stable project registry record used for route, workspace path, and display name.
     * @param apis Business API records owned by this project. Control APIs are intentionally excluded.
     */
    private fun ensureProjectSourceFiles(
        record: WorkbenchProjectRecord,
        apis: List<WorkbenchApiRecord>,
        sourcePrompt: String? = null
    ) {
        val projectDir = projectDir(record.projectId)
        projectDir.mkdirs()
        val readme = File(projectDir, "README.md")
        if (!readme.exists()) {
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
                |The frontend is rendered by OOB's native Flutter Display. This demo stores the
                |editable page contract in `frontend/page_spec.json`; the current template host code
                |lives in OOB and binds controls to Project APIs through `workbenchApiCall`.
                |
                |## Backend
                |Business APIs are declared in `backend/api_spec.json`. The current demo executor is
                |the native Workbench runtime; future projects may replace `executorKind` with a
                |workspace script or provider executor without changing the frontend call path.
                |
                |## Data
                |- `data/todos.json`: persistent todo state
                |- `logs/api_calls.jsonl`: append-only AI/UI API call log
                |- `logs/hot_updates.jsonl`: append-only Xiaowan hot update requests
                |- `android/manifest.json`: imported Android APK/project assets
                |- `logs/android_ingest.jsonl`: append-only Android asset import log
                |
                """.trimMargin()
            )
        }

        val frontendDir = File(projectDir, "frontend")
        val backendDir = File(projectDir, "backend")
        frontendDir.mkdirs()
        backendDir.mkdirs()
        val pageSpec = File(frontendDir, "page_spec.json")
        if (!pageSpec.exists()) {
            pageSpec.writeText(
                gson.toJson(
                    linkedMapOf<String, Any?>(
                        "pageId" to "todo-log-page",
                        "displayId" to "todo-log-display",
                        "title" to "Todo 日志",
                        "shortName" to "TODO",
                        "renderer" to "oob_native_flutter_display",
                        "route" to record.route,
                        "sourcePrompt" to sourcePrompt,
                        "decomposition" to listOf(
                            "Prompt requirement -> Project registry",
                            "Frontend display -> OOB native Flutter route",
                            "Controls -> Project API calls",
                            "State -> project data and logs"
                        ),
                        "state" to linkedMapOf(
                            "todos" to "data/todos.json"
                        ),
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
                )
            )
        }
        val apiSpec = File(backendDir, "api_spec.json")
        if (!apiSpec.exists()) {
            apiSpec.writeText(
                gson.toJson(
                    linkedMapOf<String, Any?>(
                        "executorBoundary" to "oob_native_workbench",
                        "sourcePrompt" to sourcePrompt,
                        "promptDecomposition" to linkedMapOf(
                            "addTodo" to WORKBENCH_TODO_ADD_TOOL_ID,
                            "archiveTodo" to WORKBENCH_TODO_FINISH_TOOL_ID,
                            "frontend" to "frontend/page_spec.json",
                            "data" to "data/todos.json",
                            "logs" to "logs/api_calls.jsonl",
                            "hotUpdates" to "logs/hot_updates.jsonl"
                        ),
                        "apis" to apis.map { api ->
                            linkedMapOf(
                                "apiId" to api.apiId,
                                "displayName" to api.displayName,
                                "description" to api.description,
                                "inputSchema" to api.inputSchema,
                                "outputSchema" to api.outputSchema,
                                "executorKind" to api.executorKind,
                                "persistence" to listOf("data/todos.json", "logs/api_calls.jsonl"),
                                "frontendBinding" to "frontend/page_spec.json",
                                "aiUsage" to "Call through workbench_api_call with this projectId."
                            )
                        }
                    )
                )
            )
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
     */
    private fun appendHotUpdate(
        projectId: String,
        prompt: String,
        caller: String,
        appliedActions: List<Map<String, Any?>>
    ) {
        val file = File(projectDir(projectId), "logs/hot_updates.jsonl")
        file.parentFile?.mkdirs()
        val row = linkedMapOf<String, Any?>(
            "timestamp" to nowIso(),
            "projectId" to projectId,
            "caller" to caller.ifBlank { "unknown" },
            "prompt" to prompt,
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

    private fun todosFile(projectId: String): File {
        return File(projectDir(projectId), "data/todos.json")
    }

    private fun androidManifestFile(projectId: String): File {
        return File(projectDir(projectId), "android/manifest.json")
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
