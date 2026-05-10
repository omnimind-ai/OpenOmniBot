package cn.com.omnimind.bot.omniinfer

import android.content.Context
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.AgentAiCapabilityConfigSync
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.manager.AssistsCoreManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object OmniInferBuiltinProviderRefresher {
    private const val TAG = "OmniInferProviderRefresh"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun refreshAsync(context: Context, reason: String) {
        val applicationContext = context.applicationContext
        scope.launch {
            runCatching {
                AgentAiCapabilityConfigSync.get(applicationContext).syncFileFromStores()
                val workspaceManager = AgentWorkspaceManager(applicationContext)
                val configFile = workspaceManager.agentConfigFile()
                AssistsCoreManager.dispatchAgentAiConfigChanged(
                    source = "file",
                    path = workspaceManager.shellPathForAndroid(configFile)
                        ?: configFile.absolutePath
                )
            }.onFailure { error ->
                OmniLog.w(TAG, "refresh builtin provider failed ($reason): ${error.message}")
            }
        }
    }
}
