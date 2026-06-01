package cn.com.omnimind.bot

import BaseApplication
import android.content.Context
import cn.com.omnimind.baselib.database.DatabaseHelper
import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.SceneModelBindingStore
import cn.com.omnimind.baselib.shizuku.ShizukuCapabilityManager
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.AgentAiCapabilityConfigSync
import cn.com.omnimind.bot.agent.AgentToolJson
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.agent.SkillIndexService
import cn.com.omnimind.bot.agent.WorkspaceMemoryRollupScheduler
import cn.com.omnimind.bot.agent.WorkspaceScheduledTaskScheduler
import cn.com.omnimind.bot.activity.StartupThemeResolver
import cn.com.omnimind.bot.localmodel.LocalModelFeatureInstaller
import cn.com.omnimind.bot.manager.AssistsCoreManager
import cn.com.omnimind.bot.mcp.McpServerManager
import cn.com.omnimind.bot.quicklog.QuickLogWidgetUpdater
import cn.com.omnimind.bot.runlog.OobOmniFlowToolkitService
import cn.com.omnimind.bot.terminal.EmbeddedTerminalRuntime
import cn.com.omnimind.bot.update.AppUpdateManager
import cn.com.omnimind.bot.util.NestedBackgroundStateUtil
import cn.com.omnimind.bot.vlm.OobVlmPageContextProvider
import cn.com.omnimind.assists.task.vlmserver.OperationResult
import cn.com.omnimind.assists.task.vlmserver.VLMFunctionRunHandler
import cn.com.omnimind.assists.task.vlmserver.VLMFunctionRunRegistry
import cn.com.omnimind.assists.task.vlmserver.VLMFunctionRunRequest
import com.rk.resources.Res
import com.tencent.mmkv.MMKV
import io.flutter.FlutterInjector
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterEngineGroup
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.plugins.GeneratedPluginRegistrant
import kotlinx.serialization.json.JsonArray as KJsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject as KJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class App : BaseApplication() {
    companion object {
        lateinit var instance: App

        private var flutterEngineGroup: FlutterEngineGroup? = null
        private var cachedMainEngine: FlutterEngine? = null
        private const val DEFAULT_PROVIDER_PROFILE_ID = "dashboard-local"
        private const val DEFAULT_PROVIDER_PROFILE_NAME = "Dashboard Local"
        private val DEFAULT_PROVIDER_SCENES = listOf(
            "scene.dispatch.model",
            "scene.vlm.operation.primary",
            "scene.compactor.context",
            "scene.compactor.context.chat"
        )

        fun getFlutterEngineGroup(): FlutterEngineGroup {
            if (flutterEngineGroup == null) {
                flutterEngineGroup = FlutterEngineGroup(instance)
                OmniLog.d("AppStartup", "FlutterEngineGroup created")
            }
            return flutterEngineGroup!!
        }

        fun getCachedMainEngine(): FlutterEngine {
            if (cachedMainEngine == null) {
                val engineStart = System.currentTimeMillis()
                OmniLog.d("AppStartup", "Creating main engine from FlutterEngineGroup")

                cachedMainEngine = getFlutterEngineGroup().createAndRunDefaultEngine(instance)

                OmniLog.d(
                    "AppStartup",
                    "Main engine created, cost: ${System.currentTimeMillis() - engineStart}ms"
                )
            }
            return cachedMainEngine!!
        }

        fun createEngineFromGroup(): FlutterEngine {
            val engineStart = System.currentTimeMillis()
            OmniLog.d(
                "AppStartup",
                "Creating secondary engine from FlutterEngineGroup with subEngineMain entry point"
            )

            val dartEntrypoint = DartExecutor.DartEntrypoint(
                FlutterInjector.instance().flutterLoader().findAppBundlePath(),
                "subEngineMain"
            )

            val options = FlutterEngineGroup.Options(instance)
                .setDartEntrypoint(dartEntrypoint)

            val engine = getFlutterEngineGroup().createAndRunEngine(options)
            GeneratedPluginRegistrant.registerWith(engine)

            OmniLog.d(
                "AppStartup",
                "Secondary engine created with subEngineMain, cost: ${System.currentTimeMillis() - engineStart}ms"
            )
            return engine
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCreate() {
        val appStartTime = System.currentTimeMillis()
        OmniLog.d("AppStartup", "App onCreate start")
        super.onCreate()
        OmniLog.d(
            "AppStartup",
            "App super.onCreate cost: ${System.currentTimeMillis() - appStartTime}ms"
        )
        instance = this
        StartupThemeResolver.applyStoredApplicationNightMode(this)
        AppLocaleManager.applyAppLocale(this)
        com.rk.libcommons.application = this
        Res.application = this

        MMKV.initialize(this)
        setupUncaughtExceptionHandler()

        DatabaseHelper.init(this)
        LocalModelFeatureInstaller.install(this)

        val nestedStart = System.currentTimeMillis()
        NestedBackgroundStateUtil.init(this)
        OmniLog.d(
            "AppStartup",
            "NestedBackgroundStateUtil.init cost: ${System.currentTimeMillis() - nestedStart}ms"
        )

        val registryStart = System.currentTimeMillis()
        cn.com.omnimind.baselib.llm.ModelSceneRegistry.init(this)
        OmniLog.d(
            "AppStartup",
            "ModelSceneRegistry.init cost: ${System.currentTimeMillis() - registryStart}ms"
        )
        runCatching {
            seedDefaultModelProviderFromBuildConfigIfNeeded()
        }.onFailure {
            OmniLog.w("AppStartup", "seed default model provider failed: ${it.message}")
        }
        runCatching {
            val workspaceManager = AgentWorkspaceManager(this)
            workspaceManager.ensureRuntimeDirectories()
            SkillIndexService(this, workspaceManager).seedBuiltinSkillsIfNeeded()
        }
        runCatching {
            AgentAiCapabilityConfigSync.get(this).initialize()
        }
        runCatching {
            WorkspaceMemoryRollupScheduler(this).ensureScheduledIfEnabled()
        }
        runCatching {
            WorkspaceScheduledTaskScheduler(this).rescheduleAllEnabled()
        }
        runCatching {
            QuickLogWidgetUpdater.updateAll(this)
        }
        runCatching {
            ShizukuCapabilityManager.get(this)
        }
        runCatching {
            cn.com.omnimind.assists.task.vlmserver.VLMPageContextProviderRegistry.register(
                OobVlmPageContextProvider(this)
            )
        }
        runCatching {
            VLMFunctionRunRegistry.register(object : VLMFunctionRunHandler {
                override suspend fun runFunction(request: VLMFunctionRunRequest): OperationResult {
                    val payload = OobOmniFlowToolkitService(this@App).runFunction(
                        linkedMapOf(
                            "function_id" to request.functionId,
                            "arguments" to jsonObjectToPlainMap(request.arguments),
                        )
                    )
                    val success = payload["success"] == true
                    val message = listOf(
                        payload["message"],
                        payload["summary"],
                        payload["error_message"],
                        payload["error"],
                    ).firstNotNullOfOrNull { value ->
                        value?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                    } ?: if (success) "复用指令执行完成" else "复用指令执行失败"
                    return OperationResult(
                        success = success,
                        message = message,
                        data = AgentToolJson.mapToJsonElement(payload),
                    )
                }
            })
        }

        initSDKsAfterPrivacyConsent()
        McpServerManager.restoreIfEnabled(this)
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                EmbeddedTerminalRuntime.warmup(this@App)
            }
        }
        OmniLog.d(
            "AppStartup",
            "App onCreate total cost: ${System.currentTimeMillis() - appStartTime}ms"
        )
    }

    private fun seedDefaultModelProviderFromBuildConfigIfNeeded() {
        if (!BuildConfig.DEBUG) return
        val baseUrl = BuildConfig.DEFAULT_MODEL_PROVIDER_BASE_URL.trim()
        val apiKey = BuildConfig.DEFAULT_MODEL_PROVIDER_API_KEY.trim()
        val modelId = BuildConfig.DEFAULT_MODEL_PROVIDER_MODEL_ID.trim()
        if (baseUrl.isEmpty() || apiKey.isEmpty() || modelId.isEmpty()) return

        val profile = ModelProviderConfigStore.saveProfile(
            id = DEFAULT_PROVIDER_PROFILE_ID,
            name = DEFAULT_PROVIDER_PROFILE_NAME,
            baseUrl = baseUrl,
            apiKey = apiKey,
            protocolType = "openai_compatible"
        )
        DEFAULT_PROVIDER_SCENES.forEach { sceneId ->
            SceneModelBindingStore.saveBinding(
                sceneId = sceneId,
                providerProfileId = profile.id,
                modelId = modelId
            )
        }
        seedFlutterManualModelId(
            profileId = profile.id,
            modelId = modelId
        )
        AgentAiCapabilityConfigSync.get(this).syncFileFromStores()
        AssistsCoreManager.dispatchAgentAiConfigChanged(
            source = "debug_install",
            path = "build_config_default_model_provider"
        )
    }

    private fun jsonObjectToPlainMap(value: KJsonObject): Map<String, Any?> =
        value.mapValues { (_, item) -> jsonElementToPlainValue(item) }

    private fun jsonElementToPlainValue(value: JsonElement?): Any? {
        return when (value) {
            null, JsonNull -> null
            is KJsonObject -> jsonObjectToPlainMap(value)
            is KJsonArray -> value.map { jsonElementToPlainValue(it) }
            is JsonPrimitive -> {
                value.booleanOrNull
                    ?: value.longOrNull
                    ?: value.doubleOrNull
                    ?: value.contentOrNull
            }
        }
    }

    private fun seedFlutterManualModelId(profileId: String, modelId: String) {
        val normalizedProfileId = profileId.trim()
        val normalizedModelId = modelId.trim()
        if (normalizedProfileId.isEmpty() || normalizedModelId.isEmpty()) return

        val prefs = applicationContext.getSharedPreferences(
            "FlutterSharedPreferences",
            Context.MODE_PRIVATE
        )
        val key = "flutter.manual_provider_model_ids_v2"
        val current = runCatching {
            JSONObject(prefs.getString(key, null).orEmpty())
        }.getOrElse {
            JSONObject()
        }
        val ids = current.optJSONArray(normalizedProfileId) ?: JSONArray()
        val exists = (0 until ids.length()).any { index ->
            ids.optString(index).trim() == normalizedModelId
        }
        if (!exists) {
            ids.put(normalizedModelId)
        }
        current.put(normalizedProfileId, ids)
        prefs.edit().putString(key, current.toString()).apply()
    }

    private fun setupUncaughtExceptionHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                OmniLog.storeCrashLog(
                    tag = "UncaughtException",
                    message = "Thread: ${thread.name}",
                    throwable = throwable,
                )
            } catch (_: Throwable) {
                // Preserve the original crash path even if crash-log persistence fails.
            } finally {
                if (defaultHandler != null) {
                    defaultHandler.uncaughtException(thread, throwable)
                } else {
                    android.os.Process.killProcess(android.os.Process.myPid())
                    kotlin.system.exitProcess(10)
                }
            }
        }
    }

    fun initSDKsAfterPrivacyConsent() {
        OmniLog.d("AppStartup", "initSDKsAfterPrivacyConsent start")
        AppUpdateManager.requestSilentCheckIfDue(this)
        OmniLog.d("AppStartup", "initSDKsAfterPrivacyConsent completed")
    }
}
