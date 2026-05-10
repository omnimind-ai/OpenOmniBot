package cn.com.omnimind.bot.agent

import android.content.Context
import android.os.FileObserver
import cn.com.omnimind.baselib.llm.DeepSeekProvider
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.ModelProviderProfile
import cn.com.omnimind.baselib.llm.SceneModelBindingEntry
import cn.com.omnimind.baselib.llm.SceneModelBindingStore
import cn.com.omnimind.baselib.llm.SceneVoiceConfig
import cn.com.omnimind.baselib.llm.SceneVoiceConfigStore
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.manager.AssistsCoreManager
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private data class AgentAiCapabilityConfigSnapshot(
    val currentProviderId: String = "",
    val providers: List<AgentAiCapabilityProviderSnapshot> = emptyList(),
    val sceneModels: Map<String, AgentAiCapabilitySceneModelSnapshot> = emptyMap(),
    val sceneSettings: Map<String, AgentAiCapabilitySceneSettingsSnapshot> = emptyMap()
)

private data class AgentAiCapabilityProviderSnapshot(
    val id: String = "",
    val name: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val protocolType: String = "openai_compatible"
)

private data class AgentAiCapabilitySceneModelSnapshot(
    val providerId: String = "",
    val model: String = ""
)

private data class AgentAiCapabilitySceneSettingsSnapshot(
    val autoPlay: Boolean = false,
    val voiceId: String = SceneVoiceConfigStore.VOICE_DEFAULT_ZH,
    val stylePreset: String = SceneVoiceConfigStore.STYLE_DEFAULT,
    val customStyle: String = ""
)

private data class AgentAiCapabilityConfigPartial(
    val currentProviderId: String? = null,
    val editingProfileId: String? = null,
    val providers: List<AgentAiCapabilityProviderPartial>? = null,
    val profiles: List<AgentAiCapabilityProviderPartial>? = null,
    val sceneModels: JsonElement? = null,
    val sceneSettings: JsonElement? = null,
    val modelProviders: AgentAiCapabilityModelProvidersPartial? = null
)

private data class AgentAiCapabilityModelProvidersPartial(
    val currentProviderId: String? = null,
    val editingProfileId: String? = null,
    val providers: List<AgentAiCapabilityProviderPartial>? = null,
    val profiles: List<AgentAiCapabilityProviderPartial>? = null
)

private data class AgentAiCapabilityProviderPartial(
    val id: String? = null,
    val name: String? = null,
    val baseUrl: String? = null,
    val apiKey: String? = null,
    val protocolType: String? = null
)

class AgentAiCapabilityConfigSync private constructor(
    context: Context
) {
    companion object {
        private const val TAG = "AgentAiCapabilityConfig"

        @Volatile
        private var instance: AgentAiCapabilityConfigSync? = null

        fun get(context: Context): AgentAiCapabilityConfigSync {
            return instance ?: synchronized(this) {
                instance ?: AgentAiCapabilityConfigSync(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val workspaceManager = AgentWorkspaceManager(context.applicationContext)
    private val prettyGson: Gson = GsonBuilder()
        .disableHtmlEscaping()
        .setPrettyPrinting()
        .create()
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    @Volatile
    private var initialized = false

    @Volatile
    private var observer: FileObserver? = null

    @Volatile
    private var lastWrittenCanonicalJson: String? = null

    fun initialize() {
        synchronized(lock) {
            if (initialized) {
                return
            }
            workspaceManager.ensureRuntimeDirectories()
            bootstrapFromDiskLocked()
            startWatchingLocked()
            initialized = true
        }
    }

    fun syncFileFromStores() {
        synchronized(lock) {
            ensureInitializedLocked()
            writeSnapshotToFileLocked(buildSnapshotFromStoresLocked())
        }
    }

    private fun ensureInitializedLocked() {
        if (initialized) {
            return
        }
        workspaceManager.ensureRuntimeDirectories()
        bootstrapFromDiskLocked()
        startWatchingLocked()
        initialized = true
    }

    private fun bootstrapFromDiskLocked() {
        val file = configFileLocked()
        val current = buildSnapshotFromStoresLocked()
        if (!file.exists()) {
            writeSnapshotToFileLocked(current)
            return
        }

        val raw = runCatching { file.readText() }
            .onFailure {
                OmniLog.w(TAG, "read config file failed: ${it.message}")
            }
            .getOrNull()
        if (raw.isNullOrBlank()) {
            writeSnapshotToFileLocked(current)
            return
        }

        val partial = parsePartial(raw)
        if (partial == null) {
            OmniLog.w(TAG, "parse config file failed during bootstrap, rewrite canonical snapshot")
            writeSnapshotToFileLocked(current)
            return
        }

        applySnapshotToStoresLocked(
            snapshot = resolveSnapshot(partial = partial, fallback = current)
        )
        writeSnapshotToFileLocked(buildSnapshotFromStoresLocked())
    }

    private fun startWatchingLocked() {
        val file = configFileLocked()
        val soulFile = soulFileLocked()
        val chatFile = chatFileLocked()
        val parentDir = file.parentFile ?: return
        observer?.stopWatching()
        observer = object : FileObserver(
            parentDir.absolutePath,
            FileObserver.CLOSE_WRITE or
                FileObserver.CREATE or
                FileObserver.MOVED_TO or
                FileObserver.DELETE
        ) {
            override fun onEvent(event: Int, path: String?) {
                val changedName = path ?: return
                if (changedName != file.name &&
                    changedName != soulFile.name &&
                    changedName != chatFile.name
                ) {
                    return
                }
                scope.launch {
                    handleObservedFileChange(changedName)
                }
            }
        }.also { it.startWatching() }
    }

    private fun handleObservedFileChange(changedFileName: String) {
        synchronized(lock) {
            ensureInitializedLocked()
            when (changedFileName) {
                configFileLocked().name -> handleObservedConfigFileChangeLocked()
                soulFileLocked().name -> handleObservedSoulFileChangeLocked()
                chatFileLocked().name -> handleObservedChatFileChangeLocked()
            }
        }
    }

    private fun handleObservedConfigFileChangeLocked() {
        val file = configFileLocked()
        val current = buildSnapshotFromStoresLocked()

        if (!file.exists()) {
            writeSnapshotToFileLocked(current)
            return
        }

        val raw = runCatching { file.readText() }
            .onFailure {
                OmniLog.w(TAG, "read observed config failed: ${it.message}")
            }
            .getOrNull()
        if (raw.isNullOrBlank()) {
            writeSnapshotToFileLocked(current)
            return
        }

        val partial = parsePartial(raw)
        if (partial == null) {
            OmniLog.w(TAG, "observed config is invalid, restore canonical snapshot")
            writeSnapshotToFileLocked(current)
            return
        }

        val resolved = resolveSnapshot(partial = partial, fallback = current)
        val incomingCanonical = toCanonicalJson(resolved)
        if (incomingCanonical == lastWrittenCanonicalJson) {
            return
        }

        val changed = applySnapshotToStoresLocked(resolved)
        val effective = buildSnapshotFromStoresLocked()
        writeSnapshotToFileLocked(effective)
        if (changed) {
            AssistsCoreManager.dispatchAgentAiConfigChanged(
                source = "file",
                path = shellPathForFileLocked(file)
            )
        }
    }

    private fun handleObservedSoulFileChangeLocked() {
        if (!soulFileLocked().exists()) {
            workspaceManager.ensureRuntimeDirectories()
        }
        AssistsCoreManager.dispatchAgentAiConfigChanged(
            source = "file",
            path = shellPathForFileLocked(soulFileLocked())
        )
    }

    private fun handleObservedChatFileChangeLocked() {
        if (!chatFileLocked().exists()) {
            workspaceManager.ensureRuntimeDirectories()
        }
        AssistsCoreManager.dispatchAgentAiConfigChanged(
            source = "file",
            path = shellPathForFileLocked(chatFileLocked())
        )
    }

    private fun buildSnapshotFromStoresLocked(): AgentAiCapabilityConfigSnapshot {
        val providers = ModelProviderConfigStore.listProfiles()
            .map { profile ->
                AgentAiCapabilityProviderSnapshot(
                    id = profile.id,
                    name = profile.name,
                    baseUrl = profile.baseUrl,
                    apiKey = profile.apiKey,
                    protocolType = profile.protocolType
                )
            }
        val sceneModels = linkedMapOf<String, AgentAiCapabilitySceneModelSnapshot>()
        SceneModelBindingStore.getBindingEntries()
            .sortedBy { it.sceneId }
            .forEach { binding ->
                sceneModels[binding.sceneId] = AgentAiCapabilitySceneModelSnapshot(
                    providerId = binding.providerProfileId,
                    model = binding.modelId
                )
            }

        return AgentAiCapabilityConfigSnapshot(
            currentProviderId = ModelProviderConfigStore.getEditingProfileId(),
            providers = providers,
            sceneModels = sceneModels,
            sceneSettings = linkedMapOf(
                SceneVoiceConfigStore.SCENE_ID to SceneVoiceConfigStore.getConfig().toSnapshot()
            )
        )
    }

    private fun applySnapshotToStoresLocked(
        snapshot: AgentAiCapabilityConfigSnapshot
    ): Boolean {
        val before = buildSnapshotFromStoresLocked()

        val replacedProfiles = ModelProviderConfigStore.replaceProfiles(
            profiles = snapshot.providers.map { provider ->
                ModelProviderProfile(
                    id = provider.id.trim(),
                    name = provider.name.trim(),
                    baseUrl = provider.baseUrl.trim(),
                    apiKey = provider.apiKey.trim(),
                    protocolType = normalizeProtocolType(provider.protocolType)
                )
            },
            editingProfileId = snapshot.currentProviderId
        )
        val validProfileIds = replacedProfiles.map { it.id }.toSet()
        SceneModelBindingStore.replaceBindings(
            snapshot.sceneModels.entries.mapNotNull { (sceneId, sceneModel) ->
                val providerId = sceneModel.providerId.trim()
                val model = sceneModel.model.trim()
                if (!validProfileIds.contains(providerId)) {
                    return@mapNotNull null
                }
                SceneModelBindingEntry(
                    sceneId = sceneId.trim(),
                    providerProfileId = providerId,
                    modelId = model
                )
            }
        )
        snapshot.sceneSettings[SceneVoiceConfigStore.SCENE_ID]?.let { settings ->
            SceneVoiceConfigStore.saveConfig(settings.toSceneVoiceConfig())
        }

        val after = buildSnapshotFromStoresLocked()
        return after != before
    }

    private fun parsePartial(raw: String): AgentAiCapabilityConfigPartial? {
        return runCatching {
            gson.fromJson(raw, AgentAiCapabilityConfigPartial::class.java)
        }.onFailure {
            OmniLog.w(TAG, "parse config json failed: ${it.message}")
        }.getOrNull()
    }

    private fun resolveSnapshot(
        partial: AgentAiCapabilityConfigPartial,
        fallback: AgentAiCapabilityConfigSnapshot
    ): AgentAiCapabilityConfigSnapshot {
        val providers = partial.providers
            ?: partial.profiles
            ?: partial.modelProviders?.providers
            ?: partial.modelProviders?.profiles
        val currentProviderId = partial.currentProviderId
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: partial.editingProfileId
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: partial.modelProviders?.currentProviderId
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            ?: partial.modelProviders?.editingProfileId
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            ?: fallback.currentProviderId
        val sceneModels = partial.sceneModels
            ?.let { resolveSceneModels(it) }
            ?: fallback.sceneModels
        val sceneSettings = partial.sceneSettings
            ?.let { resolveSceneSettings(it) }
            ?: fallback.sceneSettings

        return AgentAiCapabilityConfigSnapshot(
            currentProviderId = currentProviderId,
            providers = providers?.map { provider ->
                AgentAiCapabilityProviderSnapshot(
                    id = provider.id?.trim().orEmpty(),
                    name = provider.name?.trim().orEmpty(),
                    baseUrl = provider.baseUrl?.trim().orEmpty(),
                    apiKey = provider.apiKey?.trim().orEmpty(),
                    protocolType = normalizeProtocolType(provider.protocolType)
                )
            } ?: fallback.providers,
            sceneModels = sceneModels,
            sceneSettings = sceneSettings
        )
    }

    private fun resolveSceneModels(
        element: JsonElement
    ): Map<String, AgentAiCapabilitySceneModelSnapshot>? {
        if (!element.isJsonObject) {
            return null
        }
        val obj = element.asJsonObject
        return if (obj.has("bindings") || obj.has("scenes")) {
            resolveLegacySceneModels(obj)
        } else {
            resolveSimpleSceneModels(obj)
        }
    }

    private fun resolveSimpleSceneModels(
        obj: JsonObject
    ): Map<String, AgentAiCapabilitySceneModelSnapshot> {
        val resolved = linkedMapOf<String, AgentAiCapabilitySceneModelSnapshot>()
        obj.entrySet()
            .sortedBy { it.key }
            .forEach { (sceneId, rawBinding) ->
                val normalizedSceneId = sceneId.trim()
                if (!SceneModelBindingStore.isValidSceneId(normalizedSceneId) ||
                    !rawBinding.isJsonObject
                ) {
                    return@forEach
                }
                val bindingObj = rawBinding.asJsonObject
                val providerId = firstNonBlank(
                    bindingObj.readString("providerId"),
                    bindingObj.readString("providerProfileId")
                )
                val model = firstNonBlank(
                    bindingObj.readString("model"),
                    bindingObj.readString("modelId")
                )
                if (providerId.isEmpty() || model.isEmpty()) {
                    return@forEach
                }
                resolved[normalizedSceneId] = AgentAiCapabilitySceneModelSnapshot(
                    providerId = providerId,
                    model = model
                )
            }
        return resolved
    }

    private fun resolveLegacySceneModels(
        obj: JsonObject
    ): Map<String, AgentAiCapabilitySceneModelSnapshot>? {
        if (obj.has("bindings")) {
            val bindings = resolveLegacyBindingsArray(obj.get("bindings"))
            if (bindings != null) {
                return bindings
            }
        }
        if (obj.has("scenes")) {
            val scenes = resolveLegacyScenesArray(obj.get("scenes"))
            if (scenes != null) {
                return scenes
            }
        }
        return null
    }

    private fun resolveLegacyBindingsArray(
        element: JsonElement?
    ): Map<String, AgentAiCapabilitySceneModelSnapshot>? {
        if (element == null || !element.isJsonArray) {
            return null
        }
        val resolved = linkedMapOf<String, AgentAiCapabilitySceneModelSnapshot>()
        element.asJsonArray.forEach { raw ->
            if (!raw.isJsonObject) {
                return@forEach
            }
            val obj = raw.asJsonObject
            val sceneId = obj.readString("sceneId")
            val providerId = firstNonBlank(
                obj.readString("providerId"),
                obj.readString("providerProfileId")
            )
            val model = firstNonBlank(
                obj.readString("model"),
                obj.readString("modelId")
            )
            if (!SceneModelBindingStore.isValidSceneId(sceneId) ||
                providerId.isEmpty() ||
                model.isEmpty()
            ) {
                return@forEach
            }
            resolved[sceneId] = AgentAiCapabilitySceneModelSnapshot(
                providerId = providerId,
                model = model
            )
        }
        return resolved
    }

    private fun resolveLegacyScenesArray(
        element: JsonElement?
    ): Map<String, AgentAiCapabilitySceneModelSnapshot>? {
        if (element == null || !element.isJsonArray) {
            return null
        }
        val resolved = linkedMapOf<String, AgentAiCapabilitySceneModelSnapshot>()
        element.asJsonArray.forEach { raw ->
            if (!raw.isJsonObject) {
                return@forEach
            }
            val obj = raw.asJsonObject
            val sceneId = obj.readString("sceneId")
            if (!SceneModelBindingStore.isValidSceneId(sceneId)) {
                return@forEach
            }
            val binding = obj.getAsJsonObjectOrNull("binding")
            val providerId = firstNonBlank(
                binding?.readString("providerId"),
                binding?.readString("providerProfileId"),
                obj.readString("providerId"),
                obj.readString("boundProviderProfileId"),
                obj.getAsJsonObjectOrNull("effective")?.readString("providerProfileId"),
                obj.readString("effectiveProviderProfileId")
            )
            val model = firstNonBlank(
                binding?.readString("model"),
                binding?.readString("modelId"),
                obj.readString("model"),
                obj.readString("overrideModel"),
                obj.getAsJsonObjectOrNull("effective")?.readString("modelId"),
                obj.readString("effectiveModel")
            )
            if (providerId.isEmpty() || model.isEmpty()) {
                return@forEach
            }
            resolved[sceneId] = AgentAiCapabilitySceneModelSnapshot(
                providerId = providerId,
                model = model
            )
        }
        return resolved
    }

    private fun resolveSceneSettings(
        element: JsonElement
    ): Map<String, AgentAiCapabilitySceneSettingsSnapshot>? {
        if (!element.isJsonObject) {
            return null
        }
        val obj = element.asJsonObject
        val resolved = linkedMapOf<String, AgentAiCapabilitySceneSettingsSnapshot>()
        obj.entrySet()
            .sortedBy { it.key }
            .forEach { (sceneId, rawSettings) ->
                if (sceneId.trim() != SceneVoiceConfigStore.SCENE_ID || !rawSettings.isJsonObject) {
                    return@forEach
                }
                val settingsObj = rawSettings.asJsonObject
                val autoPlay = runCatching {
                    settingsObj.get("autoPlay")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
                }.getOrDefault(false)
                val voiceId = settingsObj.readString("voiceId")
                val stylePreset = settingsObj.readString("stylePreset")
                val customStyle = settingsObj.readString("customStyle")
                resolved[SceneVoiceConfigStore.SCENE_ID] = SceneVoiceConfigStore.normalize(
                    SceneVoiceConfig(
                        autoPlay = autoPlay,
                        voiceId = voiceId,
                        stylePreset = stylePreset,
                        customStyle = customStyle
                    )
                ).toSnapshot()
            }
        return resolved
    }

    private fun firstNonBlank(vararg values: String?): String {
        return values.firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()
    }

    private fun normalizeProtocolType(value: String?): String {
        return DeepSeekProvider.normalizeProtocolType(value)
    }

    private fun JsonObject.readString(name: String): String {
        val raw = get(name) ?: return ""
        return if (raw.isJsonPrimitive) {
            raw.asString.trim()
        } else {
            ""
        }
    }

    private fun JsonObject.getAsJsonObjectOrNull(name: String): JsonObject? {
        val value = get(name) ?: return null
        return if (value.isJsonObject) value.asJsonObject else null
    }

    private fun SceneVoiceConfig.toSnapshot(): AgentAiCapabilitySceneSettingsSnapshot {
        return AgentAiCapabilitySceneSettingsSnapshot(
            autoPlay = autoPlay,
            voiceId = voiceId,
            stylePreset = stylePreset,
            customStyle = customStyle
        )
    }

    private fun AgentAiCapabilitySceneSettingsSnapshot.toSceneVoiceConfig(): SceneVoiceConfig {
        return SceneVoiceConfig(
            autoPlay = autoPlay,
            voiceId = voiceId,
            stylePreset = stylePreset,
            customStyle = customStyle
        )
    }

    private fun writeSnapshotToFileLocked(snapshot: AgentAiCapabilityConfigSnapshot) {
        val file = configFileLocked()
        val canonical = toCanonicalJson(snapshot)
        if (file.exists() && canonical == lastWrittenCanonicalJson) {
            return
        }
        file.parentFile?.mkdirs()
        file.writeText(canonical + "\n")
        lastWrittenCanonicalJson = canonical
    }

    private fun toCanonicalJson(snapshot: AgentAiCapabilityConfigSnapshot): String {
        return prettyGson.toJson(snapshot)
    }

    private fun configFileLocked(): File {
        return workspaceManager.agentConfigFile()
    }

    private fun soulFileLocked(): File {
        return workspaceManager.soulMarkdownFile()
    }

    private fun chatFileLocked(): File {
        return workspaceManager.chatMarkdownFile()
    }

    private fun shellPathForFileLocked(file: File): String {
        return workspaceManager.shellPathForAndroid(file) ?: file.absolutePath
    }
}
