package cn.com.omnimind.bot.ui.channel

import android.annotation.SuppressLint
import android.content.Context
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.App
import cn.com.omnimind.bot.manager.AssistsCoreManager
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * 无障碍核心能力通道
 */
class AssistsCoreChannel {
    var TAG = "[AssistsCoreChannel]"
    private val EVENT_CHANNEL = "cn.com.omnimind.bot/AssistCoreEvent" // Flutter 事件通道
    private var channel: MethodChannel? = null
    private var mainJob:CoroutineScope= CoroutineScope(Dispatchers.Main)

    @SuppressLint("StaticFieldLeak")
    private var assistsCoreManager: AssistsCoreManager? = null
    fun onCreate(context: Context) {
        assistsCoreManager = AssistsCoreManager(context)
        assistsCoreManager?.setChannel(channel!!);

    }



    fun setChannel( flutterEngine: FlutterEngine) {
        channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, EVENT_CHANNEL)
        assistsCoreManager?.setChannel(channel!!);
        if (flutterEngine == App.getCachedMainEngine()) {
            AssistsCoreManager.bindMainEngineChannel(channel!!)
        }
        channel!!.setMethodCallHandler { call, result ->
            OmniLog.d(TAG, "setMethodCallHandler " + call.method)
            when (call.method) {
                "createCompanionTask" -> {
                    assistsCoreManager!!.createCompanionTask( call, result)
                }

                "createChatTask" -> {
                    assistsCoreManager!!.createChatTask( call, result)
                }

                "createAgentTask" -> {
                    assistsCoreManager!!.createAgentTask( call, result)
                }
                "agentSkillList" -> {
                    assistsCoreManager!!.agentSkillList(call, result)
                }
                "agentSkillInstall" -> {
                    assistsCoreManager!!.agentSkillInstall(call, result)
                }
                "agentSkillSetEnabled" -> {
                    assistsCoreManager!!.agentSkillSetEnabled(call, result)
                }
                "agentSkillDelete" -> {
                    assistsCoreManager!!.agentSkillDelete(call, result)
                }
                "agentSkillInstallBuiltin" -> {
                    assistsCoreManager!!.agentSkillInstallBuiltin(call, result)
                }
                "getModelProviderConfig" -> {
                    assistsCoreManager!!.getModelProviderConfig(call, result)
                }
                "listModelProviderProfiles" -> {
                    assistsCoreManager!!.listModelProviderProfiles(call, result)
                }
                "listRecentAiRequestLogs" -> {
                    assistsCoreManager!!.listRecentAiRequestLogs(call, result)
                }
                "listRuntimeLogs" -> {
                    assistsCoreManager!!.listRuntimeLogs(call, result)
                }
                "clearRuntimeLogs" -> {
                    assistsCoreManager!!.clearRuntimeLogs(call, result)
                }
                "saveModelProviderProfile" -> {
                    assistsCoreManager!!.saveModelProviderProfile(call, result)
                }
                "deleteModelProviderProfile" -> {
                    assistsCoreManager!!.deleteModelProviderProfile(call, result)
                }
                "setEditingModelProviderProfile" -> {
                    assistsCoreManager!!.setEditingModelProviderProfile(call, result)
                }
                "saveModelProviderConfig" -> {
                    assistsCoreManager!!.saveModelProviderConfig(call, result)
                }
                "clearModelProviderConfig" -> {
                    assistsCoreManager!!.clearModelProviderConfig(call, result)
                }
                "fetchProviderModels" -> {
                    assistsCoreManager!!.fetchProviderModels(call, result)
                }
                "getSceneModelCatalog" -> {
                    assistsCoreManager!!.getSceneModelCatalog(call, result)
                }
                "getSceneModelBindings" -> {
                    assistsCoreManager!!.getSceneModelBindings(call, result)
                }
                "saveSceneModelBinding" -> {
                    assistsCoreManager!!.saveSceneModelBinding(call, result)
                }
                "clearSceneModelBinding" -> {
                    assistsCoreManager!!.clearSceneModelBinding(call, result)
                }
                "getSceneVoiceConfig" -> {
                    assistsCoreManager!!.getSceneVoiceConfig(call, result)
                }
                "saveSceneVoiceConfig" -> {
                    assistsCoreManager!!.saveSceneVoiceConfig(call, result)
                }
                "getSceneModelOverrides" -> {
                    assistsCoreManager!!.getSceneModelOverrides(call, result)
                }
                "saveSceneModelOverride" -> {
                    assistsCoreManager!!.saveSceneModelOverride(call, result)
                }
                "clearSceneModelOverride" -> {
                    assistsCoreManager!!.clearSceneModelOverride(call, result)
                }
                "checkVlmModelAvailability" -> {
                    assistsCoreManager!!.checkVlmModelAvailability(call, result)
                }
                "getWorkspaceSoul" -> {
                    assistsCoreManager!!.getWorkspaceSoul(call, result)
                }
                "getWorkspaceChatPrompt" -> {
                    assistsCoreManager!!.getWorkspaceChatPrompt(call, result)
                }
                "saveWorkspaceSoul" -> {
                    assistsCoreManager!!.saveWorkspaceSoul(call, result)
                }
                "saveWorkspaceChatPrompt" -> {
                    assistsCoreManager!!.saveWorkspaceChatPrompt(call, result)
                }
                "getWorkspaceLongMemory" -> {
                    assistsCoreManager!!.getWorkspaceLongMemory(call, result)
                }
                "getWorkspaceShortMemories" -> {
                    assistsCoreManager!!.getWorkspaceShortMemories(call, result)
                }
                "saveWorkspaceLongMemory" -> {
                    assistsCoreManager!!.saveWorkspaceLongMemory(call, result)
                }
                "getWorkspaceMemoryEmbeddingConfig" -> {
                    assistsCoreManager!!.getWorkspaceMemoryEmbeddingConfig(call, result)
                }
                "saveWorkspaceMemoryEmbeddingConfig" -> {
                    assistsCoreManager!!.saveWorkspaceMemoryEmbeddingConfig(call, result)
                }
                "getWorkspaceMemoryRollupStatus" -> {
                    assistsCoreManager!!.getWorkspaceMemoryRollupStatus(call, result)
                }
                "saveWorkspaceMemoryRollupEnabled" -> {
                    assistsCoreManager!!.saveWorkspaceMemoryRollupEnabled(call, result)
                }
                "runWorkspaceMemoryRollupNow" -> {
                    assistsCoreManager!!.runWorkspaceMemoryRollupNow(call, result)
                }
                "upsertWorkspaceScheduledTask" -> {
                    assistsCoreManager!!.upsertWorkspaceScheduledTask(call, result)
                }
                "deleteWorkspaceScheduledTask" -> {
                    assistsCoreManager!!.deleteWorkspaceScheduledTask(call, result)
                }
                "syncWorkspaceScheduledTasks" -> {
                    assistsCoreManager!!.syncWorkspaceScheduledTasks(call, result)
                }

                "cancelChatTask" -> {
                    OmniLog.d(TAG, "cancelChatTask")
                    assistsCoreManager!!.cancelChatTask( call, result)
                }

                "createVLMOperationTask" -> {
                    assistsCoreManager!!.createVLMOperationTask( call, result)
                }


                "cancelTask" -> {
                    assistsCoreManager!!.cancelTask( call, result)
                }
                "cancelRunningTask" -> {
                    assistsCoreManager!!.cancelRunningTask( call, result)
                }
                "stopAgentToolCall" -> {
                    assistsCoreManager!!.stopAgentToolCall(call, result)
                }
                "isCompanionTaskRunning" -> {
                    assistsCoreManager!!.isCompanionTaskRunning( call, result)
                }
                "cancelCompanionGoHome" -> {
                    assistsCoreManager!!.cancelCompanionGoHome( call, result)
                }
                "pressHome" -> {
                    assistsCoreManager!!.pressHome(call, result)
                }

                "getInstalledApplications" -> {
                    assistsCoreManager!!.getInstalledApplications( call, result)
                }
                "getInstalledApplicationsWithIconUpdate" -> {
                    assistsCoreManager!!.getInstalledApplicationsWithIconUpdate( call, result)
                }
                "isPackageAuthorized" -> {
                    assistsCoreManager!!.isPackageAuthorized( call, result)
                }
                "scheduleVLMOperationTask" -> {
                    assistsCoreManager!!.scheduleVLMOperationTask( call, result)
                }
                "getScheduleInfo"->{
                    assistsCoreManager!!.getScheduleInfo( call, result)
                }
                "clearScheduleTask"->{
                    assistsCoreManager!!.clearScheduleTask( call, result)
                }
                "doScheduleNow"->{
                    assistsCoreManager!!.doScheduleNow( call, result)
                }
                "cancelScheduleTask"->{
                    assistsCoreManager!!.cancelScheduleTask( call, result)
                }
                "listAgentExactAlarms" -> {
                    assistsCoreManager!!.listAgentExactAlarms(call, result)
                }
                "deleteAgentExactAlarm" -> {
                    assistsCoreManager!!.deleteAgentExactAlarm(call, result)
                }
                "getAlarmSettings" -> {
                    assistsCoreManager!!.getAlarmSettings(call, result)
                }
                "saveAlarmSettings" -> {
                    assistsCoreManager!!.saveAlarmSettings(call, result)
                }
                "getNanoTime"->{
                    result.success(System.nanoTime() / 1_000_000)
                }
                "copyToClipboard"->{
                    assistsCoreManager!!.copyToClipboard( call, result)
                }
                "getClipboardText"->{
                    assistsCoreManager!!.getClipboardText(call, result)
                }
                "provideUserInputToVLMTask" -> {
                    assistsCoreManager!!.provideUserInputToVLMTask(call, result)
                }
                "notifySummarySheetReady" -> {
                    assistsCoreManager!!.notifySummarySheetReady(call, result)
                }
                "startFirstUse"->{
                    assistsCoreManager!!.startFirstUse( call, result)
                }
                "postLLMChat"->{
                    assistsCoreManager!!.postLLMChat( call, result)
                }
                "generateMemoryGreeting" -> {
                    assistsCoreManager!!.generateMemoryGreeting(call, result)
                }
                "openAPPMarket"->{
                    assistsCoreManager!!.openAPPMarket( call, result)
                }
                "isDesktop"->{
                    assistsCoreManager!!.isDesktop( call, result)
                }
                "getDeskTopPackageName"->{
                    assistsCoreManager!!.getDeskTopPackageName( call, result)
                }
                "getCurrentPackageName"->{
                    assistsCoreManager!!.getCurrentPackageName( call, result)
                }
                "setAutoBackToChatAfterTaskEnabled" -> {
                    assistsCoreManager!!.setAutoBackToChatAfterTaskEnabled(call, result)
                }
                "navigateToMainEngineRoute" -> {
                    assistsCoreManager!!.navigateToMainEngineRoute(call, result)
                }
                "reopenChatBotAfterAuth" -> {
                    assistsCoreManager!!.reopenChatBotAfterAuth(result)
                }
                "showScheduledTaskReminder" -> {
                    assistsCoreManager!!.showScheduledTaskReminder(call, result)
                }
                "hideScheduledTaskReminder" -> {
                    assistsCoreManager!!.hideScheduledTaskReminder(call, result)
                }
                "getTokenUsageRecords" -> {
                    assistsCoreManager!!.getTokenUsageRecords(call, result)
                }
                "getConversations" -> {
                    assistsCoreManager!!.getConversations(call, result)
                }
                "getConversationMessages" -> {
                    assistsCoreManager!!.getConversationMessages(call, result)
                }
                "getConversationMessagesPaged" -> {
                    assistsCoreManager!!.getConversationMessagesPaged(call, result)
                }
                "replaceConversationMessages" -> {
                    assistsCoreManager!!.replaceConversationMessages(call, result)
                }
                "upsertConversationUiCard" -> {
                    assistsCoreManager!!.upsertConversationUiCard(call, result)
                }
                "compactConversationContext" -> {
                    assistsCoreManager!!.compactConversationContext(call, result)
                }
                "clearConversationMessages" -> {
                    assistsCoreManager!!.clearConversationMessages(call, result)
                }
                "getConversationsByPage" -> {
                    assistsCoreManager!!.getConversationsByPage(call, result)
                }
                "createConversation" -> {
                    assistsCoreManager!!.createConversation(call, result)
                }
                "updateConversation" -> {
                    assistsCoreManager!!.updateConversation(call, result)
                }
                "updateConversationPromptTokenThreshold" -> {
                    assistsCoreManager!!.updateConversationPromptTokenThreshold(call, result)
                }
                "deleteConversation" -> {
                    assistsCoreManager!!.deleteConversation(call, result)
                }
                "updateConversationTitle" -> {
                    assistsCoreManager!!.updateConversationTitle(call, result)
                }
                "generateConversationSummary" -> {
                    assistsCoreManager!!.generateConversationSummary(call, result)
                }
                "completeConversation" -> {
                    assistsCoreManager!!.completeConversation(call, result)
                }
                "setCurrentConversationId" -> {
                    assistsCoreManager!!.setCurrentConversationId(call, result)
                }
                else -> result.notImplemented()
            }
        }
    }

    fun clear() {
        channel?.setMethodCallHandler(null)
        channel = null
    }

}
