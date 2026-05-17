package cn.com.omnimind.bot.ui.halfScreen

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import androidx.lifecycle.LifecycleOwner
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.App
import cn.com.omnimind.bot.activity.MainActivity

import cn.com.omnimind.bot.ui.channel.ChannelManager
import cn.com.omnimind.bot.ui.channel.RouteOptions
import cn.com.omnimind.bot.ui.channel.ScreenDialogChannel
import cn.com.omnimind.uikit.api.callback.HalfScreenApi
import io.flutter.embedding.android.ExclusiveAppComponent
import io.flutter.embedding.android.FlutterSurfaceView
import io.flutter.embedding.android.FlutterView
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

open class HalfScreenListenerImpl(val context: Context) : HalfScreenApi {
private val TAG = "[HalfScreenListenerImpl]"
    private var channelManagerForWindow: ChannelManager = ChannelManager()
    private var screenDialogChannel: ScreenDialogChannel = ScreenDialogChannel()
    private var windowFlutterEngine: FlutterEngine? = null
    private var flutterView: FlutterView? = null
    private var isViewDestroyed = false
    private var isEngineAttachedToActivity = false

    fun init() {
        // 初始化FlutterEngine (使用 FlutterEngineGroup)
        createFlutterEngine()
    }

    private fun createFlutterEngine() {
        if (windowFlutterEngine == null) {
            newCreateFlutterEngine()
        }
    }

    private fun newCreateFlutterEngine() {
        val engineStart = System.currentTimeMillis()
        OmniLog.d(TAG, "HalfScreenListenerImpl creating engine from FlutterEngineGroup")
        // 从 FlutterEngineGroup 创建引擎，共享主引擎的资源
        windowFlutterEngine = cn.com.omnimind.bot.App.createEngineFromGroup()
        attachEngineToActivityIfPossible()
        OmniLog.d(TAG, "HalfScreenListenerImpl engine created, cost: ${System.currentTimeMillis() - engineStart}ms")
    }

    private fun attachEngineToActivityIfPossible() {
        if (isEngineAttachedToActivity) {
            return
        }
        val activity = context as? Activity
        val lifecycleOwner = context as? LifecycleOwner
        val engine = windowFlutterEngine
        if (activity == null || lifecycleOwner == null || engine == null) {
            OmniLog.w(TAG, "Unable to attach half screen engine to Activity: activity=$activity lifecycleOwner=$lifecycleOwner engine=$engine")
            return
        }
        runCatching {
            engine.activityControlSurface.attachToActivity(
                HalfScreenExclusiveAppComponent(activity),
                lifecycleOwner.lifecycle
            )
            isEngineAttachedToActivity = true
            OmniLog.d(TAG, "Half screen engine attached to Activity")
        }.onFailure { error ->
            OmniLog.e(TAG, "Failed to attach half screen engine to Activity: ${error.message}", error)
        }
    }

    private fun detachEngineFromActivity() {
        if (!isEngineAttachedToActivity) {
            return
        }
        runCatching {
            windowFlutterEngine?.activityControlSurface?.detachFromActivity()
            isEngineAttachedToActivity = false
            OmniLog.d(TAG, "Half screen engine detached from Activity")
        }.onFailure { error ->
            OmniLog.e(TAG, "Failed to detach half screen engine from Activity: ${error.message}", error)
        }
    }

    private inner class HalfScreenExclusiveAppComponent(
        private val activity: Activity
    ) : ExclusiveAppComponent<Activity> {
        override fun detachFromFlutterEngine() {
            detachEngineFromActivity()
        }

        override fun getAppComponent(): Activity = activity
    }

    override fun onCreateFlutter(
        path: String
    ): View {
        OmniLog.d("HalfScreen", "🎭 HalfScreenListenerImpl.onCreateFlutter() 开始，path: $path")
        OmniLog.d(TAG, "onCreateFlutter called with path: $path")

        // 确保FlutterEngine存在
        OmniLog.d("HalfScreen", "🔧 确保 FlutterEngine 存在...")
        createFlutterEngine()
        attachEngineToActivityIfPossible()

        // 如果已经有FlutterView，先分离并清理
        OmniLog.d("HalfScreen", "🧹 清理旧的 FlutterView...")
        cleanupFlutterView()

        // 重置销毁标志
        isViewDestroyed = false
        OmniLog.d("HalfScreen", "✅ 销毁标志已重置")

        // 配置 ChannelManager 和 ScreenDialogChannel
        OmniLog.d("HalfScreen", "📡 配置 Channel...")
        channelManagerForWindow = ChannelManager()
        screenDialogChannel = ScreenDialogChannel()

        channelManagerForWindow.configureFlutterEngine(windowFlutterEngine!!)
        screenDialogChannel.setChannel(windowFlutterEngine!!)
        channelManagerForWindow.onCreate(context)

        OmniLog.d("HalfScreen", "✅ Channels 配置完成")
        OmniLog.d(TAG, "Channels configured for half screen engine")

        OmniLog.d("HalfScreen", "🎬 创建 FlutterSurfaceView...")
        val surfaceView = FlutterSurfaceView(context).apply {
            setZOrderOnTop(true)
            holder.setFormat(PixelFormat.TRANSPARENT)
        }

        // 创建新的FlutterView并绑定到半屏Engine（不是主引擎！）
        OmniLog.d("HalfScreen", "🎨 创建 FlutterView 并绑定到引擎...")
        flutterView = FlutterView(context, surfaceView).apply {
            OmniLog.d(TAG, "Creating new FlutterView and attaching to half screen engine")
            attachToFlutterEngine(windowFlutterEngine!!)
            OmniLog.d("HalfScreen", "✅ FlutterView 已绑定到引擎")

            OmniLog.d("HalfScreen", "🚀 清空路由栈并导航到: $path")
            OmniLog.d(TAG, "Clearing and navigating to: $path")
            channelManagerForWindow.getUIRouterChannel().clearAndNavigateTo(path, options = RouteOptions(noAnim = true))
            OmniLog.d("HalfScreen", "✅ 路由清理和导航完成")
        }

        OmniLog.d("HalfScreen", "🎉 FlutterView 创建成功，准备返回")
        OmniLog.d(TAG, "FlutterView created and configured successfully")
        return flutterView!!
    }

    override fun onCreateLearnFlutter(path: String): View {
        OmniLog.d(TAG, "onCreateLearnFlutter called with path: $path")

        // 确保FlutterEngine存在
        createFlutterEngine()
        attachEngineToActivityIfPossible()

        // 如果已经有FlutterView，先分离并清理
        cleanupFlutterView()

        // 重置销毁标志
        isViewDestroyed = false

        // 配置 ChannelManager 和 CacheChannel
        channelManagerForWindow = ChannelManager()
        screenDialogChannel = ScreenDialogChannel()

        channelManagerForWindow.configureFlutterEngine(windowFlutterEngine!!)
        screenDialogChannel.setChannel(windowFlutterEngine!!)
        channelManagerForWindow.onCreate(context)

        OmniLog.d(TAG, "Channels configured for learn flutter")

        // 创建新的FlutterView并绑定Engine
        val surfaceView = FlutterSurfaceView(context).apply {
            setZOrderOnTop(true)
            holder.setFormat(PixelFormat.TRANSPARENT)
        }

        flutterView = FlutterView(context, surfaceView).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isFocusableInTouchMode = true

            OmniLog.d(TAG, "Attaching FlutterView to half screen engine")
            attachToFlutterEngine(windowFlutterEngine!!)

            OmniLog.d(TAG, "Pushing route: $path to half screen engine")
            windowFlutterEngine!!.navigationChannel.pushRoute(path)
        }

        OmniLog.d(TAG, "Learn FlutterView created successfully")
        return flutterView!!
    }

    private fun cleanupFlutterView() {
        OmniLog.d(TAG, "cleanupFlutterView called")
        
        isViewDestroyed = true
        
        // 清理 channel
        screenDialogChannel.clear()
        channelManagerForWindow.clearChannel()
        
        // 禁用消息缓冲
        windowFlutterEngine?.dartExecutor?.binaryMessenger?.disableBufferingIncomingMessages()

        flutterView?.let { view ->
            try {
                OmniLog.d(TAG, "Detaching and removing existing FlutterView")
                
                // 首先分离引擎（非常重要！）
                if (view.isAttachedToWindow) {
                    view.detachFromFlutterEngine()
                    OmniLog.d(TAG, "FlutterView detached from engine")
                }

                // 然后从父视图中移除
                val parent: ViewParent? = view.parent
                if (parent != null) {
                    (parent as? ViewGroup)?.removeView(view)
                    OmniLog.d(TAG, "FlutterView removed from parent")
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "Error cleaning up FlutterView: ${e.message}", e)
            }
        }
        
        flutterView = null
        OmniLog.d(TAG, "FlutterView cleanup completed")
    }



    override fun onDestroyOrGone() {
        channelManagerForWindow.getUIRouterChannel().clearAndNavigateTo("/home/blank_page")
        onDestroy()
    }

    private fun routeMainEngine(route: String, needClear: Boolean) {
        try {
            val routerChannel = MethodChannel(
                App.getCachedMainEngine().dartExecutor.binaryMessenger,
                "ui_router_channel"
            )
            val method = if (needClear) "clearAndNavigateTo" else "resetToHomeAndPush"
            val arguments = mapOf<String, Any>(
                "route" to route,
                "options" to mapOf("noAnim" to true)
            )
            routerChannel.invokeMethod(method, arguments)
            OmniLog.d(TAG, "Requested main engine navigation via $method: $route")
        } catch (e: Exception) {
            OmniLog.e(TAG, "Failed to route main engine directly: ${e.message}", e)
        }
    }

    override fun onNeedOpenAppMainParam(path: String?, needClear: Boolean) {
        val route = path?.takeIf { it.isNotBlank() } ?: "/home/chat"
        routeMainEngine(route, needClear)

        val intent = Intent(context.applicationContext, MainActivity::class.java)
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        )
        intent.putExtra("route", route)
        intent.putExtra("needClear", needClear)
        context.applicationContext.startActivity(intent)
        OmniLog.d(TAG, "Requested MainActivity foreground route=$route needClear=$needClear")
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        return windowFlutterEngine?.activityControlSurface?.onActivityResult(
            requestCode,
            resultCode,
            data
        ) == true
    }

    fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ): Boolean {
        return windowFlutterEngine?.activityControlSurface?.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        ) == true
    }

    fun onDestroy() {
        OmniLog.d(TAG, "onDestroy called")
        
        try {
            // 标记视图为已销毁
            isViewDestroyed = true
            
            // 清理FlutterView
            cleanupFlutterView()
            detachEngineFromActivity()
            // 注意：使用 FlutterEngineGroup 时，我们不销毁引擎
            // 引擎会被保留并在下次使用时复用
            // 这样可以避免重新创建引擎的开销，并且保证引擎的正确性
            
            // windowFlutterEngine?.destroy()
            // windowFlutterEngine = null
            // channelManagerForWindow = ChannelManager()
            // screenDialogChannel = ScreenDialogChannel()
            // CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            //     //延时一秒处理flutter
            //     delay(1000)
            //     withContext(Dispatchers.Main){

            //         newCreateFlutterEngine()
            //     }

            // }

        } catch (e: Exception) {
            OmniLog.e(TAG, "Error in onDestroy: ${e.message}", e)
        }
    }
}
