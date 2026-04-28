package cn.com.omnimind.bot

import BaseApplication
import cn.com.omnimind.baselib.database.DatabaseHelper
import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.baselib.llm.LocalModelProviderBridge
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.AgentAiCapabilityConfigSync
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.agent.SkillIndexService
import cn.com.omnimind.bot.agent.WorkspaceMemoryRollupScheduler
import cn.com.omnimind.bot.agent.WorkspaceScheduledTaskScheduler
import cn.com.omnimind.bot.mcp.McpServerManager
import cn.com.omnimind.bot.omniinfer.OmniInferLocalRuntime
import cn.com.omnimind.bot.omniinfer.OmniInferMnnModelsManager
import cn.com.omnimind.bot.omniinfer.OmniInferModelsManager
import cn.com.omnimind.bot.quicklog.QuickLogWidgetUpdater
import cn.com.omnimind.bot.terminal.EmbeddedTerminalRuntime
import cn.com.omnimind.bot.update.AppUpdateManager
import cn.com.omnimind.bot.util.NestedBackgroundStateUtil
import com.omniinfer.server.OmniInferServer
import com.rk.resources.Res
import com.tencent.mmkv.MMKV
import io.flutter.FlutterInjector
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterEngineGroup
import io.flutter.embedding.engine.dart.DartExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class App : BaseApplication() {
    companion object {
        lateinit var instance: App

        private var flutterEngineGroup: FlutterEngineGroup? = null
        private var cachedMainEngine: FlutterEngine? = null

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
        AppLocaleManager.applyAppLocale(this)
        com.rk.libcommons.application = this
        Res.application = this

        MMKV.initialize(this)

        DatabaseHelper.init(this)
        OmniInferServer.init(this)
        OmniInferLocalRuntime.setContext(this)
        LocalModelProviderBridge.setDelegate(
            object : LocalModelProviderBridge.Delegate {
                override suspend fun prepareForRequest(
                    profileId: String?,
                    apiBase: String?,
                    modelId: String,
                ): Boolean {
                    val ggufReady = runCatching {
                        OmniInferModelsManager.ensureModelReady(modelId)
                    }.getOrDefault(false)
                    if (ggufReady) {
                        return true
                    }
                    return runCatching {
                        OmniInferMnnModelsManager.ensureModelReady(modelId)
                    }.getOrDefault(false)
                }
            }
        )

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

    fun initSDKsAfterPrivacyConsent() {
        OmniLog.d("AppStartup", "initSDKsAfterPrivacyConsent start")
        AppUpdateManager.requestSilentCheckIfDue(this)
        OmniLog.d("AppStartup", "initSDKsAfterPrivacyConsent completed")
    }
}
