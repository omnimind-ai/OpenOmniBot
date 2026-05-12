package cn.com.omnimind.bot.workbench

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.terminal.EmbeddedTerminalRuntime
import cn.com.omnimind.bot.workbench.executor.WorkbenchExecutor
import cn.com.omnimind.bot.workbench.executor.WorkbenchExecutorRegistry
import cn.com.omnimind.bot.webchat.AgentRunService
import cn.com.omnimind.bot.webchat.ConversationDomainService
import cn.com.omnimind.bot.webchat.FlutterChatSyncBridge
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking

const val WORKBENCH_HOT_UPDATE_CALLER = "xiaowan_hot_update"
const val WORKBENCH_ANDROID_APK_KIND = "apk"
const val WORKBENCH_ANDROID_PROJECT_KIND = "android_project"
const val WORKBENCH_OSS_REPOSITORY_KIND = "oss_repo"
const val WORKBENCH_OSS_GITHUB_KIND = "github_repo"
const val WORKBENCH_OSS_LOCAL_KIND = "local_source"
const val WORKBENCH_COLLECTION_EXECUTOR_KIND = "native_project_collection"
const val WORKBENCH_WORKSPACE_PYTHON_EXECUTOR_KIND = "workspace_python_script"
const val WORKBENCH_AGENT_TASK_EXECUTOR_KIND = "agent_task"
const val WORKBENCH_HTML_RENDERER = "html_webview"

data class WorkbenchProjectRecord(
    val projectId: String,
    val name: String,
    val route: String,
    val spacePath: String,
    val apiIds: List<String>,
    val createdAt: String,
    val updatedAt: String
) {
    fun toPayload(): Map<String, Any?> = linkedMapOf(
        "projectId" to projectId,
        "name" to name,
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
    val executorKind: String,
    val run: Map<String, Any?>? = null
) {
    fun toPayload(executionCount: Int = 0): Map<String, Any?> =
        WorkbenchToolboxBuilder.apiContract(this, executionCount)
}

data class WorkbenchProjectItemRecord(
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
 * Stores OOB Workbench projects and their registered Project Tools under the shared workspace.
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
    private val projectItemRecordListType =
        object : TypeToken<List<WorkbenchProjectItemRecord>>() {}.type
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

    // ── Executor registry ────────────────────────────────────────────────────────────────────────
    internal val executorRegistry = WorkbenchExecutorRegistry()

    init {
        executorRegistry.register(WORKBENCH_COLLECTION_EXECUTOR_KIND, object : WorkbenchExecutor {
            override suspend fun execute(projectId: String, api: WorkbenchApiRecord, inputs: Map<String, Any?>) =
                callProjectCollectionApi(projectId, api, inputs)
        })
        executorRegistry.register(WORKBENCH_AGENT_TASK_EXECUTOR_KIND, object : WorkbenchExecutor {
            override suspend fun execute(projectId: String, api: WorkbenchApiRecord, inputs: Map<String, Any?>) =
                callAgentTaskApi(findProject(projectId), api, inputs, "executor_registry")
        })
        executorRegistry.register(WORKBENCH_WORKSPACE_PYTHON_EXECUTOR_KIND, object : WorkbenchExecutor {
            override suspend fun execute(projectId: String, api: WorkbenchApiRecord, inputs: Map<String, Any?>) =
                callWorkspaceScriptApi(projectId, api, inputs)
        })
    }
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Creates a Workbench Project, or returns an existing project unchanged.
     *
     * Workbench is a skill runtime: AI supplies a Project id, Display assets, and Project Tool
     * contracts. Fixed templates are intentionally not part of this API.
     */
    @Synchronized
    fun createProject(config: Map<String, Any?>): Map<String, Any?> {
        val requestedHtmlFiles = normalizeFrontendHtmlFiles(
            config["htmlFiles"] ?: config["frontendHtmlFiles"]
        )
        val projectId = sanitizeProjectId(
            config["projectId"]?.toString()?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: defaultProjectId(config)
        )
        val name = config["name"]?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: config["displayName"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: defaultProjectName(config)
        val sourcePrompt = config["prompt"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val now = nowIso()
        val existing = readProjectRegistry().firstOrNull { it.projectId == projectId }
        val apis = defaultProjectApis(projectId, config)
        val record = existing
            ?: WorkbenchProjectRecord(
                projectId = projectId,
                name = name,
                route = routeForProjectId(projectId),
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
        if (!projectItemsFile(projectId).exists()) {
            writeProjectItems(projectId, initialProjectItems(config))
        }
        val requestedFlutterFiles = normalizeFrontendFlutterFiles(
            config["flutterFiles"] ?: config["frontendFlutterFiles"]
        )
        val creationPrompt = if (existing == null) sourcePrompt else null
        ensureProjectSourceFiles(record, apis, creationPrompt, config)
        val writtenHtmlFiles = if (
            requestedHtmlFiles.isNotEmpty() &&
            (existing == null || readFrontendHtmlPayload(record.projectId).isEmpty())
        ) {
            writeFrontendHtmlFiles(record.projectId, requestedHtmlFiles)
        } else {
            emptyList()
        }
        val writtenFlutterFiles = if (
            requestedFlutterFiles.isNotEmpty() &&
            (existing == null || readFrontendFlutterPayload(record.projectId).isEmpty())
        ) {
            writeFrontendFlutterFiles(record.projectId, requestedFlutterFiles)
        } else {
            emptyList()
        }
        appendProjectProgress(
            projectId = record.projectId,
            stage = "project_workspace_ready",
            status = "running",
            message = "Project frontend/backend source specs and persistence files are ready.",
            percent = 75,
            caller = "workbench",
            details = linkedMapOf(
                "frontend" to "frontend/page_spec.json",
                "backend" to "backend/api_spec.json",
                "htmlFiles" to writtenHtmlFiles.map { it["path"] },
                "flutterFiles" to writtenFlutterFiles.map { it["path"] }
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
     * Updates user-facing Project labels without changing its id, APIs, or data files.
     *
     * @param projectId Stable Project id whose registry record and page spec should be updated.
     * @param name Optional new Project/display title. Blank values keep the existing name.
     * @param shortName Optional compact display label shown in Workbench surfaces.
     * @param description Optional app-language summary stored in the Display contract.
     * @param displays Optional Display pages to merge into `frontend/page_spec.json`.
     * @param apis Optional Project Tools to merge into the internal registry and Tool Contract.
     * @param flutterFiles Optional Project-owned Flutter source files to write under
     * `frontend/flutter/`. Paths must be relative and stay inside that directory.
     * @param htmlFiles Optional Project-owned HTML/CSS/JS/source assets to write under
     * `frontend/html/`. Paths must be relative and stay inside that directory.
     * @param prompt Optional user iteration request written into `logs/hot_updates.jsonl`.
     * @param caller Caller label stored in progress and audit logs.
     * @return Updated Project payload for Flutter, MCP debug, or Agent callers.
     */
    @Synchronized
    fun updateProjectMetadata(
        projectId: String,
        name: String? = null,
        shortName: String? = null,
        description: String? = null,
        displays: Any? = null,
        apis: Any? = null,
        flutterFiles: Any? = null,
        htmlFiles: Any? = null,
        prompt: String? = null,
        caller: String = "unknown"
    ): Map<String, Any?> {
        val record = findProject(projectId)
        val updatedName = name?.trim()?.takeIf { it.isNotEmpty() }
        val updatedShortName = shortName?.trim()?.takeIf { it.isNotEmpty() }
        val updatedDescription = description?.trim()?.takeIf { it.isNotEmpty() }
        val updatePrompt = prompt?.trim()?.takeIf { it.isNotEmpty() }
        val newDisplays = normalizeDisplaySpecs(record, displays)
        val newApis = explicitApiRecords(record.projectId, mapOf("apis" to apis))
        val requestedFlutterFiles = normalizeFrontendFlutterFiles(flutterFiles)
        val requestedHtmlFiles = normalizeFrontendHtmlFiles(htmlFiles)
        require(
            updatedName != null ||
                updatedShortName != null ||
                updatedDescription != null ||
                newDisplays.isNotEmpty() ||
                newApis.isNotEmpty() ||
                requestedFlutterFiles.isNotEmpty() ||
                requestedHtmlFiles.isNotEmpty() ||
                updatePrompt != null
        ) {
            "Project update requires name, shortName, description, displays, apis, flutterFiles, htmlFiles, or prompt."
        }
        val mergedApis = mergeApiRecords(projectApis(record.projectId), newApis)
        val now = nowIso()
        val newApiIds = mergedApis.map { it.apiId }
        val updatedApiIds = if (newApiIds == record.apiIds) record.apiIds else newApiIds
        val updatedRecord = record.copy(
            name = updatedName ?: record.name,
            apiIds = updatedApiIds,
            updatedAt = now
        )
        writeProjectRegistry(
            readProjectRegistry().map { item ->
                if (item.projectId == record.projectId) updatedRecord else item
            }
        )
        if (newApis.isNotEmpty()) {
            writeApiRegistry(
                readApiRegistry().filterNot { it.projectId == record.projectId } + mergedApis
            )
        }
        ensureProjectSourceFiles(updatedRecord, mergedApis)
        val pageSpecFile = File(projectDir(updatedRecord.projectId), "frontend/page_spec.json")
        val pageSpec = if (pageSpecFile.exists()) {
            runCatching {
                gson.fromJson<Map<String, Any?>>(pageSpecFile.readText(), mapType)
            }.getOrNull().orEmpty()
        } else {
            emptyMap()
        }.toMutableMap()
        if (updatedName != null) {
            pageSpec["title"] = updatedName
            pageSpec["displayName"] = updatedName
        }
        if (updatedShortName != null) {
            pageSpec["shortName"] = updatedShortName
        }
        if (updatedDescription != null) {
            pageSpec["description"] = updatedDescription
        }
        val displaysValue = pageSpec["displays"]
        if (displaysValue is List<*>) {
            pageSpec["displays"] = displaysValue.map { item ->
                @Suppress("UNCHECKED_CAST")
                val display = (item as? Map<String, Any?>)?.toMutableMap() ?: return@map item
                if (display["isDefault"] == true || display["id"] == pageSpec["displayId"]) {
                    if (updatedName != null) display["title"] = updatedName
                    if (updatedShortName != null) display["shortName"] = updatedShortName
                    if (updatedDescription != null) display["description"] = updatedDescription
                }
                display
            }
        }
        val displayAdditions = if (
            requestedHtmlFiles.isNotEmpty() &&
            newDisplays.none { isHtmlDisplay(it) }
        ) {
            newDisplays + htmlWorkbenchDisplay(updatedRecord)
        } else {
            newDisplays
        }
        val existingDisplays = pageSpec["displays"] as? List<*>
        val baseDisplays = if (requestedHtmlFiles.isNotEmpty() && existingDisplays == null) {
            emptyList<Map<String, Any?>>()
        } else {
            existingDisplays ?: workbenchDisplays(record)
        }
        val mergedDisplays = normalizeDefaultDisplaySelection(
            mergeDisplaySpecs(
                base = baseDisplays,
                additions = displayAdditions
            ),
            preferredDisplayId = if (requestedHtmlFiles.isNotEmpty()) "html-main-display" else null
        )
        if (mergedDisplays.isNotEmpty()) {
            pageSpec["displays"] = mergedDisplays
            val defaultDisplay = mergedDisplays.firstOrNull { it["isDefault"] == true }
                ?: mergedDisplays.first()
            pageSpec["displayId"] = defaultDisplay["id"] ?: defaultDisplay["displayId"]
            pageSpec["pageId"] = defaultDisplay["pageId"] ?: pageSpec["pageId"]
            pageSpec["navigationScope"] = "right_workbench_display"
        }
        if (newApis.isNotEmpty()) {
            pageSpec["actions"] = mergePageActions(pageSpec["actions"], newApis)
        }
        val writtenFlutterFiles = writeFrontendFlutterFiles(
            projectId = updatedRecord.projectId,
            files = requestedFlutterFiles
        )
        val writtenHtmlFiles = writeFrontendHtmlFiles(
            projectId = updatedRecord.projectId,
            files = requestedHtmlFiles
        )
        pageSpec["iterationContract"] = projectIterationContract()
        if (updatePrompt != null) {
            pageSpec["lastUpdatePrompt"] = updatePrompt
        }
        pageSpecFile.writeText(gson.toJson(pageSpec))
        writeBackendApiSpec(updatedRecord, mergedApis, readProjectSourcePrompt(updatedRecord.projectId))
        if (
            newDisplays.isNotEmpty() ||
            newApis.isNotEmpty() ||
                writtenFlutterFiles.isNotEmpty() ||
                writtenHtmlFiles.isNotEmpty() ||
                updatedDescription != null ||
                updatePrompt != null
        ) {
            val actions = mutableListOf<Map<String, Any?>>()
            if (newDisplays.isNotEmpty()) {
                actions += linkedMapOf(
                    "kind" to "display_contract_extended",
                    "displayIds" to newDisplays.map { it["id"] ?: it["displayId"] ?: it["pageId"] }
                )
            }
            if (newApis.isNotEmpty()) {
                actions += linkedMapOf(
                    "kind" to "business_api_extended",
                    "apiIds" to newApis.map { it.apiId }
                )
            }
            if (writtenFlutterFiles.isNotEmpty()) {
                actions += linkedMapOf(
                    "kind" to "flutter_source_updated",
                    "paths" to writtenFlutterFiles.map { it["path"] }
                )
            }
            if (writtenHtmlFiles.isNotEmpty()) {
                actions += linkedMapOf(
                    "kind" to "html_source_updated",
                    "paths" to writtenHtmlFiles.map { it["path"] }
                )
            }
            if (updatedDescription != null) {
                actions += linkedMapOf("kind" to "description_updated")
            }
            appendHotUpdate(
                projectId = updatedRecord.projectId,
                prompt = updatePrompt ?: "Project contract updated.",
                caller = caller,
                appliedActions = actions,
                frontendContext = linkedMapOf(
                    "source" to "workbench_project_update",
                    "displayIds" to mergedDisplays.map { it["id"] ?: it["displayId"] ?: it["pageId"] },
                    "apiIds" to mergedApis.map { it.apiId },
                    "htmlFiles" to writtenHtmlFiles.map { it["path"] },
                    "flutterFiles" to writtenFlutterFiles.map { it["path"] }
                )
            )
            appendProjectProgress(
                projectId = updatedRecord.projectId,
                stage = "project_contract_updated",
                status = "completed",
                message = "Project displays, Project Tool contract, and source assets updated.",
                percent = 100,
                caller = caller,
                details = linkedMapOf(
                    "displayIds" to mergedDisplays.map { it["id"] ?: it["displayId"] ?: it["pageId"] },
                    "toolIds" to mergedApis.map { it.toolId },
                    "htmlFiles" to writtenHtmlFiles.map { it["path"] },
                    "flutterFiles" to writtenFlutterFiles.map { it["path"] }
                )
            )
        }
        writeProjectJson(updatedRecord, mergedApis)
        if (readActiveProjectId() == updatedRecord.projectId) {
            activeProjectFile.writeText(
                gson.toJson(activeProjectManifest(updatedRecord, readActiveProjectActivatedAt()))
            )
        }
        if (writtenFlutterFiles.isNotEmpty() || writtenHtmlFiles.isNotEmpty()) {
            FlutterChatSyncBridge.dispatchWorkbenchProjectUpdated(
                projectId = updatedRecord.projectId,
                updatedPaths = (writtenHtmlFiles + writtenFlutterFiles).map {
                    it["path"]?.toString() ?: ""
                }
            )
        }
        return linkedMapOf(
            "success" to true,
            "projectId" to updatedRecord.projectId,
            "project" to projectPayload(updatedRecord)
        )
    }

    /**
     * Applies a map-shaped Project update request from MethodChannel, Agent tools, or MCP debug.
     *
     * @param args Update arguments. Supports `name`, `shortName`, `description`, `displays`,
     * `apis`, `flutterFiles`, `htmlFiles`, and `prompt`; unknown fields are ignored for forward
     * compatibility.
     * @param caller Caller label persisted into progress and hot-update logs.
     * @return Updated Project payload.
     */
    @Synchronized
    fun updateProject(args: Map<String, Any?>, caller: String = "unknown"): Map<String, Any?> {
        return updateProjectMetadata(
            projectId = stringConfigArg(args, "projectId"),
            name = args["name"]?.toString(),
            shortName = args["shortName"]?.toString(),
            description = args["description"]?.toString(),
            displays = args["displays"],
            apis = args["apis"],
            flutterFiles = args["flutterFiles"] ?: args["frontendFlutterFiles"],
            htmlFiles = args["htmlFiles"] ?: args["frontendHtmlFiles"],
            prompt = args["prompt"]?.toString(),
            caller = caller
        )
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
        FlutterChatSyncBridge.dispatchWorkbenchProjectUpdated(
            projectId = record.projectId,
            updatedPaths = listOf("active_project.json"),
            reason = "project_activated"
        )
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
        FlutterChatSyncBridge.dispatchWorkbenchProjectUpdated(
            projectId = previousProjectId.orEmpty(),
            updatedPaths = listOf("active_project.json"),
            reason = "project_deactivated"
        )
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
        val tools = projectApis(record.projectId).joinToString("\n") { api ->
            "- ${api.toolId}: ${api.displayName}; inputs=${api.inputSchema.keys}; " +
                "outputs=${api.outputSchema.keys}; executionCount=${counts[api.apiId] ?: 0}"
        }
        val sources = readOssSources(record.projectId).joinToString("\n") { source ->
            "- ${source.sourceId}: ${source.displayName}; stack=${source.detectedStack}; entry=${source.entryPath}"
        }.ifBlank { "- none" }
        val activeItems = readProjectItems(record.projectId).filter { it.status == "active" }
        val itemsSummary = if (activeItems.isEmpty()) {
            "- (no active items)"
        } else {
            activeItems.take(20).joinToString("\n") { item ->
                val fieldStr = if (item.fields.isEmpty()) "" else " | ${item.fields.entries.joinToString(", ") { "${it.key}=${it.value}" }}"
                "- [${item.id}] ${item.title}$fieldStr"
            }.let { text ->
                if (activeItems.size > 20) "$text\n- ... (${activeItems.size - 20} more active items)" else text
            }
        }
        return """
            Active OOB Workbench Project:
            - projectId: ${record.projectId}
            - name: ${record.name}
            - workspace: ${record.spacePath}
            - skill: oob-native-workbench
            - displays:
            $displays
            - project tools:
            $tools
            - current data (${activeItems.size} active items):
            $itemsSummary
            - imported source assets:
            $sources
            Rules: treat these tools as the active Project toolbox. To use them, call `workbench_api_call` with this projectId and the toolId. "current data" above is the live Project state — act on it directly without calling list first. To create, export, delete, open, or hot-update the Project, use the `workbench_project_*` control tools instead of writing registry files.
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
        FlutterChatSyncBridge.dispatchWorkbenchProjectUpdated(
            projectId = record.projectId,
            updatedPaths = listOf("registry.json", "active_project.json"),
            reason = "project_deleted"
        )
        return linkedMapOf(
            "success" to true,
            "projectId" to record.projectId,
            "projectPath" to projectPath.absolutePath,
            "spacePath" to record.spacePath,
            "remainingProjectCount" to remainingProjects.size
        )
    }

    /**
     * Lists registered Project Tools. Control tools such as project creation are intentionally excluded.
     *
     * @param projectId Optional project id filter. Null or blank returns all Project Tools.
     * @return Project Tool entries suitable for AI calls and Flutter Tool List rendering.
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
     * Builds the active Project Toolbox manifest for MCP resources and tool discovery.
     *
     * @return Toolbox manifest for the active Project, or null when no Project is active.
     */
    @Synchronized
    fun activeToolbox(): Map<String, Any?>? {
        val activeProjectId = readActiveProjectId() ?: return null
        val record = readProjectRegistry().firstOrNull { it.projectId == activeProjectId }
            ?: run {
                activeProjectFile.delete()
                return null
            }
        val apis = projectApis(record.projectId)
        return WorkbenchToolboxBuilder.toolboxPayload(record, apis, apiExecutionCounts(record.projectId))
    }

    /**
     * Lists dynamic MCP tools mounted from the active Project's Toolbox.
     *
     * @return MCP `tools/list` descriptors for active Project Tools only.
     */
    @Synchronized
    fun activeMcpTools(): List<Map<String, Any?>> {
        val activeProjectId = readActiveProjectId() ?: return emptyList()
        val record = readProjectRegistry().firstOrNull { it.projectId == activeProjectId }
            ?: run {
                activeProjectFile.delete()
                return emptyList()
            }
        val apis = projectApis(record.projectId)
        return WorkbenchToolboxBuilder.mcpTools(record, apis, apiExecutionCounts(record.projectId))
    }

    /**
     * Calls a Project Tool by its dynamic MCP Toolbox tool name.
     *
     * @param toolName MCP dynamic tool name such as `quick_note.capture_ingest`.
     * @param inputs MCP tool arguments that will be forwarded to the Project Tool executor.
     * @param caller Caller label written to `logs/api_calls.jsonl`; external MCP uses `mcp_toolbox`.
     * @return Project Tool result with the resolved Project/tool identifiers.
     */
    @Synchronized
    fun callMcpTool(
        toolName: String,
        inputs: Map<String, Any?>,
        caller: String = "mcp_toolbox"
    ): Map<String, Any?> {
        val activeProjectId = readActiveProjectId()
            ?: return linkedMapOf(
                "success" to false,
                "toolName" to toolName,
                "errorCode" to "TOOL_NOT_FOUND",
                "errorMessage" to "No active OOB Project Toolbox is mounted."
            )
        val record = readProjectRegistry().firstOrNull { it.projectId == activeProjectId }
            ?: run {
                activeProjectFile.delete()
                return linkedMapOf(
                    "success" to false,
                    "toolName" to toolName,
                    "errorCode" to "TOOL_NOT_FOUND",
                    "errorMessage" to "Active OOB Project is no longer registered."
                )
            }
        val api = projectApis(record.projectId).firstOrNull { candidate ->
            WorkbenchToolboxBuilder.matchesTool(record.projectId, candidate, toolName)
        } ?: return linkedMapOf(
            "success" to false,
            "toolName" to toolName,
            "projectId" to record.projectId,
            "errorCode" to "TOOL_NOT_FOUND",
            "errorMessage" to "Project Toolbox tool not found: $toolName"
        )
        return callApi(
            projectId = record.projectId,
            apiId = api.apiId,
            inputs = inputs,
            caller = caller
        ) + linkedMapOf(
            "toolName" to WorkbenchToolboxBuilder.toolName(record.projectId, api.apiId),
            "projectId" to record.projectId,
            "apiId" to api.apiId
        )
    }

    /**
     * Lists read-only MCP Resources exposed by the Workbench backend.
     *
     * @return MCP `resources/list` descriptors. No arbitrary filesystem paths are exposed.
     */
    @Synchronized
    fun listMcpResources(): List<Map<String, Any?>> {
        val resources = mutableListOf<Map<String, Any?>>(
            mcpResource("oob://projects", "OOB Projects", "Registered Workbench Project summaries."),
            mcpResource("oob://projects/active", "Active OOB Project", "Current active Project and Toolbox.")
        )
        readProjectRegistry().forEach { record ->
            val prefix = "oob://projects/${record.projectId}"
            resources += mcpResource(prefix, record.name, "Project manifest for ${record.projectId}.")
            resources += mcpResource("$prefix/toolbox", "${record.name} Toolbox", "Project business tools mounted as MCP Tools.")
            resources += mcpResource("$prefix/progress", "${record.name} Progress", "Recent Project progress events.")
            resources += mcpResource("$prefix/logs/api_calls", "${record.name} Tool Calls", "Recent Project Tool call log rows.")
            resources += mcpResource("$prefix/source/manifest", "${record.name} Source Manifest", "Imported source asset summary.")
        }
        return resources
    }

    /**
     * Reads a supported MCP Resource URI as JSON text.
     *
     * @param uri Resource URI from `listMcpResources`.
     * @param limit Maximum log/progress rows to return for tail-style resources.
     * @return MCP `resources/read` payload with one JSON content item.
     */
    @Synchronized
    fun readMcpResource(uri: String, limit: Int = 50): Map<String, Any?> {
        val normalized = uri.trim().trimEnd('/')
        val boundedLimit = limit.coerceIn(1, 200)
        val payload: Any? = when {
            normalized == "oob://projects" -> linkedMapOf("projects" to listProjects())
            normalized == "oob://projects/active" -> getActiveProject()
            normalized.startsWith("oob://projects/") -> {
                val suffix = normalized.removePrefix("oob://projects/")
                val parts = suffix.split("/")
                val projectId = parts.firstOrNull()?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("Missing projectId in resource URI: $uri")
                when (parts.drop(1).joinToString("/")) {
                    "" -> getProject(projectId)
                    "toolbox" -> {
                        val record = findProject(projectId)
                        WorkbenchToolboxBuilder.toolboxPayload(
                            record,
                            projectApis(record.projectId),
                            apiExecutionCounts(record.projectId)
                        )
                    }
                    "progress" -> getProjectProgress(projectId, boundedLimit)
                    "logs/api_calls" -> linkedMapOf(
                        "projectId" to projectId,
                        "log" to "logs/api_calls.jsonl",
                        "limit" to boundedLimit,
                        "events" to readApiCallLog(projectId, boundedLimit)
                    )
                    "source/manifest" -> linkedMapOf(
                        "projectId" to projectId,
                        "manifest" to "source/manifest.json",
                        "assets" to readOssSources(projectId).map { it.toPayload() }
                    )
                    else -> throw IllegalArgumentException("Unsupported MCP resource URI: $uri")
                }
            }
            else -> throw IllegalArgumentException("Unsupported MCP resource URI: $uri")
        }
        return linkedMapOf(
            "contents" to listOf(
                linkedMapOf(
                    "uri" to normalized,
                    "mimeType" to "application/json",
                    "text" to gson.toJson(payload)
                )
            )
        )
    }

    /**
     * Calls a registered Project Tool through the native executor.
     *
     * @param projectId Project owning the tool.
     * @param apiId Legacy API id or tool id.
     * @param inputs User or AI supplied tool inputs. Shape is validated by the executor.
     * @param caller Caller label such as `ai` or `ui`, persisted in the tool call log.
     * @return Tool-style result payload plus the refreshed project state.
     */
    @Synchronized
    fun callApi(
        projectId: String,
        apiId: String,
        inputs: Map<String, Any?>,
        caller: String
    ): Map<String, Any?> {
        val record = findProject(projectId)
        val api = projectApis(record.projectId).firstOrNull { candidate ->
            candidate.apiId == apiId.trim() || candidate.toolId == apiId.trim()
        }
        val startedAt = System.currentTimeMillis()
        if (api == null) {
            val missingApi = WorkbenchApiRecord(
                apiId = apiId.trim().ifBlank { "unknown" },
                projectId = record.projectId,
                toolId = apiId.trim().ifBlank { "unknown" },
                displayName = apiId.trim().ifBlank { "Unknown tool" },
                description = "Unregistered Project Tool.",
                inputSchema = emptyMap(),
                outputSchema = emptyMap(),
                executorKind = "unknown"
            )
            val result = apiError(
                missingApi,
                "TOOL_NOT_FOUND",
                "Workbench Project Tool not found: $apiId"
            )
            appendApiCall(record.projectId, missingApi, inputs, caller, result, startedAt)
            writeProjectJson(record, projectApis(record.projectId))
            return result + ("project" to apiResultProjectPayload(record.projectId))
        }
        val validationError = validateApiInputs(api, inputs)
        val result = if (validationError != null) {
            apiError(api, "INVALID_INPUT", validationError)
        } else {
            runCatching {
                val effectiveKind = when {
                    shouldRunAsAgentTask(api) -> WORKBENCH_AGENT_TASK_EXECUTOR_KIND
                    else -> api.executorKind
                }
                runBlocking {
                    executorRegistry.get(effectiveKind)
                        ?.execute(record.projectId, api, inputs)
                        ?: apiError(api, "UNKNOWN_API", "No executor registered for kind: $effectiveKind")
                }
            }.getOrElse { error ->
                apiError(
                    api,
                    "EXECUTOR_ERROR",
                    error.message ?: "Project Tool executor failed."
                )
            }
        }
        appendApiCall(record.projectId, api, inputs, caller, result, startedAt)
        writeProjectJson(record, projectApis(record.projectId))
        val runUse = WorkbenchToolboxBuilder.runUse(api).lowercase()
        val isReadOnly = runUse == "native.collection.list" || runUse == "native.collection.get"
        if (!isReadOnly) {
            val currentItems = readProjectItems(record.projectId).map { it.toPayload() }
            FlutterChatSyncBridge.dispatchWorkbenchProjectUpdated(
                projectId = record.projectId,
                updatedPaths = listOf("data/items.json"),
                reason = "api_call:${api.toolId}",
                items = currentItems
            )
        }
        return result + ("project" to apiResultProjectPayload(record.projectId))
    }

    private fun apiResultProjectPayload(projectId: String): Map<String, Any?> {
        val record = runCatching { findProject(projectId) }.getOrNull() ?: return emptyMap()
        val apis = projectApis(projectId)
        val counts = apiExecutionCounts(projectId)
        val items = readProjectItems(projectId)
        return linkedMapOf(
            "projectId" to record.projectId,
            "name" to record.name,
            "route" to record.route,
            "items" to items.map { it.toPayload() },
            "tools" to apis.map { it.toPayload(counts[it.apiId] ?: 0) }
        )
    }


    /**
     * Applies a prompt-driven hot update to a registered Workbench project.
     *
     * @param projectId Project whose native display should refresh after the update.
     * @param prompt User request captured from the Xiaowan floating assistant; it is stored in
     * `logs/hot_updates.jsonl` and interpreted against the current Project contract.
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
        val frontendHtml = readFrontendHtmlPayload(record.projectId)
        val htmlSources = frontendHtml["sources"] as? Map<*, *>
        if (!htmlSources.isNullOrEmpty()) {
            val entryFile = frontendHtml["entryFile"]?.toString()?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: "index.html"
            val appliedActions = listOf(
                linkedMapOf<String, Any?>(
                    "kind" to "html_regenerate_requested",
                    "success" to true,
                    "entryFile" to entryFile,
                    "recommendedTool" to "workbench_project_update"
                )
            )
            appendHotUpdate(record.projectId, request, caller, appliedActions, frontendContext)
            appendProjectProgress(
                projectId = record.projectId,
                stage = "html_regenerate_requested",
                status = "running",
                message = "HTML Display hot update requires the Agent to update frontend/html source files and call workbench_project_update.",
                percent = 60,
                caller = caller,
                details = linkedMapOf(
                    "entryFile" to entryFile,
                    "sourceFiles" to htmlSources.keys.map { it.toString() },
                    "frontendContext" to frontendContext
                )
            )
            writeProjectJson(record, projectApis(record.projectId))
            return linkedMapOf(
                "success" to true,
                "projectId" to record.projectId,
                "prompt" to request,
                "frontendContext" to frontendContext,
                "appliedActions" to appliedActions,
                "requiresAgentRegeneration" to true,
                "recommendedTool" to "workbench_project_update",
                "instructions" to listOf(
                    "Read project.frontendHtml.sources and prefer editing the smallest affected HTML/CSS/JS file.",
                    "Preserve the existing window.oob.callApi(apiId, inputs), window.oob.getProject(), and data-oob-id hooks.",
                    "Call workbench_project_update with htmlFiles=[{path:\"$entryFile\", content:<updated HTML>}], or include multiple htmlFiles for CSS/JS changes.",
                    "Keep native/mobile capabilities behind registered Project Tools; do not invent direct Android, filesystem, shell, or network bridge calls."
                ),
                "hotUpdateLogPath" to "${record.spacePath}/logs/hot_updates.jsonl",
                "project" to getProject(record.projectId)
            )
        }
        val frontendFlutter = readFrontendFlutterPayload(record.projectId)
        val frontendSources = frontendFlutter["sources"] as? Map<*, *>
        if (!frontendSources.isNullOrEmpty()) {
            val entryFile = frontendFlutter["entryFile"]?.toString()?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: "lib/main.dart"
            val entryClass = frontendFlutter["entryClass"]?.toString()?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: "OobProjectWidget"
            val appliedActions = listOf(
                linkedMapOf<String, Any?>(
                    "kind" to "flutter_regenerate_requested",
                    "success" to true,
                    "entryFile" to entryFile,
                    "entryClass" to entryClass,
                    "recommendedTool" to "workbench_project_update"
                )
            )
            appendHotUpdate(record.projectId, request, caller, appliedActions, frontendContext)
            appendProjectProgress(
                projectId = record.projectId,
                stage = "flutter_regenerate_requested",
                status = "running",
                message = "Flutter Display hot update requires the Agent to rewrite the full Dart source and call workbench_project_update.",
                percent = 60,
                caller = caller,
                details = linkedMapOf(
                    "entryFile" to entryFile,
                    "entryClass" to entryClass,
                    "sourceFiles" to frontendSources.keys.map { it.toString() },
                    "frontendContext" to frontendContext
                )
            )
            writeProjectJson(record, projectApis(record.projectId))
            return linkedMapOf(
                "success" to true,
                "projectId" to record.projectId,
                "prompt" to request,
                "frontendContext" to frontendContext,
                "appliedActions" to appliedActions,
                "requiresAgentRegeneration" to true,
                "recommendedTool" to "workbench_project_update",
                "instructions" to listOf(
                    "Read project.frontendFlutter.sources[$entryFile].",
                    "Generate a complete replacement Dart file, not a patch or partial snippet.",
                    "Call workbench_project_update with flutterFiles=[{path:\"$entryFile\", content:<complete Dart>}].",
                    "Keep entry class $entryClass and the OOB MethodChannel Project Tool calls."
                ),
                "hotUpdateLogPath" to "${record.spacePath}/logs/hot_updates.jsonl",
                "project" to getProject(record.projectId)
            )
        }
        val appliedActions = mutableListOf<Map<String, Any?>>()
        val lower = request.lowercase()
        val wantsArchive =
            listOf("归档", "完成", "finish", "archive", "done").any { lower.contains(it) }
        val wantsAdd =
            listOf("增加", "新增", "添加", "add", "create").any { lower.contains(it) }
        applyProjectDataHotUpdate(record, request, wantsAdd, wantsArchive, appliedActions)

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
                    "OSS source import is ready for Project Tool binding."
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
        val record = findProject(projectId)
        return defaultDisplayRoute(record)
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
        val items = readProjectItems(record.projectId)
        val counts = apiExecutionCounts(record.projectId)
        val androidAssets = readAndroidAssets(record.projectId)
        val sourceAssets = readOssSources(record.projectId)
        val displays = workbenchDisplays(record)
        val frontendHtml = readFrontendHtmlPayload(record.projectId)
        val frontendFlutter = readFrontendFlutterPayload(record.projectId)
        val displayRoute = defaultDisplayRoute(record, displays)
        val toolbox = WorkbenchToolboxBuilder.toolboxPayload(record, apis, counts)
        return linkedMapOf(
            "projectId" to record.projectId,
            "name" to record.name,
            "route" to displayRoute,
            "spacePath" to record.spacePath,
            "pageIds" to displays.mapNotNull { it["pageId"]?.toString() ?: it["id"]?.toString() },
            "displays" to displays,
            "pageSpec" to workbenchPageSpec(record),
            "frontendHtml" to frontendHtml,
            "frontendFlutter" to frontendFlutter,
            "apiIds" to record.apiIds,
            "tools" to apis.map { it.toPayload(counts[it.apiId] ?: 0) },
            "apis" to apis.map { it.toPayload(counts[it.apiId] ?: 0) },
            "toolbox" to toolbox,
            "androidAssets" to androidAssets.map { it.toPayload() },
            "sourceAssets" to sourceAssets.map { it.toPayload() },
            "flows" to emptyList<Map<String, Any?>>(),
            "items" to items.map { it.toPayload() },
            "lastProgress" to lastProjectProgress(record.projectId),
            "lastError" to lastApiError(record.projectId),
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
        val apis = projectApis(record.projectId)
        val displays = workbenchDisplays(record)
        val frontendHtml = readFrontendHtmlPayload(record.projectId)
        val frontendFlutter = readFrontendFlutterPayload(record.projectId)
        val displayRoute = defaultDisplayRoute(record, displays)
        return linkedMapOf(
            "projectId" to record.projectId,
            "name" to record.name,
            "route" to displayRoute,
            "spacePath" to record.spacePath,
            "skillId" to "oob-native-workbench",
            "displays" to displays,
            "frontendHtml" to frontendHtml,
            "frontendFlutter" to frontendFlutter,
            "apis" to apis.map { it.toPayload(counts[it.apiId] ?: 0) },
            "toolbox" to WorkbenchToolboxBuilder.toolboxPayload(record, apis, counts),
            "sourceAssets" to readOssSources(record.projectId).map { it.toPayload() },
            "lastProgress" to lastProjectProgress(record.projectId),
            "lastError" to lastApiError(record.projectId),
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
        val items = readProjectItems(record.projectId)
        val prompt = sourcePrompt?.trim()?.takeIf { it.isNotEmpty() }
            ?: readProjectSourcePrompt(record.projectId)
        val displays = workbenchDisplays(record)
        val frontendHtml = readFrontendHtmlPayload(record.projectId)
        val frontendFlutter = readFrontendFlutterPayload(record.projectId)
        val displayRoute = defaultDisplayRoute(record, displays)
        val toolbox = WorkbenchToolboxBuilder.toolboxPayload(record, apis, counts)
        val payload = linkedMapOf<String, Any?>(
            "project" to record.toPayload(),
            "generation" to linkedMapOf(
                "skillId" to "oob-native-workbench",
                "prompt" to prompt,
                "decomposition" to listOf(
                    "Project registry",
                    "App Display surface",
                    "Editable HTML source assets",
                    "Editable Flutter source assets",
                    "Project Tools",
                    "Persistent data and tool logs"
                ),
                "displayContract" to "Workbench Displays are user app surfaces. Project ids, tool counts, executor kinds, Toolbox manifests, Workspace paths, and data/log paths belong to control/debug surfaces."
            ),
            "page" to linkedMapOf(
                "pageId" to ((displays.firstOrNull()?.get("pageId") ?: displays.firstOrNull()?.get("id"))
                    ?: "workbench-page"),
                "renderer" to (
                    (displays.firstOrNull { it["isDefault"] == true } ?: displays.firstOrNull())
                        ?.get("renderer")
                        ?: workbenchPageSpec(record)["renderer"]
                        ?: "oob_project_display"
                    ),
                "route" to displayRoute
            ),
            "frontendSource" to linkedMapOf(
                "instantRuntime" to "frontend/page_spec.json",
                "editableHtmlSource" to "frontend/html/",
                "editableFlutterSource" to "frontend/flutter/",
                "htmlRuntimeBoundary" to "HTML source under frontend/html/ can be loaded live by /workbench/html through WebView; native access stays behind Project Tool bridge.",
                "compileBoundary" to "Flutter source under frontend/flutter/ is a Project asset loaded live by /workbench/flutter_eval through flutter_eval; no APK rebuild is required for supported Dart/Material UI."
            ),
            "displays" to displays,
            "pageSpec" to workbenchPageSpec(record),
            "frontendHtml" to frontendHtml,
            "frontendFlutter" to frontendFlutter,
            "apis" to apis.map { it.toPayload(counts[it.apiId] ?: 0) },
            "toolbox" to toolbox,
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
            "lastError" to lastApiError(record.projectId),
            "state" to linkedMapOf(
                "itemCount" to items.size,
                "activeItemCount" to items.count { it.status != "archived" },
                "archivedItemCount" to items.count { it.status == "archived" },
                "androidAssetCount" to androidAssets.size,
                "sourceAssetCount" to sourceAssets.size
            )
        )
        val file = File(projectDir(record.projectId), "project.json")
        file.parentFile?.mkdirs()
        file.writeText(gson.toJson(payload))
    }

    /**
     * Reads the editable frontend page spec for one Project.
     *
     * @param record Project whose `frontend/page_spec.json` may define the current Display.
     * @return Parsed page spec map, or an empty map when the Project predates page spec files.
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
     * @return Display payloads shown by Flutter as Project-scoped frontends, not Project Tools.
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
        if (readFrontendHtmlPayload(record.projectId).isNotEmpty()) {
            return listOf(htmlWorkbenchDisplay(record))
        }
        if (readFrontendFlutterPayload(record.projectId).isNotEmpty()) {
            return listOf(
                linkedMapOf(
                    "id" to "flutter-main-display",
                    "pageId" to "flutter-main-page",
                    "title" to record.name,
                    "shortName" to "APP",
                    "route" to "/workbench/flutter_eval?projectId=${record.projectId}",
                    "kind" to "oob_flutter_eval",
                    "renderer" to "flutter_eval",
                    "isDefault" to true,
                    "description" to "Live Flutter Display bound to this Project."
                )
            )
        }
        if (spec.isNotEmpty() && spec["route"]?.toString()?.trim()?.isNotEmpty() == true) {
            return listOf(
                linkedMapOf(
                    "id" to (spec["displayId"] ?: spec["pageId"] ?: "project-main-display"),
                    "pageId" to (spec["pageId"] ?: "project-main-page"),
                    "title" to (spec["title"] ?: record.name),
                    "shortName" to (spec["shortName"] ?: "APP"),
                    "route" to spec["route"],
                    "kind" to "oob_flutter",
                    "renderer" to (spec["renderer"] ?: "oob_project_display"),
                    "isDefault" to true,
                    "description" to (spec["description"] ?: spec["subtitle"] ?: "")
                )
            )
        }
        return listOf(
            linkedMapOf(
                "id" to "project-main-display",
                "pageId" to "project-main-page",
                "title" to record.name,
                "shortName" to "APP",
                "route" to record.route,
                "kind" to "oob_flutter",
                "renderer" to "oob_project_display",
                "isDefault" to true,
                "description" to "Display bound to this Project API registry."
            )
        )
    }

    private fun defaultDisplayRoute(
        record: WorkbenchProjectRecord,
        displays: List<Map<String, Any?>> = workbenchDisplays(record)
    ): String {
        val display = displays.firstOrNull { it["isDefault"] == true } ?: displays.firstOrNull()
        val renderer = display?.get("renderer")?.toString()?.trim().orEmpty()
        return when {
            renderer == WORKBENCH_HTML_RENDERER -> display?.get("route")?.toString()?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: htmlRoute(record.projectId)
            renderer == "flutter_eval" -> display?.get("route")?.toString()?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: "/workbench/flutter_eval?projectId=${record.projectId}"
            else -> record.route
        }
    }

    /**
     * Normalizes Project update display specs into the same app-display shape used by creation.
     *
     * @param record Project whose route is used when a display omits its own route.
     * @param value Optional `displays` array from `workbench_project_update`.
     * @return Valid display maps keyed by `id`, ready to merge into `frontend/page_spec.json`.
     */
    private fun normalizeDisplaySpecs(
        record: WorkbenchProjectRecord,
        value: Any?
    ): List<Map<String, Any?>> {
        val raw = value as? Iterable<*> ?: return emptyList()
        return raw.mapNotNull { item ->
            val display = item as? Map<*, *> ?: return@mapNotNull null
            val id = display["id"]?.toString()?.trim()
                ?: display["displayId"]?.toString()?.trim()
                ?: display["pageId"]?.toString()?.trim()
                ?: return@mapNotNull null
            if (id.isEmpty()) return@mapNotNull null
            val pageId = display["pageId"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: "$id-page"
            val renderer = display["renderer"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: workbenchPageSpec(record)["renderer"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: "oob_project_display"
            val route = display["route"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: if (renderer == WORKBENCH_HTML_RENDERER) {
                    htmlRoute(record.projectId)
                } else {
                    displayRouteWithId(record.route, id)
                }
            linkedMapOf(
                "id" to id,
                "pageId" to pageId,
                "title" to (
                    display["title"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                        ?: display["displayName"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                        ?: id
                    ),
                "shortName" to (
                    display["shortName"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                        ?: display["abbr"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                        ?: "APP"
                    ),
                "route" to route,
                "kind" to (
                    display["kind"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                        ?: if (renderer == WORKBENCH_HTML_RENDERER) "oob_html_webview" else "oob_flutter"
                    ),
                "renderer" to renderer,
                "surfaceKind" to "app_display",
                "navigationScope" to "right_workbench_display",
                "isDefault" to (display["isDefault"] == true),
                "description" to display["description"]?.toString()?.trim().orEmpty()
            )
        }
    }

    /**
     * Merges display updates without losing existing pages.
     *
     * @param base Current display payloads from `frontend/page_spec.json` or runtime fallback.
     * @param additions New display payloads from the update request.
     * @return Ordered display list where additions replace matching ids and append new ids.
     */
    private fun mergeDisplaySpecs(
        base: List<*>,
        additions: List<Map<String, Any?>>
    ): List<Map<String, Any?>> {
        val byId = linkedMapOf<String, Map<String, Any?>>()
        base.forEach { item ->
            @Suppress("UNCHECKED_CAST")
            val map = item as? Map<String, Any?> ?: return@forEach
            val id = displayKey(map)
            if (id.isNotEmpty()) byId[id] = map
        }
        additions.forEach { display ->
            val id = displayKey(display)
            if (id.isNotEmpty()) byId[id] = display
        }
        val merged = byId.values.toList()
        if (merged.any { it["isDefault"] == true }) return merged
        return merged.mapIndexed { index, display ->
            if (index != 0) {
                display
            } else {
                display + ("isDefault" to true)
            }
        }
    }

    /**
     * Merges Project Tool records by the legacy `apiId` field while preserving existing order.
     *
     * @param base Existing Project Tools.
     * @param additions New or replacement Project Tools from a Project update.
     * @return Updated API record list for `api_registry.json` and Tool Contract output.
     */
    private fun mergeApiRecords(
        base: List<WorkbenchApiRecord>,
        additions: List<WorkbenchApiRecord>
    ): List<WorkbenchApiRecord> {
        if (additions.isEmpty()) return base
        val byId = linkedMapOf<String, WorkbenchApiRecord>()
        base.forEach { byId[it.apiId] = it }
        additions.forEach { byId[it.apiId] = it }
        return byId.values.toList()
    }

    /**
     * Appends frontend action bindings for newly registered Project Tools.
     *
     * @param value Existing `actions` value from `frontend/page_spec.json`.
     * @param apis Business APIs that should become available to the renderer.
     * @return JSON-safe action list with no duplicate tool ids.
     */
    private fun mergePageActions(value: Any?, apis: List<WorkbenchApiRecord>): List<Map<String, Any?>> {
        val existing = (value as? Iterable<*>)
            ?.mapNotNull { it as? Map<*, *> }
            ?.map { raw -> raw.entries.associate { it.key.toString() to it.value } }
            ?.toMutableList()
            ?: mutableListOf()
        val existingApiIds = existing.mapNotNull { it["apiId"]?.toString() }.toMutableSet()
        apis.forEach { api ->
            if (existingApiIds.add(api.apiId)) {
                existing += linkedMapOf(
                    "apiId" to api.apiId,
                    "kind" to projectApiAction(api),
                    "label" to api.displayName,
                    "inputs" to when (projectApiAction(api)) {
                        "archive", "update" -> linkedMapOf("item_id" to "item.id")
                        "list" -> emptyMap<String, String>()
                        else -> linkedMapOf("title" to "page.input.title")
                    }
                )
            }
        }
        return existing
    }

    /**
     * Builds the stable Project iteration contract embedded in `frontend/page_spec.json`.
     *
     * @return JSON-safe rule payload used by later agents and hot-update passes.
     */
    private fun projectIterationContract(): Map<String, Any?> = linkedMapOf(
        "scope" to "same_project",
        "mutationTargets" to listOf(
            "frontend/page_spec.json",
            "frontend/html/",
            "frontend/flutter/",
            "backend/api_spec.json",
            "data/items.json",
            "logs/hot_updates.jsonl"
        ),
        "displayNavigationScope" to "right_workbench_display",
            "rule" to "Add features by extending displays, actions, and Project business tools; do not create a replacement Project unless the user asks."
        )

    private fun displayKey(display: Map<String, Any?>): String {
        return display["id"]?.toString()?.trim()
            ?: display["displayId"]?.toString()?.trim()
            ?: display["pageId"]?.toString()?.trim()
            ?: ""
    }

    private fun displayRouteWithId(route: String, displayId: String): String {
        val base = route.ifBlank { "/workbench/project" }
        val separator = if (base.contains("?")) "&" else "?"
        return if (base.contains("displayId=")) base else "$base${separator}displayId=$displayId"
    }

    private fun htmlRoute(projectId: String): String = "/workbench/html?projectId=$projectId"

    private fun htmlWorkbenchDisplay(record: WorkbenchProjectRecord): Map<String, Any?> = linkedMapOf(
        "id" to "html-main-display",
        "pageId" to "html-main-page",
        "title" to record.name,
        "shortName" to "WEB",
        "route" to htmlRoute(record.projectId),
        "kind" to "oob_html_webview",
        "renderer" to WORKBENCH_HTML_RENDERER,
        "surfaceKind" to "app_display",
        "navigationScope" to "right_workbench_display",
        "isDefault" to true,
        "description" to "Live HTML Display bound to this Project."
    )

    private fun isHtmlDisplay(display: Map<String, Any?>): Boolean {
        val renderer = display["renderer"]?.toString()?.trim()
        val kind = display["kind"]?.toString()?.trim()
        val route = display["route"]?.toString()?.trim().orEmpty()
        return renderer == WORKBENCH_HTML_RENDERER ||
            kind == WORKBENCH_HTML_RENDERER ||
            route.startsWith("/workbench/html")
    }

    private fun normalizeDefaultDisplaySelection(
        displays: List<Map<String, Any?>>,
        preferredDisplayId: String?
    ): List<Map<String, Any?>> {
        val preferred = preferredDisplayId?.trim()?.takeIf { it.isNotEmpty() }
            ?: return displays
        if (displays.none { displayKey(it) == preferred }) return displays
        return displays.map { display ->
            if (displayKey(display) == preferred) {
                display + ("isDefault" to true)
            } else if (display["isDefault"] == true) {
                display + ("isDefault" to false)
            } else {
                display
            }
        }
    }

    /**
     * Normalizes Project-owned HTML source updates into relative-path/content pairs.
     *
     * @param value Tool or MethodChannel payload. Supported shapes are a map of
     * `path -> content`, a list of `{path, content}`, or `{files: [...]}`.
     * @return Sanitized relative file specs ready for bounded writes under `frontend/html/`.
     */
    private fun normalizeFrontendHtmlFiles(value: Any?): List<Pair<String, String>> {
        if (value == null) return emptyList()
        if (value is Map<*, *>) {
            val map = asStringKeyMap(value)
            val directPath = map["path"] ?: map["relativePath"] ?: map["filePath"]
            val directContent = map["content"] ?: map["source"] ?: map["text"]
            if (directPath != null && directContent != null) {
                return listOf(frontendHtmlFileSpec(directPath, directContent))
            }
            val nested = map["files"] ?: map["items"]
            if (nested is Iterable<*>) {
                return normalizeFrontendHtmlFiles(nested)
            }
            return map.mapNotNull { (path, content) ->
                if (path == "files" || path == "items") null else frontendHtmlFileSpec(path, content)
            }.distinctBy { it.first }
        }
        val raw = value as? Iterable<*> ?: return emptyList()
        return raw.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val file = asStringKeyMap(map)
            val path = file["path"] ?: file["relativePath"] ?: file["filePath"] ?: return@mapNotNull null
            val content = file["content"] ?: file["source"] ?: file["text"] ?: return@mapNotNull null
            frontendHtmlFileSpec(path, content)
        }.distinctBy { it.first }
    }

    private fun frontendHtmlFileSpec(path: Any?, content: Any?): Pair<String, String> {
        val normalized = cleanFrontendHtmlPath(path?.toString().orEmpty())
        return normalized to content?.toString().orEmpty()
    }

    private fun cleanFrontendHtmlPath(rawPath: String): String {
        val normalized = rawPath.replace('\\', '/').trim().removePrefix("/")
        require(normalized.isNotEmpty()) { "HTML source file path is required." }
        require(!normalized.contains(":")) { "HTML source file path must be relative." }
        val parts = normalized.split('/').filter { it.isNotBlank() }
        require(parts.none { it == ".." }) { "HTML source file path cannot escape frontend/html/." }
        require(parts.lastOrNull() != "manifest.json") {
            "frontend/html/manifest.json is generated by OOB."
        }
        return parts.joinToString("/")
    }

    private fun writeFrontendHtmlFiles(
        projectId: String,
        files: List<Pair<String, String>>
    ): List<Map<String, Any?>> {
        if (files.isEmpty()) return emptyList()
        val htmlDir = File(projectDir(projectId), "frontend/html")
        htmlDir.mkdirs()
        val root = htmlDir.canonicalFile
        val rootPrefix = root.path + File.separator
        val writtenAt = nowIso()
        val written = files.map { (relativePath, content) ->
            val target = File(root, relativePath).canonicalFile
            require(target.path == root.path || target.path.startsWith(rootPrefix)) {
                "HTML source file path cannot escape frontend/html/."
            }
            target.parentFile?.mkdirs()
            target.writeText(content)
            linkedMapOf<String, Any?>(
                "path" to "frontend/html/$relativePath",
                "bytes" to content.toByteArray(Charsets.UTF_8).size,
                "updatedAt" to writtenAt
            )
        }
        writeFrontendHtmlManifest(projectId)
        return written
    }

    private fun readFrontendHtmlPayload(projectId: String): Map<String, Any?> {
        val htmlDir = File(projectDir(projectId), "frontend/html")
        if (!htmlDir.exists()) return emptyMap()
        val root = htmlDir.canonicalFile
        val sources = linkedMapOf<String, String>()
        val assets = mutableListOf<Map<String, Any?>>()
        htmlDir.walkTopDown()
            .filter { it.isFile && it.name != "manifest.json" && it.name != "README.md" }
            .sortedBy { it.absolutePath }
            .forEach { file ->
                val relative = root.toPath()
                    .relativize(file.canonicalFile.toPath())
                    .toString()
                    .replace(File.separatorChar, '/')
                val payload = linkedMapOf<String, Any?>(
                    "path" to "frontend/html/$relative",
                    "relativePath" to relative,
                    "bytes" to file.length(),
                    "updatedAt" to Instant.ofEpochMilli(file.lastModified()).toString()
                )
                if (isTextFrontendHtmlFile(file)) {
                    sources[relative] = file.readText()
                    assets += payload + ("kind" to "source")
                } else {
                    assets += payload + ("kind" to "asset")
                }
            }
        if (sources.isEmpty() && assets.isEmpty()) return emptyMap()
        val manifestFile = File(htmlDir, "manifest.json")
        val manifest = if (manifestFile.exists()) {
            runCatching {
                gson.fromJson<Map<String, Any?>>(manifestFile.readText(), mapType)
            }.getOrNull().orEmpty()
        } else {
            emptyMap()
        }
        val entryFile = manifest["entryFile"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: when {
                sources.containsKey("index.html") -> "index.html"
                sources.keys.any { it.endsWith(".html") } -> sources.keys.first { it.endsWith(".html") }
                else -> sources.keys.firstOrNull().orEmpty()
            }
        val entry = entryFile.takeIf { it.isNotEmpty() }?.let { File(root, it).canonicalFile }
        val entryPath = entry?.takeIf { file ->
            val rootPrefix = root.path + File.separator
            file.path == root.path || file.path.startsWith(rootPrefix)
        }?.absolutePath.orEmpty()
        return linkedMapOf(
            "runtime" to WORKBENCH_HTML_RENDERER,
            "renderer" to WORKBENCH_HTML_RENDERER,
            "entryFile" to entryFile,
            "entryPath" to entryPath,
            "sources" to sources,
            "assets" to assets,
            "manifest" to manifest
        )
    }

    private fun writeFrontendHtmlManifest(projectId: String): List<Map<String, Any?>> {
        val htmlDir = File(projectDir(projectId), "frontend/html")
        htmlDir.mkdirs()
        val root = htmlDir.canonicalFile
        val files = htmlDir.walkTopDown()
            .filter { it.isFile && it.name != "manifest.json" }
            .map { file ->
                val relative = root.toPath()
                    .relativize(file.canonicalFile.toPath())
                    .toString()
                    .replace(File.separatorChar, '/')
                linkedMapOf<String, Any?>(
                    "path" to "frontend/html/$relative",
                    "relativePath" to relative,
                    "bytes" to file.length(),
                    "updatedAt" to Instant.ofEpochMilli(file.lastModified()).toString(),
                    "kind" to if (isTextFrontendHtmlFile(file)) "source" else "asset"
                )
            }
            .sortedBy { it["path"]?.toString().orEmpty() }
            .toList()
        val entryFile = files.firstOrNull { it["relativePath"] == "index.html" }?.get("relativePath")
            ?: files.firstOrNull {
                it["relativePath"]?.toString()?.endsWith(".html") == true
            }?.get("relativePath")
            ?: files.firstOrNull()?.get("relativePath")
        File(htmlDir, "manifest.json").writeText(
            gson.toJson(
                linkedMapOf(
                    "generatedAt" to nowIso(),
                    "runtimeBoundary" to "html_webview_live_runtime",
                    "entryFile" to entryFile,
                    "files" to files,
                    "security" to linkedMapOf(
                        "nativeBridge" to "Project Tool whitelist only",
                        "externalNavigation" to "blocked in Workbench Display",
                        "remoteSubresources" to "allowed for demo/CDN; prefer vendored assets for production"
                    )
                )
            )
        )
        return files
    }

    private fun isTextFrontendHtmlFile(file: File): Boolean {
        val name = file.name.lowercase()
        return listOf(
            ".html",
            ".htm",
            ".css",
            ".js",
            ".mjs",
            ".json",
            ".svg",
            ".txt",
            ".md",
            ".csv"
        ).any { name.endsWith(it) }
    }

    /**
     * Normalizes Project-owned Flutter source updates into relative-path/content pairs.
     *
     * @param value Tool or MethodChannel payload. Supported shapes are a map of
     * `path -> content`, a list of `{path, content}`, or `{files: [...]}`.
     * @return Sanitized relative file specs ready for bounded writes under `frontend/flutter/`.
     */
    private fun normalizeFrontendFlutterFiles(value: Any?): List<Pair<String, String>> {
        if (value == null) return emptyList()
        if (value is Map<*, *>) {
            val map = asStringKeyMap(value)
            val directPath = map["path"] ?: map["relativePath"] ?: map["filePath"]
            val directContent = map["content"] ?: map["source"] ?: map["text"]
            if (directPath != null && directContent != null) {
                return listOf(frontendFlutterFileSpec(directPath, directContent))
            }
            val nested = map["files"] ?: map["items"]
            if (nested is Iterable<*>) {
                return normalizeFrontendFlutterFiles(nested)
            }
            return map.mapNotNull { (path, content) ->
                if (path == "files" || path == "items") null else frontendFlutterFileSpec(path, content)
            }.distinctBy { it.first }
        }
        val raw = value as? Iterable<*> ?: return emptyList()
        return raw.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val file = asStringKeyMap(map)
            val path = file["path"] ?: file["relativePath"] ?: file["filePath"] ?: return@mapNotNull null
            val content = file["content"] ?: file["source"] ?: file["text"] ?: return@mapNotNull null
            frontendFlutterFileSpec(path, content)
        }.distinctBy { it.first }
    }

    /**
     * Converts one untrusted Flutter source payload into a bounded relative path and content.
     *
     * @param path Raw project-local path supplied by AI, UI, or MCP.
     * @param content Source text that should be persisted as UTF-8.
     * @return Pair of sanitized path and source content.
     */
    private fun frontendFlutterFileSpec(path: Any?, content: Any?): Pair<String, String> {
        val normalized = cleanFrontendFlutterPath(path?.toString().orEmpty())
        return normalized to content?.toString().orEmpty()
    }

    /**
     * Enforces the `frontend/flutter/` source boundary before any file write occurs.
     *
     * @param rawPath Relative path requested by a Project update.
     * @return Slash-normalized path with no absolute, parent, or manifest overwrite segments.
     */
    private fun cleanFrontendFlutterPath(rawPath: String): String {
        val normalized = rawPath.replace('\\', '/')
            .trim()
            .removePrefix("/")
            .removePrefix("frontend/flutter/")
            .removePrefix("flutter/")
        require(normalized.isNotEmpty()) { "Flutter source file path is required." }
        require(!normalized.contains(":")) { "Flutter source file path must be relative." }
        val parts = normalized.split('/').filter { it.isNotBlank() }
        require(parts.none { it == ".." }) { "Flutter source file path cannot escape frontend/flutter/." }
        require(parts.lastOrNull() != "manifest.json") {
            "frontend/flutter/manifest.json is generated by OOB."
        }
        return parts.joinToString("/")
    }

    /**
     * Writes Project-owned Flutter source files and refreshes `frontend/flutter/manifest.json`.
     *
     * @param projectId Project id whose `frontend/flutter/` directory owns the files.
     * @param files Sanitized relative-path/content pairs from `normalizeFrontendFlutterFiles`.
     * @return Project-relative paths and byte sizes that were written in this update.
     */
    private fun writeFrontendFlutterFiles(
        projectId: String,
        files: List<Pair<String, String>>
    ): List<Map<String, Any?>> {
        if (files.isEmpty()) return emptyList()
        val flutterDir = File(projectDir(projectId), "frontend/flutter")
        flutterDir.mkdirs()
        val root = flutterDir.canonicalFile
        val rootPrefix = root.path + File.separator
        val writtenAt = nowIso()
        val written = files.map { (relativePath, content) ->
            val target = File(root, relativePath).canonicalFile
            require(target.path == root.path || target.path.startsWith(rootPrefix)) {
                "Flutter source file path cannot escape frontend/flutter/."
            }
            target.parentFile?.mkdirs()
            target.writeText(content)
            linkedMapOf<String, Any?>(
                "path" to "frontend/flutter/$relativePath",
                "bytes" to content.toByteArray(Charsets.UTF_8).size,
                "updatedAt" to writtenAt
            )
        }
        writeFrontendFlutterManifest(projectId)
        return written
    }

    /**
     * Loads Project-owned Flutter sources for the live `flutter_eval` Display.
     *
     * @param projectId Project id whose `frontend/flutter/` directory should be read.
     * @return JSON-safe payload with entry metadata and relative-path source text. Empty means
     * the Project has no live Flutter sources yet.
     */
    private fun readFrontendFlutterPayload(projectId: String): Map<String, Any?> {
        val flutterDir = File(projectDir(projectId), "frontend/flutter")
        if (!flutterDir.exists()) return emptyMap()
        val root = flutterDir.canonicalFile
        val sources = linkedMapOf<String, String>()
        flutterDir.walkTopDown()
            .filter { it.isFile && it.name != "manifest.json" && it.name != "README.md" }
            .sortedBy { it.absolutePath }
            .forEach { file ->
                val relative = root.toPath()
                    .relativize(file.canonicalFile.toPath())
                    .toString()
                    .replace(File.separatorChar, '/')
                sources[relative] = file.readText()
            }
        if (sources.isEmpty()) return emptyMap()
        val manifestFile = File(flutterDir, "manifest.json")
        val manifest = if (manifestFile.exists()) {
            runCatching {
                gson.fromJson<Map<String, Any?>>(manifestFile.readText(), mapType)
            }.getOrNull().orEmpty()
        } else {
            emptyMap()
        }
        val entryFile = manifest["entryFile"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: when {
                sources.containsKey("lib/main.dart") -> "lib/main.dart"
                sources.containsKey("main.dart") -> "main.dart"
                sources.containsKey("frontend/flutter/lib/main.dart") -> "frontend/flutter/lib/main.dart"
                sources.keys.any { it.endsWith("/main.dart") } -> sources.keys.first { it.endsWith("/main.dart") }
                else -> sources.keys.firstOrNull().orEmpty()
            }
        val entryClass = manifest["entryClass"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: "OobProjectWidget"
        return linkedMapOf(
            "runtime" to "flutter_eval",
            "entryFile" to entryFile,
            "entryClass" to entryClass,
            "sources" to sources,
            "manifest" to manifest
        )
    }

    /**
     * Rebuilds the Project Flutter source manifest from files currently on disk.
     *
     * @param projectId Project id whose `frontend/flutter/manifest.json` should be materialized.
     * @return Current file summary, excluding the generated manifest itself.
     */
    private fun writeFrontendFlutterManifest(projectId: String): List<Map<String, Any?>> {
        val flutterDir = File(projectDir(projectId), "frontend/flutter")
        flutterDir.mkdirs()
        val root = flutterDir.canonicalFile
        val files = flutterDir.walkTopDown()
            .filter { it.isFile && it.name != "manifest.json" }
            .map { file ->
                val relative = root.toPath()
                    .relativize(file.canonicalFile.toPath())
                    .toString()
                    .replace(File.separatorChar, '/')
                linkedMapOf<String, Any?>(
                    "path" to "frontend/flutter/$relative",
                    "bytes" to file.length(),
                    "updatedAt" to Instant.ofEpochMilli(file.lastModified()).toString()
                )
            }
            .sortedBy { it["path"]?.toString().orEmpty() }
            .toList()
        File(flutterDir, "manifest.json").writeText(
            gson.toJson(
                linkedMapOf(
                    "generatedAt" to nowIso(),
                    "runtimeBoundary" to "flutter_eval_live_runtime",
                    "entryFile" to when {
                        files.any { it["path"] == "frontend/flutter/lib/main.dart" } -> "lib/main.dart"
                        files.any { it["path"] == "frontend/flutter/main.dart" } -> "main.dart"
                        else -> "lib/main.dart"
                    },
                    "entryClass" to "OobProjectWidget",
                    "files" to files
                )
            )
        )
        return files
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
            val dataFiles = "- `data/items.json`: persistent Project item state"
            readme.writeText(
                """
                |# ${record.name}
                |
                |This is an OOB Native Workbench project.
                |${sourcePrompt?.let { "\n## Source Prompt\n$it\n" } ?: ""}
                |
                |## Runtime
                |- Project id: `${record.projectId}`
                |- Native display route: `${record.route}`
                |- Workspace path: `${record.spacePath}`
                |
                |## Frontend
                |The frontend is rendered by OOB's native Flutter Display. The editable page
                |contract lives in `frontend/page_spec.json`; OOB binds visible controls to
                |Project Tools through `workbenchApiCall`.
                |
                |If the Project needs generated HTML, generate or edit source under
                |`frontend/html/`. OOB can load `frontend/html/index.html` directly in the
                |right-side Workbench WebView Display and bridge `window.oob.callApi()` to
                |Project Tools.
                |
                |If the Project needs custom Flutter, generate or edit source under
                |`frontend/flutter/` from the Alpine workspace. The supported runtime subset is
                |loaded by `/workbench/flutter_eval`; unsupported native/package code still needs
                |a controlled build/install path.
                |
                |The Display is the user-facing app surface. It should show the product workflow,
                |domain records, input forms, filters, and business actions. Project ids,
                |Tool counts, executor names, Toolbox metadata, Workspace paths, and data/log
                |paths belong in Workbench control/debug surfaces, not the app home.
                |
                |## Backend
                |Business APIs are declared in `backend/api_spec.json`. API execution stays behind
                |the Workbench runtime boundary, so AI calls and UI clicks share one backend path.
                |
                |## Data
                |$dataFiles
                |- `logs/api_calls.jsonl`: append-only AI/UI tool call log
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
        val htmlDir = File(frontendDir, "html")
        htmlDir.mkdirs()
        val htmlReadme = File(htmlDir, "README.md")
        if (!htmlReadme.exists()) {
            htmlReadme.writeText(
                """
                |# HTML Display Source
                |
                |This directory stores Project-owned HTML/CSS/JS generated or edited from the
                |Agent workspace.
                |
                |Runtime boundary:
                |- `frontend/html/index.html` can be loaded live by the right-side Workbench
                |  WebView Display when the Project renderer is `html_webview`.
                |- HTML calls native/project capabilities through `window.oob.callApi(...)`.
                |- The bridge is limited to registered Project Tools; do not assume arbitrary
                |  Android, filesystem, shell, or network APIs are exposed.
                |
                |Prefer local vendored JS/CSS assets for production displays. CDN dependencies
                |are acceptable for demos and iteration, but they reduce offline reliability.
                |
                """.trimMargin()
            )
        }
        val flutterDir = File(frontendDir, "flutter")
        flutterDir.mkdirs()
        val flutterReadme = File(flutterDir, "README.md")
        if (!flutterReadme.exists()) {
            flutterReadme.writeText(
                """
                |# Flutter Source Asset
                |
                |This directory is reserved for Project-owned Flutter source generated or edited
                |from the Alpine workspace.
                |
                |Runtime boundary:
                |- `frontend/page_spec.json` is the immediate OOB Display contract rendered by the
                |  installed app.
                |- `frontend/flutter/` is editable source for the `/workbench/flutter_eval`
                |  renderer, export, or a controlled native build path.
                |- Dart code written here can run only inside the supported flutter_eval subset.
                |  Unsupported packages/native integrations need a controlled Flutter build/install
                |  path before they can run as app code.
                |
                |Use this directory when a Project needs highly custom Flutter beyond the current
                |page-spec renderer. Keep `page_spec.json` as the fast preview surface.
                |
                """.trimMargin()
            )
        }
        val pageSpec = File(frontendDir, "page_spec.json")
        if (!pageSpec.exists()) {
            pageSpec.writeText(gson.toJson(defaultPageSpec(record, apis, sourcePrompt, config)))
        }
        val apiSpec = File(backendDir, "api_spec.json")
        val apiSpecText = if (apiSpec.exists()) apiSpec.readText() else ""
        val needsToolContractUpgrade = !apiSpec.exists() ||
            !apiSpecText.contains("\"toolbox\"") ||
            !apiSpecText.contains("\"toolName\"") ||
            !apiSpecText.contains("\"apiVersion\"")
        if (needsToolContractUpgrade) {
            writeBackendApiSpec(record, apis, sourcePrompt ?: readProjectSourcePrompt(record.projectId))
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
                |Project Tool and do not hand-edit `manifest.json`.
                |
                |URL-only imports are recorded as fetch-required metadata. Fetch through the
                |approved terminal/tool path, then ingest the downloaded directory with
                |`sourcePath` to analyze package files and entrypoints.
                |
                """.trimMargin()
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
                |Project Tool and do not hand-edit `manifest.json`.
                |
                """.trimMargin()
            )
        }
    }

    /**
     * Rewrites `backend/api_spec.json` from the current Project Tool registry.
     *
     * @param record Project whose backend contract is being materialized.
     * @param apis Business APIs owned by the Project; control APIs are intentionally excluded.
     * @param sourcePrompt Original creation prompt, when known.
     */
    private fun writeBackendApiSpec(
        record: WorkbenchProjectRecord,
        apis: List<WorkbenchApiRecord>,
        sourcePrompt: String?
    ) {
        val apiSpec = File(projectDir(record.projectId), "backend/api_spec.json")
        apiSpec.parentFile?.mkdirs()
        val counts = apiExecutionCounts(record.projectId)
        apiSpec.writeText(
            gson.toJson(
                linkedMapOf<String, Any?>(
                    "executorBoundary" to "oob_native_workbench",
                    "sourcePrompt" to sourcePrompt,
                        "runtime" to linkedMapOf(
                            "workspace" to record.spacePath,
                            "frontendPageSpec" to "frontend/page_spec.json",
                            "frontendHtmlSource" to "frontend/html/",
                            "frontendHtmlManifest" to "frontend/html/manifest.json",
                            "frontendFlutterSource" to "frontend/flutter/",
                            "frontendFlutterManifest" to "frontend/flutter/manifest.json",
                            "progressLog" to "logs/project_progress.jsonl",
                            "sourceManifest" to "source/manifest.json",
                            "androidManifest" to "android/manifest.json",
                            "executorKinds" to apis.map { it.executorKind }.distinct()
                        ),
                    "iterationContract" to projectIterationContract(),
                    "controlApis" to listOf(
                        "workbench_project_update",
                        "workbench_project_progress_get",
                        "workbench_project_ingest_oss",
                        "workbench_project_ingest_android",
                        "workbench_project_hot_update",
                        "workbench_project_export"
                    ),
                    "toolbox" to WorkbenchToolboxBuilder.toolboxPayload(record, apis, counts),
                    "promptDecomposition" to promptDecomposition(record, apis),
                    "apis" to apis.map { api ->
                        api.toPayload(counts[api.apiId] ?: 0) + linkedMapOf(
                            "runtime" to linkedMapOf(
                                "executorBoundary" to "oob_native_workbench",
                                "executorKind" to api.executorKind,
                                "run" to WorkbenchToolboxBuilder.runContract(api),
                                "workingDirectory" to "backend",
                                "status" to when (api.executorKind) {
                                    WORKBENCH_WORKSPACE_PYTHON_EXECUTOR_KIND ->
                                        "native_backed_workspace_script_contract"
                                    WORKBENCH_AGENT_TASK_EXECUTOR_KIND -> "agent_task_dispatch"
                                    else -> "native_executor"
                                },
                                "scriptPath" to if (api.executorKind == WORKBENCH_WORKSPACE_PYTHON_EXECUTOR_KIND) {
                                    "backend/scripts/${api.apiId.replace('.', '_')}.py"
                                } else {
                                    null
                                }
                            ),
                            "sourceRefs" to listOf(
                                linkedMapOf("kind" to "page_spec", "path" to "frontend/page_spec.json"),
                                linkedMapOf("kind" to "api_spec", "path" to "backend/api_spec.json")
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

    /**
     * Builds the editable frontend contract for a new Project.
     *
     * @param record Project registry record used for route and identity.
     * @param apis Business APIs that the Display can bind to.
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
        val entityName = projectEntityName(config, record.name, sourcePrompt)
        val title = config["title"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: config["displayName"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: record.name
        val description = config["description"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: "Display and operate $entityName records through Project APIs."
        return linkedMapOf(
            "pageId" to "project-main-page",
            "displayId" to "project-main-display",
            "title" to title,
            "shortName" to projectShortName(entityName),
            "description" to description,
            "renderer" to "oob_project_display",
            "surfaceKind" to "app_display",
            "route" to record.route,
            "sourcePrompt" to sourcePrompt,
            "entityName" to entityName,
            "primaryField" to "title",
            "decomposition" to listOf(
                "Prompt requirement -> Project registry",
                "App Display -> user workflow for $entityName",
                "Visible controls -> domain actions",
                "Control/debug surfaces -> Project metadata, tool counts, Toolbox, logs, and Workspace"
            ),
            "displayRules" to appDisplayRules(),
            "state" to linkedMapOf("items" to "data/items.json"),
            "frontendRuntime" to linkedMapOf(
                "instantSurface" to "frontend/page_spec.json",
                "editableHtmlSource" to "frontend/html/",
                "editableFlutterSource" to "frontend/flutter/",
                "htmlRuntimeBoundary" to "HTML source is generated and edited in the Project workspace and can be loaded live by /workbench/html through WebView.",
                "compileBoundary" to "Flutter source is generated and edited in the Project workspace; it is loaded by /workbench/flutter_eval for the supported runtime subset."
            ),
            "fields" to listOf(
                linkedMapOf(
                    "id" to "title",
                    "label" to entityName,
                    "type" to "string",
                    "required" to true
                )
            ),
            "actions" to apis.map { api ->
                val action = projectApiAction(api)
                linkedMapOf(
                    "apiId" to api.apiId,
                    "kind" to action,
                    "label" to api.displayName,
                    "inputs" to if (action == "archive" || action == "update") {
                        linkedMapOf("item_id" to "item.id")
                    } else {
                        linkedMapOf("title" to "page.input.title")
                    }
                )
            }
        )
    }

    /**
     * Returns the app/control surface split that every Display must preserve.
     *
     * The payload is persisted into `frontend/page_spec.json` so future agents and hot-update
     * passes keep user-facing app screens separate from Workbench management/debug metadata.
     */
    private fun appDisplayRules(): Map<String, Any?> = linkedMapOf(
        "appSurfaceMustShow" to listOf(
            "domain workflow",
            "input forms",
            "current records or state",
            "filters and business actions",
            "empty/loading/error states",
            "in-app navigation between Project displays when the workflow has multiple pages"
        ),
        "displayNavigation" to linkedMapOf(
            "scope" to "right_workbench_display",
            "leftChat" to "unchanged",
            "rule" to "Display-to-display navigation changes only the Workbench app surface, not the Home chat."
        ),
        "controlSurfaceOnly" to listOf(
            "project id",
            "Tool count",
            "executor kind",
            "Toolbox manifest",
            "Workspace path",
            "data/log paths",
            "backend/api_spec.json",
            "frontend/page_spec.json",
            "progress rows",
            "export/delete controls"
        )
    )

    /**
     * Explains the prompt-to-runtime split in `backend/api_spec.json`.
     *
     * @param record Project whose workspace owns the state file.
     * @param apis Registered Project Tools for this Project.
     * @return Stable metadata map for handoff, export, and future agents.
     */
    private fun promptDecomposition(
        record: WorkbenchProjectRecord,
        apis: List<WorkbenchApiRecord>
    ): Map<String, Any?> {
        return linkedMapOf(
            "frontend" to "frontend/page_spec.json",
            "html" to "frontend/html/",
            "data" to "data/items.json",
            "logs" to "logs/api_calls.jsonl",
            "hotUpdates" to "logs/hot_updates.jsonl",
            "businessApis" to apis.map { it.apiId }
        )
    }

    /**
     * Lists Project files that a Project Tool is expected to read or write.
     *
     * @param record Project whose workspace owns the state file.
     * @return Relative persistence paths stored in API specs.
     */
    private fun persistenceFiles(record: WorkbenchProjectRecord): List<String> {
        return listOf("data/items.json", "logs/api_calls.jsonl")
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
     * Counts persisted Project Tool executions from the append-only tool call log.
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

    /**
     * Performs minimal Tool Contract input validation before the executor runs.
     *
     * @param api Business API whose `inputSchema` declares required keys. String schema values
     * ending in `?` are optional; map schemas may set `required=false` or `nullable=true`.
     * @param inputs Caller-supplied input payload.
     * @return A human-readable validation error, or null when the payload is acceptable.
     */
    private fun validateApiInputs(api: WorkbenchApiRecord, inputs: Map<String, Any?>): String? {
        val missing = api.inputSchema.mapNotNull { (key, spec) ->
            if (!isRequiredInput(spec)) return@mapNotNull null
            val value = inputs[key]
            val isMissing = !inputs.containsKey(key) || value == null
            if (isMissing) key else null
        }
        return if (missing.isEmpty()) {
            null
        } else {
            "Missing required input field(s): ${missing.joinToString(", ")}"
        }
    }

    /**
     * Determines whether one Tool Contract schema field is required.
     *
     * @param spec Simple schema value from `inputSchema`, usually `string` or `string?`.
     * @return True when callers must provide a nonblank value before execution.
     */
    private fun isRequiredInput(spec: Any?): Boolean {
        if (spec is Map<*, *>) {
            val required = spec["required"]
            if (required is Boolean) return required
            if (required != null) return required.toString().equals("true", ignoreCase = true)
            val nullable = spec["nullable"]
            if (nullable is Boolean) return !nullable
            if (nullable != null) return !nullable.toString().equals("true", ignoreCase = true)
        }
        return !spec?.toString().orEmpty().trim().endsWith("?")
    }

    private fun appendApiCall(
        projectId: String,
        api: WorkbenchApiRecord,
        inputs: Map<String, Any?>,
        caller: String,
        result: Map<String, Any?>,
        startedAtMillis: Long = System.currentTimeMillis()
    ) {
        val file = File(projectDir(projectId), "logs/api_calls.jsonl")
        file.parentFile?.mkdirs()
        val finishedAt = System.currentTimeMillis()
        val row = linkedMapOf<String, Any?>(
            "timestamp" to nowIso(),
            "projectId" to projectId,
            "apiId" to api.apiId,
            "toolId" to api.toolId,
            "toolName" to WorkbenchToolboxBuilder.toolName(projectId, api.apiId),
            "caller" to caller.ifBlank { "unknown" },
            "inputs" to inputs,
            "success" to (result["success"] == true),
            "outputs" to result["outputs"],
            "errorCode" to result["errorCode"],
            "errorMessage" to result["errorMessage"],
            "durationMs" to (finishedAt - startedAtMillis).coerceAtLeast(0)
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
     * Reduces a full tool call result to a log-safe action summary.
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

    private fun hotUpdateItemTitle(prompt: String): String {
        val candidate = when {
            prompt.contains("：") -> prompt.substringAfter("：")
            prompt.contains(":") -> prompt.substringAfter(":")
            else -> prompt
        }.trim().ifBlank { prompt }
        return candidate.take(120)
    }

    /**
     * Applies a simple natural-language hot update to the default Project item API.
     *
     * @param request Raw user hot-update request.
     * @param wantsAdd Whether the prompt appears to request a new item.
     * @param wantsArchive Whether the prompt appears to request archiving an item.
     * @param appliedActions Mutable action list written into `hot_updates.jsonl`.
     */
    private fun applyProjectDataHotUpdate(
        record: WorkbenchProjectRecord,
        request: String,
        wantsAdd: Boolean,
        wantsArchive: Boolean,
        appliedActions: MutableList<Map<String, Any?>>
    ) {
        val apis = projectApis(record.projectId)
        val createApi = apis.firstOrNull { projectApiAction(it) == "create" }
        val archiveApi = apis.firstOrNull { projectApiAction(it) == "archive" }
        if (wantsArchive && archiveApi != null) {
            val activeItem = readProjectItems(record.projectId).firstOrNull { it.status != "archived" }
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
                            inputs = mapOf("title" to hotUpdateItemTitle(request)),
                            caller = WORKBENCH_HOT_UPDATE_CALLER
                        )
                )
            )
        }
    }

    /**
     * Parses initial generic items from Project creation config.
     *
     * @param config Project creation config; accepts `initialItems` as strings or maps.
     * @return Initial item records for `data/items.json`.
     */
    private fun initialProjectItems(config: Map<String, Any?>): List<WorkbenchProjectItemRecord> {
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
            WorkbenchProjectItemRecord(
                id = "item-initial-${index + 1}",
                title = title,
                status = "active",
                fields = fields,
                createdAt = now
            )
        }
    }

    /**
     * Builds generic Project Tools from explicit config or a prompt-derived entity namespace.
     *
     * @param projectId Project namespace for API registry records.
     * @param config Creation config. `apis` may provide apiId/displayName/schema/executorKind.
     * @return API records handled by the native Project collection executor.
     */
    private fun defaultProjectApis(
        projectId: String,
        config: Map<String, Any?>
    ): List<WorkbenchApiRecord> {
        val explicit = explicitApiRecords(projectId, config)
        if (explicit.isNotEmpty()) return explicit
        val namespace = projectApiNamespace(config)
        return listOf(
            WorkbenchApiRecord(
                apiId = "$namespace.create",
                projectId = projectId,
                toolId = "$namespace.create",
                displayName = "Create ${projectEntityName(config, namespace, null)}",
                description = "Create an item in this Project state.",
                inputSchema = linkedMapOf("title" to "string"),
                outputSchema = linkedMapOf("item" to "object"),
                executorKind = WORKBENCH_COLLECTION_EXECUTOR_KIND
            ),
            WorkbenchApiRecord(
                apiId = "$namespace.archive",
                projectId = projectId,
                toolId = "$namespace.archive",
                displayName = "Archive ${projectEntityName(config, namespace, null)}",
                description = "Archive an active item in this Project state.",
                inputSchema = linkedMapOf("item_id" to "string"),
                outputSchema = linkedMapOf("item" to "object"),
                executorKind = WORKBENCH_COLLECTION_EXECUTOR_KIND
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
        val raw = config["apis"] ?: return emptyList()
        if (raw !is Iterable<*>) return emptyList()
        return raw.mapNotNull { item ->
            @Suppress("UNCHECKED_CAST")
            val map = item as? Map<String, Any?> ?: return@mapNotNull null
            val apiId = map["apiId"]?.toString()?.trim()
                ?: map["toolId"]?.toString()?.trim()
                ?: return@mapNotNull null
            if (apiId.isEmpty()) return@mapNotNull null
            val run = normalizeProjectToolRun(
                map["run"] ?: map["runUse"] ?: map["use"]
            )
            WorkbenchApiRecord(
                apiId = apiId,
                projectId = projectId,
                toolId = map["toolId"]?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: apiId,
                displayName = map["displayName"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: apiId,
                description = map["description"]?.toString()?.trim().orEmpty(),
                inputSchema = normalizeProjectToolSchema(map["inputSchema"]),
                outputSchema = normalizeProjectToolSchema(map["outputSchema"]),
                executorKind = map["executorKind"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: inferProjectToolExecutorKind(run, apiId),
                run = run.ifEmpty { null }
            )
        }
    }


    /**
     * Normalizes Project Tool `run` declarations into the one-step v0.1 shape.
     *
     * @param value Creator-supplied run object, string shorthand, or null.
     * @return JSON-safe map. A string such as `oob.vlm_task` becomes `{use: oob.vlm_task}`.
     */
    private fun normalizeProjectToolRun(value: Any?): Map<String, Any?> {
        return when (value) {
            is Map<*, *> -> value.entries.associate { it.key.toString() to it.value }
            is String -> value.trim().takeIf { it.isNotEmpty() }
                ?.let { linkedMapOf("use" to it) }
                ?: emptyMap()
            else -> emptyMap()
        }
    }

    /**
     * Infers the existing executor boundary for a Project Tool run declaration.
     *
     * @param run Normalized Project Tool run map.
     * @param apiId Tool id used as a fallback when no run.use is supplied.
     * @return Existing executor kind string; this keeps Project Tool as a light mapper.
     */
    private fun inferProjectToolExecutorKind(
        run: Map<String, Any?>,
        apiId: String
    ): String {
        val use = run["use"]?.toString()?.trim()?.lowercase().orEmpty()
        return when {
            use == "agent" || use.startsWith("agent.") -> WORKBENCH_AGENT_TASK_EXECUTOR_KIND
            use.startsWith("oob.") || use.startsWith("mcp.") -> WORKBENCH_AGENT_TASK_EXECUTOR_KIND
            use.startsWith("native.collection.") -> WORKBENCH_COLLECTION_EXECUTOR_KIND
            use == "script" || use.startsWith("script.") -> WORKBENCH_WORKSPACE_PYTHON_EXECUTOR_KIND
            else -> WORKBENCH_COLLECTION_EXECUTOR_KIND
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
     * Accepts both OOB's compact Project Tool schema (`{"title":"string"}`) and standard JSON
     * Schema (`{"type":"object","properties":{"title":{"type":"string"}},"required":["title"]}`).
     *
     * Internally the registry stores a field map because executors validate business inputs, not
     * schema documents. Without this normalization, `type`, `properties`, and `required` become
     * fake required business fields and valid calls like `{weight: 80}` fail validation.
     */
    private fun normalizeProjectToolSchema(value: Any?): Map<String, Any?> {
        val raw = asStringKeyMap(value)
        val properties = raw["properties"] as? Map<*, *> ?: return raw
        val requiredFields = requiredFieldSet(raw["required"])
        val hasRequiredDeclaration = raw.containsKey("required")
        return properties.entries.associate { entry ->
            val fieldName = entry.key.toString()
            val fieldSpec = asRegistryFieldSpec(entry.value).toMutableMap()
            val explicitRequired = booleanLike(fieldSpec["required"])
            val explicitNullable = booleanLike(fieldSpec["nullable"])
            val required = when {
                hasRequiredDeclaration -> requiredFields.contains(fieldName)
                explicitRequired != null -> explicitRequired
                explicitNullable != null -> !explicitNullable
                else -> false
            }
            fieldSpec["required"] = required
            fieldName to fieldSpec
        }
    }

    private fun asRegistryFieldSpec(value: Any?): Map<String, Any?> {
        val map = asStringKeyMap(value)
        if (map.isNotEmpty()) {
            return map
        }
        val raw = value?.toString()?.trim().orEmpty()
        val type = raw.removeSuffix("?").ifBlank { "string" }
        return linkedMapOf(
            "type" to type,
            "required" to (raw.isNotEmpty() && !raw.endsWith("?"))
        )
    }

    private fun requiredFieldSet(value: Any?): Set<String> {
        return when (value) {
            is Iterable<*> -> value.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }.toSet()
            is Array<*> -> value.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }.toSet()
            is String -> value.split(',', ' ', '\n', '\t')
                .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
                .toSet()
            else -> emptySet()
        }
    }

    private fun booleanLike(value: Any?): Boolean? {
        return when (value) {
            is Boolean -> value
            is String -> when (value.trim().lowercase()) {
                "true" -> true
                "false" -> false
                else -> null
            }
            else -> null
        }
    }

    /**
     * Executes generic Project item create/archive/update/list APIs.
     *
     * @param projectId Project whose `data/items.json` should be updated.
     * @param api API registry record. The action is inferred from its id.
     * @param inputs Tool inputs from AI or UI.
     * @return Tool-style success/error payload.
     */
    private fun callProjectCollectionApi(
        projectId: String,
        api: WorkbenchApiRecord,
        inputs: Map<String, Any?>
    ): Map<String, Any?> {
        return when (projectApiAction(api)) {
            "create" -> createProjectItem(projectId, api, inputs)
            "archive" -> archiveProjectItem(projectId, api, inputs)
            "update" -> updateProjectItem(projectId, api, inputs)
            "list" -> listProjectItems(projectId, api)
            else -> apiError(api, "UNKNOWN_PROJECT_ACTION", "Unknown Project Tool: ${api.apiId}")
        }
    }


    /**
     * Checks whether a Project Tool should be submitted to the normal OOB Agent runtime.
     *
     * @param api Registered Project Tool/API record.
     * @return True for agent, OOB tool, and MCP tool backed runs.
     */
    private fun shouldRunAsAgentTask(api: WorkbenchApiRecord): Boolean {
        val use = WorkbenchToolboxBuilder.runUse(api).lowercase()
        return api.executorKind == WORKBENCH_AGENT_TASK_EXECUTOR_KIND ||
            use == "agent" ||
            use.startsWith("agent.") ||
            use.startsWith("oob.") ||
            use.startsWith("mcp.")
    }

    /**
     * Starts an async Agent task for a Project Tool that needs OOB capability composition.
     *
     * @param record Owning Project record used for context injection.
     * @param api Project Tool being executed.
     * @param inputs Caller-supplied inputs from UI, AI, or MCP.
     * @param caller Audit caller label written into Project logs.
     * @return Tool-style pending result containing Agent task and conversation ids.
     */
    private fun callAgentTaskApi(
        record: WorkbenchProjectRecord,
        api: WorkbenchApiRecord,
        inputs: Map<String, Any?>,
        caller: String
    ): Map<String, Any?> {
        val context = appContext ?: return apiError(
            api,
            "AGENT_CONTEXT_UNAVAILABLE",
            "Project Tool ${api.toolId} requires the OOB app context to start an Agent task."
        )
        val run = WorkbenchToolboxBuilder.runContract(api)
        val taskId = "project-tool-${System.currentTimeMillis()}-${UUID.randomUUID()}"
        return runCatching {
            runBlocking {
                val conversationService = ConversationDomainService(context)
                val conversation = conversationService.createConversation(
                    title = "Project Tool: ${api.displayName}",
                    mode = "normal"
                )
                val conversationId = (conversation["id"] as? Number)?.toLong()
                    ?: conversation["id"]?.toString()?.toLongOrNull()
                    ?: throw IllegalStateException("Conversation id is invalid")
                val accepted = AgentRunService(context).startConversationRun(
                    conversationId = conversationId,
                    request = linkedMapOf(
                        "taskId" to taskId,
                        "conversationMode" to "subagent",
                        "title" to "Project Tool: ${api.displayName}",
                        "userMessage" to buildAgentTaskPrompt(record, api, run, inputs, caller)
                    )
                )
                apiSuccess(
                    api,
                    linkedMapOf(
                        "status" to "pending",
                        "taskId" to (accepted["taskId"] ?: taskId),
                        "conversationId" to conversationId,
                        "run" to run,
                        "message" to "Project Tool submitted to the OOB Agent runtime."
                    )
                )
            }
        }.getOrElse { error ->
            apiError(api, "AGENT_TASK_START_FAILED", error.message ?: "Agent task failed to start.")
        }
    }

    /**
     * Builds the prompt used when a Project Tool delegates to the normal OOB Agent runtime.
     *
     * @param record Project that owns the tool and workspace context.
     * @param api Project Tool being executed.
     * @param run Normalized `run` declaration, such as `{use: oob.vlm_task}`.
     * @param inputs Caller-supplied inputs to include as JSON.
     * @param caller Audit label used by UI, AI, MCP, or tests.
     * @return Agent user message with enough Project context to reuse OOB capabilities.
     */

    /**
     * Executes a Project Tool backed by a workspace Python script.
     *
     * Resolves the script path from [WorkbenchApiRecord.metadata] key `scriptPath`, falling back to
     * `backend/scripts/<apiId>.py`. Inputs are passed as a JSON string via stdin; the script must
     * print a single JSON object on the last stdout line as its result.
     */
    private fun callWorkspaceScriptApi(
        projectId: String,
        api: WorkbenchApiRecord,
        inputs: Map<String, Any?>
    ): Map<String, Any?> {
        val ctx = appContext ?: return apiError(
            api, "CONTEXT_UNAVAILABLE", "workspace_script executor requires app context."
        )
        val scriptRelPath = api.run?.get("scriptPath")?.toString()?.trim()
            ?: "backend/scripts/${api.apiId.replace('.', '_')}.py"
        val scriptFile = File(projectDir(projectId), scriptRelPath)
        if (!scriptFile.exists()) {
            return apiError(
                api, "SCRIPT_NOT_FOUND",
                "Workspace script not found: $scriptRelPath (projectId=$projectId)"
            )
        }
        val inputsJson = logGson.toJson(inputs)
        val command = "echo ${shellQuote(inputsJson)} | python3 -u ${shellQuote(scriptFile.absolutePath)}"
        val result = runBlocking {
            EmbeddedTerminalRuntime.executeCommand(
                context = ctx,
                command = command,
                workingDirectory = projectDir(projectId).absolutePath,
                timeoutSeconds = 60
            )
        }
        if (!result.success) {
            return apiError(
                api, "SCRIPT_ERROR",
                result.errorMessage ?: result.output.takeLast(400).ifBlank { "Script exited non-zero." }
            )
        }
        val lastLine = result.output.trimEnd().lines().lastOrNull { it.isNotBlank() }.orEmpty()
        val parsed = runCatching {
            @Suppress("UNCHECKED_CAST")
            logGson.fromJson(lastLine, Map::class.java) as Map<String, Any?>
        }.getOrNull()
        return if (parsed != null) {
            apiSuccess(api, parsed)
        } else {
            apiSuccess(api, linkedMapOf("output" to result.output.trim()))
        }
    }

    private fun shellQuote(s: String): String = "'${s.replace("'", "'\\''")}'"

    private fun buildAgentTaskPrompt(
        record: WorkbenchProjectRecord,
        api: WorkbenchApiRecord,
        run: Map<String, Any?>,
        inputs: Map<String, Any?>,
        caller: String
    ): String {
        val activeItems = readProjectItems(record.projectId).filter { it.status == "active" }
        val itemsSummary = if (activeItems.isEmpty()) {
            "(no active items)"
        } else {
            activeItems.take(20).joinToString("\n") { item ->
                val fieldStr = if (item.fields.isEmpty()) "" else " | ${item.fields.entries.joinToString(", ") { "${it.key}=${it.value}" }}"
                "- [${item.id}] ${item.title}$fieldStr"
            }.let { if (activeItems.size > 20) it + "\n- ... (${activeItems.size - 20} more)" else it }
        }
        return """
            Execute this OOB Project Tool as an async Agent task.

            Project:
            - projectId: ${record.projectId}
            - name: ${record.name}
            - workspace: ${record.spacePath}

            Current data (${activeItems.size} active items):
            $itemsSummary

            Tool:
            - toolId: ${api.toolId}
            - displayName: ${api.displayName}
            - description: ${api.description}
            - run: ${logGson.toJson(run)}
            - caller: ${caller.ifBlank { "unknown" }}

            Inputs JSON:
            ${logGson.toJson(inputs)}

            Rules:
            - Use "current data" above as the live Project state — no need to call list first.
            - If run.use starts with oob. or mcp., use the matching OOB/MCP capability through the normal Agent tool chain.
            - To write results back, call workbench_api_call with this projectId and the appropriate toolId.
            - Do not recreate this Project or write registry files directly.
        """.trimIndent()
    }

    private fun createProjectItem(
        projectId: String,
        api: WorkbenchApiRecord,
        inputs: Map<String, Any?>
    ): Map<String, Any?> {
        val title = projectItemTitle(inputs)
        if (title.isEmpty()) {
            return apiError(api, "EMPTY_ITEM_TITLE", "Item title is required.")
        }
        val items = readProjectItems(projectId).toMutableList()
        val fields = inputs.filterKeys { key ->
            key !in setOf("id", "item_id", "itemId", "title", "name", "label", "status")
        }
        val item = WorkbenchProjectItemRecord(
            id = "item-${System.currentTimeMillis()}-${items.size + 1}",
            title = title,
            status = "active",
            fields = fields,
            createdAt = nowIso()
        )
        items.add(0, item)
        writeProjectItems(projectId, items)
        return apiSuccess(api, mapOf("item" to item.toPayload()))
    }

    private fun archiveProjectItem(
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
        val items = readProjectItems(projectId)
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
        writeProjectItems(projectId, updated)
        return apiSuccess(api, mapOf("item" to archived.toPayload()))
    }

    /**
     * Updates fields on one Project item.
     */
    private fun updateProjectItem(
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
        val items = readProjectItems(projectId)
        val index = items.indexOfFirst { it.id == itemId }
        if (index < 0) {
            return apiError(api, "ITEM_NOT_FOUND", "Item not found: $itemId")
        }
        val current = items[index]
        val title = inputs["title"]?.toString()?.trim()
            ?: inputs["name"]?.toString()?.trim()
            ?: current.title
        val fieldUpdates = inputs.filterKeys { key ->
            key !in setOf("id", "item_id", "itemId", "title", "name", "label", "status")
        }.filterValues { value -> value != null }
        val updated = current.copy(
            title = title.take(160).ifBlank { current.title },
            fields = current.fields + fieldUpdates
        )
        val next = items.toMutableList()
        next[index] = updated
        writeProjectItems(projectId, next)
        return apiSuccess(api, mapOf("item" to updated.toPayload()))
    }

    /**
     * Reads Project items without mutating Project data.
     */
    private fun listProjectItems(projectId: String, api: WorkbenchApiRecord): Map<String, Any?> {
        val items = readProjectItems(projectId)
        return apiSuccess(
            api,
            mapOf(
                "items" to items.map { it.toPayload() },
                "activeItems" to items.filter { it.status != "archived" }.map { it.toPayload() },
                "archivedItems" to items.filter { it.status == "archived" }.map { it.toPayload() }
            )
        )
    }

    private fun projectItemTitle(inputs: Map<String, Any?>): String {
        val direct = inputs["title"]?.toString()?.trim()
            ?: inputs["name"]?.toString()?.trim()
            ?: inputs["label"]?.toString()?.trim()
        if (!direct.isNullOrEmpty()) return direct.take(160)
        return inputs.values.firstOrNull { value ->
            value?.toString()?.trim()?.isNotEmpty() == true
        }?.toString()?.trim()?.take(160).orEmpty()
    }

    private fun projectApiAction(api: WorkbenchApiRecord): String {
        val id = "${api.apiId}.${api.toolId}".lowercase()
        return when {
            listOf(".archive", ".finish", ".complete", ".done").any { id.contains(it) } ->
                "archive"
            listOf(".update", ".edit", ".patch").any { id.contains(it) } ->
                "update"
            listOf(".list", ".read", ".query", ".search").any { id.contains(it) } ->
                "list"
            listOf(".create", ".add", ".new").any { id.contains(it) } ->
                "create"
            else -> "custom"
        }
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
        } ?: throw IllegalArgumentException("Workbench Project Tool not found: $apiIdOrToolId")
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

    /**
     * Reads Project items from `data/items.json`.
     */
    private fun readProjectItems(projectId: String): List<WorkbenchProjectItemRecord> {
        val file = projectItemsFile(projectId)
        if (!file.exists()) return emptyList()
        return runCatching {
            gson.fromJson<List<WorkbenchProjectItemRecord>>(
                file.readText(),
                projectItemRecordListType
            ) ?: emptyList()
        }.getOrElse { emptyList() }
    }

    /**
     * Writes Project item state to `data/items.json`.
     */
    private fun writeProjectItems(projectId: String, items: List<WorkbenchProjectItemRecord>) {
        val file = projectItemsFile(projectId)
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
     * Builds one read-only MCP Resource descriptor.
     *
     * @param uri Stable resource URI. The URI is an OOB logical id, not a filesystem path.
     * @param name Human-readable resource name.
     * @param description Short description for MCP clients.
     * @return MCP `resources/list` descriptor.
     */
    private fun mcpResource(uri: String, name: String, description: String): Map<String, Any?> {
        return linkedMapOf(
            "uri" to uri,
            "name" to name,
            "description" to description,
            "mimeType" to "application/json"
        )
    }

    private fun readApiCallLog(projectId: String, limit: Int): List<Map<String, Any?>> {
        val file = File(projectDir(projectId), "logs/api_calls.jsonl")
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
     * Reads the most recent failed Project Tool call for Project summaries.
     *
     * @param projectId Project whose tool call log should be inspected.
     * @return Latest structured error row, or null when no failed call is present.
     */
    private fun lastApiError(projectId: String): Map<String, Any?>? {
        val file = File(projectDir(projectId), "logs/api_calls.jsonl")
        if (!file.exists()) return null
        return file.readLines()
            .asReversed()
            .mapNotNull { line ->
                runCatching {
                    gson.fromJson<Map<String, Any?>>(line, mapType)
                }.getOrNull()
            }
            .firstOrNull { row -> row["success"] != true }
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
     * @return Stack labels that guide future Project Tool binding.
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

    private fun stringConfigArg(args: Map<String, Any?>, key: String): String {
        return args[key]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("$key is required.")
    }

    private fun defaultProjectId(config: Map<String, Any?>): String {
        return "oob-workbench-${projectApiNamespace(config)}"
    }

    private fun defaultProjectName(config: Map<String, Any?>): String {
        return "${projectEntityName(config, "Project", null)} Workbench"
    }

    private fun routeForProjectId(projectId: String): String {
        return "/workbench/project?projectId=$projectId"
    }

    /**
     * Resolves a human-facing entity name for Project data.
     *
     * @param config Project creation config supplied by UI or Agent.
     * @param fallback Value used when no explicit entity can be found.
     * @param prompt Optional natural language prompt used for simple domain inference.
     * @return Display entity label stored in frontend/backend specs.
     */
    private fun projectEntityName(
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

    private fun projectApiNamespace(config: Map<String, Any?>): String {
        val raw = config["apiNamespace"]?.toString()?.trim()
            ?: config["entityName"]?.toString()?.trim()
            ?: config["entity"]?.toString()?.trim()
            ?: config["name"]?.toString()?.trim()
            ?: "item"
        return sanitizeApiSegment(raw)
    }

    private fun projectShortName(entityName: String): String {
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

    private fun projectItemsFile(projectId: String): File {
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
