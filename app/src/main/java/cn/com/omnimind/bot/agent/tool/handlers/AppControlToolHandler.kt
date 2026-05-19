package cn.com.omnimind.bot.agent.tool.handlers

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.ModelProviderProfile
import cn.com.omnimind.baselib.llm.SceneModelBindingEntry
import cn.com.omnimind.baselib.llm.SceneModelBindingStore
import cn.com.omnimind.baselib.llm.SceneVoiceConfig
import cn.com.omnimind.baselib.llm.SceneVoiceConfigStore
import cn.com.omnimind.bot.activity.StartupThemeResolver
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.agent.AgentAlarmToolService
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.agent.WorkspaceMemoryRollupScheduler
import cn.com.omnimind.bot.localmodel.LocalModelFeature
import cn.com.omnimind.bot.mcp.McpServerManager
import cn.com.omnimind.bot.mcp.RemoteMcpConfigStore
import cn.com.omnimind.bot.mcp.RemoteMcpDiscoveryRegistry
import cn.com.omnimind.bot.mcp.RemoteMcpServerConfig
import cn.com.omnimind.bot.share.SharedOpenPreferenceStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

class AppControlToolHandler(
    private val helper: SharedHelper
) : ToolHandler {
    override val toolNames: Set<String> = setOf(TOOL_NAME)

    private val appContext: Context = helper.context.applicationContext
    private val gson = Gson()
    private val mapType = object : TypeToken<MutableMap<String, Any?>>() {}.type
    private val listType = object : TypeToken<List<Any?>>() {}.type

    override suspend fun execute(
        toolCall: cn.com.omnimind.baselib.llm.AssistantToolCall,
        args: JsonObject,
        runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle
    ): ToolExecutionResult {
        val toolName = toolCall.function.name
        return try {
            toolHandle.throwIfStopRequested()
            val action = stringArg(args, "action")?.lowercase(Locale.ROOT)
                ?: throw IllegalArgumentException("action 不能为空")
            val target = stringArg(args, "target")
            helper.reportToolProgress(
                callback = callback,
                toolName = toolName,
                progress = "正在执行应用控制",
                extras = mapOf("action" to action, "target" to target),
                toolHandle = toolHandle
            )
            val payload = dispatch(action, target, args, env)
            val success = payload["success"] != false
            val payloadJson = helper.encodeLocalizedPayload(payload)
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = helper.localized(
                    payload["summary"]?.toString()
                        ?: if (success) "应用控制已执行" else "应用控制执行失败"
                ),
                previewJson = payloadJson,
                rawResultJson = payloadJson,
                success = success
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolExecutionResult.Error(toolName, helper.localized(e.message ?: "app control failed"))
        }
    }

    private suspend fun dispatch(
        action: String,
        target: String?,
        args: JsonObject,
        env: AgentExecutionEnvironment
    ): Map<String, Any?> {
        return when (action) {
            "settings.list", "setting.list" -> mapOf(
                "success" to true,
                "summary" to "已列出可控制设置",
                "settings" to knownSettings()
            )
            "settings.get", "setting.get" -> getNamedSetting(requireTarget(target))
            "settings.set", "setting.set" -> setNamedSetting(requireTarget(target), args)

            "prefs.list" -> listPrefs(args)
            "prefs.get" -> getPref(args)
            "prefs.set" -> setPref(args)
            "prefs.remove" -> removePref(args)
            "prefs.json_merge" -> mergePrefJson(args)

            "mmkv.list" -> listMmkv(args)
            "mmkv.get" -> getMmkv(args)
            "mmkv.set" -> setMmkv(args)
            "mmkv.remove" -> removeMmkv(args)
            "mmkv.json_merge" -> mergeMmkvJson(args)

            "mcp.state" -> mcpState()
            "mcp.set_enabled" -> mcpSetEnabled(args)
            "mcp.refresh_token" -> mcpRefreshToken()

            "shared_open.get", "open_with_omnibot.get" -> sharedOpenGet()
            "shared_open.set", "open_with_omnibot.set" -> sharedOpenSet(args)
            "alarm_settings.get", "alarm_sound.get", "alarm_sound_settings.get" -> alarmSoundSettingsGet()
            "alarm_settings.set", "alarm_sound.set", "alarm_sound_settings.set" -> alarmSoundSettingsSet(args)

            "local_model.state" -> localModelControl("state", args)
            "local_model.get_config" -> localModelControl("get_config", args)
            "local_model.save_config" -> localModelControl("save_config", args)
            "local_model.get_backend" -> localModelControl("get_backend", args)
            "local_model.set_backend" -> localModelControl("set_backend", args)
            "local_model.set_active_model" -> localModelControl("set_active_model", args)
            "local_model.start", "local_model.start_api_service" ->
                localModelControl("start_api_service", args)
            "local_model.stop", "local_model.stop_api_service" ->
                localModelControl("stop_api_service", args)
            "local_model.list_installed", "local_model.list_installed_models" ->
                localModelControl("list_installed_models", args)
            "local_model.preload", "local_model.prepare" -> localModelControl("preload", args)

            "model_provider.list" -> modelProviderList()
            "model_provider.get" -> modelProviderGet()
            "model_provider.save" -> modelProviderSave(args)
            "model_provider.set_editing" -> modelProviderSetEditing(args)
            "model_provider.delete" -> modelProviderDelete(args)
            "model_provider.replace" -> modelProviderReplace(args)

            "scene_model.list" -> sceneModelList()
            "scene_model.save", "scene_model.set" -> sceneModelSave(args)
            "scene_model.clear" -> sceneModelClear(args)
            "scene_model.replace" -> sceneModelReplace(args)

            "scene_voice.get" -> sceneVoiceGet()
            "scene_voice.set" -> sceneVoiceSet(args)
            "scene_voice.reset" -> sceneVoiceReset()

            "remote_mcp.list" -> remoteMcpList()
            "remote_mcp.upsert" -> remoteMcpUpsert(args)
            "remote_mcp.delete" -> remoteMcpDelete(args)
            "remote_mcp.set_enabled" -> remoteMcpSetEnabled(args)

            "workspace_memory.embedding_get" -> workspaceMemoryEmbeddingGet(env)
            "workspace_memory.embedding_set" -> workspaceMemoryEmbeddingSet(args, env)
            "workspace_memory.rollup_get" -> workspaceMemoryRollupGet(env)
            "workspace_memory.rollup_set" -> workspaceMemoryRollupSet(args)

            else -> throw IllegalArgumentException("不支持的 app_control action：$action")
        }
    }

    suspend fun executeControl(
        args: JsonObject,
        env: AgentExecutionEnvironment
    ): Map<String, Any?> {
        val action = stringArg(args, "action")?.lowercase(Locale.ROOT)
            ?: throw IllegalArgumentException("action 不能为空")
        return dispatch(action, stringArg(args, "target"), args, env)
    }

    private fun knownSettings(): List<Map<String, Any?>> {
        return listOf(
            mapOf("target" to "theme", "storage" to "SharedPreferences", "values" to listOf("system", "light", "dark")),
            mapOf("target" to "language", "storage" to "SharedPreferences", "values" to listOf("system", "zhHans", "en")),
            mapOf("target" to "auto_back_to_chat_after_task", "storage" to "SharedPreferences", "type" to "boolean"),
            mapOf("target" to "use_independent_chat_send_button", "storage" to "SharedPreferences", "type" to "boolean"),
            mapOf("target" to "habitual_hand", "storage" to "SharedPreferences", "values" to listOf("left", "right")),
            mapOf("target" to "hide_from_recents", "storage" to "SharedPreferences + ActivityManager", "type" to "boolean"),
            mapOf("target" to "agent_avatar", "storage" to "SharedPreferences", "keys" to listOf("agentAvatarIndex", "agentAvatarCustomImagePath")),
            mapOf("target" to "app_background", "storage" to "SharedPreferences JSON", "key" to "app_background_config_v1"),
            mapOf("target" to "home_greeting", "storage" to "SharedPreferences JSON", "key" to "home_greeting_settings"),
            mapOf("target" to "alarm_sound_settings", "storage" to "AgentAlarmToolService", "values" to listOf("default", "local_mp3", "remote_mp3_url")),
            mapOf("target" to "open_with_omnibot", "storage" to "SharedOpenPreferenceStore", "values" to listOf("default", "workspace")),
            mapOf("target" to "manual_model_context_thresholds", "storage" to "SharedPreferences JSON"),
            mapOf("target" to "chat_terminal_environment_variables", "storage" to "SharedPreferences JSON"),
            mapOf("target" to "vibration", "storage" to "MMKV", "key" to "app_vibrate", "type" to "boolean"),
            mapOf("target" to "companion_blocked_apps", "storage" to "MMKV JSON string-list", "key" to "companion_blocked_apps"),
            mapOf("target" to "model_provider", "storage" to "ModelProviderConfigStore"),
            mapOf("target" to "scene_model", "storage" to "SceneModelBindingStore"),
            mapOf("target" to "scene_voice", "storage" to "SceneVoiceConfigStore"),
            mapOf("target" to "mcp_server", "storage" to "McpServerManager"),
            mapOf("target" to "remote_mcp", "storage" to "RemoteMcpConfigStore"),
            mapOf("target" to "workspace_memory", "storage" to "WorkspaceMemoryService + scheduler"),
            mapOf("target" to "local_model", "storage" to "LocalModelFeature")
        )
    }

    private suspend fun getNamedSetting(target: String): Map<String, Any?> {
        return when (normalizeTarget(target)) {
            "theme" -> simpleSetting(target, readFlutterPref("theme_option", "system"))
            "language" -> simpleSetting(target, readFlutterPref("language_option", "system"))
            "auto_back_to_chat_after_task" ->
                simpleSetting(target, readFlutterPref("auto_back_to_chat_after_task", true))
            "use_independent_chat_send_button", "independent_chat_send_button" ->
                simpleSetting("use_independent_chat_send_button", readFlutterPref("use_independent_chat_send_button", true))
            "habitual_hand" -> simpleSetting(target, readFlutterPref("habitual_hand", "right"))
            "hide_from_recents" -> simpleSetting(target, readFlutterPref("hide_from_recents", false))
            "agent_avatar" -> mapOf(
                "success" to true,
                "target" to "agent_avatar",
                "value" to mapOf(
                    "agentAvatarIndex" to readFlutterPref("agentAvatarIndex", 0),
                    "agentAvatarCustomImagePath" to readFlutterPref("agentAvatarCustomImagePath", "")
                ),
                "summary" to "已读取应用设置"
            )
            "app_background" -> simpleSetting(target, readFlutterJson("app_background_config_v1"))
            "home_greeting" -> simpleSetting(target, readFlutterJson("home_greeting_settings"))
            "alarm_settings", "alarm_sound", "alarm_sound_settings" -> alarmSoundSettingsGet()
            "open_with_omnibot", "shared_open", "shared_open_mode" -> sharedOpenGet()
            "manual_model_context_thresholds" -> simpleSetting(target, readFlutterJson("manual_model_context_thresholds"))
            "chat_terminal_environment_variables" -> simpleSetting(target, readFlutterJson("chat_terminal_environment_variables"))
            "vibration", "app_vibrate" -> simpleSetting("vibration", MMKV.defaultMMKV().decodeBool("app_vibrate", true))
            "companion_blocked_apps" -> simpleSetting(
                "companion_blocked_apps",
                readJsonList(MMKV.defaultMMKV().decodeString("companion_blocked_apps").orEmpty())
            )
            "model_provider" -> modelProviderGet()
            "scene_model" -> sceneModelList()
            "scene_voice" -> sceneVoiceGet()
            "mcp_server", "local_mcp_server" -> mcpState()
            "remote_mcp" -> remoteMcpList()
            "workspace_memory" -> {
                val memoryService = cn.com.omnimind.bot.agent.WorkspaceMemoryService(appContext)
                mapOf(
                    "success" to true,
                    "target" to "workspace_memory",
                    "embedding" to embeddingConfigToMap(memoryService.getEmbeddingConfigForUi()),
                    "rollup" to rollupStatusToMap(memoryService.getRollupStatusForUi()),
                    "nextRunAtMillis" to WorkspaceMemoryRollupScheduler(appContext).getNextRunAtMillis(),
                    "summary" to "已读取应用设置"
                )
            }
            "local_model" -> localModelControl("state", JsonObject(emptyMap()))
            else -> throw IllegalArgumentException("未知设置项：$target")
        }
    }

    private suspend fun setNamedSetting(target: String, args: JsonObject): Map<String, Any?> {
        return when (normalizeTarget(target)) {
            "theme" -> {
                val mode = requiredStringValue(args, "value")
                require(mode in setOf("system", "light", "dark")) { "theme 仅支持 system/light/dark" }
                writeFlutterPref("theme_option", mode)
                StartupThemeResolver.applyApplicationNightMode(appContext, mode)
                simpleSetting("theme", mode, "已更新主题设置")
            }
            "language" -> {
                val mode = requiredStringValue(args, "value")
                require(mode in setOf("system", "zhHans", "en")) { "language 仅支持 system/zhHans/en" }
                writeFlutterPref("language_option", mode)
                AppLocaleManager.applyAppLocale(appContext)
                runCatching { AgentWorkspaceManager(appContext).ensureRuntimeDirectories() }
                simpleSetting("language", mode, "已更新语言设置")
            }
            "auto_back_to_chat_after_task" -> {
                val enabled = requiredBooleanValue(args)
                writeFlutterPref("auto_back_to_chat_after_task", enabled)
                simpleSetting("auto_back_to_chat_after_task", enabled, "已更新应用设置")
            }
            "use_independent_chat_send_button", "independent_chat_send_button" -> {
                val enabled = requiredBooleanValue(args)
                writeFlutterPref("use_independent_chat_send_button", enabled)
                simpleSetting("use_independent_chat_send_button", enabled, "已更新应用设置")
            }
            "habitual_hand" -> {
                val hand = requiredStringValue(args, "value")
                require(hand in setOf("left", "right")) { "habitual_hand 仅支持 left/right" }
                writeFlutterPref("habitual_hand", hand)
                simpleSetting("habitual_hand", hand, "已更新惯用手设置")
            }
            "hide_from_recents" -> {
                val exclude = requiredBooleanValue(args)
                writeFlutterPref("hide_from_recents", exclude)
                setExcludeFromRecents(exclude)
                simpleSetting("hide_from_recents", exclude, "已更新最近任务隐藏设置")
            }
            "agent_avatar" -> {
                objectValue(args)?.let { value ->
                    value["agentAvatarIndex"]?.let { writeFlutterPref("agentAvatarIndex", numberToInt(it)) }
                    value["agentAvatarCustomImagePath"]?.let { writeFlutterPref("agentAvatarCustomImagePath", it.toString()) }
                } ?: run {
                    args["agentAvatarIndex"]?.let { writeFlutterPref("agentAvatarIndex", it.jsonPrimitive.intOrNull ?: 0) }
                    stringArg(args, "agentAvatarCustomImagePath")?.let { writeFlutterPref("agentAvatarCustomImagePath", it) }
                }
                getNamedSetting("agent_avatar")
            }
            "app_background" -> setFlutterJsonSetting("app_background_config_v1", "app_background", args)
            "home_greeting" -> setFlutterJsonSetting("home_greeting_settings", "home_greeting", args)
            "alarm_settings", "alarm_sound", "alarm_sound_settings" -> alarmSoundSettingsSet(args)
            "open_with_omnibot", "shared_open", "shared_open_mode" -> sharedOpenSet(args)
            "manual_model_context_thresholds" ->
                setFlutterJsonSetting("manual_model_context_thresholds", "manual_model_context_thresholds", args)
            "chat_terminal_environment_variables" ->
                setFlutterJsonSetting("chat_terminal_environment_variables", "chat_terminal_environment_variables", args)
            "vibration", "app_vibrate" -> {
                val enabled = requiredBooleanValue(args)
                MMKV.defaultMMKV().encode("app_vibrate", enabled)
                simpleSetting("vibration", enabled, "已更新震动设置")
            }
            "companion_blocked_apps" -> {
                val list = stringListValue(args)
                MMKV.defaultMMKV().encode("companion_blocked_apps", gson.toJson(list))
                simpleSetting("companion_blocked_apps", list, "已更新应用设置")
            }
            "model_provider" -> modelProviderSave(args)
            "scene_model" -> sceneModelSave(args)
            "scene_voice" -> sceneVoiceSet(args)
            "mcp_server", "local_mcp_server" -> mcpSetEnabled(args)
            "remote_mcp" -> remoteMcpUpsert(args)
            "workspace_memory" -> workspaceMemoryRollupSet(args)
            "local_model" -> {
                val localAction = stringArg(args, "localModelAction")
                    ?: stringArg(args, "local_model_action")
                    ?: "save_config"
                localModelControl(localAction, args)
            }
            else -> throw IllegalArgumentException("未知设置项：$target")
        }
    }

    private fun simpleSetting(
        target: String,
        value: Any?,
        summary: String = "已读取应用设置"
    ): Map<String, Any?> = mapOf(
        "success" to true,
        "target" to target,
        "value" to value,
        "summary" to summary
    )

    private fun listPrefs(args: JsonObject): Map<String, Any?> {
        val prefsName = stringArg(args, "prefsName") ?: FLUTTER_SHARED_PREFS
        val prefix = stringArg(args, "prefix")
        val prefs = sharedPrefs(prefsName)
        val entries = prefs.all.entries
            .filter { prefix == null || publicPrefKey(prefsName, it.key).startsWith(prefix) }
            .sortedBy { it.key }
            .map { (key, value) ->
                mapOf(
                    "key" to publicPrefKey(prefsName, key),
                    "nativeKey" to key,
                    "type" to valueTypeName(value),
                    "value" to normalizeStoredValue(value)
                )
            }
        return mapOf("success" to true, "prefsName" to prefsName, "entries" to entries, "summary" to "已读取 SharedPreferences")
    }

    private fun getPref(args: JsonObject): Map<String, Any?> {
        val prefsName = stringArg(args, "prefsName") ?: FLUTTER_SHARED_PREFS
        val key = requiredKey(args)
        val nativeKey = prefKey(prefsName, key)
        val value = sharedPrefs(prefsName).all[nativeKey]
        return mapOf(
            "success" to true,
            "prefsName" to prefsName,
            "key" to key,
            "nativeKey" to nativeKey,
            "exists" to (value != null),
            "type" to valueTypeName(value),
            "value" to normalizeStoredValue(value),
            "summary" to "已读取 SharedPreferences"
        )
    }

    private fun setPref(args: JsonObject): Map<String, Any?> {
        val prefsName = stringArg(args, "prefsName") ?: FLUTTER_SHARED_PREFS
        val key = requiredKey(args)
        val value = requiredAnyValue(args)
        val valueType = stringArg(args, "valueType")
        val nativeKey = prefKey(prefsName, key)
        putPreference(sharedPrefs(prefsName), nativeKey, value, valueType)
        return getPref(args)
    }

    private fun removePref(args: JsonObject): Map<String, Any?> {
        requireConfirmed(args, "移除 SharedPreferences 键需要 confirmed=true")
        val prefsName = stringArg(args, "prefsName") ?: FLUTTER_SHARED_PREFS
        val key = requiredKey(args)
        val nativeKey = prefKey(prefsName, key)
        val removed = sharedPrefs(prefsName).edit().remove(nativeKey).commit()
        return mapOf(
            "success" to removed,
            "prefsName" to prefsName,
            "key" to key,
            "nativeKey" to nativeKey,
            "summary" to if (removed) "已移除 SharedPreferences 键" else "SharedPreferences 移除失败"
        )
    }

    private fun mergePrefJson(args: JsonObject): Map<String, Any?> {
        val prefsName = stringArg(args, "prefsName") ?: FLUTTER_SHARED_PREFS
        val key = requiredKey(args)
        val nativeKey = prefKey(prefsName, key)
        val prefs = sharedPrefs(prefsName)
        val current = parseJsonMap((prefs.all[nativeKey] as? String).orEmpty())
        current.putAll(requiredPatch(args))
        prefs.edit().putString(nativeKey, gson.toJson(current)).commit()
        return getPref(args)
    }

    private fun listMmkv(args: JsonObject): Map<String, Any?> {
        val mmkvId = stringArg(args, "mmkvId") ?: DEFAULT_MMKV_ID
        val prefix = stringArg(args, "prefix")
        val keys = mmkv(mmkvId).allKeys()
            ?.filter { prefix == null || it.startsWith(prefix) }
            ?.sorted()
            ?: emptyList()
        return mapOf("success" to true, "mmkvId" to mmkvId, "keys" to keys, "summary" to "已读取 MMKV 键列表")
    }

    private fun getMmkv(args: JsonObject): Map<String, Any?> {
        val mmkvId = stringArg(args, "mmkvId") ?: DEFAULT_MMKV_ID
        val key = requiredKey(args)
        val valueType = stringArg(args, "valueType") ?: "string"
        val kv = mmkv(mmkvId)
        val exists = kv.containsKey(key)
        val value = if (exists) decodeMmkv(kv, key, valueType) else null
        return mapOf(
            "success" to true,
            "mmkvId" to mmkvId,
            "key" to key,
            "exists" to exists,
            "valueType" to valueType,
            "value" to value,
            "summary" to "已读取 MMKV"
        )
    }

    private fun setMmkv(args: JsonObject): Map<String, Any?> {
        val mmkvId = stringArg(args, "mmkvId") ?: DEFAULT_MMKV_ID
        val key = requiredKey(args)
        val value = requiredAnyValue(args)
        val valueType = stringArg(args, "valueType") ?: inferValueType(value, preferDouble = true)
        encodeMmkv(mmkv(mmkvId), key, value, valueType)
        return getMmkv(argsWithValueType(args, valueType))
    }

    private fun removeMmkv(args: JsonObject): Map<String, Any?> {
        requireConfirmed(args, "移除 MMKV 键需要 confirmed=true")
        val mmkvId = stringArg(args, "mmkvId") ?: DEFAULT_MMKV_ID
        val key = requiredKey(args)
        val kv = mmkv(mmkvId)
        kv.removeValueForKey(key)
        return mapOf(
            "success" to true,
            "mmkvId" to mmkvId,
            "key" to key,
            "summary" to "已移除 MMKV 键"
        )
    }

    private fun mergeMmkvJson(args: JsonObject): Map<String, Any?> {
        val mmkvId = stringArg(args, "mmkvId") ?: DEFAULT_MMKV_ID
        val key = requiredKey(args)
        val kv = mmkv(mmkvId)
        val current = parseJsonMap(kv.decodeString(key).orEmpty())
        current.putAll(requiredPatch(args))
        kv.encode(key, gson.toJson(current))
        return getMmkv(argsWithValueType(args, "json"))
    }

    private fun mcpState(): Map<String, Any?> = mapOf(
        "success" to true,
        "state" to McpServerManager.currentState().toMap(),
        "summary" to "已读取本地 MCP 服务状态"
    )

    private fun mcpSetEnabled(args: JsonObject): Map<String, Any?> {
        val enabled = booleanArg(args, "enabled")
            ?: booleanArg(args, "enable")
            ?: requiredBooleanValue(args)
        val port = intArg(args, "port")
        val state = McpServerManager.setEnabled(appContext, enabled, port).toMap()
        return mapOf(
            "success" to true,
            "state" to state,
            "summary" to if (enabled) "已启动本地 MCP 服务" else "已停止本地 MCP 服务"
        )
    }

    private fun mcpRefreshToken(): Map<String, Any?> = mapOf(
        "success" to true,
        "state" to McpServerManager.refreshToken(appContext).toMap(),
        "summary" to "已刷新本地 MCP 服务令牌"
    )

    private fun sharedOpenGet(): Map<String, Any?> = mapOf(
        "success" to true,
        "target" to "open_with_omnibot",
        "value" to SharedOpenPreferenceStore.getOpenModes(appContext),
        "summary" to "已读取使用小万打开设置"
    )

    private fun sharedOpenSet(args: JsonObject): Map<String, Any?> {
        val value = objectValue(args)
        val imageMode = stringFromArgsOrMap(args, value, "imageMode", "image_mode", "image")
        val fileMode = stringFromArgsOrMap(args, value, "fileMode", "file_mode", "file")
        var changed = false
        imageMode?.let {
            SharedOpenPreferenceStore.setImageOpenMode(appContext, it)
            changed = true
        }
        fileMode?.let {
            SharedOpenPreferenceStore.setFileOpenMode(appContext, it)
            changed = true
        }

        if (!changed) {
            val mode = stringFromArgsOrMap(args, value, "mode", "openMode", "open_mode", "value")
                ?: throw IllegalArgumentException("open_with_omnibot mode/value 不能为空")
            when (stringFromArgsOrMap(args, value, "openTarget", "open_target", "mediaType", "media_type", "kind", "target")
                ?.lowercase(Locale.ROOT)) {
                "image", "images", "photo", "photos", "picture", "pictures" ->
                    SharedOpenPreferenceStore.setImageOpenMode(appContext, mode)
                "file", "files", "document", "documents" ->
                    SharedOpenPreferenceStore.setFileOpenMode(appContext, mode)
                else -> SharedOpenPreferenceStore.setOpenMode(appContext, mode)
            }
        }

        return sharedOpenGet() + ("summary" to "已保存使用小万打开设置")
    }

    private fun alarmSoundSettingsGet(): Map<String, Any?> {
        val settings = AgentAlarmToolService(appContext).getAlarmSettings()
        return mapOf(
            "success" to true,
            "target" to "alarm_sound_settings",
            "value" to settings,
            "summary" to "已读取闹钟铃声设置"
        )
    }

    private fun alarmSoundSettingsSet(args: JsonObject): Map<String, Any?> {
        val value = objectValue(args)
        val source = stringFromArgsOrMap(args, value, "source", "value")
            ?: throw IllegalArgumentException("alarm_sound_settings source 不能为空")
        val result = AgentAlarmToolService(appContext).saveAlarmSettings(
            source = source,
            localPath = stringFromArgsOrMap(args, value, "localPath", "local_path"),
            remoteUrl = stringFromArgsOrMap(args, value, "remoteUrl", "remote_url")
        )
        return mapOf(
            "success" to true,
            "target" to "alarm_sound_settings",
            "value" to mapOf(
                "source" to result["source"],
                "localPath" to result["localPath"],
                "remoteUrl" to result["remoteUrl"]
            ),
            "summary" to (result["summary"] ?: "闹钟铃声设置已保存")
        )
    }

    private suspend fun localModelControl(
        action: String,
        args: JsonObject
    ): Map<String, Any?> {
        val argumentMap = helper.jsonObjectToMap(args)
        val result = LocalModelFeature.control(action, argumentMap)
        val success = result["success"] != false
        return mapOf(
            "success" to success,
            "target" to "local_model",
            "action" to action,
            "result" to result,
            "summary" to (result["summary"] ?: if (success) "已执行本地模型控制" else "本地模型控制失败")
        )
    }

    private fun modelProviderList(): Map<String, Any?> = mapOf(
        "success" to true,
        "profiles" to ModelProviderConfigStore.listProfiles().map(::providerProfileToMap),
        "editingProfileId" to ModelProviderConfigStore.getEditingProfileId(),
        "summary" to "已读取模型服务商配置"
    )

    private fun modelProviderGet(): Map<String, Any?> = mapOf(
        "success" to true,
        "config" to providerConfigToMap(ModelProviderConfigStore.getConfig()),
        "editingProfile" to providerProfileToMap(ModelProviderConfigStore.getEditingProfile()),
        "profiles" to ModelProviderConfigStore.listProfiles().map(::providerProfileToMap),
        "summary" to "已读取模型服务商配置"
    )

    private fun modelProviderSave(args: JsonObject): Map<String, Any?> {
        val value = objectValue(args)
        val profile = ModelProviderConfigStore.saveProfile(
            id = stringArg(args, "id") ?: value?.get("id")?.toString(),
            name = stringArg(args, "name") ?: value?.get("name")?.toString().orEmpty(),
            baseUrl = stringArg(args, "baseUrl") ?: stringArg(args, "base_url")
                ?: value?.get("baseUrl")?.toString()
                ?: value?.get("base_url")?.toString()
                ?: "",
            apiKey = stringArg(args, "apiKey") ?: stringArg(args, "api_key")
                ?: value?.get("apiKey")?.toString()
                ?: value?.get("api_key")?.toString()
                ?: "",
            protocolType = stringArg(args, "protocolType") ?: stringArg(args, "protocol_type")
                ?: value?.get("protocolType")?.toString()
                ?: value?.get("protocol_type")?.toString()
                ?: "openai_compatible"
        )
        return mapOf(
            "success" to true,
            "profile" to providerProfileToMap(profile),
            "summary" to "已保存模型服务商配置"
        )
    }

    private fun modelProviderSetEditing(args: JsonObject): Map<String, Any?> {
        val profileId = requiredString(args, "profileId", "profile_id", "id", "value")
        val profile = ModelProviderConfigStore.setEditingProfile(profileId)
        return mapOf(
            "success" to true,
            "editingProfile" to providerProfileToMap(profile),
            "summary" to "已切换当前模型服务商"
        )
    }

    private fun modelProviderDelete(args: JsonObject): Map<String, Any?> {
        requireConfirmed(args, "删除模型服务商配置需要 confirmed=true")
        val profileId = requiredString(args, "profileId", "profile_id", "id", "value")
        val profiles = ModelProviderConfigStore.deleteProfile(profileId)
        return mapOf(
            "success" to true,
            "profiles" to profiles.map(::providerProfileToMap),
            "summary" to "已删除模型服务商配置"
        )
    }

    private fun modelProviderReplace(args: JsonObject): Map<String, Any?> {
        val profiles = listOfMaps(args, "profiles", "value").map(::profileFromMap)
        val editingProfileId = stringArg(args, "editingProfileId") ?: stringArg(args, "editing_profile_id")
        val saved = ModelProviderConfigStore.replaceProfiles(profiles, editingProfileId)
        return mapOf(
            "success" to true,
            "profiles" to saved.map(::providerProfileToMap),
            "editingProfileId" to ModelProviderConfigStore.getEditingProfileId(),
            "summary" to "已替换模型服务商配置"
        )
    }

    private fun sceneModelList(): Map<String, Any?> = mapOf(
        "success" to true,
        "bindings" to SceneModelBindingStore.getBindingEntries().map(::sceneBindingToMap),
        "summary" to "已读取场景模型绑定"
    )

    private fun sceneModelSave(args: JsonObject): Map<String, Any?> {
        val value = objectValue(args)
        val sceneId = stringArg(args, "sceneId") ?: stringArg(args, "scene_id")
            ?: value?.get("sceneId")?.toString()
            ?: value?.get("scene_id")?.toString()
            ?: error("sceneId is required")
        val providerProfileId = stringArg(args, "providerProfileId") ?: stringArg(args, "provider_profile_id")
            ?: value?.get("providerProfileId")?.toString()
            ?: value?.get("provider_profile_id")?.toString()
            ?: error("providerProfileId is required")
        val modelId = stringArg(args, "modelId") ?: stringArg(args, "model_id")
            ?: value?.get("modelId")?.toString()
            ?: value?.get("model_id")?.toString()
            ?: error("modelId is required")
        SceneModelBindingStore.saveBinding(sceneId, providerProfileId, modelId)
        return mapOf(
            "success" to true,
            "binding" to SceneModelBindingStore.getBinding(sceneId)?.let(::sceneBindingToMap),
            "summary" to "已保存场景模型绑定"
        )
    }

    private fun sceneModelClear(args: JsonObject): Map<String, Any?> {
        val sceneId = requiredString(args, "sceneId", "scene_id", "value")
        SceneModelBindingStore.clearBinding(sceneId)
        return mapOf(
            "success" to true,
            "sceneId" to sceneId,
            "summary" to "已清除场景模型绑定"
        )
    }

    private fun sceneModelReplace(args: JsonObject): Map<String, Any?> {
        val entries = listOfMaps(args, "bindings", "entries", "value").map { map ->
            SceneModelBindingEntry(
                sceneId = map["sceneId"]?.toString()
                    ?: map["scene_id"]?.toString()
                    ?: "",
                providerProfileId = map["providerProfileId"]?.toString()
                    ?: map["provider_profile_id"]?.toString()
                    ?: "",
                modelId = map["modelId"]?.toString()
                    ?: map["model_id"]?.toString()
                    ?: ""
            )
        }
        SceneModelBindingStore.replaceBindings(entries)
        return sceneModelList() + ("summary" to "已替换场景模型绑定")
    }

    private fun sceneVoiceGet(): Map<String, Any?> = mapOf(
        "success" to true,
        "config" to sceneVoiceToMap(SceneVoiceConfigStore.getConfig()),
        "summary" to "已读取语音设置"
    )

    private fun sceneVoiceSet(args: JsonObject): Map<String, Any?> {
        val current = sceneVoiceToMap(SceneVoiceConfigStore.getConfig()).toMutableMap()
        objectValue(args)?.let { current.putAll(it) }
        patchValue(args)?.let { current.putAll(it) }
        booleanArg(args, "autoPlay")?.let { current["autoPlay"] = it }
        stringArg(args, "voiceId")?.let { current["voiceId"] = it }
        stringArg(args, "stylePreset")?.let { current["stylePreset"] = it }
        stringArg(args, "customStyle")?.let { current["customStyle"] = it }
        val saved = SceneVoiceConfigStore.saveConfig(
            SceneVoiceConfig(
                autoPlay = parseBoolean(current["autoPlay"]) ?: false,
                voiceId = current["voiceId"]?.toString().orEmpty(),
                stylePreset = current["stylePreset"]?.toString().orEmpty(),
                customStyle = current["customStyle"]?.toString().orEmpty()
            )
        )
        return mapOf(
            "success" to true,
            "config" to sceneVoiceToMap(saved),
            "summary" to "已保存语音设置"
        )
    }

    private fun sceneVoiceReset(): Map<String, Any?> {
        SceneVoiceConfigStore.reset()
        return sceneVoiceGet() + ("summary" to "已重置语音设置")
    }

    private fun remoteMcpList(): Map<String, Any?> = mapOf(
        "success" to true,
        "servers" to RemoteMcpConfigStore.listServers().map { it.toMap() },
        "summary" to "已读取远程 MCP 配置"
    )

    private fun remoteMcpUpsert(args: JsonObject): Map<String, Any?> {
        val raw = normalizeRemoteMcpMap(objectValue(args) ?: helper.jsonObjectToMap(args))
        require(!stringFromMap(raw, "name").isNullOrBlank()) { "remote_mcp.name 不能为空" }
        require(!stringFromMap(raw, "endpointUrl").isNullOrBlank()) { "remote_mcp.endpointUrl 不能为空" }
        val saved = RemoteMcpConfigStore.upsertServer(RemoteMcpServerConfig.fromMap(raw))
        RemoteMcpDiscoveryRegistry.invalidate(saved.id)
        return mapOf(
            "success" to true,
            "server" to saved.toMap(),
            "summary" to "已保存远程 MCP 配置"
        )
    }

    private fun remoteMcpDelete(args: JsonObject): Map<String, Any?> {
        requireConfirmed(args, "删除远程 MCP 配置需要 confirmed=true")
        val serverId = requiredString(args, "serverId", "server_id", "id", "value")
        RemoteMcpConfigStore.deleteServer(serverId)
        RemoteMcpDiscoveryRegistry.invalidate(serverId)
        return mapOf(
            "success" to true,
            "serverId" to serverId,
            "summary" to "已删除远程 MCP 配置"
        )
    }

    private fun remoteMcpSetEnabled(args: JsonObject): Map<String, Any?> {
        val serverId = requiredString(args, "serverId", "server_id", "id")
        val enabled = booleanArg(args, "enabled") ?: requiredBooleanValue(args)
        val updated = RemoteMcpConfigStore.setServerEnabled(serverId, enabled)
        if (!enabled) {
            RemoteMcpDiscoveryRegistry.invalidate(serverId)
        }
        return mapOf(
            "success" to (updated != null),
            "server" to updated?.toMap(),
            "summary" to if (updated != null) "已更新远程 MCP 启用状态" else "未找到远程 MCP 配置"
        )
    }

    private fun workspaceMemoryEmbeddingGet(env: AgentExecutionEnvironment): Map<String, Any?> = mapOf(
        "success" to true,
        "config" to embeddingConfigToMap(env.workspaceMemoryService.getEmbeddingConfigForUi()),
        "summary" to "已读取 workspace 记忆向量配置"
    )

    private fun workspaceMemoryEmbeddingSet(
        args: JsonObject,
        env: AgentExecutionEnvironment
    ): Map<String, Any?> {
        val enabled = booleanArg(args, "enabled") ?: requiredBooleanValue(args)
        val config = env.workspaceMemoryService.saveEmbeddingConfigForUi(
            enabled = enabled,
            providerProfileId = stringArg(args, "providerProfileId") ?: stringArg(args, "provider_profile_id"),
            modelId = stringArg(args, "modelId") ?: stringArg(args, "model_id")
        )
        return mapOf(
            "success" to true,
            "config" to embeddingConfigToMap(config),
            "summary" to "已保存 workspace 记忆向量配置"
        )
    }

    private fun workspaceMemoryRollupGet(env: AgentExecutionEnvironment): Map<String, Any?> = mapOf(
        "success" to true,
        "status" to rollupStatusToMap(env.workspaceMemoryService.getRollupStatusForUi()),
        "nextRunAtMillis" to WorkspaceMemoryRollupScheduler(appContext).getNextRunAtMillis(),
        "summary" to "已读取 workspace 记忆整理配置"
    )

    private fun workspaceMemoryRollupSet(args: JsonObject): Map<String, Any?> {
        val enabled = booleanArg(args, "enabled") ?: requiredBooleanValue(args)
        val scheduler = WorkspaceMemoryRollupScheduler(appContext)
        val status = scheduler.setEnabled(enabled)
        return mapOf(
            "success" to true,
            "status" to rollupStatusToMap(status),
            "nextRunAtMillis" to scheduler.getNextRunAtMillis(),
            "summary" to "已保存 workspace 记忆整理配置"
        )
    }

    private fun setFlutterJsonSetting(
        key: String,
        target: String,
        args: JsonObject
    ): Map<String, Any?> {
        val patch = patchValue(args)
        if (patch != null) {
            val current = parseJsonMap(readFlutterPref(key, "").toString())
            current.putAll(patch)
            writeFlutterPref(key, gson.toJson(current))
            return simpleSetting(target, current, "已更新应用设置")
        }
        val value = requiredAnyValue(args)
        val stored = when (value) {
            is Map<*, *>, is List<*> -> gson.toJson(value)
            else -> value.toString()
        }
        writeFlutterPref(key, stored)
        return simpleSetting(
            target,
            if (value is Map<*, *> || value is List<*>) value else readJsonValue(stored),
            "已更新应用设置"
        )
    }

    private fun readFlutterPref(key: String, defaultValue: Any?): Any? {
        val value = sharedPrefs(FLUTTER_SHARED_PREFS).all[prefKey(FLUTTER_SHARED_PREFS, key)]
        return normalizeStoredValue(value) ?: defaultValue
    }

    private fun writeFlutterPref(key: String, value: Any?) {
        putPreference(sharedPrefs(FLUTTER_SHARED_PREFS), prefKey(FLUTTER_SHARED_PREFS, key), value, null)
    }

    private fun readFlutterJson(key: String): Any? {
        val raw = readFlutterPref(key, "")?.toString().orEmpty()
        return readJsonValue(raw)
    }

    private fun putPreference(
        prefs: SharedPreferences,
        nativeKey: String,
        value: Any?,
        requestedType: String?
    ) {
        val type = requestedType ?: inferValueType(value)
        val editor = prefs.edit()
        when (type.lowercase(Locale.ROOT)) {
            "string" -> editor.putString(nativeKey, value?.toString().orEmpty())
            "boolean", "bool" -> editor.putBoolean(nativeKey, parseBoolean(value) ?: false)
            "int", "integer" -> editor.putInt(nativeKey, numberToInt(value))
            "long" -> editor.putLong(nativeKey, numberToLong(value))
            "float", "double" -> editor.putFloat(nativeKey, numberToDouble(value).toFloat())
            "string-list", "string_set", "strings" -> editor.putStringSet(nativeKey, stringList(value).toSet())
            "json" -> editor.putString(
                nativeKey,
                when (value) {
                    is String -> value
                    else -> gson.toJson(value)
                }
            )
            else -> throw IllegalArgumentException("不支持的 SharedPreferences valueType：$requestedType")
        }
        check(editor.commit()) { "SharedPreferences 写入失败" }
    }

    private fun decodeMmkv(kv: MMKV, key: String, valueType: String): Any? {
        return when (valueType.lowercase(Locale.ROOT)) {
            "string" -> kv.decodeString(key)
            "boolean", "bool" -> kv.decodeBool(key, false)
            "int", "integer" -> kv.decodeInt(key, 0)
            "long" -> kv.decodeLong(key, 0L)
            "float" -> kv.decodeFloat(key, 0f)
            "double" -> kv.decodeDouble(key, 0.0)
            "json" -> readJsonValue(kv.decodeString(key).orEmpty())
            "string-list", "strings" -> readJsonList(kv.decodeString(key).orEmpty()).map { it.toString() }
            else -> throw IllegalArgumentException("不支持的 MMKV valueType：$valueType")
        }
    }

    private fun encodeMmkv(kv: MMKV, key: String, value: Any?, valueType: String) {
        when (valueType.lowercase(Locale.ROOT)) {
            "string" -> kv.encode(key, value?.toString().orEmpty())
            "boolean", "bool" -> kv.encode(key, parseBoolean(value) ?: false)
            "int", "integer" -> kv.encode(key, numberToInt(value))
            "long" -> kv.encode(key, numberToLong(value))
            "float" -> kv.encode(key, numberToDouble(value).toFloat())
            "double" -> kv.encode(key, numberToDouble(value))
            "json" -> kv.encode(key, if (value is String) value else gson.toJson(value))
            "string-list", "strings" -> kv.encode(key, gson.toJson(stringList(value)))
            else -> throw IllegalArgumentException("不支持的 MMKV valueType：$valueType")
        }
    }

    private fun setExcludeFromRecents(exclude: Boolean) {
        val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return
        activityManager.appTasks.forEach { task ->
            task.setExcludeFromRecents(exclude)
        }
    }

    private fun sharedPrefs(name: String): SharedPreferences {
        return appContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    }

    private fun prefKey(prefsName: String, key: String): String {
        val trimmed = key.trim()
        return if (prefsName == FLUTTER_SHARED_PREFS && !trimmed.startsWith(FLUTTER_KEY_PREFIX)) {
            FLUTTER_KEY_PREFIX + trimmed
        } else {
            trimmed
        }
    }

    private fun publicPrefKey(prefsName: String, nativeKey: String): String {
        return if (prefsName == FLUTTER_SHARED_PREFS && nativeKey.startsWith(FLUTTER_KEY_PREFIX)) {
            nativeKey.removePrefix(FLUTTER_KEY_PREFIX)
        } else {
            nativeKey
        }
    }

    private fun mmkv(id: String): MMKV {
        return if (id == DEFAULT_MMKV_ID || id.isBlank()) {
            MMKV.defaultMMKV()
        } else {
            MMKV.mmkvWithID(id)
        }
    }

    private fun parseJsonMap(raw: String): MutableMap<String, Any?> {
        if (raw.isBlank()) return mutableMapOf()
        return runCatching {
            gson.fromJson<MutableMap<String, Any?>>(raw, mapType) ?: mutableMapOf()
        }.getOrElse {
            throw IllegalArgumentException("当前值不是 JSON object，无法 merge")
        }
    }

    private fun readJsonValue(raw: String): Any? {
        if (raw.isBlank()) return null
        return runCatching {
            gson.fromJson<Any?>(raw, Any::class.java)
        }.getOrElse { raw }
    }

    private fun readJsonList(raw: String): List<Any?> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            gson.fromJson<List<Any?>>(raw, listType) ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private fun requiredPatch(args: JsonObject): Map<String, Any?> {
        return patchValue(args) ?: throw IllegalArgumentException("patch 不能为空")
    }

    private fun patchValue(args: JsonObject): Map<String, Any?>? {
        return (args["patch"] as? JsonObject)?.let { helper.jsonObjectToMap(it) }
    }

    private fun objectValue(args: JsonObject): Map<String, Any?>? {
        return (args["value"] as? JsonObject)?.let { helper.jsonObjectToMap(it) }
    }

    private fun requiredAnyValue(args: JsonObject): Any? {
        return args["value"]?.let { helper.jsonElementToAny(it) }
            ?: throw IllegalArgumentException("value 不能为空")
    }

    private fun requiredBooleanValue(args: JsonObject): Boolean {
        return args["value"]?.jsonPrimitive?.booleanOrNull
            ?: parseBoolean(args["value"]?.let { helper.jsonElementToAny(it) })
            ?: throw IllegalArgumentException("value 必须是 boolean")
    }

    private fun requiredStringValue(args: JsonObject, key: String): String {
        return args[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("$key 不能为空")
    }

    private fun requiredString(args: JsonObject, vararg keys: String): String {
        for (key in keys) {
            stringArg(args, key)?.let { return it }
        }
        throw IllegalArgumentException("${keys.firstOrNull() ?: "value"} 不能为空")
    }

    private fun stringArg(args: JsonObject, key: String): String? {
        return (args[key] as? kotlinx.serialization.json.JsonPrimitive)
            ?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun booleanArg(args: JsonObject, key: String): Boolean? {
        return args[key]?.jsonPrimitive?.booleanOrNull
            ?: args[key]?.jsonPrimitive?.contentOrNull?.let(::parseBoolean)
    }

    private fun intArg(args: JsonObject, key: String): Int? {
        return args[key]?.jsonPrimitive?.intOrNull
    }

    private fun requiredKey(args: JsonObject): String {
        return stringArg(args, "key") ?: throw IllegalArgumentException("key 不能为空")
    }

    private fun requireTarget(target: String?): String {
        return target?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("target 不能为空")
    }

    private fun normalizeTarget(target: String): String {
        return target.trim().replace('-', '_').lowercase(Locale.ROOT)
    }

    private fun requireConfirmed(args: JsonObject, message: String) {
        if (!helper.parseConfirmedFlag(args["confirmed"])) {
            throw IllegalArgumentException(message)
        }
    }

    private fun argsWithValueType(args: JsonObject, valueType: String): JsonObject {
        return JsonObject(args + ("valueType" to kotlinx.serialization.json.JsonPrimitive(valueType)))
    }

    private fun stringListValue(args: JsonObject): List<String> {
        return stringList(requiredAnyValue(args))
    }

    private fun stringList(value: Any?): List<String> {
        return when (value) {
            null -> emptyList()
            is Iterable<*> -> value.mapNotNull { it?.toString() }
            is Array<*> -> value.mapNotNull { it?.toString() }
            is String -> readJsonList(value).takeIf { it.isNotEmpty() }?.map { it.toString() }
                ?: value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            else -> listOf(value.toString())
        }
    }

    private fun parseBoolean(value: Any?): Boolean? {
        return when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> when (value.trim().lowercase(Locale.ROOT)) {
                "1", "true", "yes", "on", "enabled", "enable" -> true
                "0", "false", "no", "off", "disabled", "disable" -> false
                else -> null
            }
            else -> null
        }
    }

    private fun numberToInt(value: Any?): Int {
        return when (value) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        } ?: throw IllegalArgumentException("value 必须是 int")
    }

    private fun numberToLong(value: Any?): Long {
        return when (value) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        } ?: throw IllegalArgumentException("value 必须是 long")
    }

    private fun numberToDouble(value: Any?): Double {
        return when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        } ?: throw IllegalArgumentException("value 必须是 number")
    }

    private fun inferValueType(value: Any?, preferDouble: Boolean = false): String {
        return when (value) {
            is Boolean -> "boolean"
            is Int -> "int"
            is Long -> "long"
            is Float -> "float"
            is Double -> if (preferDouble) "double" else "float"
            is Number -> if (preferDouble) "double" else "float"
            is Iterable<*> -> "string-list"
            is Map<*, *> -> "json"
            else -> "string"
        }
    }

    private fun valueTypeName(value: Any?): String {
        return when (value) {
            null -> "missing"
            is String -> "string"
            is Boolean -> "boolean"
            is Int -> "int"
            is Long -> "long"
            is Float -> "float"
            is Set<*> -> "string-list"
            else -> value.javaClass.simpleName
        }
    }

    private fun normalizeStoredValue(value: Any?): Any? {
        return when (value) {
            is Set<*> -> value.mapNotNull { it?.toString() }
            else -> value
        }
    }

    private fun listOfMaps(args: JsonObject, vararg keys: String): List<Map<String, Any?>> {
        for (key in keys) {
            val element = args[key] ?: continue
            val value = helper.jsonElementToAny(element)
            if (value is List<*>) {
                return value.mapNotNull { it as? Map<*, *> }
                    .map { map -> map.entries.associate { it.key.toString() to it.value } }
            }
        }
        return emptyList()
    }

    private fun stringFromArgsOrMap(
        args: JsonObject,
        map: Map<String, Any?>?,
        vararg keys: String
    ): String? {
        for (key in keys) {
            stringArg(args, key)?.let { return it }
            stringFromMap(map, key)?.let { return it }
        }
        return null
    }

    private fun stringFromMap(map: Map<String, Any?>?, vararg keys: String): String? {
        if (map == null) return null
        for (key in keys) {
            val value = map[key]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            if (value != null) return value
        }
        return null
    }

    private fun normalizeRemoteMcpMap(raw: Map<String, Any?>): Map<String, Any?> {
        val normalized = raw.toMutableMap()
        copyFirstAlias(normalized, "id", "serverId", "server_id")
        copyFirstAlias(normalized, "endpointUrl", "endpoint_url", "endpoint", "url", "baseUrl", "base_url")
        copyFirstAlias(normalized, "bearerToken", "bearer_token", "token", "apiKey", "api_key")
        copyFirstAlias(normalized, "lastHealth", "last_health")
        copyFirstAlias(normalized, "lastError", "last_error")
        copyFirstAlias(normalized, "toolCount", "tool_count")
        copyFirstAlias(normalized, "lastSyncedAt", "last_synced_at")
        parseBoolean(normalized["enabled"])?.let { normalized["enabled"] = it }
        return normalized
    }

    private fun copyFirstAlias(target: MutableMap<String, Any?>, canonical: String, vararg aliases: String) {
        if (!target[canonical]?.toString()?.trim().isNullOrEmpty()) return
        for (alias in aliases) {
            val value = target[alias] ?: continue
            if (value.toString().trim().isNotEmpty()) {
                target[canonical] = value
                return
            }
        }
    }

    private fun profileFromMap(map: Map<String, Any?>): ModelProviderProfile {
        return ModelProviderProfile(
            id = map["id"]?.toString().orEmpty(),
            name = map["name"]?.toString().orEmpty(),
            baseUrl = map["baseUrl"]?.toString() ?: map["base_url"]?.toString().orEmpty(),
            apiKey = map["apiKey"]?.toString() ?: map["api_key"]?.toString().orEmpty(),
            protocolType = map["protocolType"]?.toString()
                ?: map["protocol_type"]?.toString()
                ?: "openai_compatible"
        )
    }

    private fun providerProfileToMap(profile: ModelProviderProfile): Map<String, Any?> = mapOf(
        "id" to profile.id,
        "name" to profile.name,
        "baseUrl" to profile.baseUrl,
        "apiKey" to profile.apiKey,
        "sourceType" to profile.sourceType,
        "readOnly" to profile.readOnly,
        "ready" to profile.ready,
        "statusText" to profile.statusText,
        "protocolType" to profile.protocolType
    )

    private fun providerConfigToMap(config: cn.com.omnimind.baselib.llm.ModelProviderConfig): Map<String, Any?> = mapOf(
        "id" to config.id,
        "name" to config.name,
        "baseUrl" to config.baseUrl,
        "apiKey" to config.apiKey,
        "source" to config.source,
        "providerType" to config.providerType,
        "readOnly" to config.readOnly,
        "ready" to config.ready,
        "statusText" to config.statusText
    )

    private fun sceneBindingToMap(binding: SceneModelBindingEntry): Map<String, Any?> = mapOf(
        "sceneId" to binding.sceneId,
        "providerProfileId" to binding.providerProfileId,
        "modelId" to binding.modelId
    )

    private fun sceneVoiceToMap(config: SceneVoiceConfig): Map<String, Any?> = mapOf(
        "autoPlay" to config.autoPlay,
        "voiceId" to config.voiceId,
        "stylePreset" to config.stylePreset,
        "customStyle" to config.customStyle
    )

    private fun embeddingConfigToMap(
        config: cn.com.omnimind.bot.agent.WorkspaceMemoryEmbeddingConfig
    ): Map<String, Any?> = mapOf(
        "enabled" to config.enabled,
        "configured" to config.configured,
        "sceneId" to config.sceneId,
        "providerProfileId" to config.providerProfileId,
        "providerProfileName" to config.providerProfileName,
        "modelId" to config.modelId,
        "apiBase" to config.apiBase,
        "hasApiKey" to config.hasApiKey
    )

    private fun rollupStatusToMap(
        status: cn.com.omnimind.bot.agent.WorkspaceMemoryRollupStatus
    ): Map<String, Any?> = mapOf(
        "enabled" to status.enabled,
        "lastRunAtMillis" to status.lastRunAtMillis,
        "lastRunSummary" to status.lastRunSummary
    )

    companion object {
        private const val TOOL_NAME = "app_control"
        private const val FLUTTER_SHARED_PREFS = "FlutterSharedPreferences"
        private const val FLUTTER_KEY_PREFIX = "flutter."
        private const val DEFAULT_MMKV_ID = "default"
    }
}
