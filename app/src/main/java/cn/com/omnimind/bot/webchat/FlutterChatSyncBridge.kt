package cn.com.omnimind.bot.webchat

import android.os.Handler
import android.os.Looper
import cn.com.omnimind.baselib.util.OmniLog
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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

    fun dispatchWorkbenchProjectUpdated(
        projectId: String,
        updatedPaths: List<String> = emptyList(),
        reason: String = "project_updated",
        items: List<Map<String, Any?>>? = null
    ) {
        val args = linkedMapOf<String, Any?>(
            "projectId" to projectId,
            "updatedPaths" to updatedPaths,
            "reason" to reason
        )
        if (items != null) args["items"] = items
        dispatch(method = "workbenchProjectUpdated", arguments = args)
    }

    suspend fun invokeForResult(method: String, arguments: Map<String, Any?> = emptyMap()): Any? {
        val channel = mainChannel ?: currentChannel
            ?: throw IllegalStateException("Flutter channel unavailable for $method")
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                channel.invokeMethod(method, arguments, object : MethodChannel.Result {
                    override fun success(result: Any?) {
                        if (!cont.isCompleted) cont.resume(result)
                    }
                    override fun error(code: String, msg: String?, details: Any?) {
                        if (!cont.isCompleted) cont.resumeWithException(
                            IllegalStateException("$code: ${msg ?: "Flutter error"}")
                        )
                    }
                    override fun notImplemented() {
                        if (!cont.isCompleted) cont.resumeWithException(
                            UnsupportedOperationException("$method not implemented in Flutter")
                        )
                    }
                })
            }
        }
    }

    private fun dispatch(method: String, arguments: Any?) {
        val channels = listOfNotNull(currentChannel, mainChannel).distinct()
        if (Looper.myLooper() == Looper.getMainLooper()) {
            channels.forEach { target ->
                runCatching { target.invokeMethod(method, arguments) }
                    .onFailure { OmniLog.w(TAG, "dispatch $method failed: ${it.message}") }
            }
        } else {
            Handler(Looper.getMainLooper()).post {
                channels.forEach { target ->
                    runCatching { target.invokeMethod(method, arguments) }
                        .onFailure { OmniLog.w(TAG, "dispatch $method failed: ${it.message}") }
                }
            }
        }
    }
}
