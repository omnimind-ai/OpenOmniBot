package cn.com.omnimind.bot.webchat

import cn.com.omnimind.baselib.util.OmniLog
import io.flutter.plugin.common.MethodChannel

object FlutterChatSyncBridge {
    private const val TAG = "[FlutterChatSyncBridge]"

    @Volatile
    private var currentChannel: MethodChannel? = null

    @Volatile
    private var mainChannel: MethodChannel? = null

    fun bindCurrentChannel(channel: MethodChannel?) {
        currentChannel = channel
    }

    fun bindMainChannel(channel: MethodChannel?) {
        mainChannel = channel
    }

    fun dispatchConversationListChanged(
        reason: String,
        conversation: Map<String, Any?>? = null
    ) {
        dispatch(
            method = "onConversationListChanged",
            arguments = linkedMapOf<String, Any?>(
                "reason" to reason,
                "conversation" to conversation
            )
        )
    }

    fun dispatchConversationMessagesChanged(
        conversationId: Long,
        mode: String,
        reason: String
    ) {
        dispatch(
            method = "onConversationMessagesChanged",
            arguments = mapOf(
                "conversationId" to conversationId,
                "mode" to mode,
                "reason" to reason
            )
        )
    }

    fun dispatchBrowserSnapshotUpdated(snapshot: Map<String, Any?>) {
        dispatch(
            method = "onBrowserSessionSnapshotUpdated",
            arguments = snapshot
        )
    }

    fun dispatchWorkbenchProjectUpdated(projectId: String, updatedPaths: List<String> = emptyList()) {
        dispatch(
            method = "workbenchProjectUpdated",
            arguments = mapOf(
                "projectId" to projectId,
                "updatedPaths" to updatedPaths
            )
        )
    }

    private fun dispatch(method: String, arguments: Any?) {
        val channels = listOfNotNull(currentChannel, mainChannel).distinct()
        channels.forEach { target ->
            runCatching {
                target.invokeMethod(method, arguments)
            }.onFailure {
                OmniLog.w(TAG, "dispatch $method failed: ${it.message}")
            }
        }
    }
}
