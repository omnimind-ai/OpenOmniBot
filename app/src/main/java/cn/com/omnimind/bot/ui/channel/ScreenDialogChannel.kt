package cn.com.omnimind.bot.ui.channel

import cn.com.omnimind.bot.util.AssistsUtil
import cn.com.omnimind.uikit.loader.FloatingHalfScreenLoader
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScreenDialogChannel {
    private val eventChannel = "cn.com.omnimind.bot/ScreenDialogEvent"
    private var channel: MethodChannel? = null
    private var mainJob: CoroutineScope = CoroutineScope(Dispatchers.Main)

    fun setChannel(flutterEngine: FlutterEngine) {
        channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, eventChannel)
        channel?.setMethodCallHandler { call, result ->
            when (call.method) {
                "closeDialog" -> {
                    mainJob.launch {
                        withContext(Dispatchers.Main) { result.success("Success") }
                        AssistsUtil.UI.closeScreenDialog()
                    }
                }

                "closeChatBotDialog" -> {
                    mainJob.launch {
                        withContext(Dispatchers.Main) { result.success("Success") }
                        AssistsUtil.UI.closeChatBotDialog()
                    }
                }

                "hideForExternalActivity" -> {
                    mainJob.launch {
                        val hidden = withContext(Dispatchers.Main) {
                            FloatingHalfScreenLoader.hideForExternalActivity()
                        }
                        result.success(hidden)
                    }
                }

                "restoreAfterExternalActivity" -> {
                    mainJob.launch {
                        val restored = withContext(Dispatchers.Main) {
                            FloatingHalfScreenLoader.restoreAfterExternalActivity()
                        }
                        result.success(restored)
                    }
                }

                "hideForManualRecording" -> {
                    mainJob.launch {
                        val hidden = withContext(Dispatchers.Main) {
                            FloatingHalfScreenLoader.hideForManualRecording()
                        }
                        result.success(hidden)
                    }
                }

                "restoreAfterManualRecording" -> {
                    mainJob.launch {
                        val restored = withContext(Dispatchers.Main) {
                            FloatingHalfScreenLoader.restoreAfterManualRecording()
                        }
                        result.success(restored)
                    }
                }

                else -> result.notImplemented()
            }
        }
    }

    fun clear() {
        mainJob.cancel()
        channel?.setMethodCallHandler(null)
        channel = null
    }
}
