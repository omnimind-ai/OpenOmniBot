package cn.com.omnimind.bot.ui.channel

import android.content.Context
import cn.com.omnimind.bot.voice.SceneVoicePlaybackManager
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

class VoicePlaybackChannel {
    private val methodChannelName = "cn.com.omnimind.bot/VoicePlayback"
    private val eventChannelName = "cn.com.omnimind.bot/VoicePlaybackEvents"

    private var methodChannel: MethodChannel? = null
    private var eventChannel: EventChannel? = null
    private var manager: SceneVoicePlaybackManager? = null

    fun onCreate(context: Context) {
        manager = SceneVoicePlaybackManager(context)
    }

    fun setChannel(flutterEngine: FlutterEngine) {
        methodChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, methodChannelName)
        methodChannel?.setMethodCallHandler(::handleMethodCall)

        eventChannel = EventChannel(flutterEngine.dartExecutor.binaryMessenger, eventChannelName)
        eventChannel?.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                manager?.setEventEmitter { payload ->
                    events?.success(payload)
                }
            }

            override fun onCancel(arguments: Any?) {
                manager?.setEventEmitter(null)
            }
        })
    }

    private fun handleMethodCall(call: MethodCall, result: MethodChannel.Result) {
        val playbackManager = manager
        if (playbackManager == null) {
            result.error("NO_MANAGER", "VoicePlaybackManager is null", null)
            return
        }
        when (call.method) {
            "speakText" -> {
                result.success(
                    playbackManager.speakText(
                        messageId = call.argument<String>("messageId").orEmpty(),
                        text = call.argument<String>("text").orEmpty(),
                        enqueue = call.argument<Boolean>("enqueue") == true,
                        preferStreaming = call.argument<Boolean>("preferStreaming") != false
                    )
                )
            }

            "replayText" -> {
                result.success(
                    playbackManager.replayText(
                        messageId = call.argument<String>("messageId").orEmpty(),
                        text = call.argument<String>("text").orEmpty()
                    )
                )
            }

            "pausePlayback" -> {
                result.success(playbackManager.pause(call.argument<String>("messageId")))
            }

            "resumePlayback" -> {
                result.success(playbackManager.resume(call.argument<String>("messageId")))
            }

            "stopPlayback" -> {
                result.success(playbackManager.stop(call.argument<String>("messageId")))
            }

            else -> result.notImplemented()
        }
    }

    fun clear() {
        manager?.release()
        manager?.setEventEmitter(null)
        methodChannel?.setMethodCallHandler(null)
        eventChannel?.setStreamHandler(null)
        methodChannel = null
        eventChannel = null
    }
}
