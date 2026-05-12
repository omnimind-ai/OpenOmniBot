package cn.com.omnimind.uikit.loader


import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.view.Gravity
import android.view.View
import android.os.Looper
import android.view.ViewGroup
import android.view.WindowManager
import android.view.WindowManager.BadTokenException
import android.widget.FrameLayout
import cn.com.omnimind.accessibility.service.AssistsService
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.uikit.UIKit
import cn.com.omnimind.uikit.api.callback.HalfScreenApi
import cn.com.omnimind.uikit.settings.CompanionOverlaySettings
import cn.com.omnimind.uikit.view.layout.HalfScreenView


@SuppressLint("ClickableViewAccessibility")
class FloatingHalfScreenLoader(
    val context: Context,
    val server: AccessibilityService,
    val halfScreenApi: HalfScreenApi?
) {



    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var INSTANCE: FloatingHalfScreenLoader? = null

        fun getInstance(): FloatingHalfScreenLoader? {
            if (AssistsService.instance != null) {
                return INSTANCE ?: synchronized(this) {
                    INSTANCE ?: FloatingHalfScreenLoader(
                        BaseApplication.instance!!,
                        AssistsService.instance!!,
                        UIKit.halfScreenApi
                    ).also { INSTANCE = it }
                }
            }
            return null
        }

        fun loadFloatingHalfScreen(path: String) {
            if (!CompanionOverlaySettings.isEnabled()) {
                destroyInstance()
                return
            }
            getInstance()?.loadFloatingHalfScreen(path)
        }

        fun loadFloatingLearnScreen(path: String) {
            if (!CompanionOverlaySettings.isEnabled()) {
                destroyInstance()
                return
            }
            getInstance()?.loadFloatingLearnScreen(path)
        }

        fun destroyInstance() {
            INSTANCE?.removeView()
            INSTANCE = null
        }
        fun isShowing(): Boolean {
            return INSTANCE?.isShowing() ?: false
        }
    }
    private var flutterView: View? = null

    private var container: HalfScreenView? = null
    private lateinit var windowManager: WindowManager;

    private var isAttachedToWindow: Boolean = false

    fun isShowing(): Boolean = isAttachedToWindow

    @SuppressLint("SuspiciousIndentation")
    fun getWindowManager(): WindowManager {
        if (!this::windowManager.isInitialized) windowManager =
            server.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return windowManager
    }

    fun loadFloatingHalfScreen(path: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { loadFloatingHalfScreen(path) }
            return
        }
        OmniLog.d(
            "HalfScreen",
            "🎨 FloatingHalfScreenLoader.loadFloatingHalfScreen() 开始，path: $path"
        )
        try {
            removeView()

            OmniLog.d("HalfScreen", "🎭 创建 FlutterView...")
            // 创建 FlutterView，使用和学习弹窗相同的方式
            flutterView = halfScreenApi?.onCreateFlutter(path)?.apply {
                setBackgroundColor(Color.TRANSPARENT)
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ).apply {
                    gravity = Gravity.BOTTOM
                }
                alpha = 1f
                visibility = View.VISIBLE
            }
            OmniLog.d("HalfScreen", "✅ FlutterView 创建完成")

            OmniLog.d("HalfScreen", "📦 创建容器 FrameLayout...")
            // 使用 Service 的 context 创建容器，避免 Application context 导致 token null
            container = HalfScreenView(server).apply {
                setBackgroundColor(Color.TRANSPARENT)
                // 添加硬件加速支持
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            val screenSize = Point()
            getWindowManager().defaultDisplay.getRealSize(screenSize)
            val params = WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                } else {
                    @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM
                height = screenSize.y
                softInputMode =
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                            WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
            }

            OmniLog.d("HalfScreen", "🪟 添加视图到 WindowManager...")
            try {
                getWindowManager().addView(container, params)
                OmniLog.d("HalfScreen", "✅ 容器已添加到 WindowManager")
            } catch (e: BadTokenException) {
                OmniLog.e("HalfScreen", "❌ addView BadTokenException (token null), skip: ${e.message}")
                return
            }

            (container as HalfScreenView).addView(flutterView)
            OmniLog.d("HalfScreen", "✅ FlutterView 已添加到容器")

            isAttachedToWindow = true
            OmniLog.d("HalfScreen", "✅ 半屏已直接展示")
        } catch (e: Exception) {
            e.printStackTrace()
            OmniLog.e("HalfScreen", "❌ loadFloatingHalfScreen 失败: ${e.message}", e)
            OmniLog.e("FloatingHalfScreenLoader", "loadFloatingHalfScreen failed: ${e.message}")
        }
    }


    fun loadFloatingLearnScreen(path: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { loadFloatingLearnScreen(path) }
            return
        }
        try {
            flutterView = halfScreenApi?.onCreateLearnFlutter(path)?.apply {
                setBackgroundColor(Color.TRANSPARENT)
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.BOTTOM
                }
                translationY = 0f
                visibility = View.VISIBLE
            }

            container = HalfScreenView(server).apply {
                setBackgroundColor(Color.TRANSPARENT)
                // 添加硬件加速支持
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            val params = WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                } else {
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,

                ).apply {
                gravity = Gravity.BOTTOM
                softInputMode =
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
            }
            try {
                getWindowManager().addView(container, params)
            } catch (e: BadTokenException) {
                OmniLog.e("FloatingHalfScreenLoader", "loadFloatingLearnScreen addView BadTokenException: ${e.message}")
                return
            }
            (container as HalfScreenView).addView(flutterView)
            isAttachedToWindow = true
        } catch (e: Exception) {
            OmniLog.e("FloatingHalfScreenLoader", "load error: ${e.message}")
        }
    }

    fun removeView() {
        if (!isAttachedToWindow) {
            return
        }
        halfScreenApi?.onDestroyOrGone()
        try {
            container?.visibility = View.GONE

            if (container != null) {
                container?.removeView(flutterView)
                windowManager.removeView(container)
            }
            container = null
        } catch (e: Exception) {
            e.printStackTrace()
        }

        isAttachedToWindow = false
    }
}
