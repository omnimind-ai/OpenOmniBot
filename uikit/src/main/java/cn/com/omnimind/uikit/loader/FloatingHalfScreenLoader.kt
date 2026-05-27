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
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import cn.com.omnimind.accessibility.service.AssistsService
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.uikit.UIKit
import cn.com.omnimind.uikit.api.callback.HalfScreenApi
import cn.com.omnimind.uikit.loader.cat.DraggableBallInstance
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

        fun hideForExternalActivity(): Boolean {
            return getInstance()?.hideForExternalActivity() ?: false
        }

        fun restoreAfterExternalActivity(): Boolean {
            return getInstance()?.restoreAfterExternalActivity() ?: false
        }

        fun hideForManualRecording(): Boolean {
            return getInstance()?.hideForManualRecording() ?: false
        }

        fun restoreAfterManualRecording(): Boolean {
            return getInstance()?.restoreAfterManualRecording() ?: false
        }
    }
    private var flutterView: View? = null

    private var container: HalfScreenView? = null
    private lateinit var windowManager: WindowManager;
    private var windowParams: WindowManager.LayoutParams? = null

    private var isAttachedToWindow: Boolean = false
    private var isHiddenForExternalActivity: Boolean = false
    private var didHideScreenMaskForExternalActivity: Boolean = false
    private var didHideCancelClickForExternalActivity: Boolean = false
    private var didHideDraggableForExternalActivity: Boolean = false
    private var isHiddenForManualRecording: Boolean = false
    private var didHideScreenMaskForManualRecording: Boolean = false
    private var didHideCancelClickForManualRecording: Boolean = false

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
            windowParams = params

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
            isHiddenForExternalActivity = false

            OmniLog.d("HalfScreen", "🎬 准备启动渐变动画...")
            // 视图添加完成后，使用 post 确保在下一帧开始动画
            flutterView?.post {
                OmniLog.d("HalfScreen", "▶️ 开始执行渐变动画")
                // 使用属性动画，渐变显示
                flutterView?.animate()
                    ?.alpha(1f)
                    ?.setDuration(200)
                    ?.setInterpolator(DecelerateInterpolator())
                    ?.withEndAction {
                        OmniLog.d("HalfScreen", "🎉 [5/5] 渐变动画完成，半屏已完全展示")
                    }
                    ?.start()
            }
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
            windowParams = params
            try {
                getWindowManager().addView(container, params)
            } catch (e: BadTokenException) {
                OmniLog.e("FloatingHalfScreenLoader", "loadFloatingLearnScreen addView BadTokenException: ${e.message}")
                return
            }
            (container as HalfScreenView).addView(flutterView)
            isAttachedToWindow = true
            isHiddenForExternalActivity = false

            // 视图添加完成后，使用 post 确保在下一帧开始动画
            flutterView?.post {
                // 使用属性动画代替 TranslateAnimation，更流畅
                flutterView?.animate()
                    ?.translationY(0f)
                    ?.setDuration(300)
                    ?.setInterpolator(DecelerateInterpolator())
                    ?.start()
            }
        } catch (e: Exception) {
            OmniLog.e("FloatingHalfScreenLoader", "load error: ${e.message}")
        }
    }

    fun hideForExternalActivity(): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { hideForExternalActivity() }
            return false
        }
        didHideScreenMaskForExternalActivity = ScreenMaskLoader.hideForExternalActivity()
        didHideCancelClickForExternalActivity = CancelClickLoader.hideForExternalActivity()
        didHideDraggableForExternalActivity = DraggableBallInstance.hideForExternalActivity()
        if (!isAttachedToWindow || container == null) {
            return didHideScreenMaskForExternalActivity ||
                    didHideCancelClickForExternalActivity ||
                    didHideDraggableForExternalActivity
        }
        return try {
            flutterView?.animate()?.cancel()
            flutterView?.alpha = 1f
            getWindowManager().removeView(container)
            isAttachedToWindow = false
            isHiddenForExternalActivity = true
            OmniLog.d("FloatingHalfScreenLoader", "Half screen hidden for external activity")
            true
        } catch (e: Exception) {
            OmniLog.e("FloatingHalfScreenLoader", "hideForExternalActivity failed: ${e.message}", e)
            false
        }
    }

    fun restoreAfterExternalActivity(): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { restoreAfterExternalActivity() }
            return false
        }
        var restoredScreenMask = false
        if (didHideScreenMaskForExternalActivity) {
            restoredScreenMask = ScreenMaskLoader.restoreAfterExternalActivity()
            didHideScreenMaskForExternalActivity = false
        }
        var restoredCancelClick = false
        if (didHideCancelClickForExternalActivity) {
            restoredCancelClick = CancelClickLoader.restoreAfterExternalActivity()
            didHideCancelClickForExternalActivity = false
        }
        var restoredDraggable = false
        if (didHideDraggableForExternalActivity) {
            restoredDraggable = DraggableBallInstance.restoreAfterExternalActivity()
            didHideDraggableForExternalActivity = false
        }
        val view = container ?: return false
        val params = windowParams ?: return false
        if (isAttachedToWindow || !isHiddenForExternalActivity) {
            return restoredScreenMask || restoredCancelClick || restoredDraggable
        }
        return try {
            getWindowManager().addView(view, params)
            flutterView?.visibility = View.VISIBLE
            flutterView?.alpha = 1f
            isAttachedToWindow = true
            isHiddenForExternalActivity = false
            OmniLog.d("FloatingHalfScreenLoader", "Half screen restored after external activity")
            true
        } catch (e: BadTokenException) {
            OmniLog.e("FloatingHalfScreenLoader", "restoreAfterExternalActivity BadTokenException: ${e.message}")
            restoredScreenMask || restoredCancelClick || restoredDraggable
        } catch (e: Exception) {
            OmniLog.e("FloatingHalfScreenLoader", "restoreAfterExternalActivity failed: ${e.message}", e)
            restoredScreenMask || restoredCancelClick || restoredDraggable
        }
    }

    fun hideForManualRecording(): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { hideForManualRecording() }
            return false
        }
        didHideScreenMaskForManualRecording = ScreenMaskLoader.hideForExternalActivity()
        didHideCancelClickForManualRecording = CancelClickLoader.hideForExternalActivity()
        if (!isAttachedToWindow || container == null) {
            return didHideScreenMaskForManualRecording ||
                    didHideCancelClickForManualRecording
        }
        return try {
            flutterView?.animate()?.cancel()
            flutterView?.alpha = 1f
            getWindowManager().removeView(container)
            isAttachedToWindow = false
            isHiddenForManualRecording = true
            OmniLog.d("FloatingHalfScreenLoader", "Half screen hidden for manual recording")
            true
        } catch (e: Exception) {
            OmniLog.e("FloatingHalfScreenLoader", "hideForManualRecording failed: ${e.message}", e)
            false
        }
    }

    fun restoreAfterManualRecording(): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { restoreAfterManualRecording() }
            return false
        }
        var restoredScreenMask = false
        if (didHideScreenMaskForManualRecording) {
            restoredScreenMask = ScreenMaskLoader.restoreAfterExternalActivity()
            didHideScreenMaskForManualRecording = false
        }
        var restoredCancelClick = false
        if (didHideCancelClickForManualRecording) {
            restoredCancelClick = CancelClickLoader.restoreAfterExternalActivity()
            didHideCancelClickForManualRecording = false
        }
        val view = container ?: return restoredScreenMask || restoredCancelClick
        val params = windowParams ?: return restoredScreenMask || restoredCancelClick
        if (isAttachedToWindow || !isHiddenForManualRecording) {
            return restoredScreenMask || restoredCancelClick
        }
        return try {
            getWindowManager().addView(view, params)
            flutterView?.visibility = View.VISIBLE
            flutterView?.alpha = 1f
            isAttachedToWindow = true
            isHiddenForManualRecording = false
            OmniLog.d("FloatingHalfScreenLoader", "Half screen restored after manual recording")
            true
        } catch (e: BadTokenException) {
            OmniLog.e("FloatingHalfScreenLoader", "restoreAfterManualRecording BadTokenException: ${e.message}")
            restoredScreenMask || restoredCancelClick
        } catch (e: Exception) {
            OmniLog.e("FloatingHalfScreenLoader", "restoreAfterManualRecording failed: ${e.message}", e)
            restoredScreenMask || restoredCancelClick
        }
    }

    fun removeView() {
        if (!isAttachedToWindow) {
            if (isHiddenForExternalActivity) {
                halfScreenApi?.onDestroyOrGone()
                container = null
                flutterView = null
                windowParams = null
                isHiddenForExternalActivity = false
                didHideScreenMaskForExternalActivity = false
                didHideCancelClickForExternalActivity = false
                didHideDraggableForExternalActivity = false
                isHiddenForManualRecording = false
                didHideScreenMaskForManualRecording = false
                didHideCancelClickForManualRecording = false
            }
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
            windowParams = null
        } catch (e: Exception) {
            e.printStackTrace()
        }

        isAttachedToWindow = false
        isHiddenForExternalActivity = false
        didHideScreenMaskForExternalActivity = false
        didHideCancelClickForExternalActivity = false
        didHideDraggableForExternalActivity = false
        isHiddenForManualRecording = false
        didHideScreenMaskForManualRecording = false
        didHideCancelClickForManualRecording = false
    }
}
